package dev.jvch.atlas;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Loopback-only web UI and JSON API. No cookies, no third-party content, strict CSP. */
public final class Server {
    public static final String APP_CSP = "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    public static final String REPORT_CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    static final int MAX_STATE_BODY = 256 * 1024;
    static final int MAX_ENTRY_BODY = 16 * 1024;

    private final Catalog catalog;
    private Assessment assessment;
    private final Path persist;
    private HttpServer http;
    private java.util.concurrent.ExecutorService pool;
    private int port;

    public Server(Catalog catalog, Assessment assessment, Path persist) {
        this.catalog = catalog;
        this.assessment = assessment;
        this.persist = persist;
    }

    public int port() { return port; }

    public void start(int requestedPort) throws IOException {
        http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 0);
        port = http.getAddress().getPort();
        http.createContext("/", this::handle);
        pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        http.setExecutor(pool);
        http.start();
    }

    public void stop() {
        if (http != null) http.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!hostAllowed(ex)) { send(ex, 403, "text/plain; charset=utf-8", "bad host", APP_CSP); return; }
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            boolean mutating = method.equals("PUT") || method.equals("POST") || method.equals("DELETE");
            if (mutating && !mutationAllowed(ex)) { json(ex, 403, error("forbidden")); return; }
            if (method.equals("GET") || method.equals("HEAD")) route(ex, path);
            else if (method.equals("PUT")) put(ex, path);
            else json(ex, 405, error("method not allowed"));
        } catch (IllegalArgumentException e) {
            json(ex, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            json(ex, 500, error("internal error"));
        } finally {
            ex.close();
        }
    }

    private boolean hostAllowed(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        if (h == null) return false;
        return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port);
    }

    private boolean mutationAllowed(HttpExchange ex) {
        if (!"1".equals(ex.getRequestHeaders().getFirst("X-Atlas-Request"))) return false;
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        if (ct == null || !ct.toLowerCase().startsWith("application/json")) return false;
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin == null) return true;
        String host = ex.getRequestHeaders().getFirst("Host");
        return origin.equals("http://" + host);
    }

    private void route(HttpExchange ex, String path) throws IOException {
        switch (path) {
            case "/": static_(ex, "index.html", "text/html; charset=utf-8"); return;
            case "/style.css": static_(ex, "style.css", "text/css; charset=utf-8"); return;
            case "/app.js": static_(ex, "app.js", "text/javascript; charset=utf-8"); return;
            case "/api/catalog": json(ex, 200, catalog.toMap()); return;
            case "/api/state": json(ex, 200, stateView()); return;
            case "/report.html": send(ex, 200, "text/html; charset=utf-8", Reports.html(catalog, snapshot()), REPORT_CSP); return;
            case "/report.md": send(ex, 200, "text/markdown; charset=utf-8", Reports.markdown(catalog, snapshot()), APP_CSP); return;
            case "/gaps.csv": send(ex, 200, "text/csv; charset=utf-8", Reports.csv(catalog, snapshot()), APP_CSP); return;
            default: json(ex, 404, error("not found"));
        }
    }

    private synchronized Assessment snapshot() { return assessment; }

    private synchronized Map<String, Object> stateView() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assessment", assessment.toMap());
        m.put("summary", Scoring.summaryMap(catalog, assessment));
        return m;
    }

    private void put(HttpExchange ex, String path) throws IOException {
        if (path.equals("/api/state")) {
            Assessment a = Assessment.fromJson(body(ex, MAX_STATE_BODY), catalog);
            synchronized (this) { assessment = a; save(); }
            json(ex, 200, stateView());
        } else if (path.equals("/api/meta")) {
            Map<String, Object> m = Json.object(body(ex, MAX_ENTRY_BODY));
            synchronized (this) {
                Map<String, Object> cur = assessment.toMap();
                for (String k : new String[] {"organisation", "asOf", "scope"}) if (m.containsKey(k)) cur.put(k, m.get(k));
                assessment = Assessment.fromMap(cur, catalog);
                save();
            }
            json(ex, 200, stateView());
        } else if (path.startsWith("/api/control/")) {
            String id = path.substring("/api/control/".length());
            if (!catalog.has(id)) { json(ex, 404, error("unknown control")); return; }
            Assessment.Entry e = Assessment.parseEntry(Json.object(body(ex, MAX_ENTRY_BODY)));
            synchronized (this) { assessment.entries.put(id, e); save(); }
            json(ex, 200, stateView());
        } else json(ex, 404, error("not found"));
    }

    private void save() {
        if (persist == null) return;
        try {
            Path tmp = persist.resolveSibling(persist.getFileName() + ".tmp");
            Files.writeString(tmp, Json.write(assessment.toMap()) + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, persist, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("could not save assessment", e);
        }
    }

    private static String body(HttpExchange ex, int max) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > max) throw new IllegalArgumentException("request body too large");
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static Map<String, Object> error(String m) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("error", m);
        return e;
    }

    private void static_(HttpExchange ex, String name, String type) throws IOException {
        try (InputStream in = Server.class.getResourceAsStream("/public/" + name)) {
            if (in == null) { json(ex, 404, error("not found")); return; }
            send(ex, 200, type, new String(in.readAllBytes(), StandardCharsets.UTF_8), APP_CSP);
        }
    }

    private void json(HttpExchange ex, int status, Object v) throws IOException {
        send(ex, status, "application/json; charset=utf-8", Json.write(v), APP_CSP);
    }

    private void send(HttpExchange ex, int status, String type, String body, String csp) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.set("Content-Type", type);
        h.set("Content-Security-Policy", csp);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Cache-Control", "no-store");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : b.length);
        if (!head) try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }
}
