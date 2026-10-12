package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.opentsx.market.source.EntsoeSource;
import org.opentsx.market.source.MissingCredentials;
import org.opentsx.market.source.NetztransparenzSource;
import org.opentsx.market.source.Rebap;
import org.opentsx.market.source.SmardSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentsx.market.ParsersTest.t;

class SourcesTest {

    static final String W = "1759096800000";

    @Test
    void smardPriceIsHourlyBeforeAndQuarterHourlyAfterTheMtuSwitch() {
        Fixtures.FakeFetcher f = new Fixtures.FakeFetcher()
                .serve("4169/DE-LU/index_hour.json", "smard-4169-index_hour.json")
                .serve("4169/DE-LU/index_quarterhour.json", "smard-4169-index_quarterhour.json")
                .serve("4169_DE-LU_hour_" + W + ".json", "smard-4169-hour-" + W + ".json")
                .serve("4169_DE-LU_quarterhour_" + W + ".json", "smard-4169-quarterhour-" + W + ".json");
        List<String> q = new ArrayList<>();
        List<Point> p = new SmardSource(f).fetch(MarketCatalog.smard("day_ahead_price"), t("2025-09-30T20:00:00Z"),
                t("2025-10-01T02:00:00Z"), q);
        assertEquals(2 + 16, p.size());
        assertEquals(Micro.HOUR, p.get(0).dtMicros());
        assertEquals(101.63, p.get(0).value(), 1e-9);
        assertEquals(t("2025-09-30T22:00:00Z"), p.get(2).tMicros());
        assertEquals(Micro.QUARTER, p.get(2).dtMicros());
        assertEquals(4, q.size());   // two indexes, two data files
        assertTrue(f.requests.stream().allMatch(u -> u.startsWith(SmardSource.BASE)));
    }

    @Test
    void smardGenerationEnergiesBecomeAveragePowersAndNullsAreSkipped() {
        Fixtures.FakeFetcher f = new Fixtures.FakeFetcher()
                .serve("4067/DE/index_quarterhour.json", "smard-4067-index_quarterhour.json")
                .serve("4067_DE_quarterhour_" + W + ".json", "smard-4067-quarterhour-" + W + ".json");
        List<Point> p = new SmardSource(f).fetch(MarketCatalog.smard("wind_onshore"), t("2025-09-30T00:00:00Z"),
                t("2025-10-02T00:00:00Z"), new ArrayList<>());
        assertEquals(8, p.size());   // two hours of quarter-hours; the trailing null is dropped
        String raw = Fixtures.text("smard-4067-quarterhour-" + W + ".json");
        double mwh = Double.parseDouble(raw.split("\\[1759262400000, ")[1].split("]")[0]);
        assertEquals(4 * mwh, p.get(0).value(), 1e-9);
    }

    @Test
    void entsoeWithoutTokenSaysWhatToSet() {
        EntsoeSource s = new EntsoeSource(new Fixtures.FakeFetcher(), null);
        MissingCredentials e = assertThrows(MissingCredentials.class,
                () -> s.fetch("day_ahead_price", 0, Micro.DAY, new ArrayList<>()));
        assertTrue(e.getMessage().contains("ENTSOE_TOKEN"));
        assertEquals("ENTSOE_TOKEN not set", Collector.unavailable(MarketCatalog.require("entsoe/load"), Map.of()));
    }

    @Test
    void entsoeRequestsMonthChunksAndRedactsTheToken() {
        Fixtures.FakeFetcher f = new Fixtures.FakeFetcher().serve("documentType=A44", "entsoe-a44.xml");
        List<String> q = new ArrayList<>();
        List<Point> p = new EntsoeSource(f, "secret-token").fetch("day_ahead_price", t("2025-09-30T20:00:00Z"),
                t("2025-10-01T02:00:00Z"), q);
        assertEquals(2, f.requests.size());   // September and October
        assertTrue(f.requests.get(0).contains("periodStart=202509302000&periodEnd=202510010000"));
        assertTrue(q.stream().noneMatch(x -> x.contains("secret-token")));
        assertEquals(8, p.size());            // finest resolution of the document, once per month chunk
    }

    @Test
    void netztransparenzUsesOAuthClientCredentials() {
        Fixtures.FakeFetcher f = new Fixtures.FakeFetcher().serve("/NrvSaldo/reBAP/Qualitaetsgesichert/", "rebap-api.csv");
        NetztransparenzSource s = new NetztransparenzSource(f, "client", "s3cret");
        List<String> q = new ArrayList<>();
        Rebap.Result r = s.rebap(t("2023-06-10T00:00:00Z"), t("2023-06-11T00:00:00Z"), q);
        assertEquals(4, r.shortSide().size());
        assertEquals(1, f.posts.size());
        assertTrue(f.posts.get(0).startsWith(NetztransparenzSource.TOKEN_URL + " grant_type=client_credentials"));
        assertEquals("Bearer tok-1", f.headers.get(0).get("Authorization"));
        assertTrue(q.get(0).endsWith("/NrvSaldo/reBAP/Qualitaetsgesichert/2023-06-10T00:00:00/2023-06-11T00:00:00"));
        s.rebap(t("2023-06-10T00:00:00Z"), t("2023-06-11T00:00:00Z"), q);
        assertEquals(1, f.posts.size());      // token reused within its lifetime
    }

    @Test
    void netztransparenzMarktwerteQueryAndMissingCredentials() {
        Fixtures.FakeFetcher f = new Fixtures.FakeFetcher().serve("/marktpraemie/", "marktwerte.csv");
        List<String> q = new ArrayList<>();
        Map<String, List<Point>> m = new NetztransparenzSource(f, "c", "s").marktwerte(t("2023-12-31T23:00:00Z"),
                t("2024-12-31T23:00:00Z"), q);
        assertTrue(q.get(0).endsWith("/marktpraemie/1/2024/12/2024"));
        assertEquals(3, m.get("epex").size());
        NetztransparenzSource none = new NetztransparenzSource(f, null, null);
        MissingCredentials e = assertThrows(MissingCredentials.class, () -> none.rebap(0, Micro.DAY, new ArrayList<>()));
        assertTrue(e.getMessage().contains("NTP_CLIENT_ID"));
        assertFalse(e.getMessage().contains("s3cret"));
    }
}
