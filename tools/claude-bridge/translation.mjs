// Translates native Claude messages into shared conversation events.
import { createClaudeDeltaTranslator } from './protocol/delta-translation.ts';
import { buildClaudeProviderErrorInfo } from './protocol/error-info.ts';

export function createTranslation(s, delta, emit) {
  const dialect = createClaudeDeltaTranslator({ cwd: s.options.cwd, sandboxEnabled: s.options.permissions?.permissionMode !== 'full' });
  dialect.configureInjectedTools(s.options.tools ?? []);
  const publish = values => {
    for (const value of values) {
      // Interrupted SDK results may otherwise report success after interrupt().
      if (s.interrupted && value.kind === 'turn.boundary') value.status = 'interrupted';
      delta(s, value);
      if (value.kind === 'provider.error' && value.errorInfo && !value.willRetry) {
        const category = value.errorInfo.category;
        const recovery = category === 'unauthorized' ? 'authRequired' : category === 'rate-limit' ? 'rateLimited' : undefined;
        if (recovery) emit(s, 'recovery', { kind: recovery, category, knownRejected: false }, value.detail ?? value.message);
      }
      if (value.kind === 'turn.boundary' || value.kind === 'session.ended') s.settleTurn?.();
    }
  };
  return {
    contextWindow: usage => {
      dialect.setClaudeModelContextWindow(s.id, usage.maxTokens);
      delta(s, { kind: 'contextWindow', used: usage.totalTokens, size: usage.maxTokens, estimated: true, attach: 'currentOrLast' });
    },
    accept: requestId => publish(dialect.acceptInput(s.id, requestId)),
    message: message => {
      let values = dialect.translate({ jsonrpc: '2.0', method: 'sdk/message', params: { message } }, { threadId: s.id });
      // The SDK can finish an explicit interrupt with a generic execution error.
      // Keep its diagnostic without presenting the requested Stop as a failure.
      if (s.interrupted && message.type === 'result' && message.subtype === 'error_during_execution') {
        values = values.map(value => value.kind === 'provider.error' && value.errorInfo?.category === 'unknown'
          ? { kind: 'provider.warning', category: 'general', summary: 'Turn interrupted.',
              ...(value.detail ? { details: value.detail } : {}) }
          : value);
      }
      publish(values);
      // A result error's native status is the only basis for typed recovery.
      if (message.type === 'assistant' && message.error) {
        const info = buildClaudeProviderErrorInfo({ code: message.error, httpStatusCode: message.apiErrorStatus });
        if (info?.category) publish([{ kind: 'provider.error', message: 'Claude request failed', detail: String(message.error), errorInfo: info }]);
      }
    },
    end: () => publish(dialect.buildSessionSettlementDeltas(s.id)),
    hasOpenTurn: () => dialect.hasOpenTurn(s.id),
  };
}
