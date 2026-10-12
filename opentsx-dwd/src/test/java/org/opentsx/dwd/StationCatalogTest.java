package org.opentsx.dwd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StationCatalogTest {

    @Test
    void readsTheMosmixCatalogWithDegreesMinutes() {
        StationCatalog c = StationCatalog.parse(Fixtures.bytes(Fixtures.MOSMIX_STATIONS));
        assertEquals(11, c.stations().size());
        Station fb = c.byId("10578").orElseThrow();
        assertEquals("FICHTELBERG", fb.name());
        assertEquals(50 + 26 / 60.0, fb.latitude(), 1e-9);    // 50.26 = 50°26'
        assertEquals(12 + 57 / 60.0, fb.longitude(), 1e-9);
        assertEquals(1213, fb.elevation());
        assertEquals("mosmix", fb.catalog());
        // west longitude, right-aligned alphanumeric ids
        assertEquals(-(8 + 40 / 60.0), c.byId("01001").orElseThrow().longitude(), 1e-9);
        assertTrue(c.byId("O882").isPresent());
        assertEquals("FICHTELBERG/OBERFRA.", c.byId("P182").orElseThrow().name());
    }

    @Test
    void readsTheCdcStationDescription() {
        StationCatalog c = StationCatalog.parse(Fixtures.bytes(Fixtures.CDC_STATIONS));
        assertEquals(8, c.stations().size());
        Station fb = c.byId("01358").orElseThrow();
        assertEquals("Fichtelberg", fb.name());
        assertEquals(50.4285, fb.latitude(), 1e-9);
        assertEquals(12.9534, fb.longitude(), 1e-9);
        assertEquals(1213, fb.elevation());
        assertEquals("Fichtelberg/Oberfranken-Hüttstadl", c.byId("01357").orElseThrow().name());   // ISO-8859-1
        assertEquals("Marienberg, Bad", c.byId("03167").orElseThrow().name());
    }

    @Test
    void bothCatalogsAgreeOnTheFichtelbergPosition() {
        Station mos = StationCatalog.parse(Fixtures.bytes(Fixtures.MOSMIX_STATIONS)).byId("10578").orElseThrow();
        Station cdc = StationCatalog.parse(Fixtures.bytes(Fixtures.CDC_STATIONS)).byId("01358").orElseThrow();
        assertTrue(mos.distanceKm(cdc.latitude(), cdc.longitude()) < 1.0);
    }

    @Test
    void nearestStationsForTheHouseAndTheBalticPark() {
        StationCatalog mos = StationCatalog.parse(Fixtures.bytes(Fixtures.MOSMIX_STATIONS));
        List<StationCatalog.Nearby> house = mos.nearest(50.6, 13.0, 3);
        assertEquals(List.of("N4377", "O882", "10579"), house.stream().map(n -> n.station().id()).toList());
        assertEquals(1.85, house.get(0).distanceKm(), 0.05);
        assertTrue(house.get(0).distanceKm() <= house.get(1).distanceKm());

        StationCatalog cdc = StationCatalog.parse(Fixtures.bytes(Fixtures.CDC_STATIONS));
        assertEquals("03166", cdc.nearest(50.6, 13.0, 1).get(0).station().id());          // Marienberg
        assertEquals("01759", cdc.nearest(54.45, 13.95, 1).get(0).station().id());        // Greifswalder Oie
        assertEquals("10097", mos.nearest(54.45, 13.95, 1).get(0).station().id());
    }

    @Test
    void haversine() {
        assertEquals(0, Station.distanceKm(50, 13, 50, 13), 1e-12);
        assertEquals(111.195, Station.distanceKm(50, 13, 51, 13), 0.01);                // one degree of latitude
        assertEquals(504, Station.distanceKm(52.52, 13.405, 48.137, 11.575), 2);         // Berlin - Munich
    }

    @Test
    void rejectsUnknownFormats() {
        assertThrows(IllegalArgumentException.class, () -> StationCatalog.parse("foo bar\n1 2\n".getBytes()));
        assertEquals(0, StationCatalog.parse(new byte[0]).stations().size());
    }
}
