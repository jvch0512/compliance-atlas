package dev.jvch.atlas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Tiny dependency-free test runner. Run with assertions enabled (-ea). */
public final class AllTests {
    private static int passed = 0;

    private interface Case { void run() throws Exception; }

    private static void test(String name, Case c) {
        try {
            c.run();
            passed++;
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": " + t);
            t.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void eq(Object a, Object b) { if (!a.equals(b)) throw new AssertionError(a + " != " + b); }
    private static void yes(boolean c, String msg) { if (!c) throw new AssertionError(msg); }
    private static void near(double a, double b) { if (Math.abs(a - b) > 1e-9) throw new AssertionError(a + " !~ " + b); }

    private static void rejects(String json, Catalog cat, String fragment) {
        try {
            Assessment.fromJson(json, cat);
        } catch (IllegalArgumentException e) {
            yes(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention " + fragment);
            return;
        }
        throw new AssertionError("expected rejection: " + json);
    }

    private static Assessment assessment(Catalog cat, String body) {
        return Assessment.fromJson("{\"asOf\":\"2026-11-13\",\"scope\":[\"nis2\"]," + body + "}", cat);
    }

    public static void main(String[] args) throws Exception {
        Catalog cat = Catalog.load();

        test("json round trip and strictness", () -> {
            Map<String, Object> m = Json.object("{\"a\":[1,2.5,true,null,\"x\\n\\u00e9\"],\"b\":{\"c\":\"<>&\"}}");
            String out = Json.write(m);
            yes(!out.contains("<") && !out.contains(">"), "angle brackets escaped");
            eq(Json.object(out), m);
            for (String bad : new String[] {"{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"\\q\"", "{'a':1}"}) {
                try { Json.parse(bad); throw new AssertionError("accepted " + bad); } catch (IllegalArgumentException ok) { /* expected */ }
            }
            StringBuilder deep = new StringBuilder();
            for (int i = 0; i < 100; i++) deep.append('[');
            try { Json.parse(deep.toString()); throw new AssertionError("accepted deep nesting"); } catch (IllegalArgumentException ok) { /* expected */ }
        });

        test("catalog loads 35 controls with valid mappings", () -> {
            eq(cat.controls().size(), 35);
            for (Control c : cat.controls()) {
                int n = 0;
                for (Framework f : Framework.values()) if (c.maps(f)) n++;
                yes(n >= 1, c.id() + " maps to at least one framework");
                yes(c.weight() >= 1 && c.weight() <= 3, c.id() + " weight");
            }
            yes(cat.get("INC-02").maps(Framework.NIS2) && cat.get("INC-02").maps(Framework.GDPR) && cat.get("INC-02").maps(Framework.DORA), "incident notification maps to NIS2, GDPR and DORA");
            try { Catalog.parse("a|b|c"); throw new AssertionError("accepted short row"); } catch (IllegalArgumentException ok) { /* expected */ }
        });

        test("score rules: evidence, expiry and unverified", () -> {
            LocalDate asOf = LocalDate.parse("2026-11-13");
            Assessment.Entry e = new Assessment.Entry();
            e.status = Status.IMPLEMENTED;
            near(Scoring.score(e, asOf), 0.5);
            e.evidence.add(new Assessment.Evidence("doc", LocalDate.parse("2026-01-01"), null));
            near(Scoring.score(e, asOf), 1.0);
            e.evidence.clear();
            e.evidence.add(new Assessment.Evidence("old", LocalDate.parse("2025-01-01"), null));
            near(Scoring.score(e, asOf), 0.5);
            e.evidence.clear();
            e.evidence.add(new Assessment.Evidence("expired", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-11-12")));
            near(Scoring.score(e, asOf), 0.5);
            e.evidence.clear();
            e.evidence.add(new Assessment.Evidence("last day", null, LocalDate.parse("2026-11-13")));
            near(Scoring.score(e, asOf), 1.0);
            e.status = Status.PARTIAL;
            near(Scoring.score(e, asOf), 0.5);
            e.status = Status.NOT_APPLICABLE;
            near(Scoring.score(e, asOf), -1);
        });

        test("readiness is a weighted mean and N/A leaves the denominator", () -> {
            Assessment a = assessment(cat, "\"controls\":{}");
            near(Scoring.readiness(cat, a).get(Framework.NIS2).readiness(), 0.0);
            // Make every NIS2-mapped control implemented with evidence: readiness 1.0
            StringBuilder sb = new StringBuilder("\"controls\":{");
            boolean first = true;
            for (Control c : cat.controls()) {
                if (!c.maps(Framework.NIS2)) continue;
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(c.id()).append("\":{\"status\":\"implemented\",\"evidence\":[{\"title\":\"x\",\"reviewed\":\"2026-10-01\"}]}");
            }
            sb.append('}');
            Assessment full = assessment(cat, sb.toString());
            near(Scoring.readiness(cat, full).get(Framework.NIS2).readiness(), 1.0);
            // Mark one control N/A and another not started: weighted mean over the rest
            full.entry("GOV-01").status = Status.NOT_APPLICABLE;
            full.entry("OPS-02").status = Status.NOT_STARTED;
            double den = 0;
            for (Control c : cat.controls()) if (c.maps(Framework.NIS2) && !c.id().equals("GOV-01")) den += c.weight();
            near(Scoring.readiness(cat, full).get(Framework.NIS2).readiness(), (den - cat.get("OPS-02").weight()) / den);
        });

        test("gaps are ranked by leverage and uplift never decreases", () -> {
            Assessment a = Assessment.fromJson("{\"asOf\":\"2026-11-13\",\"controls\":{\"OPS-02\":{\"status\":\"partial\"},\"DEV-02\":{\"status\":\"not_started\"}}}", cat);
            List<Scoring.Gap> g = Scoring.gaps(cat, a);
            for (int i = 1; i < g.size(); i++) yes(g.get(i - 1).leverage() >= g.get(i).leverage(), "sorted at " + i);
            double devLeverage = 0, opsLeverage = 0;
            for (Scoring.Gap x : g) {
                if (x.control().id().equals("DEV-02")) devLeverage = x.leverage();
                if (x.control().id().equals("OPS-02")) opsLeverage = x.leverage();
            }
            yes(opsLeverage > devLeverage, "a weight-3 control on five frameworks beats a weight-1 control on three");
            var now = Scoring.readiness(cat, a);
            var up = Scoring.upliftIfClosed(cat, a, 5);
            for (Framework f : now.keySet()) yes(up.get(f).readiness() >= now.get(f).readiness(), "uplift for " + f);
        });

        test("out-of-scope controls are ignored", () -> {
            Assessment a = assessment(cat, "\"controls\":{}");
            for (Scoring.Gap g : Scoring.gaps(cat, a)) yes(g.control().maps(Framework.NIS2), g.control().id() + " is not a NIS2 control");
            yes(!Scoring.readiness(cat, a).containsKey(Framework.GDPR), "GDPR not in scope");
        });

        test("overdue applies to open controls only", () -> {
            Assessment a = Assessment.fromJson("{\"asOf\":\"2026-11-13\",\"controls\":{\"ACC-03\":{\"status\":\"not_started\",\"due\":\"2026-10-01\"},\"OPS-02\":{\"status\":\"implemented\",\"due\":\"2026-10-01\"}}}", cat);
            boolean accOverdue = false, opsListed = false;
            for (Scoring.Gap g : Scoring.gaps(cat, a)) {
                if (g.control().id().equals("ACC-03")) accOverdue = g.overdue();
                if (g.control().id().equals("OPS-02")) { opsListed = true; yes(!g.overdue(), "implemented is never overdue"); yes(g.unverified(), "no evidence"); }
            }
            yes(accOverdue, "ACC-03 overdue");
            yes(opsListed, "unverified implemented control stays in the gap list");
        });

        test("assessment validation", () -> {
            rejects("{\"controls\":{\"NOPE-1\":{}}}", cat, "unknown control");
            rejects("{\"controls\":{\"GOV-01\":{\"status\":\"done\"}}}", cat, "unknown status");
            rejects("{\"scope\":[\"hipaa\"]}", cat, "unknown framework");
            rejects("{\"scope\":[]}", cat, "at least one");
            rejects("{\"asOf\":\"13/11/2026\"}", cat, "date");
            rejects("{\"organisation\":\"" + "x".repeat(101) + "\"}", cat, "too long");
            rejects("{\"controls\":{\"GOV-01\":{\"notes\":\"" + "x".repeat(2001) + "\"}}}", cat, "too long");
            StringBuilder ev = new StringBuilder("{\"controls\":{\"GOV-01\":{\"evidence\":[");
            for (int i = 0; i < 21; i++) ev.append(i == 0 ? "" : ",").append("{\"title\":\"t\"}");
            rejects(ev.append("]}}}").toString(), cat, "too many");
            rejects("{\"controls\":{\"GOV-01\":{\"evidence\":[{\"title\":\" \"}]}}}", cat, "required");
        });

        test("assessment json round trip", () -> {
            Assessment a = Assessment.fromJson(Files.readString(Path.of("samples/costa-pay.json")), cat);
            Assessment b = Assessment.fromJson(Json.write(a.toMap()), cat);
            eq(Json.write(a.toMap()), Json.write(b.toMap()));
            eq(a.scope.size(), 5);
        });

        test("sample assessment gives a spread of results", () -> {
            Assessment a = Assessment.fromJson(Files.readString(Path.of("samples/costa-pay.json")), cat);
            var r = Scoring.readiness(cat, a);
            double lo = 1, hi = 0;
            for (var s : r.values()) { lo = Math.min(lo, s.readiness()); hi = Math.max(hi, s.readiness()); }
            yes(hi - lo > 0.05, "frameworks should differ, spread=" + (hi - lo));
            yes(Scoring.gaps(cat, a).size() > 5, "sample has gaps");
        });

        test("html report escapes hostile text and has no script", () -> {
            Assessment a = assessment(cat, "\"organisation\":\"<script>alert(1)</script>\",\"controls\":{\"GOV-01\":{\"owner\":\"<img src=x onerror=1>\"}}");
            String h = Reports.html(cat, a);
            yes(!h.contains("<script"), "no script tag");
            yes(!h.contains("<img"), "owner escaped");
            yes(h.contains("&lt;script&gt;"), "organisation escaped");
        });

        test("csv neutralises formula injection and quotes cells", () -> {
            eq(Reports.csvCell("=HYPERLINK(\"x\")"), "\"'=HYPERLINK(\"\"x\"\")\"");
            eq(Reports.csvCell("+1"), "\"'+1\"");
            eq(Reports.csvCell("a,b"), "\"a,b\"");
            Assessment a = assessment(cat, "\"controls\":{\"GOV-01\":{\"owner\":\"=cmd|' /C calc'!A0\"}}");
            for (String line : Reports.csv(cat, a).split("\n")) yes(!line.matches(".*,=.*"), "no raw formula cell: " + line);
        });

        test("markdown report has table and disclaimer", () -> {
            Assessment a = Assessment.fromJson(Files.readString(Path.of("samples/costa-pay.json")), cat);
            String md = Reports.markdown(cat, a);
            yes(md.contains("| NIS2 |") && md.contains("Priority gaps") && md.contains("not legal advice"), "sections");
        });

        test("CLI check gates on threshold", () -> {
            var out = new java.io.PrintStream(java.io.OutputStream.nullOutputStream());
            eq(Main.run(new String[] {"check", "samples/costa-pay.json", "--min", "100"}, out, out), 2);
            eq(Main.run(new String[] {"check", "samples/costa-pay.json", "--min", "0"}, out, out), 0);
            eq(Main.run(new String[] {"report", "missing.json"}, out, out), 1);
        });

        test("server: host check, CSRF guards, limits and persistence", () -> {
            Path tmp = Files.createTempFile("atlas", ".json");
            Files.delete(tmp);
            Server s = new Server(cat, new Assessment(), tmp);
            s.start(0);
            try {
                HttpClient http = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:" + s.port();
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + "/api/catalog")).build(), HttpResponse.BodyHandlers.ofString());
                eq(r.statusCode(), 200);
                yes(r.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'self'"), "CSP present");
                yes(!r.headers().firstValue("Content-Security-Policy").orElse("").contains("unsafe-inline"), "app CSP has no unsafe-inline");

                // Mutations need the custom header and JSON content type
                HttpRequest noHeader = HttpRequest.newBuilder(URI.create(base + "/api/control/GOV-01")).header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"partial\"}")).build();
                eq(http.send(noHeader, HttpResponse.BodyHandlers.ofString()).statusCode(), 403);
                HttpRequest wrongType = HttpRequest.newBuilder(URI.create(base + "/api/control/GOV-01")).header("X-Atlas-Request", "1").header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"partial\"}")).build();
                eq(http.send(wrongType, HttpResponse.BodyHandlers.ofString()).statusCode(), 403);

                HttpRequest ok = HttpRequest.newBuilder(URI.create(base + "/api/control/GOV-01")).header("X-Atlas-Request", "1").header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"partial\",\"owner\":\"Ana\"}")).build();
                HttpResponse<String> saved = http.send(ok, HttpResponse.BodyHandlers.ofString());
                eq(saved.statusCode(), 200);
                yes(Files.readString(tmp, StandardCharsets.UTF_8).contains("\"Ana\""), "persisted to disk");

