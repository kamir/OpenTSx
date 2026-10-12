package org.opentsx.twin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory {@link MessageSink}. */
public final class MessageLog implements MessageSink {

    private final List<PortMessage> messages = new ArrayList<>();
    private final List<Barrier> barriers = new ArrayList<>();

    @Override
    public void message(PortMessage m) {
        messages.add(m);
    }

    @Override
    public void barrier(Barrier b) {
        barriers.add(b);
    }

    public List<PortMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    public List<Barrier> barriers() {
        return Collections.unmodifiableList(barriers);
    }
}
