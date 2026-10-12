package org.opentsx.twin;

/** Receives port messages and barriers in order (in-memory log, later a KafScale topic). */
public interface MessageSink {

    void message(PortMessage m);

    void barrier(Barrier b);

    MessageSink NONE = new MessageSink() {
        @Override
        public void message(PortMessage m) {
        }

        @Override
        public void barrier(Barrier b) {
        }
    };
}
