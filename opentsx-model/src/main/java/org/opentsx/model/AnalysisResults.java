package org.opentsx.model;

import org.opentsx.model.v2.AnalysisResult;
import org.opentsx.model.v2.ResultCurve;
import org.opentsx.model.v2.ResultMatrix;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Analysis result identity.
 *
 * <p>Canonical form: {@code escape(method) + "@" + escape(methodVersion) + "(" + k1=v1,k2=v2,... + ")"
 * + "[" + e1,e2,... + "]"} with parameter keys sorted by their UTF-8 bytes (unsigned), input episode ids in their
 * given order and {@code \ { } , = ( ) [ ] @} escaped with a backslash. Values carry their Avro type: long
 * {@code l<decimal>}, double {@code d<16 hex digits of the IEEE 754 bits>}, string {@code s<escaped>}, array of
 * doubles {@code a<hex>;<hex>} ({@code a} when empty). NaN is not allowed in parameters.
 * {@code resultId = hex(xxHash64(utf8(canonical), 0))}.
 */
public final class AnalysisResults {

    private static final HexFormat HEX = HexFormat.of();

    private AnalysisResults() {
    }

    public static String resultId(String method, String methodVersion, Map<String, ?> params,
                                  List<String> inputEpisodeIds) {
        return XxHash64.hex(XxHash64.hash(
                canonical(method, methodVersion, params, inputEpisodeIds).getBytes(StandardCharsets.UTF_8)));
    }

    public static String canonical(String method, String methodVersion, Map<String, ?> params,
                                   List<String> inputEpisodeIds) {
        if (method == null || method.isEmpty()) {
            throw new IllegalArgumentException("method must not be empty");
        }
        if (methodVersion == null || methodVersion.isEmpty()) {
            throw new IllegalArgumentException("methodVersion must not be empty");
        }
        if (inputEpisodeIds == null || inputEpisodeIds.isEmpty()) {
            throw new IllegalArgumentException("an analysis result needs at least one input episode");
        }
        StringBuilder sb = new StringBuilder(escape(method)).append('@').append(escape(methodVersion)).append('(');
        int n = 0;
        for (Map.Entry<String, Object> e : params(params).entrySet()) {
            if (n++ > 0) {
                sb.append(',');
            }
            sb.append(escape(e.getKey())).append('=');
            Object v = e.getValue();
            if (v instanceof Long) {
                sb.append('l').append(v);
            } else if (v instanceof Double d) {
                sb.append('d').append(HEX.toHexDigits(Double.doubleToRawLongBits(d)));
            } else if (v instanceof String s) {
                sb.append('s').append(escape(s));
            } else {
                sb.append('a');
                List<?> list = (List<?>) v;
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(';');
                    }
                    sb.append(HEX.toHexDigits(Double.doubleToRawLongBits((Double) list.get(i))));
                }
            }
        }
        sb.append(")[");
        for (int i = 0; i < inputEpisodeIds.size(); i++) {
            String id = inputEpisodeIds.get(i);
            if (id == null || id.isEmpty()) {
                throw new IllegalArgumentException("input episode ids must not be empty");
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(id));
        }
        return sb.append(']').toString();
    }

    /**
     * Parameters as Avro values in UTF-8 key order: Integer becomes Long, a List of Numbers becomes a List of
     * Doubles. Avro writes maps in iteration order, so the encoding is the same bytes as in Python.
     */
    public static Map<String, Object> params(Map<String, ?> params) {
        if (params == null) {
            return new LinkedHashMap<>();
        }
        String[] keys = new String[params.size()];
        Object[] values = new Object[keys.length];
        int i = 0;
        for (Map.Entry<String, ?> e : params.entrySet()) {
            keys[i] = e.getKey();
            values[i] = value(e.getValue());
            i++;
        }
        byte[][] utf8 = new byte[keys.length][];
        Integer[] order = new Integer[keys.length];
        for (i = 0; i < keys.length; i++) {
            if (keys[i] == null || keys[i].isEmpty()) {
                throw new IllegalArgumentException("parameter names must not be empty");
            }
            utf8[i] = keys[i].getBytes(StandardCharsets.UTF_8);
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> Arrays.compareUnsigned(utf8[a], utf8[b]));
        Map<String, Object> out = new LinkedHashMap<>();
        for (int k : order) {
            out.put(keys[k], values[k]);
        }
        return out;
    }

    /** Throws if resultId, input lists, time range, curves or matrices are inconsistent. */
    public static void verify(AnalysisResult r) {
        String expected = resultId(r.getMethod(), r.getMethodVersion(), r.getParams(), r.getInputEpisodeIds());
        if (!expected.equals(r.getResultId())) {
            throw new IllegalArgumentException("resultId " + r.getResultId() + " does not match " + expected);
        }
        if (r.getInputSeriesIds().size() != r.getInputEpisodeIds().size()) {
            throw new IllegalArgumentException(r.getInputSeriesIds().size() + " inputSeriesIds for "
                    + r.getInputEpisodeIds().size() + " inputEpisodeIds");
        }
        long tStart = Micros.of(r.getTStart());
        long tEnd = Micros.of(r.getTEnd());
        if (tStart >= tEnd) {
            throw new IllegalArgumentException("tStart " + tStart + " must be before tEnd " + tEnd);
        }
        for (ResultCurve c : r.getCurves()) {
            if (c.getX().size() != c.getY().size()) {
                throw new IllegalArgumentException("curve " + c.getName() + ": x has " + c.getX().size()
                        + " values, y has " + c.getY().size());
            }
        }
        for (ResultMatrix m : r.getMatrices()) {
            if (m.getValues().size() != (long) m.getRowKeys().size() * m.getColKeys().size()) {
                throw new IllegalArgumentException("matrix " + m.getName() + ": " + m.getValues().size()
                        + " values for " + m.getRowKeys().size() + " x " + m.getColKeys().size());
            }
        }
    }

    private static Object value(Object v) {
        if (v instanceof Long) {
            return v;
        }
        if (v instanceof Integer i) {
            return i.longValue();
        }
        if (v instanceof Double d) {
            return notNaN(d);
        }
        if (v instanceof CharSequence s) {
            return s.toString();
        }
        if (v instanceof List<?> list) {
            List<Double> out = new ArrayList<>(list.size());
            for (Object x : list) {
                if (!(x instanceof Number n)) {
                    throw new IllegalArgumentException("unsupported array parameter element: "
                            + (x == null ? "null" : x.getClass().getSimpleName()));
                }
                out.add(notNaN(n.doubleValue()));
            }
            return out;
        }
        if (v instanceof Boolean) {
            throw new IllegalArgumentException("boolean parameters are not supported; use a long (0 or 1) or a string");
        }
        throw new IllegalArgumentException("unsupported parameter type: "
                + (v == null ? "null" : v.getClass().getSimpleName()));
    }

    private static double notNaN(double v) {
        if (Double.isNaN(v)) {
            throw new IllegalArgumentException("NaN is not allowed in analysis parameters");
        }
        return v;
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '{' || c == '}' || c == ',' || c == '=' || c == '(' || c == ')' || c == '['
                    || c == ']' || c == '@') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
