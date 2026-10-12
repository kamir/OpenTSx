# opentsx-app-tools: network utilities

> **Archived 2026-10-12** (not part of the build). See `archive/modules/README.md`.

Small stand-alone utilities. They are not specific to time series.

| Class | Purpose |
|-------|---------|
| `net.netcat.NetCat4JDataStreamRecorder` | record a TCP data stream to a file |
| `net.sshclient.SSHConsole`, `SSHClientTool`, `SSHCrawlServerClient` | SSH helpers |
| `net.clusterscan.ScanIPS`, `ClusterCheck` | scan a network range, check cluster hosts |

Run them with `java -cp "$(bin/opentsx classpath opentsx-app-tools)" <class>`.
