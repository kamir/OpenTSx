package org.opentsx.dwd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Hand-made fixtures under {@code src/test/resources/dwd/} in the layout of the real DWD files. */
final class Fixtures {

    static final String KML = "MOSMIX_L_2026101209_10578.kml";
    static final String MOSMIX_STATIONS = "mosmix_stationskatalog.cfg";
    static final String CDC_STATIONS = "zehn_now_ff_Beschreibung_Stationen.txt";
    static final String WIND_10MIN = "produkt_zehn_now_ff_20261012_20261012_01358.txt";
    static final String TU_10MIN = "produkt_zehn_now_tu_20261012_20261012_01358.txt";
    static final String SOLAR_10MIN = "produkt_zehn_now_sd_20261012_20261012_01358.txt";
    static final String TU_HOURLY = "produkt_tu_stunde_20260113_20260115_01358.txt";

    private Fixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/dwd/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> lines(String name) {
        return new String(bytes(name), StandardCharsets.ISO_8859_1).lines().toList();
    }

    /** A zip like the DWD downloads: metadata entries first, then the product (or KML) entry. */
    static byte[] zip(String entryName, byte[] content, boolean withMetadata) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            if (withMetadata) {
                z.putNextEntry(new ZipEntry("Metadaten_Geographie_01358.txt"));
                z.write("Stations_id;Stationshoehe;Geogr.Breite;Geogr.Laenge;von_datum;bis_datum;Stationsname\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry(entryName));
            z.write(content);
            z.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }
}
