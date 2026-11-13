package dev.jvch.atlas;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Readiness scoring and gap ranking.
 *
 * <p>Rules: implemented with at least one valid evidence item scores 1.0; implemented without valid
 * (unexpired) evidence scores 0.5 and is flagged "unverified"; partial scores 0.5; not started scores 0.
 * Not-applicable controls leave the denominator. Readiness is the weighted mean over controls that map
 * to the framework.
 */
public final class Scoring {
    private Scoring() {}

    public static final int EVIDENCE_MAX_AGE_DAYS = 365;

    public record FrameworkScore(Framework framework, int controls, int implemented, int partial, int notStarted,
                                 int unverified, double readiness) {}

    public record Gap(Control control, Status status, double score, int frameworksHit, double leverage,
                      boolean overdue, boolean unverified, String owner, LocalDate due) {}

    public static boolean evidenceValid(Assessment.Evidence e, LocalDate asOf) {
        if (e.validUntil() != null) return !e.validUntil().isBefore(asOf);
        if (e.reviewed() == null) return false;
        return !e.reviewed().plusDays(EVIDENCE_MAX_AGE_DAYS).isBefore(asOf);
    }

    public static boolean verified(Assessment.Entry e, LocalDate asOf) {
        for (Assessment.Evidence x : e.evidence) if (evidenceValid(x, asOf)) return true;
        return false;
    }

    /** Score in [0,1] or -1 when the control is not applicable. */
    public static double score(Assessment.Entry e, LocalDate asOf) {
        switch (e.status) {
            case NOT_APPLICABLE: return -1;
            case NOT_STARTED: return 0;
            case PARTIAL: return 0.5;
            default: return verified(e, asOf) ? 1.0 : 0.5;
        }
    }

    private static int hits(Control c, Set<Framework> scope) {
        int n = 0;
        for (Framework f : scope) if (c.maps(f)) n++;
        return n;
    }

    private static Assessment.Entry entryOf(Assessment a, String id) {
        Assessment.Entry e = a.entries.get(id);
        return e == null ? new Assessment.Entry() : e;
    }

    /** Readiness per in-scope framework. {@code forcedFull} controls are treated as fully implemented and verified. */
    public static Map<Framework, FrameworkScore> readiness(Catalog cat, Assessment a, Set<String> forcedFull) {
        Map<Framework, FrameworkScore> out = new EnumMap<>(Framework.class);
        for (Framework f : Framework.values()) {
            if (!a.scope.contains(f)) continue;
            double num = 0, den = 0;
            int n = 0, impl = 0, part = 0, none = 0, unv = 0;
            for (Control c : cat.controls()) {
                if (!c.maps(f)) continue;
                Assessment.Entry e = entryOf(a, c.id());
                double s = forcedFull.contains(c.id()) && e.status != Status.NOT_APPLICABLE ? 1.0 : score(e, a.asOf);
                if (s < 0) continue;
                n++;
                num += c.weight() * s;
                den += c.weight();
                if (forcedFull.contains(c.id())) { impl++; continue; }
                if (e.status == Status.IMPLEMENTED) { impl++; if (s < 1.0) unv++; }
                else if (e.status == Status.PARTIAL) part++;
                else none++;
            }
            out.put(f, new FrameworkScore(f, n, impl, part, none, unv, den == 0 ? 0 : num / den));
        }
        return out;
    }

    public static Map<Framework, FrameworkScore> readiness(Catalog cat, Assessment a) {
        return readiness(cat, a, Set.of());
    }

    /** Gaps ranked by leverage: weight x number of in-scope frameworks satisfied x remaining distance to full. */
    public static List<Gap> gaps(Catalog cat, Assessment a) {
        List<Gap> out = new ArrayList<>();
        for (Control c : cat.controls()) {
            int h = hits(c, a.scope);
            if (h == 0) continue;
            Assessment.Entry e = entryOf(a, c.id());
            double s = score(e, a.asOf);
            if (s < 0 || s >= 1.0) continue;
            boolean unverified = e.status == Status.IMPLEMENTED;
            boolean overdue = e.due != null && e.due.isBefore(a.asOf) && e.status != Status.IMPLEMENTED;
            double lev = c.weight() * h * (1.0 - s);
            out.add(new Gap(c, e.status, s, h, lev, overdue, unverified, e.owner, e.due));
        }
        out.sort(Comparator.comparingDouble(Gap::leverage).reversed().thenComparing(g -> g.control().id()));
        return out;
    }

    /** Readiness if the top {@code n} gaps were closed. */
    public static Map<Framework, FrameworkScore> upliftIfClosed(Catalog cat, Assessment a, int n) {
        Set<String> ids = new java.util.HashSet<>();
        List<Gap> g = gaps(cat, a);
        for (int i = 0; i < Math.min(n, g.size()); i++) ids.add(g.get(i).control().id());
        return readiness(cat, a, ids);
    }

    public static double overall(Map<Framework, FrameworkScore> m) {
        if (m.isEmpty()) return 0;
        double s = 0;
        for (FrameworkScore f : m.values()) s += f.readiness();
        return s / m.size();
    }

    // ---- JSON view for the API and UI ----

    public static Map<String, Object> summaryMap(Catalog cat, Assessment a) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<Framework, FrameworkScore> now = readiness(cat, a);
        Map<Framework, FrameworkScore> up = upliftIfClosed(cat, a, 5);
        List<Object> fl = new ArrayList<>();
        for (FrameworkScore s : now.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.framework().id());
            m.put("label", s.framework().label());
            m.put("controls", s.controls());
            m.put("implemented", s.implemented());
            m.put("partial", s.partial());
            m.put("notStarted", s.notStarted());
            m.put("unverified", s.unverified());
            m.put("readiness", round(s.readiness()));
            m.put("readinessIfTop5Closed", round(up.get(s.framework()).readiness()));
            fl.add(m);
        }
        out.put("frameworks", fl);
        out.put("overall", round(overall(now)));
        List<Object> gl = new ArrayList<>();
        for (Gap g : gaps(cat, a)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.control().id());
            m.put("title", g.control().title());
            m.put("status", g.status().id());
            m.put("frameworksHit", g.frameworksHit());
            m.put("leverage", round(g.leverage()));
            m.put("overdue", g.overdue());
            m.put("unverified", g.unverified());
            m.put("owner", g.owner());
            m.put("due", g.due() == null ? null : g.due().toString());
            gl.add(m);
        }
        out.put("gaps", gl);
        Map<String, Object> sc = new LinkedHashMap<>();
        for (Control c : cat.controls()) {
            Assessment.Entry e = a.entries.get(c.id());
            sc.put(c.id(), round(e == null ? 0 : score(e, a.asOf)));
        }
        out.put("scores", sc);
        return out;
    }

    public static double round(double d) { return Math.round(d * 1000.0) / 1000.0; }
}
