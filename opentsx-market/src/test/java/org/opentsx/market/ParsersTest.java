package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.opentsx.market.source.EnergyChartsJson;
import org.opentsx.market.source.EntsoeXml;
import org.opentsx.market.source.Marktwerte;
import org.opentsx.market.source.PartnerCsv;
import org.opentsx.market.source.Rebap;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParsersTest {

    static long t(String iso) {
        return Micro.of(Instant.parse(iso));
    }

    @Test
    void energyChartsSpringDstDayHas23HourlyPrices() {
        EnergyChartsJson.Prices p = EnergyChartsJson.prices(Fixtures.text("energy-charts-price-2024-03-31.json"));
        assertEquals(23, p.points().size());
        assertTrue(p.licence().contains("CC BY 4.0"));
        assertEquals(t("2024-03-30T23:00:00Z"), p.points().get(0).tMicros());   // local midnight, CET
        p.points().forEach(x -> assertEquals(Micro.HOUR, x.dtMicros()));
        assertEquals(t("2024-03-31T21:00:00Z"), p.points().get(22).tMicros()); // 23:00 CEST
    }

    @Test
    void entsoePricesFillA03BlocksAndKeepBothResolutions() {
        Map<Long, List<Point>> r = EntsoeXml.parse(Fixtures.text("entsoe-a44.xml"));
        assertEquals(2, r.get(Micro.HOUR).size());
        List<Point> q = r.get(Micro.QUARTER);
        assertEquals(4, q.size());
        assertEquals(95.10, q.get(1).value(), 1e-9);   // omitted position repeats the previous value
        assertEquals(-0.01, q.get(2).value(), 1e-9);
        assertEquals(t("2025-09-30T22:15:00Z"), q.get(1).tMicros());
        assertEquals(q, EntsoeXml.finest(r));
    }

    @Test
    void entsoeLoadQuantitiesAndAcknowledgement() {
        List<Point> load = EntsoeXml.finest(EntsoeXml.parse(Fixtures.text("entsoe-a65.xml")));
        assertEquals(4, load.size());
        assertEquals(41000, load.get(0).value());
        EntsoeXml.NoData e = assertThrows(EntsoeXml.NoData.class, () -> EntsoeXml.parse(Fixtures.text("entsoe-ack.xml")));
        assertTrue(e.getMessage().startsWith("999 No matching data"));
    }

    @Test
    void entsoeRejectsDoctypes() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>";
        assertThrows(IllegalArgumentException.class, () -> EntsoeXml.parse(xxe));
    }

    @Test
    void rebapApiFormat() {
        Rebap.Result r = Rebap.parse(Fixtures.lines("rebap-api.csv"));
        assertEquals(4, r.shortSide().size());              // N.A. skipped
        assertEquals(1204.57, r.shortSide().get(2).value(), 1e-9);
        assertEquals(-15.20, r.longSide().get(2).value(), 1e-9);
        assertEquals(t("2023-06-10T13:00:00Z"), r.shortSide().get(0).tMicros());
        assertEquals(Micro.QUARTER, r.shortSide().get(0).dtMicros());
        Point midnight = r.shortSide().get(3);              // 23:45-00:00
        assertEquals(t("2023-06-10T23:45:00Z"), midnight.tMicros());
        assertEquals(Micro.QUARTER, midnight.dtMicros());
    }

    @Test
    void rebapDownloadAcrossTheEndOfDst() {
        Rebap.Result r = Rebap.parse(Fixtures.lines("rebap-download-dst.csv"));
        assertEquals(r.shortSide(), r.longSide());          // one reBAP column = both sides
        assertEquals(t("2024-10-27T00:45:00Z"), r.shortSide().get(0).tMicros());   // 02:45 CEST
        assertEquals(Micro.QUARTER, r.shortSide().get(0).dtMicros());              // ... to 02:00 CET
        assertEquals(t("2024-10-27T01:00:00Z"), r.shortSide().get(1).tMicros());   // 02:00 CET
    }

    @Test
    void marktwerteMonthlyInEurPerMwhOnLocalMonths() {
        Map<String, List<Point>> m = Marktwerte.parse(Fixtures.lines("marktwerte.csv"));
        assertEquals(List.of("epex", "wind_onshore", "wind_offshore", "solar", "controllable"), List.copyOf(m.keySet()));
        Point jan = m.get("wind_onshore").get(0);
        assertEquals(65.02, jan.value(), 1e-9);
        assertEquals(t("2023-12-31T23:00:00Z"), jan.tMicros());
        assertEquals(31 * Micro.DAY, jan.dtMicros());
        Point mar = m.get("solar").get(2);                  // March 2024 has 31 days minus the DST hour
        assertEquals(31 * Micro.DAY - Micro.HOUR, mar.dtMicros());
    }

    @Test
    void partnerScheduleAcrossTheDoubledHour() {
        List<Point> p = PartnerCsv.parse(Fixtures.lines("partner-schedule.csv"), null, null, null);
        assertEquals(3, p.size());
        assertEquals(3100.5, p.get(1).value(), 1e-9);
        assertEquals(Micro.HOUR, p.get(2).tMicros() - p.get(1).tMicros());   // 02:00 CEST and 02:00 CET
        List<String> local = List.of("start,value", "2025-10-26 02:00,1");
        assertThrows(IllegalArgumentException.class,
                () -> PartnerCsv.parse(local, null, Duration.ofMinutes(15), ZoneId.of("Europe/Berlin")));
        assertEquals(1, PartnerCsv.parse(List.of("start,value", "2025-10-27 02:00,1"), null, Duration.ofMinutes(15),
                ZoneId.of("Europe/Berlin")).size());
    }
}
