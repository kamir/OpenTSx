package org.opentsx.wayang;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsxStateProgressTest {

    @Test
    void progressIsAnImmutableSnapshotOutsideTheCheckpointState() {
        TsxState state = new TsxState(Map.of());
        assertEquals(new TsxState.Progress(Long.MIN_VALUE, List.of(), 0), state.progress());
        List<String> waiting = new ArrayList<>(List.of("b"));
        state.progress("first", 20, waiting, 3);
        waiting.clear();
        TsxState.Progress saved = state.progress();
        assertEquals(new TsxState.Progress(20, List.of("b"), 3), saved);
        assertThrows(UnsupportedOperationException.class, () -> saved.waitingFor().clear());
        state.progress("second", 10, List.of("a", "b"), 2);
        assertEquals(new TsxState.Progress(10, List.of("a", "b"), 5), state.progress());
        assertEquals(new TsxState.Progress(20, List.of("b"), 3), saved);
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void theNextIncrementKeepsProgressUntilEachRestoredNodeUpdatesIt() {
        TsxState first = new TsxState(Map.of());
        first.progress("first", 20, List.of("b"), 3);
        first.progress("second", 10, List.of("a"), 2);
        TsxState next = new TsxState(Map.of());
        next.inheritProgress(first);
        assertEquals(first.progress(), next.progress());
        next.progress("first", 30, List.of(), 4);
        assertEquals(new TsxState.Progress(10, List.of("a"), 6), next.progress());
        assertEquals(new TsxState.Progress(10, List.of("a", "b"), 5), first.progress());
        assertTrue(next.snapshot().isEmpty());
    }
}
