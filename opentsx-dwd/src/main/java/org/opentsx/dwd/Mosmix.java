package org.opentsx.dwd;

import org.opentsx.model.Micros;
import org.opentsx.twin.Origin;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleUnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * MOSMIX point forecasts (KML inside a KMZ zip), parsed with {@code java.util.zip} and StAX.
 * <p>
 * Layout: {@code kml:Document/kml:ExtendedData/dwd:ProductDefinition} holds {@code dwd:IssueTime},
 * {@code dwd:ReferencedModel/dwd:Model} and {@code dwd:ForecastTimeSteps/dwd:TimeStep} (UTC); each
 * {@code kml:Placemark} is a station ({@code kml:name} = id, {@code kml:description} = name,
 * {@code kml:Point/kml:coordinates} = lon,lat,elevation) with one {@code dwd:Forecast dwd:elementName=".."} per
 * element, whose {@code dwd:value} lists one whitespace-separated value per time step ({@code -} = undefined).
 * The single-station files ({@code MOSMIX_L_LATEST_<id>.kmz}) hold one placemark, the all-stations file many.
 */
public final class Mosmix {

    /** URL of the latest MOSMIX_L run for one station. */
    public static String latestUrl(String stationId) {
        return "https://opendata.dwd.de/weather/local_forecasts/mos/MOSMIX_L/single_stations/" + stationId
                + "/kml/MOSMIX_L_LATEST_" + stationId + ".kmz";
    }

    /**
     * How a MOSMIX element maps to a canonical variable.
     *
     * @param endStampedHours {@code > 0}: the value covers the hours before the time step; it is moved to the interval
     *                        start and tagged with {@code aggregation}
     */
    public record Element(String name, Variable variable, DoubleUnaryOperator convert, String aggregation,
                          int endStampedHours) {
    }

    /** Elements the collector converts (all others are kept raw in {@link Placemark#elements()}). */
    public static final List<Element> ELEMENTS = List.of(
            new Element("FF", Variable.WIND_SPEED, v -> v, null, 0),                       // m/s, 10 min mean
            new Element("DD", Variable.WIND_DIRECTION, v -> v, null, 0),                   // degrees
            new Element("TTT", Variable.AIR_TEMPERATURE, v -> v - 273.15, null, 0),        // K -> °C, 2 m
            new Element("PPPP", Variable.PRESSURE, v -> v / 100.0, null, 0),               // Pa -> hPa
            new Element("N", Variable.CLOUD_COVER, v -> v, null, 0),                       // %
            new Element("Rad1h", Variable.GLOBAL_IRRADIANCE, v -> v / 3.6, "mean.PT1H", 1), // kJ/m² in 1 h -> W/m²
            new Element("RR1c", Variable.PRECIPITATION, v -> v, "sum.PT1H", 1));           // kg/m² in 1 h = mm

    /** One station of a MOSMIX file with its raw element values (DWD units, NaN = undefined). */
    public record Placemark(String id, String name, double latitude, double longitude, double elevation,
                            Map<String, double[]> elements) {

        public Station station() {
            return new Station(id, name, latitude, longitude, elevation, "mosmix");
        }
    }

    /**
     * A parsed MOSMIX file.
     *
     * @param product    {@code mosmix-l} (or {@code mosmix-s})
     * @param issueTime  forecast reference time ({@code dwd:IssueTime})
     * @param models     referenced NWP models, {@code name@referenceTime}
     * @param timeSteps  valid times, UTC microseconds
     * @param placemarks stations
     */
    public record Forecast(String product, Instant issueTime, List<String> models, long[] timeSteps,
                           List<Placemark> placemarks) {

        public Placemark placemark(String stationId) {
            return placemarks.stream().filter(p -> p.id().equals(stationId)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("station " + stationId + " not in forecast"));
        }

        /** Canonical series of one station (origin FORECAST, reference time = issue time). */
        public List<WeatherSeries> series(String stationId) {
            Placemark p = placemark(stationId);
            List<WeatherSeries> out = new ArrayList<>();
            for (Element e : ELEMENTS) {
                double[] raw = p.elements().get(e.name());
                if (raw == null) {
                    continue;
                }
                long shift = e.endStampedHours() * 3_600_000_000L;
                long[] ts = new long[timeSteps.length];
                double[] v = new double[timeSteps.length];
                for (int i = 0; i < ts.length; i++) {
                    ts[i] = timeSteps[i] - shift;
                    v[i] = Double.isNaN(raw[i]) ? Double.NaN : round(e.convert().applyAsDouble(raw[i]));
                }
                long interval = timeSteps.length >= 2 ? timeSteps[1] - timeSteps[0] : 3_600_000_000L;
                out.add(new WeatherSeries(p.id(), product, e.variable(), e.aggregation(), Origin.FORECAST, issueTime,
                        e.name(), interval, ts, v));
            }
            return out;
        }

        /** Series of the only (or first) station. */
        public List<WeatherSeries> series() {
            return series(placemarks.get(0).id());
        }
    }

