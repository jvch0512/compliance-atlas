package dev.jvch.atlas;

/** Implementation status of a control. */
public enum Status {
    NOT_STARTED("not_started"), PARTIAL("partial"), IMPLEMENTED("implemented"), NOT_APPLICABLE("not_applicable");

    private final String id;
    Status(String id) { this.id = id; }
    public String id() { return id; }

    public static Status fromId(String s) {
        for (Status v : values()) if (v.id.equals(s)) return v;
        throw new IllegalArgumentException("unknown status: " + s);
    }
}
