package org.opentsx.bridge;

import org.junit.jupiter.api.Test;
import org.opentsx.data.model.EpisodesRecord;
import org.opentsx.data.model.Observation;
import org.opentsx.data.series.TimeSeriesObject;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesKey;
import org.opentsx.model.v2.TimeEncoding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void anUnlabeledV1RecordWithoutAKeyIsRefused() {
        List<Observation> obs = List.of(Observation.newBuilder().setTimestamp(1_000L).setUri("x").setValue(1).build());
        EpisodesRecord named = EpisodesRecord.newBuilder().setObservationArray(obs).setUri("opentsx://demo").build();
        EpisodesRecord anonymous = EpisodesRecord.newBuilder().setObservationArray(obs).build();

        assertEquals("label: missing in the EpisodesRecord \"opentsx://demo\"; expected a label such as"
                        + " \"metric k1=v1,k2=v2\" or a SeriesKey argument",
                assertThrows(IllegalArgumentException.class,
                        () -> LegacyBridge.fromEpisodesRecord(named, null)).getMessage());
        assertEquals("label: missing in an EpisodesRecord without uri; expected a label such as"
                        + " \"metric k1=v1,k2=v2\" or a SeriesKey argument",
                assertThrows(IllegalArgumentException.class,
                        () -> LegacyBridge.fromEpisodesRecord(anonymous, null)).getMessage());
        SeriesKey key = LegacyBridge.seriesKeyFromLabel("sensor.temp site=a", null);
        assertEquals(key, LegacyBridge.fromEpisodesRecord(anonymous, key).getSeries());
    }

    @Test
    void anEmptySeriesIsRefused() {
        TimeSeriesObject empty = new TimeSeriesObject("rotor.rpm");
        assertEquals("values: empty; expected at least one value", assertThrows(IllegalArgumentException.class,
                () -> LegacyBridge.fromTimeSeriesObject(empty, TimeUnitOfX.EPOCH_SECONDS, "legacy:test")).getMessage());
        EpisodesRecord none = EpisodesRecord.newBuilder().setObservationArray(List.of()).setLabel("rotor.rpm").build();
        assertEquals("values: empty; expected at least one value", assertThrows(IllegalArgumentException.class,
                () -> LegacyBridge.fromEpisodesRecord(none, null)).getMessage());
    }

    @Test
    void episodesCarryTheProducerOfThisBuild() {
        TimeSeriesObject tso = new TimeSeriesObject("rotor.rpm");
        tso.addValuePair(1.0, 2.0);
        EpisodesRecord v1 = EpisodesRecord.newBuilder().setLabel("rotor.rpm").setObservationArray(List.of(
                Observation.newBuilder().setTimestamp(1_000L).setUri("x").setValue(1).build())).build();
        String producer = Episodes.producer("opentsx-legacy-bridge");
        assertEquals(producer, LegacyBridge.fromTimeSeriesObject(tso, TimeUnitOfX.EPOCH_SECONDS, "legacy:test")
                .getProvenance().getProducer());
        assertEquals(producer, LegacyBridge.fromEpisodesRecord(v1, null).getProvenance().getProducer());
    }

    @Test
    void noSourceFileCarriesALiteralProducerVersion() throws IOException {
        Pattern literal = Pattern.compile("opentsx-[a-z0-9-]+/[0-9]");
        List<Path> scanned;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            scanned = files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
        assertTrue(scanned.contains(Path.of("src/main/java/org/opentsx/bridge/LegacyBridge.java")), scanned.toString());
        List<String> hits = new ArrayList<>();
        for (Path f : scanned) {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (literal.matcher(lines.get(i)).find()) {
                    hits.add(f + ":" + (i + 1));
                }
            }
        }
        assertEquals(List.of(), hits);
    }

    @Test
    void labelParsingAndFormattingAreInverse() {
        SeriesKey k = LegacyBridge.seriesKeyFromLabel("m b=2,a=1", "kW");
        assertEquals("m a=1,b=2", LegacyBridge.legacyLabel(k));
        assertEquals("kW", k.getUnit());
        assertThrows(IllegalArgumentException.class, () -> LegacyBridge.seriesKeyFromLabel("m broken", null));
    }
}
