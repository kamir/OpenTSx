package org.opentsx.wayang.site;

public final class PlanRefusal extends IllegalArgumentException {
    private final String code;

    public PlanRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() { return code; }
}