    private Mosmix() {
    }

    public static Forecast read(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reads a KMZ (zip, detected by its magic bytes) or a plain KML stream.
     *
     * @param stations only keep these station ids ({@code null} = all), useful for the all-stations file
     */
    public static Forecast read(InputStream raw, Set<String> stations) throws IOException {
        BufferedInputStream in = new BufferedInputStream(raw);
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        if (b0 == 'P' && b1 == 'K') {
            ZipInputStream zip = new ZipInputStream(in);
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.getName().toLowerCase().endsWith(".kml")) {
                    return parseKml(zip, stations);
                }
            }
            throw new IOException("no .kml entry in the KMZ");
        }
        return parseKml(in, stations);
    }

    private static Forecast parseKml(InputStream in, Set<String> stations) throws IOException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
        try {
            XMLStreamReader r = f.createXMLStreamReader(in);
            String product = "mosmix-l";
            Instant issue = null;
            List<String> models = new ArrayList<>();
            List<Long> steps = new ArrayList<>();
            String undef = "-";
            List<Placemark> placemarks = new ArrayList<>();
            // placemark under construction
            String pmId = null;
            String pmName = null;
            double[] coords = null;
            Map<String, double[]> elements = null;
            String element = null;
            boolean inPlacemark = false;
            while (r.hasNext()) {
                if (r.next() != XMLStreamConstants.START_ELEMENT) {
                    if (r.getEventType() == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("Placemark")) {
                        if (pmId != null && (stations == null || stations.contains(pmId))) {
                            double[] c = coords == null ? new double[]{Double.NaN, Double.NaN, Double.NaN} : coords;
                            placemarks.add(new Placemark(pmId, pmName, c[1], c[0], c.length > 2 ? c[2] : Double.NaN,
                                    elements));
                        }
                        inPlacemark = false;
                    }
                    continue;
                }
                switch (r.getLocalName()) {
                    case "ProductID" -> {
                        String p = r.getElementText().strip();
                        product = p.equalsIgnoreCase("MOSMIX") ? "mosmix-l" : p.toLowerCase();
                    }
                    case "GeneratingProcess" -> {
                        if (r.getElementText().contains("MOSMIX_S")) {
                            product = "mosmix-s";
                        }
                    }
                    case "IssueTime" -> issue = Instant.parse(r.getElementText().strip());
                    case "Model" -> models.add(attr(r, "name") + "@" + attr(r, "referenceTime"));
                    case "TimeStep" -> steps.add(Micros.of(Instant.parse(r.getElementText().strip())));
                    case "DefaultUndefSign" -> undef = r.getElementText().strip();
                    case "Placemark" -> {
                        inPlacemark = true;
                        pmId = null;
                        pmName = null;
                        coords = null;
                        elements = new LinkedHashMap<>();
                    }
                    case "name" -> {
                        if (inPlacemark) {
                            pmId = r.getElementText().strip();
                        }
                    }
                    case "description" -> {
                        if (inPlacemark) {
                            pmName = r.getElementText().strip();
                        }
                    }
                    case "coordinates" -> {
                        if (inPlacemark) {
                            String[] c = r.getElementText().strip().split(",");
                            coords = new double[c.length];
                            for (int i = 0; i < c.length; i++) {
                                coords[i] = Double.parseDouble(c[i].strip());
                            }
                        }
                    }
                    case "Forecast" -> element = attr(r, "elementName");
                    case "value" -> {
                        if (inPlacemark && element != null && (stations == null || stations.contains(pmId))) {
                            elements.put(element, values(r.getElementText(), undef, steps.size(), element));
                        }
                    }
                    default -> {
                    }
                }
            }
            if (issue == null) {
                throw new IOException("no dwd:IssueTime - not a MOSMIX KML?");
            }
            long[] ts = steps.stream().mapToLong(Long::longValue).toArray();
            return new Forecast(product, issue, List.copyOf(models), ts, List.copyOf(placemarks));
        } catch (XMLStreamException e) {
            throw new IOException("malformed MOSMIX KML: " + e.getMessage(), e);
        }
    }

    private static double[] values(String text, String undef, int expected, String element) {
        String[] tok = text.strip().split("\\s+");
        if (tok.length != expected) {
            throw new IllegalArgumentException("element " + element + ": " + tok.length + " values for " + expected
                    + " time steps");
        }
        double[] v = new double[tok.length];
        for (int i = 0; i < tok.length; i++) {
            v[i] = tok[i].equals(undef) ? Double.NaN : Double.parseDouble(tok[i]);
        }
        return v;
    }

    private static String attr(XMLStreamReader r, String localName) {
        for (int i = 0; i < r.getAttributeCount(); i++) {
            if (r.getAttributeLocalName(i).equals(localName)) {
                return r.getAttributeValue(i);
            }
        }
        return null;
    }

    static double round(double v) {
        return Math.round(v * 1e4) / 1e4;
    }
}
