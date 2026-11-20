# Build the core tool image

This Dockerfile builds an image that runs the time series generator `org.opentsx.lg.TSDataSineWaveGenerator` of
`opentsx-lg` 3.0.1. No workflow builds or publishes the image.

The image holds:

* the base image `eclipse-temurin:21.0.8_9-jre-noble`, Ubuntu 24.04 with a Java 21 runtime;
* the packages `apt-utils`, `xvfb`, `x11vnc`, `x11-xkb-utils`, `xfonts-100dpi`, `xfonts-75dpi`, `xfonts-scalable`,
  `xfonts-cyrillic` and `x11-apps`;
* the directory `opentsx-lg/` of the build context at `/opentsx-lg/`.

The command is `java -jar /opentsx-lg/opentsx-lg-3.0.1.jar`. The image starts no X server.

## Build the image

You need Docker and the OpenTSx modules installed in your local Maven repository (`mvn -B -ntp install` in the
repository root). `<context>` is an empty directory that becomes the build context, `<image>` the image name you choose.

Build the jar with its dependencies, put it and the topic list into the build context, then build the image. Run the
commands in `opentsx-lg`:

```bash
mvn -B -ntp package -PSimpleTimeSeriesProducer -DskipTests
mkdir -p <context>/opentsx-lg/config
cp target/opentsx-lg-3.0.1.jar <context>/opentsx-lg/
cp src/main/resources/config/topiclist.def <context>/opentsx-lg/config/
docker build -f src/main/docker/core-tool-image/Dockerfile -t <image> <context>
```

## Run the image

The image holds no Kafka client profile. The generator reads it at run time from the file that
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` names. Mount a directory with `opentsx-local.props` at
`/etc/opentsx/config/`. `<config-dir>` is the absolute path of that directory:

```bash
docker run --rm -v <config-dir>:/etc/opentsx/config:ro <image>
```

The image sets these environment variables:

| Variable | Value | Read by |
|---|---|---|
| `OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` | `/etc/opentsx/config/opentsx-local.props` | the generator: the Kafka client profile |
| `OPENTSX_TOPIC_MAP_FILE_NAME` | `/opentsx-lg/config/topiclist.def` | the generator: the topic list |
| `OPENTSX_SHOW_GUI` | `false` | the generator: `false` opens no chart |
| `DISPLAY` | `:99` | Java AWT; no X server listens on it unless you start one |

Set `OPENTSX_USE_KAFKA=true` with `docker run --env` to send the series to Kafka. Without the profile file and without
its `.example` template, the generator stops with
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME: "/etc/opentsx/config/opentsx-local.props" cannot be read (/etc/opentsx/config/opentsx-local.props); give a readable Kafka client profile or its .example template`.
