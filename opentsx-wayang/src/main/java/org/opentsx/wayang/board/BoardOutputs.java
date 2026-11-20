package org.opentsx.wayang.board;

import org.apache.avro.specific.SpecificRecord;
import org.opentsx.model.Episodes;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compares the output records of one B4.2 run with the reference output of the same feed. Every run emits only what
 * its increments closed, so the run's records of one series must equal the first records of the reference for that
 * series, field for field. No series may fall behind the most advanced series by more than {@link #LAG_TOLERANCE}
 * records; the feed staggers the series by less than one block.
 */
public final class BoardOutputs {

    public static final int LAG_TOLERANCE = 2;

    /** What a successful comparison covered. */
    public record Comparison(int series, long records) {
    }

    private BoardOutputs() {
    }

    /** One record as exact text: identity, times and every value as raw IEEE 754 bits. */
    static String canonical(SpecificRecord record) {
        if (record instanceof Episode e) {
            StringBuilder s = new StringBuilder("episode|").append(e.getEpisodeId()).append('|').append(e.getSeries().getSeriesId())
                    .append("|end=").append(e.getTEnd());
            for (long t : Episodes.timestampsMicros(e)) s.append('|').append(t);
            for (double v : Episodes.values(e)) s.append('|').append(Long.toHexString(Double.doubleToRawLongBits(v)));
            return s.toString();
        }
        if (record instanceof SeriesStats s) {
            ByteBuffer sketch = s.getQuantileSketch().duplicate();
            byte[] bytes = new byte[sketch.remaining()];
            sketch.get(bytes);
            return String.join("|", "stats", s.getSeriesId(), s.getWindowType(), String.valueOf(s.getWindowStart()),
                    String.valueOf(s.getWindowEnd()), String.valueOf(s.getValidCount()), String.valueOf(s.getNanCount()),
                    String.valueOf(s.getGapCount()), bits(s.getMin()), bits(s.getMax()), bits(s.getMean()), bits(s.getM2()),
                    String.valueOf(s.getLastSeen()), HexFormat.of().formatHex(bytes));
        }
        throw new IllegalArgumentException("unexpected output record " + record.getClass().getName());
    }

    private static String bits(double value) {
        return Long.toHexString(Double.doubleToRawLongBits(value));
    }

    static String series(SpecificRecord record) {
        if (record instanceof Episode e) return e.getSeries().getSeriesId();
        if (record instanceof SeriesStats s) return s.getSeriesId();
        throw new IllegalArgumentException("unexpected output record " + record.getClass().getName());
    }

    private static long start(SpecificRecord record) {
        if (record instanceof Episode e) return Episodes.timestampsMicros(e)[0];
        return ((SeriesStats) record).getWindowStart().toEpochMilli();
    }

    /** The canonical records of each series in time order. */
    public static Map<String, List<String>> bySeries(List<? extends SpecificRecord> records) {
        Map<String, List<SpecificRecord>> grouped = new TreeMap<>();
        for (SpecificRecord r : records) grouped.computeIfAbsent(series(r), k -> new ArrayList<>()).add(r);
        Map<String, List<String>> out = new LinkedHashMap<>();
        grouped.forEach((id, list) -> out.put(id, list.stream()
                .sorted(Comparator.comparingLong(BoardOutputs::start).thenComparing(BoardOutputs::canonical))
                .map(BoardOutputs::canonical).toList()));
        return out;
    }

    /**
     * The output series of the reference are the inventory: the source series for pass-through and statistics, the
     * derived series for clean.
     *
     * @throws AssertionError naming the first difference when the run is not a per-series prefix of the reference
     */
    public static Comparison compare(Map<String, List<String>> reference, Map<String, List<String>> run) {
        List<String> inventory = List.copyOf(reference.keySet());
        int most = run.values().stream().mapToInt(List::size).max().orElse(0);
        if (most == 0) throw new AssertionError("the run wrote no output records");
        long records = 0;
        for (String id : inventory) {
            List<String> actual = run.getOrDefault(id, List.of());
            List<String> expected = reference.getOrDefault(id, List.of());
            if (actual.size() > expected.size()) {
                throw new AssertionError("series " + id + ": the run wrote " + actual.size() + " records, the reference has "
                        + expected.size());
            }
            for (int i = 0; i < actual.size(); i++) {
                if (!actual.get(i).equals(expected.get(i))) {
                    throw new AssertionError("series " + id + ", record " + i + ": expected " + expected.get(i) + ", got " + actual.get(i));
                }
            }
            if (actual.size() < Math.min(most - LAG_TOLERANCE, expected.size())) {
                throw new AssertionError("series " + id + " has " + actual.size() + " records, " + (most - actual.size())
                        + " fewer than the most advanced series");
            }
            records += actual.size();
        }
        for (String id : run.keySet()) {
            if (!inventory.contains(id)) throw new AssertionError("the run wrote series " + id + ", which the reference does not have");
        }
        return new Comparison(inventory.size(), records);
    }

    /** A JUnit XML report of one successful test case with the run's binding properties. */
    public static String proof(String checkerClass, String testName, Map<String, String> properties, double seconds) {
        StringWriter text = new StringWriter();
        try {
            XMLStreamWriter xml = XMLOutputFactory.newFactory().createXMLStreamWriter(text);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("testsuite");
            xml.writeAttribute("name", checkerClass);
            xml.writeAttribute("tests", "1");
            xml.writeAttribute("failures", "0");
            xml.writeAttribute("errors", "0");
            xml.writeAttribute("skipped", "0");
            xml.writeAttribute("time", String.format(java.util.Locale.ROOT, "%.3f", seconds));
            xml.writeStartElement("properties");
            for (Map.Entry<String, String> p : new TreeMap<>(properties).entrySet()) {
                xml.writeEmptyElement("property");
                xml.writeAttribute("name", p.getKey());
                xml.writeAttribute("value", p.getValue());
            }
            xml.writeEndElement();
            xml.writeEmptyElement("testcase");
            xml.writeAttribute("name", testName);
            xml.writeAttribute("classname", checkerClass);
            xml.writeAttribute("time", String.format(java.util.Locale.ROOT, "%.3f", seconds));
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("cannot write the proof", e);
        }
        return text + "\n";
    }
}
