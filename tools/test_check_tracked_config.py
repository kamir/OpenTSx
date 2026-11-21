"""Tests for tools/check_tracked_config.py.

Run from the repository root: python -m pytest -q tools
"""
import errno
import os
import pathlib
import random
import re
import shutil
import subprocess
import sys

import pytest

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


def non_utf8_name(parent: pathlib.Path, raw: bytes) -> str:
    """Skips the test where the file system refuses names that are not valid UTF-8, as APFS does, or renames them, as
    HFS+ does."""
    name = os.fsdecode(raw)
    try:
        (parent / name).mkdir()
    except OSError as error:
        if error.errno != errno.EILSEQ:
            raise
        pytest.skip(f"the file system refuses names that are not valid UTF-8 ({error.strerror})")
    kept = raw in os.listdir(os.fsencode(parent))
    (parent / name).rmdir()
    if not kept:
        pytest.skip("the file system renames names that are not valid UTF-8")
    return name


def test_a_cloud_host_in_a_profile_fails(tmp_path, capsys):
    paths = write(tmp_path, {"opentsx-lg/config/cluster.props": f"bootstrap.servers={CLOUD_HOST}:9092\n"})
    assert guard.main(tmp_path, paths) == 1
    out = capsys.readouterr().out
    assert f'opentsx-lg/config/cluster.props:1: bootstrap.servers: "{CLOUD_HOST}"' in out
    assert f'opentsx-lg/config/cluster.props:1: "{CLOUD_HOST}" is a real host' in out
    assert "checked 1 tracked files, 2 findings" in out


def test_a_private_address_in_an_env_file_fails(tmp_path, capsys):
    paths = write(tmp_path, {"ping/.env": "MDC1=192.168.0.9\nENV_CONTEXT=/etc/opentsx/config/client.props\n",
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
        "a/client.props": "bootstrap.servers=<bootstrap-host>:9092,127.0.0.1:9093\n"
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
    return {**{k: v for k, v in os.environ.items() if not k.startswith("GIT_")}, "LC_ALL": "C"}


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
                             "nb/project.xml":
                                 f"<definitions>{MAC_HOMES}/Shared/home/{PERSON}/nb.properties</definitions>\n",
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
        "bin/demo.sh":
            f"export CONFLUENT_HOME=$HOME/bin/confluent-7.3.0\ncd {MAC_HOMES}/<name>/OpenTSx\nls /home/${{USER}}/\n",
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


def gitlink_repository(root: pathlib.Path, modules: str | None) -> pathlib.Path:
    subprocess.run(["git", "init", "-q", str(root)], check=True, env=git_environment())
    subprocess.run(["git", "-C", str(root), "update-index", "--add", "--cacheinfo", f"160000,{'1' * 40},dir/sub"],
                   check=True, env=git_environment())
    if modules is not None:
        write(root, {".gitmodules": modules})
        subprocess.run(["git", "-C", str(root), "add", ".gitmodules"], check=True, env=git_environment())
    return root


def run_guard(root: pathlib.Path) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, str(pathlib.Path(guard.__file__)), str(root)], capture_output=True,
                          text=True, env=git_environment(), check=False)


def test_a_gitlink_without_a_submodule_entry_fails(tmp_path):
    other = '[submodule "other"]\n\tpath = dir/other\n\turl = https://example.com/other.git\n'
    for name, modules, files in (("bare", None, 1), ("other", other, 2)):
        result = run_guard(gitlink_repository(tmp_path / name, modules))
        assert result.returncode == 1, result.stdout + result.stderr
        assert result.stdout.splitlines() == [
            "dir/sub: gitlink without a .gitmodules entry; at the top level of the repository, add one with git config "
            "-f .gitmodules submodule.dir/sub.path dir/sub, git config -f .gitmodules submodule.dir/sub.url <url> and "
            "git add .gitmodules, or remove it with git rm --cached dir/sub",
            f"checked {files} tracked files, 1 findings"]


