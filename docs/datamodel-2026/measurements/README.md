# Measurement reports

Each folder holds the report of one measurement run (`REPORT.md`, some with a `README.md`) and its record
(`metadata.json`, and for the Rust runs `manifest.json` and `test-evidence.json`).

The raw files of a run (CSV samples, per-case JSON, resource traces) are not in this repository. The record lists the
SHA-256 of every raw file (`artifactsSha256` in `metadata.json`, or `manifest.json`), so a copy of the raw data can be
checked against it.

Check the Track A raw data against its record:

```bash
OPENTSX_TRACK_A_DATA=<folder with the raw files> python3 -m unittest discover -s tools -p 'test_wayang_track_a_results.py'
```

`<folder with the raw files>` contains the `federation/` and `streaming/` folders of the run
`wayang-track-a-evaluation-2026-11-18`. Without the variable the raw-data test is skipped and the public record is
still checked.
