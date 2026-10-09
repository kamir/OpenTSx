package org.opentsx.bridge;

import org.junit.jupiter.api.Test;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LegacyBridgeTest {

    @Test
    void regularTimeSeriesObjectRoundTripsLosslessly() {
        TimeSeriesObject tso = new TimeSeriesObject("wind.turbine.power_active park=ber-01,turbine=T07");
        long t0 = 1_700_000_000_000L;
        for (int i = 0; i < 144; i++) {
            tso.addValuePair(t0 + i * 600_000L, Math.sin(i / 10.0) * 1000);
        }
        Episode ep = LegacyBridge.fromTimeSeriesObject(tso, TimeUnitOfX.EPOCH_MILLIS, "legacy:test");

        assertEquals(TimeEncoding.REGULAR, ep.getTimeEncoding());
        assertEquals(600_000_000L, ep.getIntervalMicros());
        assertEquals(Map.of("park", "ber-01", "turbine", "T07"), ep.getSeries().getTags());

        TimeSeriesObject back = LegacyBridge.toTimeSeriesObject(ep, TimeUnitOfX.EPOCH_MILLIS);
        assertEquals(tso.getLabel(), back.getLabel());
        assertArrayEquals(tso.getYData(), back.getYData());
        assertEquals(tso.getXValues(), back.getXValues());
    }

    @Test
    void irregularTimeSeriesObjectKeepsItsTimestamps() {
        TimeSeriesObject tso = new TimeSeriesObject("rotor.rpm");
        double[] xs = {10.0, 10.5, 12.25, 20.0};
        for (double x : xs) {
            tso.addValuePair(x, x * 2);
        }
        Episode ep = LegacyBridge.fromTimeSeriesObject(tso, TimeUnitOfX.EPOCH_SECONDS, "legacy:test");
        assertEquals(TimeEncoding.IRREGULAR_DELTA, ep.getTimeEncoding());
        assertArrayEquals(new long[]{10_000_000, 10_500_000, 12_250_000, 20_000_000}, Episodes.timestampsMicros(ep));
        assertEquals(tso.getXValues(), LegacyBridge.toTimeSeriesObject(ep, TimeUnitOfX.EPOCH_SECONDS).getXValues());
    }

    @Test
    void episodesRecordV1UsesObservationTimestamps() {
        List<Observation> obs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            obs.add(Observation.newBuilder().setTimestamp(1_000L + i * 200).setUri("x").setValue(i).build());
        }
        EpisodesRecord v1 = EpisodesRecord.newBuilder()
                .setObservationArray(obs).setLabel("sensor.temp site=a").setTStart(0L).setTEnd(0L)
                .setZObservations(5L).setIncrement(999).setUri("opentsx://demo").build();

        Episode ep = LegacyBridge.fromEpisodesRecord(v1, null);

        assertEquals("sensor.temp", ep.getSeries().getMetric());
        assertEquals(200_000L, ep.getIntervalMicros()); // header increment (999) is ignored
        assertEquals(1_000_000L, Episodes.timestampsMicros(ep)[0]);
        assertEquals("legacy:opentsx://demo", ep.getProvenance().getSource());
    }

    @Test
    void labelParsingAndFormattingAreInverse() {
        SeriesKey k = LegacyBridge.seriesKeyFromLabel("m b=2,a=1", "kW");
        assertEquals("m a=1,b=2", LegacyBridge.legacyLabel(k));
        assertEquals("kW", k.getUnit());
        assertThrows(IllegalArgumentException.class, () -> LegacyBridge.seriesKeyFromLabel("m broken", null));
    }
}
