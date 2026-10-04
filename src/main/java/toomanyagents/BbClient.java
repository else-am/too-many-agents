package toomanyagents;

import com.google.gson.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.*;

/** The plugin is the only agent backend. Requests with unknown outcomes are never retried. */
final class BbClient implements AutoCloseable {
    // BB's desktop app records its server address here.
    private static final Path ADDRESS = Path.of(System.getProperty("user.home"),".bb","bb-app-runtime.json");
    private static final Set<String> QUICK = Set.of("backend.status","hello");
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();

    CompletableFuture<JsonElement> call(JsonObject request) {
        try {
            var address=JsonParser.parseString(Files.readString(ADDRESS)).getAsJsonObject();
            var base=URI.create(JsonState.text(address,"serverUrl"));
            if(!"http".equals(base.getScheme()) || !Set.of("127.0.0.1","[::1]","::1").contains(base.getHost())
                || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
                throw new IllegalStateException("BB must use a literal loopback HTTP address.");
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
            return CompletableFuture.failedFuture(new IllegalStateException("BB is not running, or the Minecraft plugin is not installed in it.",failure));
        }
    }

    @Override public void close() { http.close(); }
}
