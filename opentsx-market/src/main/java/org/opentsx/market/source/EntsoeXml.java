package org.opentsx.market.source;

import org.opentsx.market.Micro;
import org.opentsx.market.Point;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Parser for ENTSO-E Transparency Platform documents (IEC 62325-451): {@code Publication_MarketDocument}
 * (day-ahead prices, A44: {@code price.amount}) and {@code GL_MarketDocument} (load A65, generation forecast A69:
 * {@code quantity} in MW). Every {@code TimeSeries/Period} has a {@code timeInterval} and a {@code resolution}
 * ({@code PT60M}, {@code PT15M}, ...). With curve type A03 ("variable sized block") positions without a point repeat
 * the previous value; A01 lists every position.
 * <p>
 * Since the 15-minute MTU (delivery day 2025-10-01) the DE-LU day-ahead document can contain series in two
 * resolutions; {@link #parse} returns the points per resolution so the caller can choose.
 * <p>
 * An {@code Acknowledgement_MarketDocument} (no data, wrong parameters, missing token) raises {@link NoData}.
 */
public final class EntsoeXml {

    /** The platform answered with an acknowledgement instead of data. */
    public static final class NoData extends RuntimeException {
        public NoData(String reason) {
            super(reason);
        }
    }

    private EntsoeXml() {
    }

    /** Points by resolution (micros), each list sorted by start. */
    public static Map<Long, List<Point>> parse(String xml) {
        Document doc = dom(xml);
        Element root = doc.getDocumentElement();
        if (root.getLocalName().startsWith("Acknowledgement")) {
            List<String> texts = new ArrayList<>();
            for (Element r : children(root, "Reason")) {
                texts.add(text(r, "code") + " " + text(r, "text"));
            }
            throw new NoData(String.join("; ", texts));
        }
        Map<Long, TreeMap<Long, Point>> byRes = new TreeMap<>();
        for (Element ts : descendants(root, "TimeSeries")) {
            for (Element period : children(ts, "Period")) {
                Element ti = children(period, "timeInterval").get(0);
                long start = Micro.of(instant(text(ti, "start")));
                long end = Micro.of(instant(text(ti, "end")));
                long res = Duration.parse(text(period, "resolution")).toNanos() / 1000;
                String curve = text(ts, "curveType");
                int n = (int) ((end - start) / res);
                double[] v = new double[n];
                java.util.Arrays.fill(v, Double.NaN);
                for (Element p : children(period, "Point")) {
                    int pos = Integer.parseInt(text(p, "position"));
                    String val = text(p, "price.amount");
                    if (val.isEmpty()) {
                        val = text(p, "quantity");
                    }
                    if (pos >= 1 && pos <= n && !val.isEmpty()) {
                        v[pos - 1] = Double.parseDouble(val);
                    }
                }
                if (curve.equals("A03")) {
                    for (int i = 1; i < n; i++) {
                        if (Double.isNaN(v[i])) {
                            v[i] = v[i - 1];
                        }
                    }
                }
                TreeMap<Long, Point> m = byRes.computeIfAbsent(res, x -> new TreeMap<>());
                for (int i = 0; i < n; i++) {
                    if (!Double.isNaN(v[i])) {
                        long t = start + i * res;
                        m.put(t, new Point(t, res, v[i]));
                    }
                }
            }
        }
        Map<Long, List<Point>> out = new TreeMap<>();
        byRes.forEach((r, m) -> out.put(r, new ArrayList<>(m.values())));
        return out;
    }

    /** The finest resolution of a parsed document, or an empty list. */
    public static List<Point> finest(Map<Long, List<Point>> byResolution) {
        return byResolution.isEmpty() ? List.of() : byResolution.values().iterator().next();
    }

    private static Instant instant(String s) {
        // ENTSO-E writes minutes without seconds: 2024-01-01T23:00Z
        String t = s.trim();
        if (t.length() == 17 && t.endsWith("Z")) {
            t = t.substring(0, 16) + ":00Z";
        }
        return Instant.parse(t);
    }

    private static Document dom(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setExpandEntityReferences(false);
            return f.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IllegalArgumentException("not an ENTSO-E XML document: " + e.getMessage(), e);
        }
    }

    private static List<Element> children(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<Element> descendants(Element parent, String localName) {
        List<Element> out = new ArrayList<>();
        NodeList all = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < all.getLength(); i++) {
            out.add((Element) all.item(i));
        }
        return out;
    }

    private static String text(Element parent, String localName) {
        List<Element> c = children(parent, localName);
        return c.isEmpty() ? "" : c.get(0).getTextContent().trim();
    }
}