def git(root: pathlib.Path, *arguments: str) -> str:
    return subprocess.run(["git", "-C", str(root), "-c", "user.name=test", "-c", "user.email=test@example.com", "-c",
                           "commit.gpgsign=false", "-c", "protocol.file.allow=always", *arguments],
                          capture_output=True, text=True, check=True, env=git_environment()).stdout


def test_the_suggested_submodule_entry_passes_the_guard_in_a_clone_and_keeps_the_pinned_commit(tmp_path):
    source, origin, clone = tmp_path / "source", tmp_path / "origin", tmp_path / "clone"
    git(tmp_path, "init", "-q", str(source))
    git(source, "commit", "-q", "--allow-empty", "-m", "pinned")
    pinned = git(source, "rev-parse", "HEAD").strip()
    git(source, "commit", "-q", "--allow-empty", "-m", "later")
    git(tmp_path, "init", "-q", str(origin))
    git(origin, "update-index", "--add", "--cacheinfo", f"160000,{pinned},dir/sub")
    git(origin, "commit", "-q", "-m", "stray gitlink")
    git(tmp_path, "clone", "-q", str(origin), str(clone))
    assert (clone / "dir" / "sub").is_dir()
    advice = run_guard(clone).stdout.splitlines()[0]
    commands = advice.partition("add one with ")[2].partition(", or remove it with ")[0]
    assert commands == ("git config -f .gitmodules submodule.dir/sub.path dir/sub, "
                        "git config -f .gitmodules submodule.dir/sub.url <url> and git add .gitmodules")
    for command in commands.replace(" and ", ", ").split(", "):
        git(clone, *[str(source) if word == "<url>" else word for word in command.split()[1:]])
    result = run_guard(clone)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines() == ["checked 2 tracked files, 0 findings"]
    assert git(clone, "ls-files", "-s", "dir/sub") == f"160000 {pinned} 0\tdir/sub\n"
    git(clone, "submodule", "update", "--init")
    assert git(clone / "dir" / "sub", "rev-parse", "HEAD").strip() == pinned


def test_a_gitmodules_file_that_git_cannot_read_is_one_finding_and_the_other_checks_run(tmp_path):
    conflict = '<<<<<<< HEAD\n[submodule "sub"]\n\tpath = dir/sub\n=======\n>>>>>>> other\n'
    repository = gitlink_repository(tmp_path / "repository", conflict)
    write(repository, {"config/cpl.props": "bootstrap.servers=192.168.3.5:9092\n"})
    subprocess.run(["git", "-C", str(repository), "add", "config"], check=True, env=git_environment())
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ""
    assert result.stdout.splitlines() == [
        ".gitmodules: git cannot read it (fatal: bad config line 1 in file .gitmodules); fix the file, for example "
        "resolve its conflict markers",
        'config/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder',
        "checked 3 tracked files, 2 findings"]


def test_a_gitlink_with_its_submodule_entry_passes(tmp_path):
    modules = '[submodule "sub"]\n\tpath = dir/sub\n\turl = https://example.com/sub.git\n'
    result = run_guard(gitlink_repository(tmp_path / "repository", modules))
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines() == ["checked 2 tracked files, 0 findings"]


def test_a_root_below_the_top_level_reads_the_top_level_submodule_entries(tmp_path):
    modules = '[submodule "sub"]\n\tpath = dir/sub\n\turl = https://example.com/sub.git\n'
    repository = gitlink_repository(tmp_path / "repository", modules)
    subprocess.run(["git", "-C", str(repository), "update-index", "--add", "--cacheinfo",
                    f"160000,{'2' * 40},dir/stray"], check=True, env=git_environment())
    (repository / "dir").mkdir()
    result = run_guard(repository / "dir")
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout.splitlines() == [
        "stray: gitlink without a .gitmodules entry; at the top level of the repository, add one with git config -f "
        ".gitmodules submodule.dir/stray.path dir/stray, git config -f .gitmodules submodule.dir/stray.url <url> and "
        "git add .gitmodules, or remove it with git rm --cached dir/stray",
        "checked 2 tracked files, 1 findings"]


