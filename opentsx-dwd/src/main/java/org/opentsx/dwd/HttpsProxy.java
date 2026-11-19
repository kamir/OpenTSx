package org.opentsx.dwd;

import org.opentsx.conf.OpenTsxConfException;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * The proxy of the downloads of opentsx-dwd and opentsx-market: the JVM proxy settings win when
 * {@code https.proxyHost} is set; otherwise the key {@value #KEY} ({@code <host>:<port>} or
 * {@code http://<host>:<port>}), which is checked in any case.
 */
public final class HttpsProxy {

    public static final String KEY = "opentsx.https.proxy";

    private HttpsProxy() {
    }

    public static ProxySelector selector(String jvmProxyHost, Optional<String> configured) {
        ProxySelector own = configured.map(HttpsProxy::of).orElse(null);
        return jvmProxyHost != null || own == null ? ProxySelector.getDefault() : own;
    }

    private static ProxySelector of(String value) {
        URI u;
        try {
            u = new URI(value.contains("://") ? value : "http://" + value);
        } catch (URISyntaxException e) {
            // not kept as the cause: its message holds the value, which is a secret
            u = null;
        }
        if (u == null || u.getHost() == null) {
            throw new OpenTsxConfException(KEY, "not a proxy address", "expected <host>:<port> or http://<host>:<port>");
        }
        return ProxySelector.of(InetSocketAddress.createUnresolved(u.getHost(), u.getPort() > 0 ? u.getPort() : 80));
    }
}
