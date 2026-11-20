package org.opentsx.twin.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.opentsx.conf.ConfCli;
import org.opentsx.conf.OpenTsxConf;
import org.opentsx.conf.OpenTsxConfException;
import org.opentsx.conf.Registry;
import org.opentsx.twin.TwinPlan;
import org.opentsx.twin.TwinRunner;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command line of {@code twin}, {@code house}, {@code boat}, {@code twin-coupling}, {@code twin-schemas} and
 * {@code studio-export}: the keys of the command come from {@link OpenTsxConf#load}, {@code --help} and
 * {@code --version} print on stdout. Exit codes: 0 success, 1 runtime failure, 2 usage or configuration error.
 */
public final class TwinCli {

    static final String PLAN = "opentsx.twin.plan";
    static final String SIMULATED = "opentsx.twin.simulated";
    static final String WRITE_FAILED = "stdout: cannot write the output; check that standard output is open and not full";

    /** The checked work of a command; it writes its result to {@code out}. */
    public interface Run {
        void run(PrintStream out) throws IOException;
    }

    private TwinCli() {
    }

    /**
     * {@code args} without the command word. {@code check} reads and checks the configuration and the files it names and
     * writes nothing; an IllegalArgumentException from it is a usage or configuration error.
     */
    public static int run(String command, List<String> args, Map<String, String> env, PrintStream out, PrintStream err,
                          BiFunction<OpenTsxConf, List<String>, Run> check) {
        List<String> argv = new ArrayList<>(args.size() + 1);
        argv.add(command);
        argv.addAll(args);
        Run run;
        try {
            OpenTsxConf.Loaded loaded = OpenTsxConf.load(argv, env, env.get("OPENTSX_CONF_DIR"));
            if (loaded.help()) {
                out.print(Registry.shipped().help(command));
                return written(out, err);
            }
            if (loaded.version()) {
                out.print(ConfCli.versionLine());
                return written(out, err);
            }
            run = check.apply(loaded.conf(), loaded.args());
        } catch (IllegalArgumentException e) {
            err.print("opentsx: " + oneLine(e.getMessage() == null ? e.toString() : e.getMessage()) + "\n");
            err.flush();
            return 2;
        } catch (RuntimeException e) {
            return failed(command, e, err);
        }
        try {
            run.run(out);
        } catch (IOException | RuntimeException e) {
            return failed(command, e, err);
        }
        return written(out, err);
    }

    /**
     * The plan of {@code opentsx.twin.plan}, else the built-in plan; atoms, wires, policies and at most
     * {@code maxSteps} steps are checked. A refusal that does not say what is allowed names {@code example}, the
     * resource of the built-in plan.
     */
    static TwinPlan plan(OpenTsxConf conf, String example, Supplier<TwinPlan> builtIn, long maxSteps) {
        Optional<String> file = conf.getOption(PLAN);
        if (file.isEmpty()) {
            return builtIn.get();
        }
        Path path = Path.of(file.get());
        byte[] yaml = read(path, "plan file", Files::readAllBytes);
        try {
            TwinPlan plan = TwinPlan.load(new ByteArrayInputStream(yaml), maxSteps);
            new TwinRunner(plan);
            return plan;
        } catch (IOException e) {
            String problem = e.getCause() instanceof IllegalArgumentException rule ? rule.getMessage()
                    : e instanceof JsonProcessingException j ? j.getOriginalMessage() : e.getMessage();
            throw new IllegalArgumentException(planRefusal(path, problem, example), e);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(planRefusal(path, e.getMessage(), example), e);
        }
    }

    private static String planRefusal(Path path, String problem, String example) {
        String line = path + ": " + oneLine(problem);
        return line.contains("; expected ") ? line
                : line + "; expected a twin plan in YAML such as opentsx-twin/src/main/resources/" + example;
    }

    /**
     * Parses the CSV file a key names; a refusal of its content names the file. Stops reading at the first data row
     * over {@code maxRows}, a line after the header that is neither blank nor a comment; {@code parse} refuses that
     * row. A file of more than {@code maxBytes} bytes is refused once the reader passes them. A blank line reaches
     * {@code parse} as {@code ""} and a comment line as {@code "#"}, so that a file of short lines fits the heap.
     */
    static <T> T csv(Path path, String what, int maxRows, long maxBytes, Function<List<String>, T> parse) {
        long[] data = {-1};
        List<String> lines = read(path, what, p -> {
            List<String> head = new ArrayList<>();
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    new Bounded(Files.newInputStream(p), maxBytes), StandardCharsets.UTF_8.newDecoder()))) {
                String line;
                while (data[0] <= maxRows && (line = in.readLine()) != null) {
                    String l = line.strip();
                    if (l.isEmpty()) {
                        head.add("");
                    } else if (l.startsWith("#")) {
                        head.add("#");
                    } else {
                        head.add(line);
                        data[0]++;
                    }
                }
            } catch (TooLarge e) {
                throw new IllegalArgumentException(path + ": more than " + maxBytes + " bytes; expected a file of at most "
                        + maxBytes + " bytes", e);
            }
            return head;
        });
        T parsed = parsed(path, lines, parse);
        if (data[0] > maxRows) {
            throw new IllegalStateException(path + ": read up to data row " + data[0] + ", but the parser did not "
                    + "refuse more than " + maxRows + " data rows");
        }
        return parsed;
    }

    private static <T> T parsed(Path path, List<String> lines, Function<List<String>, T> parse) {
        try {
            return parse.apply(lines);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + ": " + oneLine(e.getMessage()), e);
        }
    }

    private interface Reader<T> {
        T read(Path path) throws IOException;
    }

    private static final class TooLarge extends IOException {
    }

    private static final class Bounded extends FilterInputStream {
        private final long max;
        private long count;

        Bounded(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) {
                counted(1);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(b, off, len);
            if (n > 0) {
                counted(n);
            }
            return n;
        }

        private void counted(int n) throws TooLarge {
            count += n;
            if (count > max) {
                throw new TooLarge();
            }
        }
    }

    private static <T> T read(Path path, String what, Reader<T> reader) {
        try {
            return reader.read(path);
        } catch (NoSuchFileException e) {
            throw new OpenTsxConfException(path.toString(), "no such file", "give an existing " + what);
        } catch (IOException e) {
            throw new UncheckedIOException(path + ": cannot read the " + what, e);
        }
    }

    private static int failed(String command, Exception e, PrintStream err) {
        LoggerFactory.getLogger(TwinCli.class).debug("stack trace of the failed {} run", command, e);
        err.print("opentsx: " + causes(e) + "\n");
        err.flush();
        return 1;
    }

    private static int written(PrintStream out, PrintStream err) {
        out.flush();
        if (out.checkError()) {
            err.print("opentsx: " + WRITE_FAILED + "\n");
            err.flush();
            return 1;
        }
        return 0;
    }

    /** The message and its causes on one line; a checked exception with its class, its message is often a bare path. */
    static String causes(Throwable t) {
        boolean bare = !(t instanceof RuntimeException) || t.getMessage() == null;
        StringBuilder s = new StringBuilder(bare ? t.toString() : t.getMessage());
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.add(t);
        for (Throwable c = t.getCause(); c != null && seen.add(c); c = c.getCause()) {
            s.append("; caused by ").append(c);
        }
        return oneLine(s.toString());
    }

    private static String oneLine(String s) {
        return String.valueOf(s).replaceAll("[\\s\\p{Cntrl}]+", " ").strip();
    }
}