def test_the_advice_for_a_root_below_the_top_level_names_the_top_level_and_works_there(tmp_path):
    for fix in ("add", "remove"):
        repository = gitlink_repository(tmp_path / fix, None)
        below = repository / "dir"
        below.mkdir()
        guard_below = [sys.executable, str(pathlib.Path(guard.__file__))]
        result = subprocess.run(guard_below, cwd=below, capture_output=True, text=True, env=git_environment(),
                                check=False)
        assert result.returncode == 1, result.stdout + result.stderr
        advice = result.stdout.splitlines()[0]
        assert result.stdout.splitlines() == [
            "sub: gitlink without a .gitmodules entry; at the top level of the repository, add one with git config -f "
            ".gitmodules submodule.dir/sub.path dir/sub, git config -f .gitmodules submodule.dir/sub.url <url> and "
            "git add .gitmodules, or remove it with git rm --cached dir/sub",
            "checked 1 tracked files, 1 findings"]
        added, _, removed = advice.partition("add one with ")[2].partition(", or remove it with ")
        commands = added.replace(" and ", ", ").split(", ") if fix == "add" else [removed]
        for command in commands:
            git(repository, *["https://example.com/sub.git" if word == "<url>" else word
                              for word in command.split()[1:]])
        result = subprocess.run(guard_below, cwd=below, capture_output=True, text=True, env=git_environment(),
                                check=False)
        assert result.returncode == 0, result.stdout + result.stderr
        assert result.stdout.splitlines() == [f"checked {1 if fix == 'add' else 0} tracked files, 0 findings"]


def test_the_gitlinks_of_the_repository_have_their_submodule_entry():
    root = pathlib.Path(guard.__file__).resolve().parent.parent
    assert "external/wayang" in guard.gitlinks(root)
    assert guard.gitlink_findings(root) == []


DENIED = "a word of the private deny list; remove it or write a placeholder"
DENIED_PATH = "a word of the private deny list in the path; rename the file"


def test_a_word_of_the_private_deny_list_in_a_tracked_line_or_path_fails(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "# zzqcomment\n\nzzqword\n   \nZZQOTHER\n"})
    paths = write(tmp_path, {
        "docs/notes.md": "first line\nthe ZzqWord tool, zzqword again and zzqword-x\n- zzqcomment -\nand zzqother\n",
        "deploy/ZZQWORD/run.sh": "echo zzqwordy xzzqword zzqword7 zzqwords\n",
        "zzqwordy/a.txt": "ok\n",
    })
    assert guard.check(tmp_path, paths + ["config/zzqword_client.props"]) == [
        f"docs/notes.md:2: zzqword: {DENIED}",
        f"docs/notes.md:4: ZZQOTHER: {DENIED}",
        f"deploy/ZZQWORD/run.sh: zzqword: {DENIED_PATH}",
        f"config/zzqword_client.props: zzqword: {DENIED_PATH}",
    ]


def test_without_a_deny_list_its_words_pass(tmp_path, capsys):
    paths = write(tmp_path, {"docs/notes.md": "the zzqword tool\n", "zzqword/run.sh": "echo zzqword\n"})
    assert guard.main(tmp_path, paths) == 0, capsys.readouterr().out


