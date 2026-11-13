package dev.jvch.atlas;

import java.util.List;
import java.util.Map;

/** A common control and the clauses it maps to in each framework. */
public record Control(String id, String domain, String title, int weight, String summary, String evidenceHint,
                      Map<Framework, List<String>> refs) {

    public boolean maps(Framework f) { return !refs.getOrDefault(f, List.of()).isEmpty(); }
}
