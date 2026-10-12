package org.opentsx.market.source;

/** A source needs credentials that are not configured; the message says which environment variables to set. */
public final class MissingCredentials extends RuntimeException {

    public MissingCredentials(String message) {
        super(message);
    }
}
