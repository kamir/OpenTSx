package org.opentsx.dwd;

import org.junit.jupiter.api.Test;
import org.opentsx.conf.OpenTsxConf;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The proxy rule of the downloads of opentsx-dwd and opentsx-market. */
class HttpsProxyTest {

    @Test
    void theJvmProxySettingsWinOverTheProxyKey() {
        assertSame(ProxySelector.getDefault(), HttpsProxy.selector("jvm-proxy.example", Optional.of("proxy.example:3128")));
        assertSame(ProxySelector.getDefault(), HttpsProxy.selector(null, Optional.empty()));
    }

    @Test
    void theProxyKeyComesFromOpentsxHttpsProxyWithoutJvmSettingsForDwdAndMarketCommands() {
        URI url = URI.create("https://opendata.dwd.de/");
        for (List<String> command : List.of(List.of("dwd", "stations", "--near", "50.6,13.0"), List.of("market", "prices"))) {
            OpenTsxConf conf = OpenTsxConf.load(command,
                    Map.of("OPENTSX_HTTPS_PROXY", "proxy.example:3128", "HTTPS_PROXY", "legacy.example:8080"), null).conf();
            assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example", 3128))),
                    HttpsProxy.selector(null, conf.getOption(HttpsProxy.KEY)).select(url), command.toString());
        }
        OpenTsxConf legacy = OpenTsxConf.load(List.of("dwd", "obs", "--station", "01358"),
                Map.of("HTTPS_PROXY", "http://legacy.example:8080"), null).conf();
        assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("legacy.example", 8080))),
                HttpsProxy.selector(null, legacy.getOption(HttpsProxy.KEY)).select(url));
    }

    @Test
    void aMalformedProxyIsRefusedWithoutEchoingItAlsoWhenTheJvmProxyIsSet() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> HttpsProxy.selector("jvm-proxy.example", Optional.of("http://user:s3cret@")));
        assertEquals("opentsx.https.proxy: not a proxy address; expected <host>:<port> or http://<host>:<port>",
                e.getMessage());
        assertEquals(null, e.getCause());
    }
}
