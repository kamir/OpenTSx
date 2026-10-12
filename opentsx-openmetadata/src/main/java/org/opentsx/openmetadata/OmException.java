package org.opentsx.openmetadata;

/** A non-successful OpenMetadata response. */
public final class OmException extends RuntimeException {

    private final int status;

    public OmException(String method, String path, int status, String body) {
        super(method + " " + path + " -> HTTP " + status + ": " + (body.length() > 2000 ? body.substring(0, 2000) + "..." : body));
        this.status = status;
    }

    public int status() {
        return status;
    }
}
