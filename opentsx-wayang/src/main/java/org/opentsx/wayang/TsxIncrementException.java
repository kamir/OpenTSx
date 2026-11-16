package org.opentsx.wayang;

/** A failed increment; the message is the root cause, as the status record reports it (S3.2, S5.4). */
public final class TsxIncrementException extends RuntimeException {

    public TsxIncrementException(String message, Throwable cause) {
        super(message, cause);
    }
}
