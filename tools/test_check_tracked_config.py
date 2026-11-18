"""Tests for tools/check_tracked_config.py.

Run from the repository root: python -m pytest -q tools
"""
import pathlib
import subprocess
import sys

import check_tracked_config as guard

CLOUD_HOST = "pkc-" + "abc12.eu-west-1.aws.confluent" + ".cloud"
CLOUDERA_HOST = "cc-host-1.gce.cloudera" + ".com"
LOCAL_PROFILE = (
    "bootstrap.servers=localhost:9092\n"
    "schema.registry.url=http://localhost:8081\n"
    "key.serializer=org.apache.kafka.common.serialization.StringSerializer\n"
)


def write(root: pathlib.Path, files: dict[str, str]) -> list[str]:
    for path, text in files.items():
        (root / path).parent.mkdir(parents=True, exist_ok=True)
        (root / path).write_text(text, encoding="utf-8")
    return list(files)


def test_a_cloud_host_in_a_profile_fails(tmp_path, capsys):
    paths = write(tmp_path, {"opentsx-lg/config/ccloud.props": f"bootstrap.servers={CLOUD_HOST}:9092\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert f'opentsx-lg/config/ccloud.props:1: bootstrap.servers: "{CLOUD_HOST}"' in out


def test_a_private_address_in_an_env_file_fails(tmp_path, capsys):
    paths = write(tmp_path, {"ping/.env": "MDC1=192.168.0.9\nENV_CONTEXT=/etc/opentsx/config/cuh.props\n",
                             "ping/.env-inhouse": "export MDC2=10.1.2.3\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert 'ping/.env:1: MDC1: "192.168.0.9"' in out
    assert 'ping/.env-inhouse:1: MDC2: "10.1.2.3"' in out
    assert "ENV_CONTEXT" not in out


def test_a_personal_host_name_in_a_url_fails(tmp_path, capsys):
    paths = write(tmp_path, {"a/cpl.props": "schema.registry.url=http://somebodys-laptop:8081\n"})
    assert guard.main(tmp_path, paths) == 1
    assert 'schema.registry.url: "somebodys-laptop"' in capsys.readouterr().out


def test_localhost_and_placeholders_pass(tmp_path, capsys):
    paths = write(tmp_path, {
        "config/opentsx-local.props.example": LOCAL_PROFILE,
        "a/cuh.props": "bootstrap.servers=<bootstrap-host>:9092,127.0.0.1:9093\n"
                       "schema.registry.url=https://<schema-registry-host>\n",
        "a/.env": "MDC1=<mdc1-address>\nMDC2=${MDC2}\nBIND=0.0.0.0\n",
        "a/.env.example": "VITE_API_BASE_URL=http://localhost:8000\nSERVICE_HOST=broker.example.com\n",
        "nbproject/genfiles.properties": "build-impl.xml.stylesheet.CRC32=99ea4b56@1.68.1.1\n",
        "docs/guide.md": "bootstrap.servers=pkc-xxxxx.us-east-1.aws.confluent.cloud:9092\n",
    })
    assert guard.main(tmp_path, paths) == 0, capsys.readouterr().out


def test_ide_state_and_private_configuration_fail_even_when_not_on_disk(tmp_path, capsys):
    paths = [".idea/workspace.xml", "archive/demo/.idea/misc.xml", "config/private/ccloud.props"]
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    for path in paths:
        assert f"{path}: " in out
    assert "checked 3 tracked files, 3 findings" in out


def test_a_real_host_in_any_tracked_text_file_fails(tmp_path, capsys):
    paths = write(tmp_path, {"bin/010_build.sh": f"#scp app.jar root@{CLOUDERA_HOST}:/opt/app.jar\n",
                             "src/Producer.java": f'// String BOOTSTRAP = "{CLOUD_HOST}:9092";\n'})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert f'bin/010_build.sh:1: "{CLOUDERA_HOST}" is a real host' in out
    assert f'src/Producer.java:1: "{CLOUD_HOST}" is a real host' in out


def test_the_guard_reads_the_git_listing(tmp_path):
    subprocess.run(["git", "init", "-q", str(tmp_path)], check=True)
    write(tmp_path, {"config/cpl.props": "bootstrap.servers=192.168.3.5:9092\n", "config/ok.props": LOCAL_PROFILE,
                     "untracked.props": "bootstrap.servers=192.168.3.6:9092\n"})
    subprocess.run(["git", "-C", str(tmp_path), "add", "config"], check=True)
    script = pathlib.Path(guard.__file__)
    result = subprocess.run([sys.executable, str(script), str(tmp_path)], capture_output=True, text=True, check=False)
    assert result.returncode == 1, result.stdout + result.stderr
    assert 'config/cpl.props:1: bootstrap.servers: "192.168.3.5"' in result.stdout
    assert "untracked.props" not in result.stdout
    assert "checked 2 tracked files, 1 findings" in result.stdout
