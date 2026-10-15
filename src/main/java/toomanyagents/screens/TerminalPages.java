package toomanyagents.screens;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import org.cef.browser.CefFrame;
import org.cef.browser.ScreenBrowser;
import org.cef.callback.CefQueryCallback;

/** A terminal page can reach only the BB session granted to its current native browser. */
final class TerminalPages {
    private TerminalPages() {}

    static boolean location(String value) {
        try { return !id(value).isEmpty(); }
        catch (RuntimeException invalid) { return false; }
    }

    static String id(String value) {
        var uri = URI.create(value);
        String path = uri.getRawPath();
        if (!"tma".equals(uri.getScheme()) || !"terminal".equals(uri.getRawAuthority()) || uri.getRawQuery() != null
                || uri.getRawFragment() != null || path == null || !path.matches("/[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("Use a BB terminal ID or tma://terminal/<id>.");
        return path.substring(1);
    }

    static String url(String id) {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("Invalid terminal ID.");
        return "tma://terminal/" + id;
    }

    static String address(String target) {
        try { return target.startsWith("tma:") ? url(id(target)) : url(target); }
        catch (RuntimeException invalid) { throw new ScreenTools.ScreenError("invalid_request", "Use a BB terminal ID or tma://terminal/<id>."); }
    }

    static CompletableFuture<JsonObject> authorize(String target, String owner) {
        var args = new JsonObject();
        args.addProperty("terminalId", id(target));
        return ScreenTools.terminalRpc.call("terminal.access", args, owner);
    }

    static CompletableFuture<JsonObject> call(String op, JsonObject args, String owner) {
        var request = ScreenTools.terminalRpc.call(op, args, owner);
        return request == null ? CompletableFuture.failedFuture(new IllegalStateException("Minecraft is not connected to BB yet.")) : request;
    }

    static CompletableFuture<JsonObject> create(Path folder) {
        return FilePages.submit(() -> ConfinedFiles.grant(folder).path()).thenCompose(path -> {
            var args = new JsonObject();
            args.addProperty("cwd", path);
            args.addProperty("cols", 80);
            args.addProperty("rows", 24);
            return call("terminal.create", args, "");
        });
    }

    /** Capture authority at query time; neither the page nor its request chooses a terminal or an agent. */
    static void query(ScreenView view, ScreenBrowser source, CefFrame frame, String request, CefQueryCallback callback) {
        var access = view == null ? null : view.access;
        if (access == null || source != access.browser() || !source.builtin || frame == null || !frame.isMain() || !FilePages.current(view, access)
                || access.terminal().isEmpty() || !url(access.terminal()).equals(frame.getURL())) {
            callback.failure(1, "Only the current terminal page can use this session.");
            return;
        }
        if (request.length() > 100_000) { callback.failure(1, "Terminal request is too large."); return; }
        Minecraft.getInstance().execute(() -> {
            if (!FilePages.current(view, access)) { callback.failure(1, "The terminal page changed."); return; }
            if (view.terminalPending >= 8) { callback.failure(1, "The terminal is busy."); return; }
            try {
                var data = JsonParser.parseString(request).getAsJsonObject();
                String op = data.get("op").getAsString();
                var args = new JsonObject();
                args.addProperty("terminalId", access.terminal());
                String owner = "";
                switch (op) {
                    case "state" -> { op = "access"; owner = access.owner(); }
                    case "output" -> { if (data.has("sinceSeq")) args.add("sinceSeq", data.get("sinceSeq")); }
                    case "resize" -> { args.add("cols", data.get("cols")); args.add("rows", data.get("rows")); }
                    case "input" -> {
                        if (!view.recentInput()) throw new IllegalStateException("Terminal input needs the player's keyboard or paste.");
                        args.add("dataBase64", data.get("dataBase64"));
                    }
                    default -> throw new IllegalArgumentException("Unknown terminal operation.");
                }
                view.terminalPending++;
                call("terminal." + op, args, owner).whenComplete((answer, failure) -> Minecraft.getInstance().execute(() -> {
                    view.terminalPending--;
                    if (!FilePages.current(view, access)) { callback.failure(1, "The terminal page changed."); return; }
                    if (failure != null) callback.failure(1, message(failure));
                    else callback.success(answer.toString());
                }));
            } catch (RuntimeException failure) { callback.failure(1, message(failure)); }
        });
    }

    static String message(Throwable failure) {
        while (failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null) failure = failure.getCause();
        return failure.getMessage() == null ? "Could not reach the terminal." : failure.getMessage();
    }
}
