package org.opentsx.market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentsx.market.archive.Archive;
import org.opentsx.market.archive.Backfill;
import org.opentsx.market.source.EntsoeSource;
import org.opentsx.market.source.Fetcher;
import org.opentsx.market.source.Http;
import org.opentsx.market.source.MissingCredentials;
import org.opentsx.market.source.NetztransparenzSource;
import org.opentsx.market.source.Rebap;
import org.opentsx.market.source.SmardSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
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
        assertEquals("opentsx.market.entsoeToken: not set; ENTSO-E needs a security token (register at "
                + "https://transparency.entsoe.eu, then request Web API access) in OPENTSX_MARKET_ENTSOE_TOKEN, ENTSOE_TOKEN "
                + "or a properties file", e.getMessage());
        assertEquals("opentsx.market.entsoeToken not set",
                Collector.unavailable(MarketCatalog.require("entsoe/load"), Map.of()));
        assertEquals("opentsx.market.ntpClientId or opentsx.market.ntpClientSecret not set",
                Collector.unavailable(MarketCatalog.require("netztransparenz/rebap_short"), Map.of("NTP_CLIENT_ID", "c")));
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
    void entsoeHttp400IsNoDataOnlyForTheNoMatchingDataAcknowledgement(@TempDir Path dir) {
        String noData = Fixtures.text("entsoe-ack.xml");
        String refused = noData.replace("No matching data found for Data item Day-ahead Prices [12.1.D]",
                "The amount of requested data exceeds allowed limit.");
        long from = t("2024-01-01T00:00:00Z");
        long to = t("2024-02-01T00:00:00Z");
        assertTrue(new EntsoeSource(status400(noData), "tok").fetch("day_ahead_price", from, to, new ArrayList<>())
                .isEmpty());
        String cut = noData.replace("</Acknowledgement_MarketDocument>", "");
        for (String body : List.of(refused, cut, "<html><body>Bad Request</body></html>")) {
            Http.StatusException e = assertThrows(Http.StatusException.class,
                    () -> new EntsoeSource(status400(body), "tok").fetch("day_ahead_price", from, to,
                            new ArrayList<>()));
            assertEquals(400, e.status());
        }
        String failed = failedYear(dir, status400(refused), "tok");
        assertTrue(failed.contains("HTTP 400 for " + EntsoeSource.BASE), failed);
        assertTrue(failed.contains("&securityToken=***: 999 The amount of requested data exceeds allowed limit.\n"),
                failed);
    }

    static Fetcher status400(String body) {
        return (uri, h) -> {
            throw new Http.StatusException(400, uri, body);
        };
    }

    @Test
    void entsoeFailuresKeepTheTokenOutOfOutcomesAndTheLog(@TempDir Path dir) {
        String token = "SECRET-TOKEN-123";
        Fetcher refused = (uri, h) -> {
            throw new Http.StatusException(503, uri, "Service Unavailable: GET " + uri);
        };
        // port 0: the connection is refused on the loopback interface, nothing leaves the host
        Fetcher unreachable = (uri, h) -> new Http(0, 0).get(uri.replace(EntsoeSource.BASE, "http://127.0.0.1:0/api"),
                h);
        for (String shown : List.of(failedYear(dir, refused, token), failedYear(dir, unreachable, token),
                failedYear(dir, unreachable, token + "\n"))) {
            assertTrue(shown.contains("securityToken=***"), shown);
            assertFalse(shown.contains(token), shown);
        }
    }

    @Test
    void aRefusedEntsoeRequestEndsTheCommandWithOneLineWithoutTheToken() {
        String token = "SECRET-TOKEN-123";
        Fetcher refused = (uri, h) -> {
            throw new Http.StatusException(503, uri, "Service Unavailable: GET " + uri);
        };
        MarketCliTest.Result r = MarketCliTest.cli((delay, credentials) -> new Collector(null, null, refused, null,
                credentials), Map.of("ENTSOE_TOKEN", token), "prices", "--source", "entsoe", "--from", "2025-10-01", "--to",
                "2025-10-02");
        assertEquals(1, r.code(), r.toString());
        assertEquals("", r.out());
        assertEquals(1, r.err().lines().count(), r.err());
        assertTrue(r.err().startsWith("opentsx: HTTP 503 for " + EntsoeSource.BASE + "?documentType=A44"), r.err());
        assertFalse(r.err().contains(token), r.err());
    }

    @Test
    void aRejectedCredentialEndsTheCommandWithOneLineThatNamesIt() {
        Fetcher unauthorized = new Fetcher() {
            @Override
            public String get(String uri, Map<String, String> headers) {
                throw new Http.StatusException(401, uri, "Unauthorized");
            }

            @Override
            public String postForm(String uri, String form, Map<String, String> headers) {
                throw new Http.StatusException(401, uri, "invalid_client");
            }
        };
        MarketCli.Collectors c = (delay, credentials) -> new Collector(null, null, unauthorized, unauthorized, credentials);
        Map<String, String> env = Map.of("ENTSOE_TOKEN", "SECRET-TOKEN-123", "NTP_CLIENT_ID", "client",
                "NTP_CLIENT_SECRET", "s3cret");
        assertEquals(new MarketCliTest.Result(1, "", "opentsx: opentsx.market.entsoeToken: "
                        + "rejected by ENTSO-E (HTTP 401); check the token in OPENTSX_MARKET_ENTSOE_TOKEN, ENTSOE_TOKEN or the "
                        + "properties file\n"),
                MarketCliTest.cli(c, env, "prices", "--source", "entsoe", "--from", "2025-10-01", "--to", "2025-10-02"));
        assertEquals(new MarketCliTest.Result(1, "", "opentsx: netztransparenz.de rejected the client credentials (HTTP 401)\n"),
                MarketCliTest.cli(c, env, "rebap", "--from", "2025-10-01", "--to", "2025-10-02"));
    }

    /** Outcome message and log of a backfill year of the ENTSO-E price whose request fails. */
    static String failedYear(Path dir, Fetcher entsoe, String token) {
        Map<String, String> env = Map.of("ENTSOE_TOKEN", token);
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        Backfill b = new Backfill(new Archive(dir), new Collector(null, null, entsoe, null, env), env,
                new PrintStream(log, true, StandardCharsets.UTF_8));
        Backfill.Outcome o = b.run(List.of(MarketCatalog.require("entsoe/day_ahead_price")), 2024, 2024, false,
                Instant.parse("2026-01-10T00:00:00Z")).get(0);
        assertEquals(Backfill.Status.FAILED, o.status(), o.message());
        return o.message() + "\n" + log.toString(StandardCharsets.UTF_8);
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
        assertEquals("opentsx.market.ntpClientId, opentsx.market.ntpClientSecret: not set; netztransparenz.de needs an "
                + "OAuth client (OAuth manager in the netztransparenz.de extranet) in OPENTSX_MARKET_NTP_CLIENT_ID and "
                + "OPENTSX_MARKET_NTP_CLIENT_SECRET, NTP_CLIENT_ID and NTP_CLIENT_SECRET or a properties file, or import the "
                + "CSV download with --offline", e.getMessage());
    }
}
