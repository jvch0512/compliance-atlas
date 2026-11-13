package dev.jvch.atlas;

import java.util.Locale;

/** Regulatory and standards frameworks covered by the crosswalk. */
public enum Framework {
    NIS2("NIS2", "NIS2 Directive (EU) 2022/2555"),
    ISO27001("ISO 27001", "ISO/IEC 27001:2022"),
    ENS("ENS", "Esquema Nacional de Seguridad (RD 311/2022)"),
    GDPR("GDPR", "GDPR (EU) 2016/679"),
    DORA("DORA", "DORA (EU) 2022/2554");

    private final String label;
    private final String longName;

    Framework(String label, String longName) { this.label = label; this.longName = longName; }

    public String label() { return label; }
    public String longName() { return longName; }
    public String id() { return name().toLowerCase(Locale.ROOT); }

    public static Framework fromId(String s) {
        for (Framework f : values()) if (f.id().equals(s.toLowerCase(Locale.ROOT))) return f;
        throw new IllegalArgumentException("unknown framework: " + s);
    }
}