def test_a_deny_list_that_cannot_be_read_is_one_finding_and_the_other_checks_run(tmp_path):
    deny_list = tmp_path / "config" / "private" / "denylist.txt"
    deny_list.mkdir(parents=True)
    paths = write(tmp_path, {"a/cpl.props": "bootstrap.servers=192.168.3.5:9092\n", "docs/notes.md": "zzqword\n"})
    assert guard.check(tmp_path, paths) == [
        "config/private/denylist.txt: cannot read it (Is a directory); fix the file or remove it",
        'a/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder']
    deny_list.rmdir()
    deny_list.write_bytes(b"zzqword\n\xff\n")
    assert guard.check(tmp_path, paths) == [
        "config/private/denylist.txt: cannot read it ('utf-8' codec can't decode byte 0xff in position 8: invalid "
        "start byte); fix the file or remove it",
        'a/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder']
    deny_list.unlink()
    deny_list.symlink_to(tmp_path / "missing.txt")
    assert guard.check(tmp_path, paths) == [
        "config/private/denylist.txt: cannot read it (No such file or directory); fix the file or remove it",
        'a/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder']


def test_a_private_folder_that_links_to_a_missing_folder_is_one_finding(tmp_path):
    (tmp_path / "config").mkdir()
    (tmp_path / "config" / "private").symlink_to(tmp_path / "missing", target_is_directory=True)
    paths = write(tmp_path, {"docs/notes.md": "zzqword\n"})
    assert guard.check(tmp_path, paths) == [
        "config/private/denylist.txt: cannot read it (No such file or directory); fix the file or remove it"]


def test_a_linked_private_folder_without_a_deny_list_is_no_deny_list(tmp_path, capsys):
    (tmp_path / "elsewhere").mkdir()
    (tmp_path / "config").mkdir()
    (tmp_path / "config" / "private").symlink_to(tmp_path / "elsewhere", target_is_directory=True)
    paths = write(tmp_path, {"docs/notes.md": "the zzqword tool\n"})
    assert guard.main(tmp_path, paths) == 0, capsys.readouterr().out


def test_a_deny_list_with_a_byte_order_mark_keeps_its_first_word(tmp_path):
    (tmp_path / "config" / "private").mkdir(parents=True)
    (tmp_path / "config" / "private" / "denylist.txt").write_bytes(b"\xef\xbb\xbfzzqword\nzzqother\n")
    paths = write(tmp_path, {"docs/notes.md": "zzqword here\nzzqother here\n"})
    assert guard.check(tmp_path, paths) == [
        f"docs/notes.md:1: zzqword: {DENIED}", f"docs/notes.md:2: zzqother: {DENIED}"]


def test_the_guard_reads_the_deny_list_below_the_root_and_not_the_ignored_list_itself(tmp_path):
    repository = tmp_path / "repository"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    write(repository, {".gitignore": "**/config/private/**\n", "config/private/denylist.txt": "zzqword\n",
                       "docs/notes.md": "first line\nuse ZZQWORD here\n"})
    subprocess.run(["git", "-C", str(repository), "add", ".gitignore", "docs"], check=True, env=git_environment())
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout.splitlines() == [f"docs/notes.md:2: zzqword: {DENIED}", "checked 2 tracked files, 1 findings"]


def test_a_tracked_link_is_checked_by_its_text_and_not_by_the_file_it_points_to(tmp_path):
    repository, outside = tmp_path / "repository", tmp_path / "outside"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    write(repository, {".gitignore": "config/private/\n", "config/private/denylist.txt": "zzqword\n"})
    write(outside, {"notes.txt": f"zzqword in /home/{PERSON}/x\n"})
    cluster = "lkc-" + "0test1"
    links = {"lib/opt": "/opt/zzqword/x", "lib/home": f"/home/{PERSON}/x", "lib/cluster": f"/opt/{cluster}/x",
             "lib/outside": "../../outside/notes.txt"}
    (repository / "lib").mkdir()
    for path, target in links.items():
        (repository / path).symlink_to(target)
    subprocess.run(["git", "-C", str(repository), "add", ".gitignore", "lib"], check=True, env=git_environment())
    assert git(repository, "ls-files", "-s", "lib").count("120000 ") == 4
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout.splitlines() == [
        f"lib/cluster:1: {cluster}: looks like a Confluent Cloud resource id; write a placeholder such as lkc-xxxxx",
        f'lib/home:1: "/home/{PERSON}" is a personal home path; use $HOME or a placeholder such as <home>',
        f"lib/opt:1: zzqword: {DENIED}",
        "checked 5 tracked files, 3 findings"]


