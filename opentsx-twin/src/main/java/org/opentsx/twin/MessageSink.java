package org.opentsx.twin;

/** Receives port messages and barriers in order ({@link MessageLog} keeps them in memory; no topic carries them). */
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
