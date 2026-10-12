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
 * H4: domestic hot water, heated by the boiler through a storage cylinder. Heat drawn per day:
 * {@code persons · litresPerPersonDay} at {@code tUseC} from cold water at {@code tColdC}, spread over a fixed
 * morning/evening profile (weekday and weekend differ), plus storage losses {@code storeLossW} at 60 °C, scaled
 * with the storage temperature. {@code scale} multiplies the whole draw; it is a calibration parameter.
 * <p>
 * Control: {@code t_store_c} (storage temperature).
 */
public final class HotWater extends Atom {

    private static final double KWH_PER_LITRE_K = 4.186 / 3600.0;

    private final double usePerDayKwh;
    private final double storeLossKw;
    private final double scale;
    private final LocalClock clock;
    private final double[] weekday;
    private final double[] weekend;
    private final double weekdayHours;
    private final double weekendHours;

    private double useKwh;
    private double lossKwh;

    public HotWater(String id, Params params) {
        super(id, params);
        double litres = params.d("persons", 2) * params.d("litresPerPersonDay", 40);
        usePerDayKwh = litres * KWH_PER_LITRE_K * (params.d("tUseC", 45) - params.d("tColdC", 10));
        storeLossKw = params.d("storeLossW", 60) / 1000.0;
        scale = params.d("scale", 1.0);
        clock = new LocalClock(params.s("zone", "Europe/Berlin"));
        weekday = LocalClock.parse(params.list("weekday", List.of("6-7", "19-21")));
        weekend = LocalClock.parse(params.list("weekend", List.of("8-10", "19-20")));
        weekdayHours = LocalClock.hours(weekday);
        weekendHours = LocalClock.hours(weekend);
    }

    @Override
    public String type() {
        return "dhw";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of(PortSpec.signal("t_store_c", "°C"));
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("q_dhw_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        return Map.of("t_store_c", params.d("tStoreC", 60));
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("use_kwh", useKwh);
        s.put("loss_kwh", lossKwh);
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        useKwh = s.get("use_kwh");
        lossKwh = s.get("loss_kwh");
    }

    @Override
    public void step(StepContext ctx) {
        double h = clock.hour(ctx.tMicros());
        boolean we = clock.weekend(ctx.tMicros());
        double[] profile = we ? weekend : weekday;
        double hours = we ? weekendHours : weekdayHours;
        double use = LocalClock.in(profile, h) ? usePerDayKwh / hours : 0;
        double loss = storeLossKw * Math.max(0, ctx.control("t_store_c") - 20) / 40.0;
        double q = scale * (use + loss);
        double dt = ctx.dtHours();
        useKwh += scale * use * dt;
        lossKwh += scale * loss * dt;
        ctx.out("q_dhw_kw", q);
    }
}
