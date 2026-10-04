package toomanyagents.ui;

import com.google.gson.JsonObject;
import java.util.ArrayList;

/** Native BB interaction and queued-message text for Minecraft widgets. */
final class AgentInteractions {
    private AgentInteractions() {}

    static String decisionLabel(String decision) {
        return switch (decision) {
            case "allow_once" -> "Allow once";
            case "allow_for_session" -> "Allow for this session";
            case "deny" -> "Deny";
            default -> decision;
        };
    }

    static JsonObject approvalResolution(JsonObject interaction, String decision) {
        var result = new JsonObject();
        result.addProperty("decision", decision);
        if (!decision.equals("deny")) {
            var subject = AgentModels.object(AgentModels.object(interaction, "payload"), "subject");
            var grant = subject.get(AgentModels.text(subject,"kind").equals("permission_grant") ? "permissions" : "sessionGrant");
            result.add("grantedPermissions", grant == null ? com.google.gson.JsonNull.INSTANCE : grant.deepCopy());
        }
        return result;
    }

    static String approvalDetails(JsonObject interaction) {
        var payload = AgentModels.object(interaction, "payload");
        var subject = AgentModels.object(payload, "subject");
        var parts = new ArrayList<String>();
        for (String key : new String[]{"command", "cwd", "writeScope", "plan", "planFilePath", "tool", "toolName"}) {
            String text = AgentModels.text(subject, key);
            if (!text.isBlank()) parts.add(text);
        }
        var presentation = AgentModels.object(subject, "presentation");
        for (String key : new String[]{"title", "detail"}) {
            String text = AgentModels.text(presentation, key);
            if (!text.isBlank()) parts.add(text);
        }
        String permissions = permissionsText(AgentModels.object(subject, "permissions"));
        if (!permissions.isBlank()) parts.add(permissions);
        String reason = AgentModels.text(payload, "reason");
        if (!reason.isBlank()) parts.add(reason);
        return parts.isEmpty() ? AgentModels.text(subject, "kind") : String.join("\n\n", parts);
    }

    /** BB permission grants as plain lines, e.g. "Network access" or "Write: /path". */
    private static String permissionsText(JsonObject permissions) {
        var lines = new ArrayList<String>();
        var network = AgentModels.object(permissions, "network");
        if (network.has("enabled") && network.get("enabled").isJsonPrimitive() && network.get("enabled").getAsBoolean()) lines.add("Network access");
        var files = AgentModels.object(permissions, "fileSystem");
        for (String key : new String[]{"read", "write"})
            for (var path : AgentModels.array(files, key))
                lines.add((key.equals("read") ? "Read: " : "Write: ") + path.getAsString());
        return String.join("\n", lines);
    }

    static String queuedText(JsonObject message) {
        var parts = new ArrayList<String>();
        for (var item : AgentModels.array(message, "content")) {
            var block = item.getAsJsonObject();
            String type = AgentModels.text(block, "type");
            if (type.equals("text")) parts.add(AgentModels.text(block,"text"));
            else parts.add("[" + type + "] " + AgentModels.text(block, type.equals("image") ? "url" : "path"));
        }
        return String.join("\n", parts);
    }

    static String waitingReason(JsonObject message) {
        String failure = AgentModels.text(message,"failureReason");
        var waiting = AgentModels.object(message,"waitingOn");
        String text = switch (AgentModels.text(waiting,"kind")) {
            case "plugin" -> AgentModels.text(waiting,"reason");
            case "host-offline" -> "Waiting for " + AgentModels.text(waiting,"hostName");
            case "time" -> "Scheduled";
            case "thread-busy", "turn-starting" -> "Waiting for the current turn";
            case "stopping" -> "Waiting for Stop to finish";
            case "provisioning" -> "Preparing the workspace";
            case "interaction" -> "Waiting for an answer";
            default -> "Waiting to run";
        };
        return failure.isBlank() ? text : text + " — " + failure;
    }
}
