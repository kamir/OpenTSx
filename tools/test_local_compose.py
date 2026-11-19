"""docker-compose.local.yml pins images that exist: no image without a tag, no latest, no HBase or OpenTSDB service.

The CI deploy job also runs docker compose config and docker manifest inspect for every image.
Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import subprocess

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPOSITORIES = {"postgres", "confluentinc/cp-kafka", "confluentinc/cp-schema-registry"}


def services() -> dict:
    return yaml.safe_load((ROOT / "docker-compose.local.yml").read_text(encoding="utf-8"))["services"]


def problems(services: dict) -> list[str]:
    found = []
    for name, service in services.items():
        if name in ("hbase", "opentsdb"):
            found.append(f"services.{name}: dropped from the local stack; the OpenTSDB store tests use an emulator")
        image = service.get("image")
        if image is None:
            continue
        repository, _, tag = image.rpartition(":") if ":" in image.split("/")[-1] else (image, "", "")
        if not tag or tag == "latest":
            found.append(f"services.{name}.image: {image} has no fixed tag; expected a pinned version")
        elif repository not in REPOSITORIES:
            found.append(f"services.{name}.image: {image} is not one of {sorted(REPOSITORIES)};"
                         " check that the image exists and add its repository here")
    return found


def test_every_image_is_pinned_and_known():
    assert problems(services()) == []


def test_the_confluent_images_share_one_version():
    tags = {s["image"].rpartition(":")[2] for s in services().values() if s.get("image", "").startswith("confluentinc/")}
    assert tags == {"7.6.0"}


def test_a_latest_tag_an_untagged_image_and_hbase_are_found():
    assert problems({"a": {"image": "postgres"}, "b": {"image": "postgres:latest"},
                     "hbase": {"image": "harisekhon/hbase:2.1"}, "c": {"image": "localhost:5000/x"}}) == [
        "services.a.image: postgres has no fixed tag; expected a pinned version",
        "services.b.image: postgres:latest has no fixed tag; expected a pinned version",
        "services.hbase: dropped from the local stack; the OpenTSDB store tests use an emulator",
        "services.hbase.image: harisekhon/hbase:2.1 is not one of"
        " ['confluentinc/cp-kafka', 'confluentinc/cp-schema-registry', 'postgres'];"
        " check that the image exists and add its repository here",
        "services.c.image: localhost:5000/x has no fixed tag; expected a pinned version",
    ]


def compose_step() -> str:
    workflow = yaml.safe_load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    for job in workflow["jobs"].values():
        for step in job.get("steps", []):
            if step.get("name", "").startswith("Local compose stack (docker-compose.local.yml)"):
                return step["run"]
    raise AssertionError("ci.yml: no step 'Local compose stack (docker-compose.local.yml) ...'")


def run_compose_step(tmp_path: pathlib.Path, images: str) -> subprocess.CompletedProcess:
    fake = tmp_path / "fake"
    fake.mkdir()
    docker = fake / "docker"
    docker.write_text(f'#!/bin/bash\ncase "$*" in\n  *"config --images") {images} ;;\n  *) exit 0 ;;\nesac\n',
                      encoding="utf-8")
    docker.chmod(0o755)
    return subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", "-c", compose_step()],
                          cwd=ROOT, env=dict(os.environ, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}"),
                          capture_output=True, text=True, check=False)


def test_the_ci_step_fails_when_config_images_fails(tmp_path):
    result = run_compose_step(tmp_path, "echo 'unknown flag: --images' >&2; exit 1")
    assert result.returncode != 0, result.stdout + result.stderr
    assert "unknown flag: --images" in result.stderr


def test_the_ci_step_fails_when_config_images_prints_no_image(tmp_path):
    result = run_compose_step(tmp_path, "exit 0")
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stderr == ("docker-compose.local.yml: docker compose config --images printed no image;"
                             " expected postgres, cp-kafka and cp-schema-registry\n")


def test_the_ci_step_passes_when_every_image_exists(tmp_path):
    result = run_compose_step(tmp_path, "printf '%s\\n' postgres:16-alpine confluentinc/cp-kafka:7.6.0")
    assert result.returncode == 0, result.stdout + result.stderr
