package org.opentsx.conf;

/**
 * A usage or configuration error: one line of the form {@code <subject>: <problem>; <what is allowed>}. A command
 * prints it as {@code opentsx: <message>} on stderr and exits with code 2.
 */
public class OpenTsxConfException extends IllegalArgumentException {

    public OpenTsxConfException(String subject, String problem, String allowed) {
        super(subject + ": " + problem + "; " + allowed);
    }

    OpenTsxConfException(String message) {
        super(message);
    }
}
