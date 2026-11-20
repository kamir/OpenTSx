"""Tests for tools/check_tracked_config.py.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import subprocess
import sys

import check_tracked_config as guard

CLOUD_HOST = "pkc-" + "abc12.eu-west-1.aws.confluent" + ".cloud"
CLOUDERA_HOST = "cc-host-1.gce.cloudera" + ".com"
PERSON = "somebody"
MAC_HOMES = "/" + "Users"
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
    paths = write(tmp_path, {"opentsx-lg/config/cluster.props": f"bootstrap.servers={CLOUD_HOST}:9092\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert f'opentsx-lg/config/cluster.props:1: bootstrap.servers: "{CLOUD_HOST}"' in out
    assert f'opentsx-lg/config/cluster.props:1: "{CLOUD_HOST}" is a real host' in out
    assert "checked 1 tracked files, 2 findings" in out


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


def test_a_tracked_client_profile_or_mqtt_password_file_fails_even_when_not_on_disk(tmp_path):
    profile = "opentsx-lg/src/main/docker-compose/config/ccloud.props"
    password = "opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd"
    assert guard.check(tmp_path, [profile, password]) == [
        f"{profile}: a Kafka client profile with credentials is tracked; untrack it, ccloud.props is git-ignored",
        f"{password}: the MQTT password file is tracked; untrack it, 01_bootstrap.sh creates it",
    ]


def test_a_tracked_client_profile_or_mqtt_password_file_on_disk_fails_once(tmp_path):
    paths = write(tmp_path, {"opentsx-lg/config/ccloud.props": f"bootstrap.servers={CLOUD_HOST}:9092\n",
                             "opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd": "demo1:hash\n",
                             "opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/mosquitto.conf":
                                 "password_file /mqtt/config/passwd\n"})
    assert guard.check(tmp_path, paths) == [
        "opentsx-lg/config/ccloud.props: a Kafka client profile with credentials is tracked; untrack it, "
        "ccloud.props is git-ignored",
        "opentsx-lg/src/main/mqtt-example/volume1/docker/mqtt/config/passwd: the MQTT password file is tracked; "
        "untrack it, 01_bootstrap.sh creates it",
    ]


def test_a_real_host_in_any_tracked_text_file_fails(tmp_path, capsys):
    paths = write(tmp_path, {"bin/010_build.sh": f"#scp app.jar root@{CLOUDERA_HOST}:/opt/app.jar\n",
                             "src/Producer.java": f'// String BOOTSTRAP = "{CLOUD_HOST}:9092";\n'})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert f'bin/010_build.sh:1: "{CLOUDERA_HOST}" is a real host' in out
    assert f'src/Producer.java:1: "{CLOUD_HOST}" is a real host' in out


def git_environment() -> dict[str, str]:
    return {k: v for k, v in os.environ.items() if not k.startswith("GIT_")}


def test_the_guard_reads_the_git_listing(tmp_path, monkeypatch):
    other, repository = tmp_path / "other", tmp_path / "repository"
    subprocess.run(["git", "init", "-q", str(other)], check=True, env=git_environment())
    write(other, {"other.props": LOCAL_PROFILE})
    subprocess.run(["git", "-C", str(other), "add", "other.props"], check=True, env=git_environment())
    monkeypatch.setenv("GIT_INDEX_FILE", str(other / ".git" / "index"))
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    write(repository, {"config/cpl.props": "bootstrap.servers=192.168.3.5:9092\n", "config/ok.props": LOCAL_PROFILE,
                       "untracked.props": "bootstrap.servers=192.168.3.6:9092\n"})
    subprocess.run(["git", "-C", str(repository), "add", "config"], check=True, env=git_environment())
    script = pathlib.Path(guard.__file__)
    result = subprocess.run([sys.executable, str(script), str(repository)], capture_output=True, text=True,
                            env=git_environment(), check=False)
    assert result.returncode == 1, result.stdout + result.stderr
    assert 'config/cpl.props:1: bootstrap.servers: "192.168.3.5"' in result.stdout
    assert "untracked.props" not in result.stdout
    assert "checked 2 tracked files, 1 findings" in result.stdout


def test_measurement_output_of_the_load_generator_fails_even_when_not_on_disk(tmp_path, capsys):
    paths = ["opentsx-lg/src/main/docker-compose/output/latency-by-message-1602860282194"]
    assert guard.main(tmp_path, paths) == 1
    assert ("opentsx-lg/src/main/docker-compose/output/latency-by-message-1602860282194: measurement output is "
            "tracked; untrack it, opentsx-lg/src/main/docker-compose/output/ is git-ignored") in capsys.readouterr().out


def test_latency_plots_of_the_load_generator_fail_even_when_not_on_disk(tmp_path, capsys):
    paths = ["opentsx-lg/opentsx/plots/latency-by-message-1615480579887.mwt.data",
             "opentsx-lg/opentsx/plots/latency-by-message-1615480579887.png"]
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    for path in paths:
        assert (f"{path}: measurement output is tracked; untrack it, opentsx-lg/opentsx/plots/ is git-ignored") in out


def test_a_personal_home_path_in_any_tracked_text_file_fails(tmp_path, capsys):
    paths = write(tmp_path, {"bin/demo.sh": f"export CONFLUENT_HOME={MAC_HOMES}/{PERSON}/bin/confluent-7.3.0\n",
                             "nb/project.xml": f"<definitions>{MAC_HOMES}/Shared/home/{PERSON}/nb.properties</definitions>\n",
                             "notes.md": f"cd /home/{PERSON}.else/OpenTSx\n",
                             "run.sh": f"cd /home/{PERSON}\nX=\"{MAC_HOMES}/{PERSON}\" # `/home/{PERSON}`\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    rest = "is a personal home path; use $HOME or a placeholder such as <home>"
    assert f'bin/demo.sh:1: "{MAC_HOMES}/{PERSON}" {rest}' in out
    assert f'nb/project.xml:1: "/home/{PERSON}" {rest}' in out
    assert f'notes.md:1: "/home/{PERSON}.else" {rest}' in out
    assert f'run.sh:1: "/home/{PERSON}" {rest}' in out
    assert f'run.sh:2: "{MAC_HOMES}/{PERSON}" {rest}' in out
    assert f'run.sh:2: "/home/{PERSON}" {rest}' in out
    assert f"{MAC_HOMES}/Shared" not in out
    assert "checked 4 tracked files, 6 findings" in out


def test_home_placeholders_and_service_account_homes_pass(tmp_path, capsys):
    paths = write(tmp_path, {
        "bin/demo.sh": f"export CONFLUENT_HOME=$HOME/bin/confluent-7.3.0\ncd {MAC_HOMES}/<name>/OpenTSx\nls /home/${{USER}}/\n",
        "deploy/notebook.yaml": "mountPath: /home/jovyan/opentsx\n",
        "archive/update.sh": "scp app.jar ubuntu@localhost:/home/ubuntu/\nls /home/ec2-user/.cache/\n",
        "HOW_TO_RUN.md": "cd /home/user/OpenTSx\n",
    })
    assert guard.main(tmp_path, paths) == 0, capsys.readouterr().out


def test_a_confluent_cloud_resource_id_in_any_tracked_text_file_fails(tmp_path, capsys):
    cluster = "lkc-" + "0test1"
    paths = write(tmp_path, {"opentsx-lg/bin/bootstrap.sh": f"export CLUSTER={cluster}\n",
                             "notes.md": f"cluster {'pkc-' + '0test1'}, account {'sa-' + '0test1x'} and env-0te\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    rest = "looks like a Confluent Cloud resource id; write a placeholder such as lkc-xxxxx"
    assert f"opentsx-lg/bin/bootstrap.sh:1: {cluster}: {rest}" in out
    assert f"notes.md:1: {'pkc-' + '0test1'}: {rest}" in out
    assert f"notes.md:1: {'sa-' + '0test1x'}: {rest}" in out
    assert "env-0te:" not in out
    assert "checked 2 tracked files, 3 findings" in out


def test_resource_id_placeholders_and_words_with_a_prefix_pass(tmp_path, capsys):
    paths = write(tmp_path, {
        "docs/guide.md": "CLUSTER=lkc-xxxxx\nbootstrap.servers=pkc-xxxxx.us-east-1.aws.confluent.cloud:9092\n",
        "docs/datamodel-2026/testvectors/conf.v1.json": '{"name": "env-blank-is-unset"}\n',
        "deploy/region.yaml": "region: sa-east-1\nname: my-env-a1b2c3d\nkey: SA-ABC123\n",
        "deploy/labels.yaml": "tag: lkc-ab12cXYZ\nuser: sa-ab12cd_suffix\n",
    })
    assert guard.main(tmp_path, paths) == 0, capsys.readouterr().out
