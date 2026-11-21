# Run the latency producer

This compose file starts the latency producer of the ping tool in one container. Verified on 3.0.1 with
`docker compose config`.

You need the image `opentsx/time-series-generator:3.0.1`, built with the `Docker` profile as step (2) of
[`opentsx-lg/README.md`](../../../../README.md) describes, and Docker Compose.

## What it starts

`docker-compose.yml` starts one service, `lg-west-PROD`, from the image `opentsx/time-series-generator:3.0.1`. Its
entrypoint `/opentsx-lg/bin/run_LatencyProbe_PROD.sh` runs `org.opentsx.lg.kping.EventFlowAnalysisProducer`.
The producer checks the topics of `topiclist.def`, creates the missing ones, and sends one sine-wave series per round
as events to the topic `OpenTSx_Events`. The number of rounds is `kping.producer.ziterations` of the client profile.

The container mounts:

* `../config/` at `/etc/opentsx/config/`: the client profiles and `topiclist.def`
* `../bin/` at `/opentsx-lg/bin/`: the entrypoint scripts

The compose file sets `OPENTSX_TOPIC_MAP_FILE_NAME` to `/etc/opentsx/config/topiclist.def`,
`OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME` to the value of `ENV_CONTEXT` and `OPENTSX_SHOW_GUI` to `false`, so
the producer opens no chart window. It maps these host names to the addresses in `MDC1`, `MDC2` and `MDC3`:

| Variable | Host names |
|----------|------------|
| `MDC1` | `broker-west-5`, `broker-west-6`, `broker-west-7` |
| `MDC2` | `broker-west-1`, `broker-west-2`, `broker-east-3`, `broker-east-4`, `zookeeper-central`, `zookeeper-east`, `zookeeper-west`, `schema-registry-west-1` |
| `MDC3` | `broker-east-8`, `broker-east-9`, `broker-east-10` |

## Choose the env file

Docker Compose reads `.env` next to the compose file. With `--env-file .env-inhouse` it reads `.env-inhouse`
instead. That file sets `MDC1`, `MDC2`, `MDC3` and `ENV_CONTEXT`, the variables the compose file reads.
`ENV_CONTEXT` names the client profile: `.env` names `/etc/opentsx/config/opentsx-local.props`, `.env-inhouse` names
`/etc/opentsx/config/cpl.props`.

Replace the placeholders `<mdc1-address>`, `<mdc2-address>` and `<mdc3-address>` in that file with the addresses of
your three clusters. Then check what Docker Compose starts, from this folder:

```bash
docker compose --env-file .env-inhouse config
```

`opentsx-lg/run_demo_docker_compose.sh` starts this service and the one of `../ping-tool-cons/` with
`docker compose --env-file .env-inhouse up -d`, the Compose plugin of Docker.
