package toomanyagents;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.Supplier;

/** HTTP only. The supplied functions own all Minecraft thread/lifecycle rules. */
final class LocalBridge implements AutoCloseable {
    record Reply(int status, String body) {}
    interface AgentRoutes { Reply handle(String method, String path, JsonObject request); }
    interface ScopedRoute { Reply handle(String token, JsonObject request); }
    String url() { return "http://127.0.0.1:" + http.getAddress().getPort(); }
    private static final Gson JSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private final HttpServer http;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        var thread = new Thread(runnable, "too_many_agents-http");
        thread.setDaemon(true);
        return thread;
    });
    private final Path discovery;
    private final String token;

    LocalBridge(Path directory, Supplier<String> snapshot,
                Function<JsonObject, Reply> command, AgentRoutes agents, ScopedRoute scoped) throws IOException {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        token = HexFormat.of().formatHex(bytes);
        Files.createDirectories(directory);
        discovery = directory.resolve("connection.json");
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
        http.setExecutor(workers);
        http.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/v1/agent-tool") && exchange.getRequestMethod().equals("POST") && !exchange.getRequestHeaders().containsKey("Origin")) {
                    String auth = exchange.getRequestHeaders().getFirst("Authorization");
                    byte[] bytesIn = exchange.getRequestBody().readNBytes(65537);
                    if (bytesIn.length > 65536) { send(exchange,error(413,"request_too_large")); return; }
                    if (auth == null || !auth.startsWith("Bearer ")) { send(exchange,error(401,"unauthorized")); return; }
                    try { send(exchange,scoped.handle(auth.substring(7),JsonParser.parseString(new String(bytesIn,StandardCharsets.UTF_8)).getAsJsonObject())); }
                    catch (RuntimeException invalid) { send(exchange,error(400,"invalid_request")); }
                    return;
                }
                if (exchange.getRequestHeaders().containsKey("Origin") ||
                    !MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8),
                        exchange.getRequestHeaders().getFirst("Authorization") == null ? new byte[0] :
                        exchange.getRequestHeaders().getFirst("Authorization").getBytes(StandardCharsets.UTF_8))) {
                    send(exchange, error(401, "unauthorized"));
                    return;
                }
                if (path.equals("/v1/state") && exchange.getRequestMethod().equals("GET")) {
                    send(exchange, new Reply(200, snapshot.get()));
                } else if (path.equals("/v1/command") && exchange.getRequestMethod().equals("POST")) {
                    byte[] body = exchange.getRequestBody().readNBytes(8193);
                    if (body.length > 8192) {
                        send(exchange, error(413, "request_too_large"));
                        return;
                    }
                    JsonObject request;
                    try {
                        request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                        if (!request.has("session") || !request.has("command") ||
                            !request.get("session").isJsonPrimitive() || !request.get("command").isJsonPrimitive() ||
                            !request.get("session").getAsJsonPrimitive().isString() ||
                            !request.get("command").getAsJsonPrimitive().isString()) {
                            throw new IllegalArgumentException("Expected session and command strings");
                        }
                    } catch (RuntimeException exception) {
                        send(exchange, error(400, "invalid_request"));
                        return;
                    }
                    send(exchange, command.apply(request));
                } else if (path.equals("/v1/dev") || path.equals("/v1/ui") || path.equals("/v1/agents") || path.startsWith("/v1/agents/")) {
                    String method = exchange.getRequestMethod();
                    if (!method.equals("GET") && !method.equals("POST")) {
                        send(exchange, error(405, "method_not_allowed"));
                        return;
                    }
                    var request = new JsonObject();
                    if (method.equals("POST")) {
                        byte[] body = exchange.getRequestBody().readNBytes(65537);
                        if (body.length > 65536) {
                            send(exchange, error(413, "request_too_large"));
                            return;
                        }
                        try {
                            request = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
                        } catch (RuntimeException exception) {
                            send(exchange, error(400, "invalid_request"));
                            return;
                        }
                    }
                    send(exchange, agents.handle(method, path, request));
                } else {
                    send(exchange, error(404, "unknown_endpoint"));
                }
            } catch (IOException ignored) {
                // A disconnected caller must never interrupt the game.
            }
        });
        var info = new JsonObject();
        info.addProperty("protocol", 1);
        info.addProperty("url", "http://127.0.0.1:" + http.getAddress().getPort());
        info.addProperty("token", token);
        info.addProperty("pid", ProcessHandle.current().pid());
        Path temporary = directory.resolve("connection.json.tmp");
        try {
            Files.deleteIfExists(temporary);
            if (directory.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } else {
                Files.createFile(temporary);
            }
            Files.writeString(temporary, JSON.toJson(info));
            Files.move(temporary, discovery, StandardCopyOption.REPLACE_EXISTING);
            http.start();
        } catch (IOException exception) {
            http.stop(0);
            workers.shutdownNow();
            throw exception;
        }
    }

    static Reply error(int status, String code) {
        var body = new JsonObject();
        body.addProperty("error", code);
        return new Reply(status, JSON.toJson(body));
    }

    private static void send(HttpExchange exchange, Reply reply) throws IOException {
        byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(reply.status(), body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() {
        http.stop(0);
        workers.shutdownNow();
        try {
            if (Files.exists(discovery) && JsonParser.parseString(Files.readString(discovery))
                .getAsJsonObject().get("token").getAsString().equals(token)) Files.delete(discovery);
        } catch (IOException | RuntimeException ignored) {
            // A newer process may have replaced the descriptor.
        }
    }
}