                // Validation errors are 400, unknown control 404, oversized body 400
                HttpRequest bad = HttpRequest.newBuilder(URI.create(base + "/api/control/GOV-01")).header("X-Atlas-Request", "1").header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"status\":\"bogus\"}")).build();
                eq(http.send(bad, HttpResponse.BodyHandlers.ofString()).statusCode(), 400);
                HttpRequest missing = HttpRequest.newBuilder(URI.create(base + "/api/control/ZZZ-9")).header("X-Atlas-Request", "1").header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
                eq(http.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode(), 404);
                HttpRequest big = HttpRequest.newBuilder(URI.create(base + "/api/control/GOV-01")).header("X-Atlas-Request", "1").header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString("{\"notes\":\"" + "x".repeat(20000) + "\"}")).build();
                eq(http.send(big, HttpResponse.BodyHandlers.ofString()).statusCode(), 400);

                // Cross-origin writes are refused
                HttpRequest cross = HttpRequest.newBuilder(URI.create(base + "/api/meta")).header("X-Atlas-Request", "1").header("Content-Type", "application/json")
                        .header("Origin", "http://evil.example").PUT(HttpRequest.BodyPublishers.ofString("{\"organisation\":\"x\"}")).build();
                eq(http.send(cross, HttpResponse.BodyHandlers.ofString()).statusCode(), 403);

                // Report endpoints
                HttpResponse<String> rep = http.send(HttpRequest.newBuilder(URI.create(base + "/report.html")).build(), HttpResponse.BodyHandlers.ofString());
                yes(rep.body().contains("Compliance readiness report"), "report served");
                yes(rep.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"), "report CSP");
                eq(http.send(HttpRequest.newBuilder(URI.create(base + "/gaps.csv")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), 200);
                eq(http.send(HttpRequest.newBuilder(URI.create(base + "/nope")).build(), HttpResponse.BodyHandlers.ofString()).statusCode(), 404);
            } finally {
                s.stop();
                Files.deleteIfExists(tmp);
            }
        });

        test("server rejects foreign Host headers (DNS rebinding)", () -> {
            Server s = new Server(cat, new Assessment(), null);
            s.start(0);
            try (java.net.Socket sock = new java.net.Socket("127.0.0.1", s.port())) {
                sock.getOutputStream().write(("GET /api/state HTTP/1.1\r\nHost: attacker.example\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String resp = new String(sock.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
                yes(resp.startsWith("HTTP/1.1 403"), "foreign host refused: " + resp.split("\r\n")[0]);
            } finally {
                s.stop();
            }
        });

        test("UI source has no innerHTML, inline handlers or remote URLs", () -> {
            String js = Files.readString(Path.of("src/main/resources/public/app.js"));
            String html = Files.readString(Path.of("src/main/resources/public/index.html"));
            yes(!js.contains("innerHTML") && !js.contains("outerHTML") && !js.contains("insertAdjacentHTML") && !js.contains("eval("), "no HTML injection sinks");
            yes(!html.contains(" onclick=") && !html.contains("<script>") && !html.contains("style="), "no inline script or style");
            yes(!js.contains("http://") && !js.contains("https://") && !html.contains("https://"), "no third-party URLs");
        });

        System.out.println(passed + " tests passed");
    }

}
