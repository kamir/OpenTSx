package org.opentsx.modbus.map;

import java.util.List;

/** A register map that cannot be used; lists every problem found, not just the first. */
public final class RegisterMapException extends IllegalArgumentException {

    private final List<String> problems;

    public RegisterMapException(String source, List<String> problems) {
        super("register map " + source + " is invalid:\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