def test_a_tracked_path_that_is_not_valid_utf8_is_one_finding_named_as_git_shows_it(tmp_path):
    repository = tmp_path / "repository"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    (repository / non_utf8_name(repository, b"caf\xe9.txt")).write_text("zzqword\n", encoding="utf-8")
    write(repository, {".gitignore": "config/private/\n", "config/private/denylist.txt": "zzqword\n",
                       "config/cpl.props": "bootstrap.servers=192.168.3.5:9092\n"})
    subprocess.run(["git", "-C", str(repository), "add", "-A"], check=True, env=git_environment())
    subprocess.run(["git", "-C", str(repository), "update-index", "--add", "--cacheinfo",
                    f"160000,{'1' * 40}," + os.fsdecode(b"sub\xe9")], check=True, env=git_environment())
    shown = git(repository, "-c", "core.quotepath=true", "ls-files").splitlines()
    assert shown == [".gitignore", '"caf\\351.txt"', "config/cpl.props", '"sub\\351"']
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ""
    assert result.stdout.splitlines() == [
        f"{shown[1]}: the file name is not valid UTF-8; rename the file",
        'config/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder',
        f"{shown[3]}: the file name is not valid UTF-8; rename the file",
        "checked 4 tracked files, 3 findings"]


def test_a_gitlink_that_is_not_valid_utf8_with_its_submodule_entry_is_one_finding(tmp_path):
    repository = tmp_path / "repository"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    (repository / ".gitmodules").write_bytes(b'[submodule "s"]\n\tpath = sub\xe9\n\turl = https://example.com/s.git\n')
    subprocess.run(["git", "-C", str(repository), "add", ".gitmodules"], check=True, env=git_environment())
    subprocess.run(["git", "-C", str(repository), "update-index", "--add", "--cacheinfo",
                    f"160000,{'1' * 40}," + os.fsdecode(b"sub\xe9")], check=True, env=git_environment())
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ""
    assert result.stdout.splitlines() == ['"sub\\351": the file name is not valid UTF-8; rename the file',
                                          "checked 2 tracked files, 1 findings"]


def test_a_repository_in_a_directory_whose_name_is_not_valid_utf8_is_checked(tmp_path):
    repository = gitlink_repository(tmp_path / non_utf8_name(tmp_path, b"repository\xe9"), None)
    write(repository, {"config/cpl.props": "bootstrap.servers=192.168.3.5:9092\n"})
    subprocess.run(["git", "-C", str(repository), "add", "config"], check=True, env=git_environment())
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ""
    assert result.stdout.splitlines() == [
        "dir/sub: gitlink without a .gitmodules entry; at the top level of the repository, add one with git config "
        "-f .gitmodules submodule.dir/sub.path dir/sub, git config -f .gitmodules submodule.dir/sub.url <url> and "
        "git add .gitmodules, or remove it with git rm --cached dir/sub",
        'config/cpl.props:1: bootstrap.servers: "192.168.3.5" is not localhost or a placeholder',
        "checked 2 tracked files, 2 findings"]


def test_a_repository_or_a_root_whose_folder_name_holds_a_newline_reads_its_submodule_entries(tmp_path):
    entry = '[submodule "s"]\n\tpath = dir/sub\n\turl = https://example.com/s.git\n'
    repository = gitlink_repository(tmp_path / "repo\nsitory", entry)
    result = run_guard(repository)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines() == ["checked 2 tracked files, 0 findings"]
    folder = repository / "d\nx"
    folder.mkdir()
    git(repository, "config", "-f", ".gitmodules", "submodule.k.path", "d\nx/known")
    git(repository, "add", ".gitmodules")
    git(repository, "update-index", "--add", "--cacheinfo", f"160000,{'1' * 40},d\nx/known")
    result = run_guard(folder)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines() == ["checked 1 tracked files, 0 findings"]


