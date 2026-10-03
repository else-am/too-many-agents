// Shared parsing and presentation helpers for Claude translation.
import { z } from 'zod';
import { jsonValueSchema } from './json-value.ts';
export * from './adapter-utils.ts';
export * from './json-rpc-envelope.ts';
export * from './tool-arg-schemas.ts';
export * from './provider-visibility.ts';
export * from './provider-visibility-helpers.ts';
export * from './background-task.ts';
export * from './number-utils.ts';
export * from './reasoning-efforts.ts';
export { COMPACTION_PRESENTATION,
  fileReadPresentation, searchPresentation,
  toolPresentation, webFetchPresentation,
  webSearchPresentation, planStepsPresentation,
  presentationDetail, presentationFileName,
  presentationTitle, withTitle } from './tool-presentation.ts';
export const THREAD_EVENT_ITEM_PRESENTATION_DETAIL_MAX_LENGTH = 280;
export const USER_QUESTION_MAX_OPTIONS = 4;
export const USER_QUESTION_MAX_QUESTIONS = 4;
export const providerRawEventSchema = z.object({
  jsonrpc: z.literal('2.0'), id: z.union([z.string(), z.number()]).optional(),
  method: z.string(), params: jsonValueSchema.optional(),
});
// Too Many Agents validates typed outcomes against its complete shared schema before forwarding.
export const isApprovalInteractionOutcome = (outcome: {payload: {kind: string}}) => outcome.payload.kind === 'approval';
export class ProviderResponseEncodeError extends Error {}
