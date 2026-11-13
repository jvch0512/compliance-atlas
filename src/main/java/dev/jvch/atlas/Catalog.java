package dev.jvch.atlas;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The bundled control catalog, loaded from a pipe-separated resource file. */
public final class Catalog {
    private final Map<String, Control> byId;

    private Catalog(Map<String, Control> byId) { this.byId = byId; }

    public static Catalog load() {
        try (InputStream in = Catalog.class.getResourceAsStream("/data/controls.psv")) {
            if (in == null) throw new IllegalStateException("controls.psv missing from classpath");
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Catalog parse(String text) {
        Map<String, Control> m = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new java.io.StringReader(text))) {
            String line;
            int n = 0;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\\|", -1);
                if (p.length != 11) throw new IllegalArgumentException("line " + n + ": expected 11 fields, got " + p.length);
                int w = Integer.parseInt(p[3].trim());
                if (w < 1 || w > 3) throw new IllegalArgumentException("line " + n + ": weight must be 1..3");
                Map<Framework, List<String>> refs = new EnumMap<>(Framework.class);
                Framework[] fws = Framework.values();
                for (int k = 0; k < fws.length; k++) {
                    List<String> l = new ArrayList<>();
                    for (String s : p[6 + k].split(";")) if (!s.isBlank()) l.add(s.trim());
                    refs.put(fws[k], Collections.unmodifiableList(l));
                }
                Control c = new Control(p[0].trim(), p[1].trim(), p[2].trim(), w, p[4].trim(), p[5].trim(), refs);
                if (m.put(c.id(), c) != null) throw new IllegalArgumentException("duplicate control " + c.id());
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new Catalog(Collections.unmodifiableMap(m));
    }

    public List<Control> controls() { return List.copyOf(byId.values()); }
    public Control get(String id) { return byId.get(id); }
    public boolean has(String id) { return byId.containsKey(id); }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> fws = new ArrayList<>();
        for (Framework f : Framework.values()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id());
            fm.put("label", f.label());
            fm.put("name", f.longName());
            fws.add(fm);
        }
        out.put("frameworks", fws);
        List<Object> cs = new ArrayList<>();
        for (Control c : byId.values()) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("id", c.id());
            cm.put("domain", c.domain());
            cm.put("title", c.title());
            cm.put("weight", c.weight());
            cm.put("summary", c.summary());
            cm.put("evidenceHint", c.evidenceHint());
            Map<String, Object> rm = new LinkedHashMap<>();
            for (Framework f : Framework.values()) if (c.maps(f)) rm.put(f.id(), c.refs().get(f));
            cm.put("refs", rm);
            cs.add(cm);
        }
        out.put("controls", cs);
        return out;
    }
}
