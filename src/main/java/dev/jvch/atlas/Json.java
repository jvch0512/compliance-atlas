package dev.jvch.atlas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal strict JSON reader and writer (objects, arrays, strings, numbers, booleans, null). */
public final class Json {
    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) throw new IllegalArgumentException("expected a JSON object");
        return (Map<String, Object>) v;
    }

    private IllegalArgumentException err(String m) { return new IllegalArgumentException("JSON: " + m + " at " + i); }

    private void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return obj(depth);
            case '[': return arr(depth);
            case '"': return str();
            case 't': lit("true"); return Boolean.TRUE;
            case 'f': lit("false"); return Boolean.FALSE;
            case 'n': lit("null"); return null;
            default: return num();
        }
    }

    private void lit(String w) {
        if (!s.startsWith(w, i)) throw err("bad literal");
        i += w.length();
    }

    private Map<String, Object> obj(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("expected key");
            String k = str();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("expected ':'");
            i++; ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw err("unterminated object");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected ',' or '}'");
        }
    }

    private List<Object> arr(int depth) {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (i < s.length() && s.charAt(i) == ']') { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw err("unterminated array");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("expected ',' or ']'");
        }
    }

    private String str() {
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("bad unicode escape");
                    try { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                    catch (NumberFormatException ex) { throw err("bad unicode escape"); }
                    i += 4;
                    break;
                default: throw err("bad escape");
            }
        }
    }

    private Object num() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("unexpected character");
        String t = s.substring(st, i);
        try {
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) return Double.valueOf(t);
            return Long.valueOf(t);
        } catch (NumberFormatException e) { throw err("bad number"); }
    }

    // ---- writer ----

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(b, v);
        return b.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder b, Object v) {
        if (v == null) b.append("null");
        else if (v instanceof String) quote(b, (String) v);
        else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) b.append(v);
        else if (v instanceof Double) {
            double d = (Double) v;
            b.append(Double.isFinite(d) ? (d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d)) : "null");
        } else if (v instanceof Map) {
            b.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                quote(b, e.getKey());
                b.append(':');
                write(b, e.getValue());
            }
            b.append('}');
        } else if (v instanceof Iterable) {
            b.append('[');
            boolean first = true;
            for (Object o : (Iterable<Object>) v) {
                if (!first) b.append(',');
                first = false;
                write(b, o);
            }
            b.append(']');
        } else throw new IllegalArgumentException("cannot serialize " + v.getClass());
    }

    private static void quote(StringBuilder b, String s) {
        b.append('"');
        for (int k = 0; k < s.length(); k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"': b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20 || c == '<' || c == '>' || c == '&') b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        b.append('"');
    }
}
