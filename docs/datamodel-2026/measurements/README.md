# Measurement reports

Each folder holds the report of one measurement run (`REPORT.md`, some with a `README.md`) and its record
(`metadata.json`, and for the Rust runs `manifest.json` and `test-evidence.json`).

The raw files of a run (CSV samples, per-case JSON, resource traces) are not in this repository. The records of
`rust-b4-mixed-20261120`, `wayang-federation-2026-11-17` and `wayang-track-a-evaluation-2026-11-18` list the SHA-256
of every raw file (`manifest.json`, or `artifactsSha256` in `metadata.json`), so a copy of the raw data can be checked
against it. The record of `wayang-increment-latency-2026-11-17` lists no raw-file hashes.

Check the Track A raw data against its record:

```bash
OPENTSX_TRACK_A_DATA=<folder with the raw files> python3 -m unittest discover -s tools -p 'test_wayang_track_a_results.py'
```

`<folder with the raw files>` contains the `federation/` and `streaming/` folders of the run
`wayang-track-a-evaluation-2026-11-18`. Without the variable the raw-data test is skipped and the public record is
still checked.
