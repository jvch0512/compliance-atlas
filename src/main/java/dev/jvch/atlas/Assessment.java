package dev.jvch.atlas;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The state of one organisation's assessment: scope, control statuses and evidence. */
public final class Assessment {
    public static final int MAX_EVIDENCE = 20;

    /** A piece of evidence with the date it was reviewed and optional expiry. */
    public record Evidence(String title, LocalDate reviewed, LocalDate validUntil) {}

    /** The state of a single control. */
    public static final class Entry {
        public Status status = Status.NOT_STARTED;
        public String owner = "";
        public LocalDate due;
        public String notes = "";
        public List<Evidence> evidence = new ArrayList<>();
    }

    public String organisation = "Unnamed organisation";
    public LocalDate asOf = LocalDate.now();
    public Set<Framework> scope = EnumSet.allOf(Framework.class);
    public final Map<String, Entry> entries = new LinkedHashMap<>();

    public Entry entry(String id) { return entries.computeIfAbsent(id, k -> new Entry()); }

    // ---- parsing ----

    public static Assessment fromJson(String json, Catalog catalog) {
        return fromMap(Json.object(json), catalog);
    }

    @SuppressWarnings("unchecked")
    public static Assessment fromMap(Map<String, Object> m, Catalog catalog) {
        Assessment a = new Assessment();
        if (m.containsKey("organisation")) a.organisation = str(m.get("organisation"), "organisation", 100);
        if (m.containsKey("asOf")) a.asOf = date(m.get("asOf"), "asOf");
        if (m.containsKey("scope")) {
            if (!(m.get("scope") instanceof List)) throw new IllegalArgumentException("scope must be a list");
            Set<Framework> s = EnumSet.noneOf(Framework.class);
            for (Object o : (List<Object>) m.get("scope")) s.add(Framework.fromId(str(o, "scope", 20)));
            if (s.isEmpty()) throw new IllegalArgumentException("scope must contain at least one framework");
            a.scope = s;
        }
        Object ce = m.get("controls");
        if (ce != null) {
            if (!(ce instanceof Map)) throw new IllegalArgumentException("controls must be an object");
            for (Map.Entry<String, Object> e : ((Map<String, Object>) ce).entrySet()) {
                if (!catalog.has(e.getKey())) throw new IllegalArgumentException("unknown control: " + e.getKey());
                if (!(e.getValue() instanceof Map)) throw new IllegalArgumentException("control " + e.getKey() + " must be an object");
                a.entries.put(e.getKey(), parseEntry((Map<String, Object>) e.getValue()));
            }
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    public static Entry parseEntry(Map<String, Object> m) {
        Entry e = new Entry();
        if (m.containsKey("status")) e.status = Status.fromId(str(m.get("status"), "status", 20));
        if (m.containsKey("owner")) e.owner = str(m.get("owner"), "owner", 80);
        if (m.get("due") != null && !"".equals(m.get("due"))) e.due = date(m.get("due"), "due");
        if (m.containsKey("notes")) e.notes = str(m.get("notes"), "notes", 2000);
        Object ev = m.get("evidence");
        if (ev != null) {
            if (!(ev instanceof List)) throw new IllegalArgumentException("evidence must be a list");
            List<Object> l = (List<Object>) ev;
            if (l.size() > MAX_EVIDENCE) throw new IllegalArgumentException("too many evidence items (max " + MAX_EVIDENCE + ")");
            for (Object o : l) {
                if (!(o instanceof Map)) throw new IllegalArgumentException("evidence item must be an object");
                Map<String, Object> em = (Map<String, Object>) o;
                String title = str(em.get("title"), "evidence title", 200);
                if (title.isBlank()) throw new IllegalArgumentException("evidence title is required");
                LocalDate reviewed = em.get("reviewed") == null || "".equals(em.get("reviewed")) ? null : date(em.get("reviewed"), "reviewed");
                LocalDate until = em.get("validUntil") == null || "".equals(em.get("validUntil")) ? null : date(em.get("validUntil"), "validUntil");
                e.evidence.add(new Evidence(title, reviewed, until));
            }
        }
        return e;
    }

    private static String str(Object o, String field, int max) {
        if (!(o instanceof String)) throw new IllegalArgumentException(field + " must be a string");
        String s = (String) o;
        if (s.length() > max) throw new IllegalArgumentException(field + " is too long (max " + max + ")");
        return s.strip();
    }

    private static LocalDate date(Object o, String field) {
        try {
            return LocalDate.parse(str(o, field, 10));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " must be a date like 2026-03-31");
        }
    }

    // ---- serialisation ----

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("organisation", organisation);
        out.put("asOf", asOf.toString());
        List<Object> sc = new ArrayList<>();
        for (Framework f : scope) sc.add(f.id());
        out.put("scope", sc);
        Map<String, Object> cs = new LinkedHashMap<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) cs.put(e.getKey(), entryMap(e.getValue()));
        out.put("controls", cs);
        return out;
    }

    public static Map<String, Object> entryMap(Entry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", e.status.id());
        m.put("owner", e.owner);
        m.put("due", e.due == null ? null : e.due.toString());
        m.put("notes", e.notes);
        List<Object> ev = new ArrayList<>();
        for (Evidence x : e.evidence) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("title", x.title());
            em.put("reviewed", x.reviewed() == null ? null : x.reviewed().toString());
            em.put("validUntil", x.validUntil() == null ? null : x.validUntil().toString());
            ev.add(em);
        }
        m.put("evidence", ev);
        return m;
    }
}
