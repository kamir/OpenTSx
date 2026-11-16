package org.opentsx.retrieval.examples;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chart data of an operator example, written as {@code <id>.csv} into {@code OPENTSX_EXAMPLES_DIR} when that is set.
 * tools/operator_charts.py draws docs/user-manual/img/operators/{@code <id>}.png from it; the file format is described there.
 */
final class ExampleChart {

    private final String id;
    private final StringBuilder head = new StringBuilder();
    private final StringBuilder rows = new StringBuilder("panel,series,mark,x,y\n");
    private int panels;

    ExampleChart(String id, String title) {
        this.id = id;
        head.append("#title=").append(text(title)).append('\n');
    }

    ExampleChart panel(String title, String x, String y, String log) {
        if (!log.matches("x?y?")) {
            throw new IllegalArgumentException("log must be empty, x, y or xy, not " + log);
        }
        panels++;
        head.append("#panel=").append(panels).append(";title=").append(text(title)).append(";x=").append(text(x))
                .append(";y=").append(text(y)).append(";log=").append(log).append('\n');
        return this;
    }

    ExampleChart note(String s) {
        head.append("#note=").append(panels).append(';').append(text(s)).append('\n');
        return this;
    }

    ExampleChart add(String series, String mark, double[] x, double[] y) {
        if (panels == 0) {
            throw new IllegalStateException("add a panel first");
        }
        if (series.contains(",")) {
            throw new IllegalArgumentException("a series name holds no comma: " + series);
        }
        if (x.length != y.length) {
            throw new IllegalArgumentException(series + ": " + x.length + " x values but " + y.length + " y values");
        }
        for (int i = 0; i < x.length; i++) {
            rows.append(panels).append(',').append(series).append(',').append(mark).append(',')
                    .append(x[i]).append(',').append(y[i]).append('\n');
        }
        return this;
    }

    void write() throws IOException {
        String dir = System.getenv("OPENTSX_EXAMPLES_DIR");
        if (dir == null) {
            return;
        }
        Files.writeString(Path.of(dir, id + ".csv"), head.toString() + rows, StandardCharsets.UTF_8);
    }

    private static String text(String s) {
        if (s.contains(";") || s.contains("\n")) {
            throw new IllegalArgumentException("chart text holds no ';' or line break: " + s);
        }
        return s;
    }
}
