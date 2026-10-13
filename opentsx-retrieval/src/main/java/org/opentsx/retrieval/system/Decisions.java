package org.opentsx.retrieval.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * What the analyses of a decision card may claim today (docs/datamodel-2026/15-ENTSCHEIDUNGSKREIS.md, sections 3 and
 * 7; decision D59). A card says which signals a decision needs and what makes each fit for this purpose; this class
 * puts the state of the signals next to that and gives every analysis a status:
 *
 * <ul>
 *   <li><b>blocked</b>: a signal it needs is missing in the grade asked for, is older than allowed, or has an origin
 *   the decision does not accept. What is missing blocks exactly the analyses that need it.</li>
 *   <li><b>restricted</b>: the inputs are there, but the analysis has no evidence, was only checked on generated data,
 *   an input lies outside the range it was checked in, or an input was made of generated data.</li>
 *   <li><b>usable</b>: neither.</li>
 * </ul>
 *
 * Clean inputs are a condition, not a proof: an analysis without evidence is restricted however good its inputs are.
 * Age is measured against the newest data among the signals the decision needs, not against the clock and not
 * against series of the system the decision does not use: the question is whether an input lags behind the others.
 */
public final class Decisions {

    public static final String SCHEMA = "opentsx.decision-status.v1";
    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private Decisions() {
    }

    /**
     * @param signals the state of the signals of the system ({@code opentsx.signal-status.v1})
     */
    public static JsonNode status(SystemDoc d, JsonNode signals) {
        Map<String, JsonNode> byId = new TreeMap<>();
        signals.path("signals").forEach(s -> byId.put(s.path("id").asText(), s));
        ObjectNode out = JSON.objectNode();
        out.put("schema", SCHEMA);
        out.put("system", d.system());
        out.put("title", d.title());
        ArrayNode list = out.putArray("decisions");
        String newest = null;
        for (Map.Entry<String, JsonNode> e : d.decisions().entrySet()) {
            // the newest data among what this decision needs: a system may hold series of other periods
            String asOf = null;
            for (JsonNode n : e.getValue().path("needs")) {
                JsonNode s = byId.get(n.path("signal").asText());
                String to = s == null ? null : s.path("grades").path(n.path("grade").asText("clean")).path("data").path("to").asText(null);
                asOf = to != null && (asOf == null || to.compareTo(asOf) > 0) ? to : asOf;
            }
            ObjectNode one = decision(e.getKey(), e.getValue(), byId, asOf);
            if (asOf != null) {
                one.put("asOf", asOf);
                newest = newest == null || asOf.compareTo(newest) > 0 ? asOf : newest;
            }
            list.add(one);
        }
        if (newest != null) {
            out.put("asOf", newest);
        }
        return out;
    }

    private static ObjectNode decision(String id, JsonNode card, Map<String, JsonNode> signals, String asOf) {
        ObjectNode o = JSON.objectNode();
        o.put("id", id);
        for (String k : List.of("title", "question", "version", "example", "responsibility", "cadence", "objectives", "levers", "constraints")) {
            if (card.has(k)) {
                o.set(k, card.get(k));
            }
        }
        ArrayNode needs = o.putArray("needs");
        List<ObjectNode> checked = new ArrayList<>();
        for (JsonNode n : card.path("needs")) {
            ObjectNode need = need(n, signals.get(n.path("signal").asText()), asOf);
            needs.add(need);
            checked.add(need);
        }
        ArrayNode analyses = o.putArray("analyses");
        Map<String, String> statusOf = new TreeMap<>();
        for (JsonNode a : card.path("analyses")) {
            ObjectNode an = analysis(a, checked, signals);
            analyses.add(an);
            statusOf.put(a.path("id").asText(), an.path("status").asText());
        }
        ArrayNode alternatives = o.putArray("alternatives");
        for (JsonNode a : card.path("alternatives")) {
            ObjectNode alt = a.deepCopy();
            alt.put("status", statusOf.getOrDefault(a.path("analysis").asText(), "blocked"));
            alternatives.add(alt);
        }
        return o;
    }

