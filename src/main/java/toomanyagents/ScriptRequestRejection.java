package toomanyagents;

/** Explicit rejection provenance for script HTTP replies, never inferred from an exception message. */
final class ScriptRequestRejection extends IllegalStateException {
    final String code;

    private ScriptRequestRejection(String code, String message) {
        super(message);
        this.code = code;
    }

    static ScriptRequestRejection beforeStart(String message) {
        return new ScriptRequestRejection("action_rejected_before_start", message);
    }

    static ScriptRequestRejection bodyUnavailable() {
        return new ScriptRequestRejection("body_missing_or_unloaded", "body_missing_or_unloaded");
    }

    static ScriptRequestRejection leaseUnavailable() {
        return new ScriptRequestRejection("script_no_longer_controls_body", "script_no_longer_controls_body");
    }
}
