"""The opentsx-lg scripts take ids and passwords from the caller, and the module README names scripts that exist.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
LG = ROOT / "opentsx-lg"
BOOTSTRAP = LG / "bin" / "bootstrap_LatencyProbe_CHECK.sh"
GRAFANA = LG / "src" / "main" / "mqtt-example" / "influxdb-grafana" / "__run_container_01.sh"
CLOUD_IDS = {"CCLOUDENV": "env-" + "0test1", "CLUSTER": "lkc-" + "0test1", "SERVICE_ACCOUNT": "sa-" + "0test1"}
CLOUD_KINDS = {"CCLOUDENV": "environment", "CLUSTER": "cluster", "SERVICE_ACCOUNT": "service account"}
GRAFANA_CONTAINER = "docker-influxdb-grafana"


def run(script: pathlib.Path, tmp_path: pathlib.Path, tools: tuple[str, ...], fail: str = "", **variables: str):
    fake = tmp_path / "fake"
    fake.mkdir()
    calls = tmp_path / "calls"
    calls.touch()
    for tool in tools:
        (fake / tool).write_text(f'#!/bin/bash\necho "{tool} $*" >> "{calls}"\n'
                                 f'[[ -n "{fail}" && "{tool} $*" == "{fail}"* ]] && exit 1\nexit 0\n',
                                 encoding="utf-8")
        (fake / tool).chmod(0o755)
    (fake / "env.sh").write_text(f'echo "env.sh" >> "{calls}"\n', encoding="utf-8")
    environment = {k: v for k, v in os.environ.items()
                   if k not in CLOUD_IDS and k != "GRAFANA_ADMIN_PASSWORD"}
    environment.update(variables, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}")
    result = subprocess.run(["bash", str(script)], cwd=fake, capture_output=True, text=True, env=environment,
                            check=False)
    return result, calls.read_text(encoding="utf-8").splitlines()


def missing(variable: str) -> str:
    return (f"{variable}: missing; export the id of your Confluent Cloud {CLOUD_KINDS[variable]} "
            "before you run the script")


def test_the_bootstrap_refuses_to_run_without_the_cloud_ids(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr.splitlines() == [missing(v) for v in CLOUD_IDS]
    assert not [c for c in calls if c.startswith("ccloud")]


def test_the_bootstrap_counts_an_empty_id_as_missing(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",), **dict(CLOUD_IDS, CLUSTER=""))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr.splitlines() == [missing("CLUSTER")]
    assert not [c for c in calls if c.startswith("ccloud")]


def test_the_bootstrap_uses_the_given_cloud_ids(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",), **CLOUD_IDS)
    assert result.returncode == 0, result.stdout + result.stderr
    ccloud = [c for c in calls if c.startswith("ccloud")]
    assert ccloud[:2] == [f"ccloud environment use {CLOUD_IDS['CCLOUDENV']}",
                          f"ccloud kafka cluster use {CLOUD_IDS['CLUSTER']}"]
    acls = ccloud[2:]
    assert len(acls) == 6
    assert all(f"--service-account {CLOUD_IDS['SERVICE_ACCOUNT']} " in c for c in acls)


def test_the_bootstrap_stops_when_the_environment_cannot_be_selected(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",), fail="ccloud environment use", **CLOUD_IDS)
    assert result.returncode == 1, result.stdout + result.stderr
    assert [c for c in calls if c.startswith("ccloud")] == [f"ccloud environment use {CLOUD_IDS['CCLOUDENV']}"]


def test_the_bootstrap_stops_when_the_cluster_cannot_be_selected(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",), fail="ccloud kafka cluster use", **CLOUD_IDS)
    assert result.returncode == 1, result.stdout + result.stderr
    assert not [c for c in calls if c.startswith("ccloud kafka acl")]


def test_the_bootstrap_stops_at_the_first_acl_that_fails(tmp_path):
    result, calls = run(BOOTSTRAP, tmp_path, ("ccloud",), fail="ccloud kafka acl create", **CLOUD_IDS)
    assert result.returncode == 1, result.stdout + result.stderr
    assert len([c for c in calls if c.startswith("ccloud kafka acl")]) == 1


def test_the_grafana_script_refuses_to_run_without_the_admin_password(tmp_path):
    result, calls = run(GRAFANA, tmp_path, ("docker", "grafana-cli"))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr.splitlines() == [
        "GRAFANA_ADMIN_PASSWORD: missing; export the Grafana admin password to set before you run the script"]
    assert calls == []


def test_the_grafana_script_resets_the_password_inside_the_named_container(tmp_path):
    result, calls = run(GRAFANA, tmp_path, ("docker", "grafana-cli"), GRAFANA_ADMIN_PASSWORD="test-password")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls[0].startswith(f"docker run -d --name {GRAFANA_CONTAINER} ")
    assert [c for c in calls if c.startswith("docker exec")] == [
        f"docker exec {GRAFANA_CONTAINER} grafana-cli admin reset-admin-password test-password"]
    assert not [c for c in calls if c.startswith("grafana-cli")]


def test_the_readme_names_only_scripts_that_exist():
    text = (LG / "README.md").read_text(encoding="utf-8")
    names = {name.removeprefix("./") for name in re.findall(r"[\w./-]+\.sh\b", text)}
    scripts = [p.relative_to(LG).as_posix() for p in LG.rglob("*.sh") if "target" not in p.parts]
    dangling = sorted(n for n in names if not any(s == n or s.endswith("/" + n) for s in scripts))
    assert names
    assert not dangling, dangling