    /** One signal the decision needs, with what was found for it and whether that is fit for this purpose. */
    private static ObjectNode need(JsonNode n, JsonNode signal, String asOf) {
        ObjectNode o = n.deepCopy();
        ArrayNode reasons = JSON.arrayNode();
        boolean made = false;
        String id = n.path("signal").asText();
        String grade = n.path("grade").asText("clean");
        if (signal == null) {
            reasons.add(id + " is no signal of the system");
        } else {
            JsonNode g = signal.path("grades").path(grade);
            JsonNode data = g.path("data");
            boolean constant = grade.equals("raw") && "manual".equals(g.path("source").path("kind").asText());
            ObjectNode found = o.putObject("found");
            found.put("state", signal.path("state").asText());
            if (data.isObject()) {
                found.set("data", data);
            }
            // the origin of the data that was found, else that of the signal
            String origin = data.path("origin").asText(signal.path("origin").asText(null));
            if (origin != null) {
                found.put("origin", origin);
            }
            if (!data.isObject() && !constant) {
                reasons.add(id + " has no " + grade + " data (" + signal.path("state").asText().replace('-', ' ') + ")");
            } else {
                List<String> accepted = new ArrayList<>();
                n.path("origins").forEach(x -> accepted.add(x.asText()));
                if (!accepted.isEmpty() && origin != null && !accepted.contains(origin)) {
                    reasons.add(id + " is " + origin.toLowerCase() + "; the decision accepts " + String.join(", ", accepted).toLowerCase());
                }
                String to = data.path("to").asText(null);
                if (n.hasNonNull("maxAge") && to != null && asOf != null) {
                    long age = Duration.between(Instant.parse(to), Instant.parse(asOf)).getSeconds();
                    long allowed = Duration.parse(n.path("maxAge").asText()).getSeconds();
                    found.put("ageSeconds", age);
                    if (age > allowed) {
                        reasons.add(id + " ends " + minutes(age) + " before the newest data the decision needs; allowed are " + minutes(allowed));
                    }
                }
                made = "SYNTHETIC".equals(signal.path("origin").asText()) || data.path("madeOf").asText("").contains("SYNTHETIC")
                        || "demo".equals(data.path("fidelity").asText());
            }
        }
        o.put("ok", reasons.isEmpty());
        o.put("containsSynthetic", made);
        o.set("reasons", reasons);
        return o;
    }

    private static String minutes(long seconds) {
        return seconds < 120 ? seconds + " s" : seconds / 60 + " min";
    }

    private static ObjectNode analysis(JsonNode a, List<ObjectNode> needs, Map<String, JsonNode> signals) {
        ObjectNode o = a.deepCopy();
        String id = a.path("id").asText();
        ArrayNode blocking = JSON.arrayNode();
        ArrayNode restricting = JSON.arrayNode();
        boolean synthetic = false;
        for (ObjectNode n : needs) {
            boolean mine = !n.has("for");
            for (JsonNode f : n.path("for")) {
                mine |= id.equals(f.asText());
            }
            if (!mine || n.path("optional").asBoolean(false)) {
                continue;
            }
            n.path("reasons").forEach(blocking::add);
            synthetic |= n.path("containsSynthetic").asBoolean(false);
        }
        if (synthetic) {
            restricting.add("an input is generated or made of generated data: the result shows the mechanism, not an effect");
        }
        // the range the analysis was checked in, against what the inputs hold
        ArrayNode validity = o.putArray("validity");
        for (JsonNode v : a.path("validity")) {
            ObjectNode r = v.deepCopy();
            JsonNode signal = signals.get(v.path("signal").asText());
            JsonNode data = signal == null ? JSON.missingNode() : best(signal);
            boolean inside = true;
            if (data.has("min") && data.has("max")) {
                r.put("observedMin", data.path("min").asDouble());
                r.put("observedMax", data.path("max").asDouble());
                inside = (!v.has("min") || data.path("min").asDouble() >= v.path("min").asDouble())
                        && (!v.has("max") || data.path("max").asDouble() <= v.path("max").asDouble());
            }
            r.put("inside", inside);
            if (!inside) {
                restricting.add(v.path("signal").asText() + " holds values from " + data.path("min").asDouble() + " to "
                        + data.path("max").asDouble() + ", outside the range the analysis was checked in");
            }
            validity.add(r);
        }
        JsonNode evidence = a.path("evidence");
        if (!evidence.isArray() || evidence.isEmpty()) {
            restricting.add("the analysis has no evidence: it was not checked against anything");
        } else {
            boolean onlySynthetic = true;
            for (JsonNode ev : evidence) {
                onlySynthetic &= ev.path("synthetic").asBoolean(false);
            }
            if (onlySynthetic) {
                restricting.add("the analysis was checked on generated data only");
            }
        }
        o.put("status", !blocking.isEmpty() ? "blocked" : !restricting.isEmpty() ? "restricted" : "usable");
        ArrayNode reasons = o.putArray("reasons");
        blocking.forEach(reasons::add);
        restricting.forEach(reasons::add);
        return o;
    }

    /** The data of the best grade a signal has: clean, else raw. */
    private static JsonNode best(JsonNode signal) {
        JsonNode clean = signal.path("grades").path("clean").path("data");
        return clean.isObject() ? clean : signal.path("grades").path("raw").path("data");
    }
}
