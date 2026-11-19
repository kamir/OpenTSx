package org.opentsx.store;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SeriesStoreContractTest {

    @Test
    void theLimitContractRefusesAnAdapterThatReturnsNothing() {
        SeriesStoreContract contract = new SeriesStoreContract() {
            @Override
            protected SeriesStore newStore() {
                return new EmptyWhenLimited(new InMemorySeriesStore());
            }
        };
        contract.open();
        try {
            assertThrows(AssertionFailedError.class, contract::limitReturnsTheFirstEpisodesInReadOrder);
        } finally {
            contract.closeStore();
        }
    }

    private record EmptyWhenLimited(SeriesStore delegate) implements SeriesStore {

        @Override
        public StoreCapabilities capabilities() {
            return delegate.capabilities();
        }

        @Override
        public WriteResult write(Collection<Episode> episodes) {
            return delegate.write(episodes);
        }

        @Override
        public List<Episode> read(EpisodeQuery query) {
            return query.limit() == Integer.MAX_VALUE ? delegate.read(query) : List.of();
        }

        @Override
        public List<SeriesKey> listSeries(SeriesSelector selector) {
            return delegate.listSeries(selector);
        }
    }
}