def test_a_gitlink_below_a_root_whose_folder_name_is_not_valid_utf8_is_named_as_git_shows_it(tmp_path):
    repository = tmp_path / "repository"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    folder = non_utf8_name(repository, b"d\xe9")
    modules = b'[submodule "k"]\n\tpath = d\xe9/known\n\turl = https://example.com/k.git\n'
    (repository / ".gitmodules").write_bytes(modules)
    subprocess.run(["git", "-C", str(repository), "add", ".gitmodules"], check=True, env=git_environment())
    for name in ("known", "stray"):
        subprocess.run(["git", "-C", str(repository), "update-index", "--add", "--cacheinfo",
                        f"160000,{'1' * 40},{folder}/{name}"], check=True, env=git_environment())
    (repository / folder).mkdir()
    # A UTF-8 locale such as en_US.UTF-8 gives standard output the strict error handler.
    result = subprocess.run([sys.executable, str(pathlib.Path(guard.__file__)), str(repository / folder)],
                            capture_output=True, text=True, check=False,
                            env={**git_environment(), "PYTHONIOENCODING": "utf-8:strict"})
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ""
    assert result.stdout.splitlines() == ['"d\\351/stray": the file name is not valid UTF-8; rename the file',
                                          "checked 2 tracked files, 1 findings"]


NON_UTF8_CASES = pytest.mark.parametrize("case", [
    test_a_tracked_path_that_is_not_valid_utf8_is_one_finding_named_as_git_shows_it,
    test_a_repository_in_a_directory_whose_name_is_not_valid_utf8_is_checked,
    test_a_gitlink_below_a_root_whose_folder_name_is_not_valid_utf8_is_named_as_git_shows_it,
], ids=["file", "repository", "folder"])


@NON_UTF8_CASES
def test_a_case_with_a_name_that_is_not_valid_utf8_is_skipped_where_the_file_system_refuses_the_name(case, tmp_path,
                                                                                                    monkeypatch):
    mkdir = pathlib.Path.mkdir

    def refuse(path, *arguments, **options):
        if not guard.utf8(str(path)):
            raise OSError(errno.EILSEQ, os.strerror(errno.EILSEQ), str(path))
        mkdir(path, *arguments, **options)

    monkeypatch.setattr(pathlib.Path, "mkdir", refuse)
    with pytest.raises(pytest.skip.Exception, match="the file system refuses names that are not valid UTF-8"):
        case(tmp_path)


@NON_UTF8_CASES
def test_a_case_with_a_name_that_is_not_valid_utf8_is_skipped_where_the_file_system_renames_the_name(case, tmp_path,
                                                                                                    monkeypatch):
    mkdir, rmdir = pathlib.Path.mkdir, pathlib.Path.rmdir

    def escaped(path):
        if guard.utf8(path.name):
            return path
        return path.with_name("".join(chr(b) if b < 0x80 else f"%{b:02X}" for b in os.fsencode(path.name)))

    monkeypatch.setattr(pathlib.Path, "mkdir", lambda path, *arguments, **options: mkdir(escaped(path), *arguments,
                                                                                         **options))
    monkeypatch.setattr(pathlib.Path, "rmdir", lambda path: rmdir(escaped(path)))
    with pytest.raises(pytest.skip.Exception, match="the file system renames names that are not valid UTF-8"):
        case(tmp_path)


def test_the_name_probe_raises_any_other_failure(tmp_path, monkeypatch):
    def deny(path, *arguments, **options):
        raise OSError(errno.EACCES, os.strerror(errno.EACCES), str(path))

    monkeypatch.setattr(pathlib.Path, "mkdir", deny)
    with pytest.raises(PermissionError):
        non_utf8_name(tmp_path, b"d\xe9")


