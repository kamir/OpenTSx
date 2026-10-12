package org.opentsx.twin.atoms;

import org.opentsx.twin.Atom;
import org.opentsx.twin.Params;
import org.opentsx.twin.Phase;
import org.opentsx.twin.PortSpec;
import org.opentsx.twin.StepContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * H5: household electricity from an inventory, plus internal heat gains. Each item of {@code inventory} is either
 * <ul>
 *   <li>continuous ({@code avgW}: mean power of a cycling appliance such as a fridge or freezer), or</li>
 *   <li>scheduled ({@code w} while on, {@code standbyW} while off) with hour ranges {@code weekday} and
 *       {@code weekend}, e.g. {@code ["18-23"]}; {@code seasonal} (0..1) scales use up in winter and down in summer
 *       (lighting).</li>
 * </ul>
 * {@code inside: false} marks items outside the heated envelope (e.g. a freezer in the cellar); their electricity is
 * not a heat gain. Persons add {@code personW} each while at home ({@code homeWeekday}, {@code homeWeekend}).
 * {@code scale} multiplies all appliance electricity; it is a calibration parameter.
 * <p>
 * Controls: {@code standby_off} (1 = switchable standby removed, e.g. by switched power strips) and
 * {@code factor.<item>} per item (e.g. 0.4 for a new freezer, 0.5 for half the TV time).
 */
public final class Appliances extends Atom {

    private record Item(String name, double avgW, double w, double standbyW, double[] weekday,
                        double[] weekend, double seasonal, boolean inside) {
    }

    private final List<Item> items = new ArrayList<>();
    private final double scale;
    private final double persons;
    private final double personKw;
    private final double[] homeWeekday;
    private final double[] homeWeekend;
    private final LocalClock clock;

    private final Map<String, Double> itemKwh = new LinkedHashMap<>();
    private double elKwh;
    private double standbyKwh;
    private double gainKwh;

    @SuppressWarnings("unchecked")
    public Appliances(String id, Params params) {
        super(id, params);
        scale = params.d("scale", 1.0);
        persons = params.d("persons", 2);
        personKw = params.d("personW", 80) / 1000.0;
        homeWeekday = LocalClock.parse(params.list("homeWeekday", List.of("0-7", "16-24")));
        homeWeekend = LocalClock.parse(params.list("homeWeekend", List.of("0-24")));
        clock = new LocalClock(params.s("zone", "Europe/Berlin"));
        Object inv = params.asMap().get("inventory");
        if (inv instanceof List<?> list) {
            for (Object o : list) {
                Params p = new Params((Map<String, Object>) o);
                String name = p.s("name", null);
                if (name == null || itemKwh.containsKey(name)) {
                    throw new IllegalArgumentException("inventory items need a unique name: " + o);
                }
                items.add(new Item(name, p.d("avgW", 0), p.d("w", 0), p.d("standbyW", 0),
                        LocalClock.parse(p.list("weekday", List.of())),
                        LocalClock.parse(p.list("weekend", List.of())), p.d("seasonal", 0),
                        Boolean.parseBoolean(p.s("inside", "true"))));
                itemKwh.put(name, 0.0);
            }
        }
    }

    @Override
    public String type() {
        return "appliances";
    }

    @Override
    public Phase phase() {
        return Phase.SOURCES;
    }

    @Override
    public List<PortSpec> inputs() {
        return List.of();
    }

    @Override
    public List<PortSpec> outputs() {
        return List.of(PortSpec.flow("p_el_kw", "kW"), PortSpec.flow("q_gain_kw", "kW"));
    }

    @Override
    public Map<String, Double> controlDefaults() {
        Map<String, Double> c = new LinkedHashMap<>();
        c.put("standby_off", 0.0);
        items.forEach(i -> c.put("factor." + i.name(), 1.0));
        return c;
    }

    @Override
    public Map<String, Double> state() {
        Map<String, Double> s = new LinkedHashMap<>();
        s.put("el_kwh", elKwh);
        s.put("standby_kwh", standbyKwh);
        s.put("gain_kwh", gainKwh);
        itemKwh.forEach((k, v) -> s.put("el_" + k + "_kwh", v));
        return s;
    }

    @Override
    public void restore(Map<String, Double> s) {
        elKwh = s.get("el_kwh");
        standbyKwh = s.get("standby_kwh");
        gainKwh = s.get("gain_kwh");
        itemKwh.replaceAll((k, v) -> s.get("el_" + k + "_kwh"));
    }

    @Override
    public void step(StepContext ctx) {
        long t = ctx.tMicros() + (long) (ctx.dtSeconds() * 5e5);
        double h = clock.hour(t);
        boolean we = clock.weekend(t);
        // +1 at the winter solstice, -1 at the summer solstice
        double winter = Math.cos(2 * Math.PI * (clock.dayOfYear(t) + 10) / 365.25);
        boolean standbyOff = ctx.control("standby_off") > 0;
        double dt = ctx.dtHours();
        double total = 0;
        double standby = 0;
        double gain = 0;
        for (Item i : items) {
            double f = ctx.control("factor." + i.name());
            double kw;
            double sb = 0;
            if (i.avgW() > 0) {
                kw = i.avgW() / 1000.0;
            } else if (LocalClock.in(we ? i.weekend() : i.weekday(), h)) {
                kw = i.w() / 1000.0 * (1 + i.seasonal() * winter);
            } else {
                sb = standbyOff ? 0 : i.standbyW() / 1000.0;
                kw = sb;
            }
            kw *= scale * f;
            sb *= scale * f;
            total += kw;
            standby += sb;
            if (i.inside()) {
                gain += kw;
            }
            itemKwh.merge(i.name(), kw * dt, Double::sum);
        }
        if (LocalClock.in(we ? homeWeekend : homeWeekday, h)) {
            gain += persons * personKw;
        }
        elKwh += total * dt;
        standbyKwh += standby * dt;
        gainKwh += gain * dt;
        ctx.out("p_el_kw", total);
        ctx.out("q_gain_kw", gain);
    }
}
