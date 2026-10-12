package org.opentsx.dwd;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * DWD station lists and nearest-station search. Two formats are read (detected from the header):
 * <ul>
 *   <li><b>MOSMIX station catalog</b> ({@code mosmix_stationskatalog.cfg}): fixed-width columns
 *   {@code ID ICAO NAME LAT LON ELEV} under a dash line. LAT/LON are <b>degrees.minutes</b> (50.26 = 50°26' =
 *   50.433°) and are converted to decimal degrees.</li>
 *   <li><b>CDC station description</b> ({@code *_Beschreibung_Stationen.txt}, ISO-8859-1): {@code Stations_id
 *   von_datum bis_datum Stationshoehe geoBreite geoLaenge Stationsname Bundesland Abgabe}, decimal degrees.</li>
 * </ul>
 */
public final class StationCatalog {

    /** A station and its distance from the query point. */
    public record Nearby(Station station, double distanceKm) {
    }

    private final List<Station> stations;

    public StationCatalog(List<Station> stations) {
        this.stations = List.copyOf(stations);
    }

    public List<Station> stations() {
        return stations;
    }

    public Optional<Station> byId(String id) {
        return stations.stream().filter(s -> s.id().equals(id)).findFirst();
    }

    /** The {@code n} stations closest to {@code lat}/{@code lon} (haversine), nearest first. */
    public List<Nearby> nearest(double lat, double lon, int n) {
        return stations.stream()
                .map(s -> new Nearby(s, s.distanceKm(lat, lon)))
                .sorted(Comparator.comparingDouble(Nearby::distanceKm).thenComparing(x -> x.station().id()))
                .limit(n)
                .toList();
    }

    public static StationCatalog read(Path file) {
        try {
            return parse(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static StationCatalog read(InputStream in) throws IOException {
        return parse(in.readAllBytes());
    }

    /** Parses either format; both are read as ISO-8859-1 (the MOSMIX list is plain ASCII). */
    public static StationCatalog parse(byte[] bytes) {
        List<String> lines = new String(bytes, StandardCharsets.ISO_8859_1).lines().map(StationCatalog::stripCr).toList();
        int header = 0;
        while (header < lines.size() && lines.get(header).isBlank()) {
            header++;
        }
        if (header >= lines.size()) {
            return new StationCatalog(List.of());
        }
        String h = lines.get(header);
        if (h.startsWith("Stations_id")) {
            return new StationCatalog(parseCdc(lines.subList(header + 1, lines.size())));
        }
        if (h.contains("ICAO") && h.contains("LAT")) {
            return new StationCatalog(parseMosmix(lines.subList(header + 1, lines.size())));
        }
        throw new IllegalArgumentException("unknown station list format, header: " + h);
    }

    private static List<Station> parseMosmix(List<String> lines) {
        if (lines.isEmpty() || !lines.get(0).startsWith("-")) {
            throw new IllegalArgumentException("MOSMIX catalog: dash line expected under the header");
        }
        // column starts from the dash line: ID ICAO NAME LAT LON ELEV
        String dashes = lines.get(0);
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < dashes.length(); i++) {
            if (dashes.charAt(i) == '-' && (i == 0 || dashes.charAt(i - 1) == ' ')) {
                starts.add(i);
            }
        }
        if (starts.size() < 6) {
            throw new IllegalArgumentException("MOSMIX catalog: 6 columns expected, dash line: " + dashes);
        }
        List<Station> out = new ArrayList<>();
        for (String l : lines.subList(1, lines.size())) {
            if (l.isBlank()) {
                continue;
            }
            String id = field(l, starts, 0);
            String name = field(l, starts, 2);
            double lat = degreesMinutes(Double.parseDouble(field(l, starts, 3)));
            double lon = degreesMinutes(Double.parseDouble(field(l, starts, 4)));
            double elev = Double.parseDouble(field(l, starts, 5));
            out.add(new Station(id, name, lat, lon, elev, "mosmix"));
        }
        return out;
    }

    private static List<Station> parseCdc(List<String> lines) {
        List<Station> out = new ArrayList<>();
        for (String l : lines) {
            if (l.isBlank() || l.startsWith("-")) {
                continue;
            }
            String[] head = l.strip().split("\\s+", 7);
            if (head.length < 7) {
                throw new IllegalArgumentException("CDC station list: too few columns: " + l);
            }
            String[] rest = head[6].strip().split("\\s{2,}");
            out.add(new Station(head[0], rest[0], Double.parseDouble(head[4]), Double.parseDouble(head[5]),
                    Double.parseDouble(head[3]), "cdc"));
        }
        return out;
    }

    /** 50.26 (50°26') -> 50.4333; sign kept. The published list has a few entries with minutes >= 60; they are kept as is. */
    static double degreesMinutes(double dm) {
        double a = Math.abs(dm);
        double deg = Math.floor(a);
        double min = Math.round((a - deg) * 100 * 1e6) / 1e6;
        return Math.copySign(deg + min / 60.0, dm);
    }

    private static String field(String line, List<Integer> starts, int col) {
        int a = starts.get(col);
        int b = col + 1 < starts.size() ? starts.get(col + 1) : line.length();
        if (a >= line.length()) {
            return "";
        }
        return line.substring(a, Math.min(b, line.length())).strip();
    }

    private static String stripCr(String s) {
        return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
    }
}