def test_a_tracked_file_below_a_directory_that_the_working_tree_replaced_by_a_link_is_not_read(tmp_path):
    repository, outside = tmp_path / "repository", tmp_path / "outside"
    subprocess.run(["git", "init", "-q", str(repository)], check=True, env=git_environment())
    write(repository, {".gitignore": "config/private/\n", "config/private/denylist.txt": "zzqword\n",
                       "docs/notes.txt": "ok\n", "lib/notes.txt": "zzqword\n", "lib/sub/more.txt": "ok\n"})
    subprocess.run(["git", "-C", str(repository), "add", ".gitignore", "docs", "lib"], check=True,
                   env=git_environment())
    write(outside, {"notes.txt": f"zzqword in /home/{PERSON}/x\n", "sub/more.txt": "zzqword\n"})
    shutil.rmtree(repository / "docs")
    (repository / "docs").symlink_to(outside, target_is_directory=True)
    shutil.rmtree(repository / "lib" / "sub")
    (repository / "lib" / "sub").symlink_to(outside / "sub", target_is_directory=True)
    status = git(repository, "status", "--porcelain").splitlines()
    assert "AD docs/notes.txt" in status and "AD lib/sub/more.txt" in status
    result = run_guard(repository)
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout.splitlines() == [f"lib/notes.txt:1: zzqword: {DENIED}", "checked 4 tracked files, 1 findings"]


def test_a_deny_list_with_a_control_or_format_character_in_a_word_is_one_finding(tmp_path, capsys):
    paths = write(tmp_path, {"docs/notes.md": "zzqword and zzqother\n"})
    deny_list = tmp_path / "config" / "private" / "denylist.txt"
    deny_list.parent.mkdir(parents=True)
    for data, cause in (("zzqword\nzzqother\n".encode("utf-16-le"), "line 1 holds the control character U+0000"),
                        (b"zzqword\n\xef\xbb\xbfzzqother\n", "line 2 holds the format character U+FEFF")):
        deny_list.write_bytes(data)
        assert guard.main(tmp_path, paths) == 1
        assert capsys.readouterr().out.splitlines() == [
            f"config/private/denylist.txt: cannot read it ({cause}); fix the file or remove it",
            "checked 1 tracked files, 1 findings"]


def test_a_text_file_that_is_not_utf8_is_also_searched_in_its_cp1252_decoding(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqüword\nzzqword\n"})
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "stations.txt").write_bytes(b"caf\xe9\x85 first\nthe ZZQ\xdcWORD tool\nzzqword \xe9\n")
    assert guard.check(tmp_path, ["docs/stations.txt"]) == [
        f"docs/stations.txt:2: zzqüword: {DENIED}", f"docs/stations.txt:3: zzqword: {DENIED}"]


def test_only_the_lines_that_are_not_utf8_are_also_searched_in_their_cp1252_decoding(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqüword\nzzqword\n"})
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "mixed.txt").write_bytes(
        "äzzqword, cafézzqword and \u53f8zzqword\n".encode("utf-8") + b"stray \x80 byte\nthe ZZQ\xdcWORD tool\n")
    assert guard.check(tmp_path, ["docs/mixed.txt"]) == [f"docs/mixed.txt:3: zzqüword: {DENIED}"]


def test_a_word_glued_to_a_valid_letter_stays_glued_on_a_line_that_is_not_utf8(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqüword\nzzqword\n"})
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "mixed.txt").write_bytes(
        "äzzqword, cafézzqword and \u53f8zzqword ".encode("utf-8") + b"\x80 byte\n"
        + "ä ".encode("utf-8") + b"the ZZQ\xdcWORD tool\n")
    assert guard.check(tmp_path, ["docs/mixed.txt"]) == [f"docs/mixed.txt:2: zzqüword: {DENIED}"]


