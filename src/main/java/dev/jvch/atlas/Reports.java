package dev.jvch.atlas;

import java.util.List;
import java.util.Map;

/** Board-ready report renderers: Markdown, standalone HTML (no scripts) and CSV gap list. */
public final class Reports {
    private Reports() {}

    public static int pctInt(double r) { return (int) Math.round(r * 100); }

    public static String pct(double r) { return pctInt(r) + "%"; }

    public static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                case '\'': b.append("&#39;"); break;
                default: b.append(c);
            }
        }
        return b.toString();
    }

    private static String mdCell(String s) { return s.replace("|", "\\|").replace("\n", " ").replace("\r", " "); }

    private static String statusLabel(Scoring.Gap g) {
        if (g.unverified()) return "implemented, evidence missing or expired";
        return g.status().id().replace('_', ' ');
    }

    public static String markdown(Catalog cat, Assessment a) {
        Map<Framework, Scoring.FrameworkScore> now = Scoring.readiness(cat, a);
        Map<Framework, Scoring.FrameworkScore> up = Scoring.upliftIfClosed(cat, a, 5);
        List<Scoring.Gap> gaps = Scoring.gaps(cat, a);
        StringBuilder b = new StringBuilder();
        b.append("# Compliance readiness report\n\n");
        b.append("**Organisation:** ").append(mdCell(a.organisation)).append("  \n");
        b.append("**As of:** ").append(a.asOf).append("  \n");
        b.append("**Overall readiness:** ").append(pct(Scoring.overall(now))).append("\n\n");
        b.append("## Readiness by framework\n\n| Framework | Readiness | If top 5 gaps closed | Controls | Implemented | Partial | Not started | Unverified |\n|---|---|---|---|---|---|---|---|\n");
        for (Scoring.FrameworkScore s : now.values()) {
            b.append("| ").append(s.framework().label()).append(" | ").append(pct(s.readiness())).append(" | ")
                    .append(pct(up.get(s.framework()).readiness())).append(" | ").append(s.controls()).append(" | ")
                    .append(s.implemented()).append(" | ").append(s.partial()).append(" | ").append(s.notStarted()).append(" | ")
                    .append(s.unverified()).append(" |\n");
        }
        b.append("\n## Priority gaps (most requirements closed per control)\n\n");
        if (gaps.isEmpty()) b.append("No open gaps.\n");
        else {
            b.append("| # | Control | Status | Frameworks | Leverage | Owner | Due |\n|---|---|---|---|---|---|---|\n");
            int i = 1;
            for (Scoring.Gap g : gaps) {
                b.append("| ").append(i++).append(" | ").append(g.control().id()).append(" ").append(mdCell(g.control().title()))
                        .append(" | ").append(statusLabel(g)).append(g.overdue() ? " (overdue)" : "").append(" | ")
                        .append(g.frameworksHit()).append(" | ").append(Scoring.round(g.leverage())).append(" | ")
                        .append(mdCell(g.owner().isBlank() ? "unassigned" : g.owner())).append(" | ")
                        .append(g.due() == null ? "" : g.due().toString()).append(" |\n");
            }
        }
        b.append("\n---\nIndicative crosswalk for orientation, not legal advice. Verify clause references against the official texts.\n");
        return b.toString();
    }

    public static String csv(Catalog cat, Assessment a) {
        StringBuilder b = new StringBuilder("rank,control,title,status,frameworks_hit,leverage,overdue,owner,due\n");
        int i = 1;
        for (Scoring.Gap g : Scoring.gaps(cat, a)) {
            b.append(i++).append(',').append(csvCell(g.control().id())).append(',').append(csvCell(g.control().title())).append(',')
                    .append(csvCell(statusLabel(g))).append(',').append(g.frameworksHit()).append(',')
                    .append(Scoring.round(g.leverage())).append(',').append(g.overdue()).append(',')
                    .append(csvCell(g.owner())).append(',').append(g.due() == null ? "" : g.due()).append('\n');
        }
        return b.toString();
    }

    /** Quotes a CSV cell and neutralises spreadsheet formula injection. */
    public static String csvCell(String s) {
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String bar(double r) {
        int w = (int) Math.round(r * 100);
        String cls = r >= 0.75 ? "ok" : r >= 0.4 ? "mid" : "low";
        return "<div class=\"bar\"><div class=\"fill " + cls + "\" style=\"width:" + w + "%\"></div></div>";
    }

    public static String html(Catalog cat, Assessment a) {
        Map<Framework, Scoring.FrameworkScore> now = Scoring.readiness(cat, a);
        Map<Framework, Scoring.FrameworkScore> up = Scoring.upliftIfClosed(cat, a, 5);
        List<Scoring.Gap> gaps = Scoring.gaps(cat, a);
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Compliance readiness report</title>")
                .append("<style>body{font:15px/1.5 system-ui,sans-serif;margin:0;background:#f6f7f9;color:#1c2330}main{max-width:960px;margin:0 auto;padding:32px 24px}")
                .append("h1{margin:0 0 4px}h2{margin-top:32px}.meta{color:#5b6677}.card{background:#fff;border:1px solid #dde1e8;border-radius:10px;padding:16px 20px;margin:12px 0}")
                .append(".row{display:grid;grid-template-columns:120px 1fr 150px;gap:16px;align-items:center;margin:10px 0}.bar{background:#e6e9ef;border-radius:6px;height:14px;overflow:hidden}")
                .append(".fill{height:100%}.ok{background:#2e9e6a}.mid{background:#e0a526}.low{background:#d2503f}table{border-collapse:collapse;width:100%;background:#fff}")
                .append("th,td{text-align:left;padding:8px 10px;border-bottom:1px solid #e6e9ef;font-size:14px}th{background:#eef1f6}.tag{font-size:12px;padding:2px 8px;border-radius:10px;background:#eef1f6}")
                .append(".bad{background:#fbe3df;color:#8a2417}.foot{color:#5b6677;font-size:13px;margin-top:32px}td:last-child{white-space:nowrap}</style></head><body><main>");
        b.append("<h1>Compliance readiness report</h1><div class=\"meta\">").append(esc(a.organisation)).append(" &middot; as of ")
                .append(a.asOf).append(" &middot; overall ").append(pct(Scoring.overall(now))).append("</div>");
        b.append("<h2>Readiness by framework</h2><div class=\"card\">");
        for (Scoring.FrameworkScore s : now.values()) {
            b.append("<div class=\"row\"><strong>").append(esc(s.framework().label())).append("</strong>").append(bar(s.readiness()))
                    .append("<span>").append(pct(s.readiness())).append(" now, ").append(pct(up.get(s.framework()).readiness()))
                    .append(" after top 5</span></div>");
        }
        b.append("</div><h2>Priority gaps</h2><p class=\"meta\">Ranked by how many in-scope requirements one control closes, weighted by importance.</p>");
        if (gaps.isEmpty()) b.append("<p>No open gaps.</p>");
        else {
            b.append("<table><thead><tr><th>#</th><th>Control</th><th>Status</th><th>Frameworks</th><th>Owner</th><th>Due</th></tr></thead><tbody>");
            int i = 1;
            for (Scoring.Gap g : gaps) {
                b.append("<tr><td>").append(i++).append("</td><td><strong>").append(esc(g.control().id())).append("</strong> ")
                        .append(esc(g.control().title())).append("</td><td><span class=\"tag\">").append(esc(statusLabel(g))).append("</span>")
                        .append(g.overdue() ? " <span class=\"tag bad\">overdue</span>" : "").append("</td><td>").append(g.frameworksHit())
                        .append("</td><td>").append(esc(g.owner().isBlank() ? "unassigned" : g.owner())).append("</td><td>")
                        .append(g.due() == null ? "" : g.due().toString()).append("</td></tr>");
            }
            b.append("</tbody></table>");
        }
        b.append("<p class=\"foot\">Indicative crosswalk for orientation, not legal advice. Verify clause references against the official texts.</p></main></body></html>");
        return b.toString();
    }
}
