package dev.jvch.atlas;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Command line entry point: catalog, gaps, report, check (CI gate) and serve (web UI). */
public final class Main {
    private Main() {}

    static final String USAGE = String.join("\n",
            "compliance-atlas: one control library mapped to NIS2, ISO 27001, ENS, GDPR and DORA",
            "",
            "  catalog [--framework nis2|iso27001|ens|gdpr|dora]   list controls and clause references",
            "  gaps <assessment.json>                              ranked gaps (most requirements closed first)",
            "  report <assessment.json> [--format md|html|csv|json]  board-ready report on stdout",
            "  check <assessment.json> [--min 70]                  exit 2 if any in-scope framework is below the threshold",
            "  serve [assessment.json] [--port 8500]               local web UI (127.0.0.1 only), edits saved to the file",
            "");

    public static void main(String[] args) throws Exception {
        System.exit(run(args, System.out, System.err));
    }

    static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws Exception {
        if (args.length == 0 || args[0].equals("-h") || args[0].equals("--help")) { out.print(USAGE); return args.length == 0 ? 1 : 0; }
        Catalog cat = Catalog.load();
        String cmd = args[0];
        try {
            switch (cmd) {
                case "catalog": return catalog(cat, args, out);
                case "gaps": {
                    Assessment a = load(cat, need(args, 1));
                    int i = 1;
                    for (Scoring.Gap g : Scoring.gaps(cat, a)) {
                        out.printf("%2d. %-7s %-44s frameworks=%d leverage=%.1f %s%s%n", i++, g.control().id(), g.control().title(),
                                g.frameworksHit(), g.leverage(), g.status().id(), g.overdue() ? " OVERDUE" : "");
                    }
                    return 0;
                }
                case "report": {
                    Assessment a = load(cat, need(args, 1));
                    String f = opt(args, "--format", "md");
                    switch (f) {
                        case "md": out.print(Reports.markdown(cat, a)); break;
                        case "html": out.print(Reports.html(cat, a)); break;
                        case "csv": out.print(Reports.csv(cat, a)); break;
                        case "json": out.println(Json.write(Scoring.summaryMap(cat, a))); break;
                        default: err.println("unknown format: " + f); return 1;
                    }
                    return 0;
                }
                case "check": {
                    Assessment a = load(cat, need(args, 1));
                    double min = Double.parseDouble(opt(args, "--min", "70")) / 100.0;
                    int rc = 0;
                    for (Scoring.FrameworkScore s : Scoring.readiness(cat, a).values()) {
                        boolean ok = s.readiness() >= min;
                        out.printf("%-10s %4s %s%n", s.framework().label(), Reports.pct(s.readiness()), ok ? "ok" : "BELOW THRESHOLD");
                        if (!ok) rc = 2;
                    }
                    return rc;
                }
                case "serve": return serve(cat, args, out);
                default: err.println("unknown command: " + cmd); out.print(USAGE); return 1;
            }
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    private static int catalog(Catalog cat, String[] args, java.io.PrintStream out) {
        String fw = opt(args, "--framework", null);
        Framework only = fw == null ? null : Framework.fromId(fw);
        for (Control c : cat.controls()) {
            if (only != null && !c.maps(only)) continue;
            out.printf("%-7s %-16s %s%n", c.id(), c.domain(), c.title());
            for (Framework f : Framework.values()) {
                if (only != null && f != only) continue;
                if (c.maps(f)) out.printf("          %-10s %s%n", f.label(), String.join(", ", c.refs().get(f)));
            }
        }
        return 0;
    }

    private static int serve(Catalog cat, String[] args, java.io.PrintStream out) throws IOException, InterruptedException {
        Path file = args.length > 1 && !args[1].startsWith("--") ? Path.of(args[1]) : null;
        int port = Integer.parseInt(opt(args, "--port", "8500"));
        if (port < 0 || port > 65535) throw new IllegalArgumentException("invalid port");
        Assessment a = file != null && Files.exists(file) ? load(cat, file.toString()) : new Assessment();
        Server s = new Server(cat, a, file);
        s.start(port);
        out.println("compliance-atlas listening on http://127.0.0.1:" + s.port() + (file == null ? " (changes are not saved)" : " (saving to " + file + ")"));
        Thread.currentThread().join();
        return 0;
    }

    static Assessment load(Catalog cat, String path) throws IOException {
        return Assessment.fromJson(Files.readString(Path.of(path), StandardCharsets.UTF_8), cat);
    }

    private static String need(String[] args, int i) {
        if (args.length <= i || args[i].startsWith("--")) throw new IllegalArgumentException("missing assessment file");
        return args[i];
    }

    private static String opt(String[] args, String name, String def) {
        for (int i = 0; i < args.length - 1; i++) if (args[i].equals(name)) return args[i + 1];
        return def;
    }

}