def test_a_line_break_that_cp1252_does_not_know_keeps_each_finding_once_at_its_line(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqüword\nzzqword\n"})
    (tmp_path / "docs").mkdir()
    for separator in ("\u2028", "\u2029", "\u0085"):
        (tmp_path / "docs" / "a.txt").write_bytes(f"a{separator}b\n".encode("utf-8")
                                                  + b"zzqword \xe9\nthe ZZQ\xdcWORD tool\n")
        assert guard.check(tmp_path, ["docs/a.txt"]) == [
            f"docs/a.txt:3: zzqword: {DENIED}", f"docs/a.txt:4: zzqüword: {DENIED}"], repr(separator)


def test_deny_words_that_differ_only_in_letter_case_give_one_finding(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqword\nZZQWORD\nZzqWord\n"})
    paths = write(tmp_path, {"docs/notes.md": "use zzqword here\n", "zzqword/run.sh": "echo ok\n"})
    assert guard.check(tmp_path, paths) == [f"docs/notes.md:1: zzqword: {DENIED}",
                                            f"zzqword/run.sh: zzqword: {DENIED_PATH}"]


def test_deny_words_that_lower_case_joins_but_a_case_insensitive_match_tells_apart_both_stay(tmp_path):
    dotted, combining = "zzqİx", "zzqi̇x"
    write(tmp_path, {"config/private/denylist.txt": f"{dotted}\n{combining}\n"})
    paths = write(tmp_path, {"docs/notes.md": f"{combining}\nzzqix\n"})
    assert guard.check(tmp_path, paths) == [f"docs/notes.md:1: {combining}: {DENIED}",
                                            f"docs/notes.md:2: {dotted}: {DENIED}"]


def test_deny_words_that_a_case_insensitive_match_joins_give_one_finding(tmp_path):
    write(tmp_path, {"config/private/denylist.txt": "zzqİx\nZZQIX\nzzqſword\nzzqsword\n"})
    paths = write(tmp_path, {"docs/notes.md": "zzqix and zzqsword\n"})
    assert guard.check(tmp_path, paths) == [f"docs/notes.md:1: zzqİx: {DENIED}",
                                            f"docs/notes.md:1: zzqſword: {DENIED}"]


def test_many_deny_words_give_the_findings_of_one_search_per_word(tmp_path):
    words = [f"zzq{n:03d}" for n in range(296)] + ["zzqa", "zzqa-zzqb", "zzqa zzqb", "zzqb"]
    generator = random.Random(30)
    tokens = words + [w.upper() for w in words[:40]] + ["lorem", "ipsum", "-", "_", "7", "x", "zzq", "zzqa-"]
    lines = ["the zzqa-zzqb pair"]
    for _ in range(3999):
        line = ""
        for _ in range(generator.randrange(8)):
            line += generator.choice(tokens) + generator.choice(["", " ", " ", "-", "_", "7", "x", "."])
        lines.append(line)
    write(tmp_path, {"config/private/denylist.txt": "\n".join(words) + "\n", "docs/many.txt": "\n".join(lines) + "\n"})
    paths = ["zzq007/a.txt", "a/ZZQA-zzqb.md", "a/xzzq001.md", "docs/many.txt"]
    patterns = [(w, re.compile(rf"(?<![^\W_]){re.escape(w)}(?![^\W_])", re.I)) for w in words]
    expected = [f"{path}: {w}: {DENIED_PATH}" for path in paths for w, p in patterns if p.search(path)]
    expected += [f"docs/many.txt:{n}: {w}: {DENIED}" for n, line in enumerate(lines, 1) for w, p in patterns
                 if p.search(line)]
    findings = guard.check(tmp_path, paths)
    assert [f for f in findings if f.startswith("docs/many.txt:1: ")] == [
        f"docs/many.txt:1: {w}: {DENIED}" for w in ("zzqa", "zzqa-zzqb", "zzqb")]
    assert len(findings) > 1000
    assert findings == expected
