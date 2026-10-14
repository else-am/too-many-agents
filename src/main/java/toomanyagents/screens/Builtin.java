package toomanyagents.screens;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefResourceReadCallback;
import org.cef.handler.CefResourceHandler;
import org.cef.handler.CefResourceHandlerAdapter;
import org.cef.handler.CefResourceRequestHandler;
import org.cef.handler.CefResourceRequestHandlerAdapter;
import org.cef.misc.BoolRef;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

/**
 * The mod's own tma: pages, served from the JAR inside Chromium. Chromium only asks for a request once it has checked
 * the request comes from a built-in browser; see Chromium's resource handler.
 */
final class Builtin {
    static final String SCHEME = "tma";
    private static final String ASSETS = "/assets/too_many_agents/builtin/";
    /** Only our own bundled code runs, and the page can't be framed or send its contents anywhere else. */
    private static final String POLICY = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'; "
        + "base-uri 'none'; form-action 'none'; frame-ancestors 'none'";

    private Builtin() {}

    /** The file address for an absolute path, e.g. tma://files/Users/you/project. */
    static String files(java.nio.file.Path path) {
        String text = path.toString().replace('\\', '/');
        try {
            return new URI(SCHEME, "files", text.startsWith("/") ? text : "/" + text, null).toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    static final CefResourceRequestHandler REQUESTS = new CefResourceRequestHandlerAdapter() {
        @Override public CefResourceHandler getResourceHandler(CefBrowser browser, CefFrame frame, CefRequest request) {
            return new Response(request.getURL());
        }
    };

    /** tma://files/_app/NAME is a bundled file; any other tma://files/ address is the files page. */
    private static byte[] body(String url, StringRef type) throws IOException {
        var uri = URI.create(url);
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (!"files".equals(uri.getHost())) return null;
        String name = path.startsWith("/_app/") ? path.substring(6) : "files.html";
        if (!name.matches("[a-z0-9-]+\\.(html|js|css)")) return null;
        type.set(switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "html" -> "text/html";
            case "js" -> "text/javascript";
            default -> "text/css";
        });
        try (InputStream stream = Builtin.class.getResourceAsStream(ASSETS + name)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static final class Response extends CefResourceHandlerAdapter {
        private final String url;
        private final StringRef type = new StringRef("text/plain");
        private byte[] data;
        private int status = 200, offset;

        Response(String url) { this.url = url; }

        @Override public boolean open(CefRequest request, BoolRef handleRequest, CefCallback callback) {
            handleRequest.set(true);
            try {
                data = body(url, type);
            } catch (IOException | IllegalArgumentException e) {
                data = null;
            }
            if (data == null) {
                status = 404;
                data = "Not found".getBytes(StandardCharsets.UTF_8);
                type.set("text/plain");
            }
            return true;
        }

        @Override public void getResponseHeaders(CefResponse response, IntRef length, StringRef redirect) {
            response.setStatus(status);
            response.setMimeType(type.get());
            response.setHeaderByName("Content-Security-Policy", POLICY, true);
            response.setHeaderByName("X-Content-Type-Options", "nosniff", true);
            response.setHeaderByName("Referrer-Policy", "no-referrer", true);
            response.setHeaderByName("Cache-Control", "no-store", true);
            length.set(data.length);
        }

        @Override public boolean read(byte[] out, int wanted, IntRef read, CefResourceReadCallback callback) {
            int count = Math.min(wanted, data.length - offset);
            read.set(Math.max(count, 0));
            if (count <= 0) return false;
            System.arraycopy(data, offset, out, 0, count);
            offset += count;
            return true;
        }
    }
}
