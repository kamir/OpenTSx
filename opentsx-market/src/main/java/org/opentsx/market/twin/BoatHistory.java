package org.opentsx.market.twin;

import org.opentsx.market.MarketCatalog;
import org.opentsx.twin.Origin;
import org.opentsx.twin.SeriesInputs;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.boat.BoatClock;
import org.opentsx.twin.boat.BoatMode;
import org.opentsx.twin.demo.BoatDemo;
import org.opentsx.twin.house.HouseScenario;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Map;

/**
 * The boat twin ({@code twin/boot-etap22.yaml}) on real irradiance: the two-week trip of the example moved to a
 * summer of the archive (from the first Saturday of July, or a given date) with the global radiation of the
 * sailing area's DWD station bound to {@code solar.ghi_wm2} (MEASURED) instead of the synthetic clear-sky model
 * times a cycled clearness index. The logbook (itinerary) stays the example's.
 */
public final class BoatHistory {

    /** One trip: plan, inputs (logbook + irradiance), irradiance coverage. */
    public record Trip(LocalDate first, TwinPlan plan, SeriesInputs inputs, SeriesInputs logbookOnly,
                       double radiationCoverage) {
    }

    private final HistoryInputs history;
    private final HistorySites.Site site;

    public BoatHistory(HistoryInputs history, HistorySites.Site site) {
        this.history = history;
        this.site = site;
    }

    /** First Saturday of July. */
    public static LocalDate defaultStart(int year) {
        return LocalDate.of(year, 7, 1).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
    }

    public Trip trip(LocalDate first, int days) {
        TwinPlan base = BoatDemo.plan();
        double utcOffsetH = HouseScenario.param(base, "boat", "utcOffsetH", 2);
        int stepsPerDay = (int) (24 * 3_600_000_000L / base.baseStepMicros());
        TwinPlan plan = HouseScenario.withParams(base, Map.of("boat.dayZero", first.toString(),
                        "solar.dayZero", first.toString(), "shore.dayZero", first.toString()),
                first.atStartOfDay(ZoneOffset.ofTotalSeconds((int) (utcOffsetH * 3600))).toInstant(), days * stepsPerDay);
        long t0 = plan.startMicros();
        long step = plan.baseStepMicros();
        SeriesInputs log = logbook(plan, BoatDemo.TRIP, first, utcOffsetH);
        double[] ghi = history.resampled(MarketCatalog.dwd(site.radiation(), "global_radiation"), t0, step, plan.steps());
        SeriesInputs in = log.with(new SeriesInputs().bind("solar.ghi_wm2", t0, step, ghi, Origin.MEASURED));
        return new Trip(first, plan, in, log, HistoryInputs.coverage(ghi));
    }

    /** Logbook {@code boat.mode_obs} for an itinerary from {@code dayZero} (as {@link BoatDemo#logbook}). */
    static SeriesInputs logbook(TwinPlan plan, String itinerary, LocalDate dayZero, double utcOffsetH) {
        double[] mode = new double[plan.steps()];
        for (int i = 0; i < mode.length; i++) {
            long t = plan.startMicros() + i * plan.baseStepMicros();
            mode[i] = BoatMode.modeAt(itinerary, BoatClock.dayIndex(t, utcOffsetH, dayZero.toEpochDay()),
                    BoatClock.localHour(t, utcOffsetH), 9, 17);
        }
        return new SeriesInputs().bind("boat.mode_obs", plan.startMicros(), plan.baseStepMicros(), mode, Origin.REPORTED);
    }
}
