package org.opentsx.wayang;

import org.opentsx.model.Episodes;
import org.opentsx.model.Micros;
import org.opentsx.model.SeriesKeys;
import org.opentsx.model.v2.Episode;
import org.opentsx.model.v2.SeriesStats;
import org.opentsx.stats.QuantileSketch;
import org.opentsx.stats.StatsAccumulator;
import org.opentsx.stats.StatsEngine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

record FederatedWindparkData(List<Episode> episodes, Map<String, List<Episode>> sites,
                            Map<Window, List<TsxStatsMerge.Partial>> partials,
                            Map<Window, SeriesStats> centralized, Map<Window, Long> gaps,
                            Map<Window, byte[]> uncollapsedCentral,
                            Map<String, Map<Window, byte[]>> uncollapsedSites, Map<String, String> rawGroups) {

    static final double ALPHA = 0.01;
    static final List<Duration> WINDOWS = List.of(Duration.ofMinutes(10), Duration.ofHours(1));

    record Window(String groupId, String windowType, long startMicros, long endMicros) implements Comparable<Window> {
        @Override
        public int compareTo(Window other) {
            int comparison = groupId.compareTo(other.groupId);
            if (comparison == 0) comparison = windowType.compareTo(other.windowType);
            if (comparison == 0) comparison = Long.compare(startMicros, other.startMicros);
            return comparison == 0 ? Long.compare(endMicros, other.endMicros) : comparison;
        }
    }

    static String site(Episode episode) {
        return switch (episode.getSeries().getTags().get("turbine")) {
            case "T01", "T02", "T03" -> "s1";
            case "T04", "T05", "T06" -> "s2";
            case "T07", "T08", "T09", "T10" -> "s3";
            default -> throw new IllegalArgumentException("unexpected W3 turbine");
        };
    }

    static String group(Episode episode) {
        return SeriesKeys.seriesId(episode.getSeries().getMetric(), Map.of("park", episode.getSeries().getTags().get("park")));
    }

    String metric(String groupId) {
        return episodes.stream().filter(e -> groupId.equals(rawGroups.get(e.getSeries().getSeriesId())))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown W3 group")).getSeries().getMetric();
    }

    private static Window window(SeriesStats s, String group) {
        return new Window(group, s.getWindowType(), Micros.of(s.getWindowStart()), Micros.of(s.getWindowEnd()));
    }

    private static List<SeriesStats> rawStatistics(List<Episode> episodes) {
        StatsEngine engine = new StatsEngine(StatsEngine.Config.defaults().withWindows(WINDOWS.toArray(Duration[]::new))
                .withSketchAlpha(ALPHA));
        List<SeriesStats> output = new ArrayList<>();
        episodes.forEach(e -> output.addAll(engine.onEpisode(e)));
        output.addAll(engine.flush());
        return output.stream().filter(s -> !s.getWindowType().equals(StatsEngine.GLOBAL)).toList();
    }

    static FederatedWindparkData load() {
        return fromEpisodes(KafScaleFixture.scenarioW());
    }

    static FederatedWindparkData fromEpisodes(List<Episode> input) {
        List<Episode> episodes = List.copyOf(input);
        Map<String, List<Episode>> sites = new TreeMap<>();
        Map<String, String> groups = new TreeMap<>();
        for (Episode episode : episodes) {
            sites.computeIfAbsent(site(episode), key -> new ArrayList<>()).add(episode);
            String previous = groups.putIfAbsent(episode.getSeries().getSeriesId(), group(episode));
            if (previous != null && !previous.equals(group(episode))) throw new IllegalStateException("W3 series changed group");
        }
        Map<Window, List<TsxStatsMerge.Partial>> partials = new TreeMap<>();
        for (var site : sites.entrySet()) {
            Map<Window, Map<String, SeriesStats>> windows = new TreeMap<>();
            for (SeriesStats statistics : rawStatistics(site.getValue())) {
                Map<String, SeriesStats> raw = windows.computeIfAbsent(window(statistics, groups.get(statistics.getSeriesId())),
                        key -> new TreeMap<>());
                if (raw.putIfAbsent(statistics.getSeriesId(), statistics) != null) throw new IllegalStateException("duplicate raw W3 window");
            }
            windows.forEach((window, raw) -> {
                StatsAccumulator combined = new StatsAccumulator(ALPHA);
                raw.values().forEach(s -> combined.merge(StatsAccumulator.of(s)));
                partials.computeIfAbsent(window, key -> new ArrayList<>()).add(new TsxStatsMerge.Partial(site.getKey(),
                        combined.toSeriesStats(window.groupId(), window.windowType(), window.startMicros(), window.endMicros())));
            });
        }
        Map<Window, Long> gaps = new TreeMap<>();
        for (SeriesStats raw : rawStatistics(episodes)) gaps.merge(window(raw, groups.get(raw.getSeriesId())), raw.getGapCount(), Math::addExact);
        Map<Window, StatsAccumulator> central = new TreeMap<>();
        Map<Window, QuantileSketch> centralSketches = new TreeMap<>();
        Map<String, Map<Window, QuantileSketch>> siteSketches = new TreeMap<>();
        for (Episode episode : episodes) {
            String group = group(episode);
            Map<Window, QuantileSketch> localSketches = siteSketches.computeIfAbsent(site(episode), key -> new TreeMap<>());
            long[] times = Episodes.timestampsMicros(episode);
            double[] values = Episodes.values(episode);
            for (Duration duration : WINDOWS) {
                long width = duration.toNanos() / 1000;
                for (int i = 0; i < values.length; i++) {
                    long start = Math.floorDiv(times[i], width) * width;
                    Window window = new Window(group, duration.toString(), start, Math.addExact(start, width));
                    central.computeIfAbsent(window, key -> new StatsAccumulator(ALPHA)).add(times[i], values[i]);
                    centralSketches.computeIfAbsent(window, key -> new QuantileSketch(ALPHA, Integer.MAX_VALUE)).add(values[i]);
                    localSketches.computeIfAbsent(window, key -> new QuantileSketch(ALPHA, Integer.MAX_VALUE)).add(values[i]);
                }
            }
        }
        Map<Window, SeriesStats> centralized = new TreeMap<>();
        central.forEach((window, accumulator) -> centralized.put(window, accumulator.toSeriesStats(
                window.groupId(), window.windowType(), window.startMicros(), window.endMicros())));
        Map<Window, byte[]> uncollapsed = new TreeMap<>();
        centralSketches.forEach((window, sketch) -> uncollapsed.put(window, sketch.toBytes()));
        Map<String, Map<Window, byte[]>> uncollapsedSites = new TreeMap<>();
        siteSketches.forEach((site, windows) -> {
            Map<Window, byte[]> bytes = new TreeMap<>();
            windows.forEach((window, sketch) -> bytes.put(window, sketch.toBytes()));
            uncollapsedSites.put(site, Collections.unmodifiableMap(bytes));
        });
        sites.replaceAll((key, value) -> List.copyOf(value));
        partials.replaceAll((key, value) -> List.copyOf(value));
        return new FederatedWindparkData(episodes, Collections.unmodifiableMap(sites), Collections.unmodifiableMap(partials),
                Collections.unmodifiableMap(centralized), Collections.unmodifiableMap(gaps),
                Collections.unmodifiableMap(uncollapsed), Collections.unmodifiableMap(uncollapsedSites), Collections.unmodifiableMap(groups));
    }
}
