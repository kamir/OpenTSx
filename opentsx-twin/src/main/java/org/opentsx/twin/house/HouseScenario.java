package org.opentsx.twin.house;

import org.opentsx.twin.Intervention;
import org.opentsx.twin.TwinPlan;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The example house (plan {@code twin/haus-erzgebirge.yaml}): the reference operation of one year and the
 * efficiency levers as alternatives. Every lever is a set of interventions on top of the same reference, with the
 * same weather, so its effect is "alternative minus reference" (P8 evaluation principle). All numbers are invented
 * example assumptions until the plan is calibrated against real bills.
 */
public final class HouseScenario {

    public static final String PLAN = "twin/haus-erzgebirge.yaml";
    public static final ZoneId ZONE = ZoneId.of("Europe/Berlin");

    /** A lever: name, what it changes, and its interventions (in force from the start of the run). */
    public record Lever(String name, String description, List<Intervention> interventions) {
    }

    private HouseScenario() {
    }

    public static TwinPlan plan() {
        return TwinPlan.resource(PLAN);
    }

    /** Local date-time to an instant in the house's time zone. */
    public static Instant local(String isoLocal) {
        return LocalDateTime.parse(isoLocal).atZone(ZONE).toInstant();
    }

    /** An oil delivery of {@code litres}: {@code delivery_lph} is set for one superstep and released afterwards. */
    public static List<Intervention> oilDelivery(String tank, Instant at, double litres, Duration step) {
        double hours = step.toNanos() / 3.6e12;
        return List.of(
                Intervention.set(at, tank, "delivery_lph", litres / hours, "homeowner", "oil delivery " + litres + " l"),
                Intervention.release(at.plus(step), tank, "delivery_lph", "homeowner", "delivery done"));
    }

    /** A firewood delivery (or own felling) of {@code ster}, one superstep long. */
    public static List<Intervention> woodDelivery(String store, Instant at, double ster, Duration step) {
        double hours = step.toNanos() / 3.6e12;
        return List.of(
                Intervention.set(at, store, "delivery_ster_ph", ster / hours, "homeowner", "wood " + ster + " Ster"),
                Intervention.release(at.plus(step), store, "delivery_ster_ph", "homeowner", "delivery done"));
    }

    /** Reference operation of the example year: one oil delivery in autumn, one wood delivery in early summer. */
    public static List<Intervention> reference(TwinPlan plan) {
        List<Intervention> log = new ArrayList<>();
        log.addAll(oilDelivery(id(plan, "oil-tank"), local("2025-09-15T08:00"), 2000, plan.baseStep()));
        log.addAll(woodDelivery(id(plan, "wood-store"), local("2025-06-02T10:00"), 4, plan.baseStep()));
        return log;
    }

    /** The efficiency levers of the concept (08 §6), each as alternative to the reference. */
    public static List<Lever> levers(TwinPlan plan) {
        Instant t0 = plan.start();
        String b = id(plan, "building");
        String stove = id(plan, "wood-stove");
        String boiler = id(plan, "oil-boiler");
        String dhw = id(plan, "dhw");
        String app = id(plan, "appliances");
        List<Lever> out = new ArrayList<>();
        out.add(lever("minus-1K", "room set-point 21 -> 20 C", t0, b, "setpoint_c", 20));
        out.add(lever("night-setback-5K", "night setback 3 -> 5 K (22-06 h)", t0, b, "setback_k", 5));
        out.add(lever("unused-rooms-17C", "guest/study rooms (20 % of area) 4 K cooler", t0, b, "unused_setback_k", 4));
        out.add(lever("more-wood", "stove 4 -> 7 h per evening (more wood, less oil)", t0, stove, "hours_per_day", 7));
        out.add(lever("dhw-50C", "hot-water storage 60 -> 50 C", t0, dhw, "t_store_c", 50));
        out.add(lever("he-pump", "circulation pump 65 W -> 8 W (high-efficiency)", t0, boiler, "pump_w", 8));
        out.add(lever("new-freezer", "chest freezer replaced (-60 %)", t0, app, "factor.gefriertruhe", 0.4));
        out.add(lever("standby-off", "switchable standby removed (power strips)", t0, app, "standby_off", 1));
        out.add(new Lever("less-tv", "TV time -30 % on all three TVs", List.of(
                set(t0, app, "factor.tv-wohnzimmer", 0.7),
                set(t0, app, "factor.tv-schlafzimmer", 0.7),
                set(t0, app, "factor.tv-kueche", 0.7))));
        return out;
    }

    /** A plan with some atom parameters replaced, keyed {@code "atom.param"}. */
    public static TwinPlan withParams(TwinPlan plan, Map<String, ?> overrides) {
        return withParams(plan, overrides, plan.start(), plan.steps());
    }

    /** A plan with replaced parameters, start and number of steps. */
    public static TwinPlan withParams(TwinPlan plan, Map<String, ?> overrides, Instant start, int steps) {
        Map<String, Map<String, Object>> byAtom = new HashMap<>();
        overrides.forEach((key, v) -> {
            int dot = key.indexOf('.');
            if (dot <= 0) {
                throw new IllegalArgumentException("override key must be 'atom.param': " + key);
            }
            byAtom.computeIfAbsent(key.substring(0, dot), x -> new LinkedHashMap<>()).put(key.substring(dot + 1), v);
        });
        List<TwinPlan.AtomSpec> atoms = new ArrayList<>();
        for (TwinPlan.AtomSpec a : plan.atoms()) {
            Map<String, Object> p = new LinkedHashMap<>(a.params());
            Map<String, Object> o = byAtom.remove(a.id());
            if (o != null) {
                p.putAll(o);
            }
            atoms.add(new TwinPlan.AtomSpec(a.id(), a.type(), p, a.policy()));
        }
        if (!byAtom.isEmpty()) {
            throw new IllegalArgumentException("overrides for unknown atoms " + byAtom.keySet());
        }
        return new TwinPlan(plan.twin(), start, plan.baseStep(), steps, atoms, plan.wiring());
    }

    /** Id of the (first) atom of a type in the plan. */
    public static String id(TwinPlan plan, String type) {
        return plan.atoms().stream().filter(a -> a.type().equals(type)).map(TwinPlan.AtomSpec::id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("plan " + plan.twin() + " has no atom of type " + type));
    }

    /** A numeric parameter of an atom in the plan, or {@code dflt}. */
    public static double param(TwinPlan plan, String atomId, String name, double dflt) {
        return plan.atoms().stream().filter(a -> a.id().equals(atomId)).findFirst()
                .map(a -> a.params().get(name)).map(v -> ((Number) v).doubleValue()).orElse(dflt);
    }

    private static Lever lever(String name, String description, Instant t0, String atom, String control, double v) {
        return new Lever(name, description, List.of(set(t0, atom, control, v)));
    }

    private static Intervention set(Instant t0, String atom, String control, double v) {
        return Intervention.set(t0, atom, control, v, "what-if", "lever");
    }
}
