"""No tracked YAML file or Dockerfile of the repository pulls an image from the frozen registry k8s.gcr.io.

Run from the repository root: python -m pytest -q tools
"""
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parent.parent
FROZEN = re.compile(r"(?<![\w.-])k8s\.gcr\.io(?![\w.-])")
ECHO_DEMOS = [
    "archive/demos/opentsx-clusters/krake-container/echo-demo.yaml",
    "archive/demos/opentsx-clusters/mmdc-mrc/mdc1/scripts/scenario3/kube/echo-demo.yaml",
    "archive/demos/opentsx-clusters/mmdc-mrc/mdc2/scripts/scenario3/kube/echo-demo.yaml",
    "archive/demos/opentsx-clusters/mmdc-mrc/mdc3/scripts/scenario3/kube/echo-demo.yaml",
]


def manifests() -> list[str]:
    listing = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", "*.yaml", "*.yml", "*Dockerfile*"],
                             capture_output=True, check=True)
    return [p for p in listing.stdout.decode("utf-8").split("\0") if p]


def registry_problems(root: pathlib.Path, name: str) -> list[str]:
    text = (root / name).read_text(encoding="utf-8")
    return [f"{name}:{number}: k8s.gcr.io is frozen; pull the image from registry.k8s.io"
            for number, line in enumerate(text.splitlines(), 1)
            if not line.lstrip().startswith("#") and FROZEN.search(line)]


def test_an_image_from_k8s_gcr_io_fails_the_check(tmp_path):
    name = ECHO_DEMOS[0]
    copy = tmp_path / name
    copy.parent.mkdir(parents=True)
    text = (ROOT / name).read_text(encoding="utf-8")
    assert "registry.k8s.io/echoserver:1.10" in text
    copy.write_text(text.replace("registry.k8s.io/", "k8s.gcr.io/"), encoding="utf-8")
    line = text.splitlines().index("        image: registry.k8s.io/echoserver:1.10") + 1
    assert registry_problems(tmp_path, name) == [
        f"{name}:{line}: k8s.gcr.io is frozen; pull the image from registry.k8s.io"]


def test_a_dockerfile_from_k8s_gcr_io_fails_and_a_comment_or_another_host_passes(tmp_path):
    (tmp_path / "Dockerfile").write_text(
        "# FROM k8s.gcr.io/pause:3.2\nFROM k8s.gcr.io/pause:3.2\nFROM registry.k8s.io/pause:3.2\n"
        "FROM mirror.k8s.gcr.io.example/pause:3.2\n", encoding="utf-8")
    assert registry_problems(tmp_path, "Dockerfile") == [
        "Dockerfile:2: k8s.gcr.io is frozen; pull the image from registry.k8s.io"]
    (tmp_path / "values.yaml").write_text("image:\n  registry: k8s.gcr.io\n  repository: pause\n", encoding="utf-8")
    assert registry_problems(tmp_path, "values.yaml") == [
        "values.yaml:2: k8s.gcr.io is frozen; pull the image from registry.k8s.io"]


def test_no_tracked_yaml_file_or_dockerfile_pulls_from_k8s_gcr_io():
    files = manifests()
    assert [d for d in ECHO_DEMOS if d not in files] == []
    assert any(pathlib.PurePosixPath(f).name.endswith("Dockerfile") for f in files)
    assert [p for f in files for p in registry_problems(ROOT, f)] == []
