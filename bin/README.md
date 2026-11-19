# Scripts

| Script | Purpose |
|--------|---------|
| `opentsx` | **Launcher for the current tools.** `bin/opentsx --help` lists the commands; [user manual, appendix A](../docs/user-manual/appendix-a-cli.md) lists every command with its options and the differences to the container image; see also [below](#fix-a-failed-start-of-binopentsx). |
| `000_validate_environment.sh` | checks the JDK (17+), Maven, memory, ports and Docker |
| `010_build.sh` | builds all modules (`mvn install`) |
| `build_docs_pdfs.sh`, `Dockerfile.docs-pdf` | PDF of the user manual and dev guide (used by `mvn -Ppdf-manual`) |
| `005_minimal_no_kafka_demo.sh` | 2.x sine-wave generator without Kafka; writes CSV files to `data/temp/` |
| `000_launch_tsa_workbench.sh` | 2.x GUI (MacroRecorder2) through `mvn -f opentsx-core/pom.xml exec:java`, or without Maven with the classpath that `bin/opentsx` cached |
| `120_run_demo.sh` | 2.x GUI (MacroRecorder2) through `mvn -f opentsx-core/pom.xml exec:java`; `--help` prints the usage |
| `episode_02/03/09/10_*.sh` | 2.x guided demos (`org.opentsx.demo.onboarding.*`), used with `exercises/` |
| `run_all_tests.sh` | runs Maven exercise regressions; full mode also checks demo syntax and required docs |

Tests for the current modules run with `mvn verify` and `pytest` (see the [user manual, chapter 12](../docs/user-manual/12-operations.md)).
Obsolete scripts (Kudu, Spark 2, Cloudera deployment) are in `archive/bin/`.

## Fix a failed start of bin/opentsx

`bin/opentsx` resolves the runtime classpath of a module with Maven and caches it in
`<module>/target/classpath-runtime.txt`. It resolves again when the `pom.xml` of the module or of the repository root
is newer than the cache, or when an OpenTSx jar on the cached classpath is newer, which happens when you install a
sibling module again (`mvn -q -pl <sibling> install`). `stats-flink` also caches the provided
scope (Flink and its log4j2 binding) in `opentsx-flink-core/target/classpath-provided.txt`. The launcher adds
`conf/` and, when the classpath holds slf4j-api but no slf4j-simple, the slf4j-simple of the same version, so log
lines on stderr follow `conf/simplelogger.properties`; `stats-flink` uses `conf/log4j2-stats-flink.properties`
instead. `bin/opentsx classpath <module>` prints the same classpath.

When the classpath step fails, the launcher prints one of these lines on stderr and exits with 1 before Java starts:

| Message | Fix |
|---------|-----|
| `<module>: module is not built; run: mvn -q install -DskipTests` | build the modules |
| `mvn: not found on PATH; install Maven 3.9 or later` | install Maven and put `mvn` on `PATH` |
| Maven's `[ERROR]` lines, then `<module>: Maven could not resolve the classpath; see the Maven output above` | fix what Maven reports; a sibling module that is not installed needs `mvn -q install -DskipTests` |
| `slf4j-simple: <jar> is missing; run: mvn -q dependency:get -Dartifact=org.slf4j:slf4j-simple:<version>` | run the command it names |
