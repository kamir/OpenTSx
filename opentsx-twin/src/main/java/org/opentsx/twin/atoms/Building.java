package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H1: the building as one lumped thermal zone (1R1C): heat capacity {@code capacityKwhPerK} and heat-loss coefficient
 * {@code lossWPerK} (transmission plus ventilation, H = H_T + H_V). Heat balance per step:
 * <pre>C · dT_in/dt = Q_heater + Q_stove + Q_gains + Q_solar − H · (T_in − T_out)</pre>
 * integrated explicitly, so the balance closes exactly ({@code stored = Σ in − Σ loss}).
 * <p>
 * <b>Thermostat (the comfort limit).</b> At the end of each step the atom computes the heat the heating circuit must
 * deliver in the next step to reach the set-point of the next step ({@code q_req_kw}); the boiler reads it over a
 * feedback wire. The set-point follows a day/night schedule ({@code dayStartH}..{@code dayEndH}, {@code setback_k}
 * at night) and is lowered by {@code unused_setback_k} on the area share {@code unusedShare} of rarely used rooms
 * (one zone, so this is an area-weighted approximation). Above the heating limit ({@code heatingLimitC}, on a 24-h
 * mean of the outdoor temperature) the season signal is 0 and no space heat is requested.
 * <p>
 * Controls: {@code setpoint_c} (day), {@code setback_k} (night reduction), {@code unused_setback_k}.
 */
public final class Building extends Atom {

    private final double areaM2;
    private final double lossKwPerK;
    private final double capKwhPerK;
    private final double apertureM2;
    private final double heatingLimitC;
    private final double dayStartH;
    private final double dayEndH;
    private final double unusedShare;
    private final double comfortBandK;
    private final LocalClock clock;

    private double tIn;
    private double tAvg24;
    private double heaterKwh;
    private double stoveKwh;
    private double gainKwh;
    private double solarKwh;
    private double lossKwh;
    private double underHours;
    private double tInSum;
    private double hours;
    private double seasonHours;

    public Building(String id, Params params) {
        super(id, params);
        areaM2 = params.d("areaM2", 140);
        lossKwPerK = params.d("lossWPerK", 250) / 1000.0;
        capKwhPerK = params.d("capacityKwhPerK", 14);
        apertureM2 = params.d("solarApertureM2", 4);
        heatingLimitC = params.d("heatingLimitC", 15);
        dayStartH = params.d("dayStartH", 6);
        dayEndH = params.d("dayEndH", 22);
        unusedShare = params.d("unusedShare", 0);
        comfortBandK = params.d("comfortBandK", 0.5);
        clock = new LocalClock(params.s("zone", "Europe/Berlin"));
        tIn = params.d("t0C", 20);
        tAvg24 = params.d("tAvg0C", 0);
    }

    @Override
    public String type() {
        return "building";
    }

    @Override
    public Phase phase() {
        return Phase.FLOWS;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(
                PortSpec.signal("t_out_c", "°C"),
                PortSpec.signal("ghi_w_m2", "W/m2"),
                PortSpec.flow("q_heater_kw", "kW"),
                PortSpec.flow("q_stove_kw", "kW"),
                PortSpec.flow("q_gain_kw", "kW"),
                PortSpec.signal("setpoint_c", "°C"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(
                PortSpec.signal("t_in_c", "°C"),
                PortSpec.signal("q_req_kw", "kW"),
                PortSpec.signal("season", "1"),
                PortSpec.flow("q_loss_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("setpoint_c", params.d("setpointC", 21));
        c.put("setback_k", params.d("setbackK", 3));
        c.put("unused_setback_k", params.d("unusedSetbackK", 0));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("t_in_c", tIn);
        s.put("t_avg24_c", tAvg24);
        s.put("heater_kwh", heaterKwh);
        s.put("stove_kwh", stoveKwh);
        s.put("gain_kwh", gainKwh);
        s.put("solar_kwh", solarKwh);
        s.put("loss_kwh", lossKwh);
        s.put("under_h", underHours);
        s.put("t_in_sum", tInSum);
        s.put("hours", hours);
        s.put("season_h", seasonHours);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        tIn = s.get("t_in_c");
        tAvg24 = s.get("t_avg24_c");
        heaterKwh = s.get("heater_kwh");
        stoveKwh = s.get("stove_kwh");
        gainKwh = s.get("gain_kwh");
        solarKwh = s.get("solar_kwh");
        lossKwh = s.get("loss_kwh");
        underHours = s.get("under_h");
        tInSum = s.get("t_in_sum");
        hours = s.get("hours");
        seasonHours = s.get("season_h");
    }

    @Override
    public Map<String, Double> restingOutputs() {
        return Map.of("t_in_c", tIn, "q_req_kw", 0.0, "season", tAvg24 < heatingLimitC ? 1.0 : 0.0);
    }

    /** Effective (area-weighted) set-point at {@code tMicros} under the given controls. */
    double setpoint(StepContext ctx, long tMicros) {
        double h = clock.hour(tMicros);
        double sp = ctx.control("setpoint_c");
        if (h < dayStartH || h >= dayEndH) {
            sp -= ctx.control("setback_k");
        }
        return sp - unusedShare * ctx.control("unused_setback_k");
    }

    @Override
    public void step(StepContext ctx) {
        double dt = ctx.dtHours();
        double tOut = ctx.inOr("t_out_c", 5);
        double solar = Math.max(0, ctx.inOr("ghi_w_m2", 0)) / 1000.0 * apertureM2;
        double heater = Math.max(0, ctx.inOr("q_heater_kw", 0));
        double stove = Math.max(0, ctx.inOr("q_stove_kw", 0));
        double gains = Math.max(0, ctx.inOr("q_gain_kw", 0));

        double loss = lossKwPerK * (tIn - tOut);
        tIn += dt * (heater + stove + gains + solar - loss) / capKwhPerK;
        heaterKwh += heater * dt;
        stoveKwh += stove * dt;
        gainKwh += gains * dt;
        solarKwh += solar * dt;
        lossKwh += loss * dt;
        hours += dt;
        tInSum += tIn * dt;
        tAvg24 += (tOut - tAvg24) * Math.min(1, dt / 24.0);

        boolean season = tAvg24 < heatingLimitC;
        if (season) {
            seasonHours += dt;
            if (tIn < setpoint(ctx, ctx.tMicros()) - comfortBandK) {
                underHours += dt;
            }
        }

        // heat needed in the next step to reach the next set-point (persistence forecast of weather and gains)
        double req = 0;
        if (season) {
            long next = ctx.tMicros() + (long) (ctx.dtSeconds() * 1e6);
            req = capKwhPerK * (setpoint(ctx, next) - tIn) / dt + lossKwPerK * (tIn - tOut) - gains - solar - stove;
            req = Math.max(0, req);
        }
        ctx.out("t_in_c", tIn);
        ctx.out("q_req_kw", req);
        ctx.out("season", season ? 1 : 0);
        ctx.out("q_loss_kw", loss);
    }

    public double areaM2() {
        return areaM2;
    }

    public double capacityKwhPerK() {
        return capKwhPerK;
    }
}
