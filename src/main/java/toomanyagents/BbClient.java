package toomanyagents;

import com.google.gson.*;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/** The plugin is the only agent backend. Requests with unknown outcomes are never retried. */
final class BbClient implements AutoCloseable {
    private static final Set<String> QUICK = Set.of("backend.status","hello");
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    CompletableFuture<JsonElement> call(JsonObject request) {
        try {
            var base=BbSetup.get().address();
            request = request.deepCopy();
            request.addProperty("modVersion", BbSetup.version());
            var message=HttpRequest.newBuilder(base.resolve("/api/v1/plugins/minecraft/http/v1/rpc"))
                .timeout(Duration.ofSeconds(QUICK.contains(JsonState.text(request,"op"))?5:110)).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
            return http.sendAsync(message,HttpResponse.BodyHandlers.ofString()).thenApply(reply -> {
                JsonObject body;
                try { body=JsonParser.parseString(reply.body()).getAsJsonObject(); }
                catch(RuntimeException malformed) { throw new CompletionException(new IllegalStateException("BB returned an unreadable response (HTTP "+reply.statusCode()+"). The outcome is unknown; inspect its state before retrying.")); }
                if(!JsonState.flag(body,"ok")) {
                    String detail=JsonState.text(JsonState.obj(body,"error"),"message");
                    throw new CompletionException(new IllegalStateException(detail.isBlank()?"BB request failed (HTTP "+reply.statusCode()+"). Inspect its state before retrying.":detail));
                }
                return body.has("result")?body.get("result"):JsonNull.INSTANCE;
            });
        } catch(Exception failure) {
            return CompletableFuture.failedFuture(new IllegalStateException("Could not find BB: " + failure.getMessage(), failure));
        }
    }

    @Override public void close() { http.close(); }
}
