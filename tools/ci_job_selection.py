import argparse
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

from sync_operator_exercises import METADATA


JOBS = (
    "operator-learning", "execution-contracts", "rust-runner", "rust-runner-native",
    "rust-runner-static", "rust-b1-acceptance", "rust-runner-compose", "wayang-rust-compose",
    "java", "python", "saas-frontend", "saas-backend", "deploy", "kafscale-conformance", "cross-language",
)
MAVEN_ROOTS = {
    "rust-b1-acceptance": ("opentsx-wayang",),
    "rust-runner-compose": ("opentsx-wayang",),
    "wayang-rust-compose": ("opentsx-wayang",),
    "kafscale-conformance": (
        "opentsx-kafscale-conformance", "opentsx-kafka-v2", "opentsx-legacy-bridge", "opentsx-windpark",
        "opentsx-stats", "opentsx-twin-live", "opentsx-retrieval", "opentsx-wayang", "opentsx-flink-core",
    ),
    "cross-language": ("opentsx-store-iceberg", "opentsx-retrieval"),
}
RUNNER_JOBS = {
    "rust-runner", "rust-runner-native", "rust-runner-static", "rust-b1-acceptance",
    "rust-runner-compose", "wayang-rust-compose",
}
XML = {"m": "http://maven.apache.org/POM/4.0.0"}


def relative_path(value):
    if (not isinstance(value, str) or not value or "\0" in value or "\\" in value
            or value.startswith("/") or any(part in ("", ".", "..") for part in value.split("/"))):
        raise ValueError("Expected a normalized relative repository path")
    return value


def json_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def maven_dependencies(root):
    root = Path(root).resolve()
    project = ET.parse(root / "pom.xml").getroot()
    group = project.findtext("m:groupId", namespaces=XML)
    modules = [relative_path(node.text) for node in project.findall("m:modules/m:module", XML)]
    if not group or not modules or len(set(modules)) != len(modules):
        raise ValueError("Invalid Maven reactor modules")
    artifacts = {}
    projects = {}
    for module in modules:
        path = root / module / "pom.xml"
        if not path.resolve().is_relative_to(root):
            raise ValueError("Maven module escapes repository")
        child = ET.parse(path).getroot()
        artifact = child.findtext("m:artifactId", namespaces=XML)
        child_group = child.findtext("m:groupId", namespaces=XML)
        if child_group is None:
            child_group = child.findtext("m:parent/m:groupId", namespaces=XML)
        if not artifact or artifact in artifacts or child_group != group:
            raise ValueError("Invalid internal Maven artifact")
        artifacts[artifact] = module
        projects[module] = child
    graph = {module: set() for module in modules}
    for module, child in projects.items():
        dependencies = child.findall("m:dependencies/m:dependency", XML)
        dependencies += child.findall("m:profiles/m:profile/m:dependencies/m:dependency", XML)
        for dependency in dependencies:
            dependency_group = dependency.findtext("m:groupId", namespaces=XML)
            if dependency_group in ("${project.groupId}", "${pom.groupId}"):
                dependency_group = group
            if dependency_group and "${" in dependency_group:
                raise ValueError("Unresolved Maven dependency group")
            if dependency_group != group:
                continue
            artifact = dependency.findtext("m:artifactId", namespaces=XML)
            if artifact not in artifacts:
                raise ValueError("Unknown internal Maven dependency")
            graph[module].add(artifacts[artifact])
    visiting, complete = set(), set()

    def visit(module):
        if module in visiting:
            raise ValueError("Cyclic Maven dependency")
        if module not in complete:
            visiting.add(module)
            for dependency in graph[module]:
                visit(dependency)
            visiting.remove(module)
            complete.add(module)

    for module in graph:
        visit(module)
    return graph


def dependency_closure(graph, roots):
    result = set()
    pending = list(roots)
    while pending:
        module = pending.pop()
        if module not in graph:
            raise ValueError("CI job references an unknown Maven module")
        if module not in result:
            result.add(module)
            pending.extend(graph[module])
    return result


def learning_inputs(root):
    result = {}
    for metadata, job in zip(METADATA, ("saas-frontend", "python")):
        document = json.loads((root / metadata).read_text(encoding="utf-8"), object_pairs_hook=json_object)
        if (not isinstance(document, dict) or document.get("schema") != "opentsx.learning.v1"
                or not isinstance(document.get("lessons"), list)):
            raise ValueError("Invalid learning metadata for CI selection")
        paths = set()
        for lesson in document["lessons"]:
            if not isinstance(lesson, dict) or not isinstance(lesson.get("references"), dict):
                raise ValueError("Invalid learning references for CI selection")
            for references in lesson["references"].values():
                if not isinstance(references, list):
                    raise ValueError("Invalid learning reference list for CI selection")
                for reference in references:
                    if not isinstance(reference, str):
                        raise ValueError("Invalid learning reference for CI selection")
                    paths.add(relative_path(reference.partition("#")[0]))
        result[job] = paths
    return result


def select_jobs(root, paths):
    root = Path(root).resolve()
    paths = [relative_path(path) for path in paths]
    full_files = {"pom.xml", ".gitignore", ".gitmodules", "AGENTS.md", "DOCS.md"}
    full_prefixes = (".github/", ".mvn/", "tools/", "bin/", "systems/", "docs/datamodel-2026/",
                     "docs/learning/", "docs/user-manual/", "docs/devguide/schemas/")
    if any(path in full_files or path.endswith("/pom.xml") or path.startswith(full_prefixes) for path in paths):
        return dict.fromkeys(JOBS, True)
    graph = maven_dependencies(root)
    closures = {job: dependency_closure(graph, roots) for job, roots in MAVEN_ROOTS.items()}
    references = learning_inputs(root)
    selected = {"operator-learning"}
    for path in paths:
        matched = False
        for job, inputs in references.items():
            if path in inputs:
                selected.add(job)
                matched = True
        module = next((module for module in graph if path.startswith(module + "/")), None)
        if module is not None:
            selected.add("java")
            selected.update(job for job, dependencies in closures.items() if module in dependencies)
            if path.startswith(("opentsx-model/src/main/avro/", "opentsx-model/src/test/resources/")):
                selected.update({"python", "saas-frontend", "execution-contracts", *RUNNER_JOBS})
            if path.startswith("opentsx-twin/src/main/resources/twin/avro/"):
                selected.add("python")
            matched = True
        if path.startswith("python-package/"):
            selected.update(("python", "cross-language"))
            matched = True
        if path.startswith("opentsx-saas-frontend/"):
            selected.update(("saas-frontend", "wayang-rust-compose", "python"))
            matched = True
        if path.startswith("opentsx-saas-backend/"):
            selected.update(("saas-backend", "wayang-rust-compose"))
            matched = True
        if path.startswith(("rust/tsx-contracts/", "rust/tsx-runner/")):
            selected.update(RUNNER_JOBS)
            if path.startswith("rust/tsx-contracts/"):
                selected.add("execution-contracts")
            matched = True
        if path.startswith("deploy/"):
            selected.add("deploy")
            if path.startswith("deploy/local-cp/"):
                selected.add("python")
            if path.startswith("deploy/compose/"):
                selected.update(("rust-runner-compose", "wayang-rust-compose"))
            matched = True
        if path == "docker-compose.rust-runner.yml":
            selected.add("rust-runner-compose")
            matched = True
        if path == "docker-compose.wayang-rust.yml":
            selected.add("wayang-rust-compose")
            matched = True
        if (path.startswith("docs/") or "/" not in path and PurePosixPath(path).suffix.lower() == ".md"
                or path == "LICENSE"):
            matched = True
        if not matched:
            return dict.fromkeys(JOBS, True)
    return {job: job in selected for job in JOBS}


def git(root, *arguments):
    return subprocess.run(["git", "-C", str(root), *arguments], check=True, capture_output=True).stdout


def pr_changed_paths(root, base_sha, head_sha, merge_sha):
    for sha in (base_sha, head_sha, merge_sha):
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("Invalid pull request commit SHA")
    if git(root, "rev-parse", "--verify", "HEAD").decode().strip() != merge_sha:
        raise ValueError("Checkout does not match the pull request merge commit")
    parents = git(root, "show", "--no-patch", "--format=%P", merge_sha).decode().strip().split()
    if parents != [base_sha, head_sha]:
        raise ValueError("Pull request merge parents do not match the event")
    data = git(root, "diff", "--name-only", "-z", "--no-renames", base_sha, merge_sha, "--")
    return [relative_path(path.decode("utf-8")) for path in data.split(b"\0") if path]


def full_event(event_name, ref):
    if event_name == "push" and ref == "refs/heads/master":
        return True
    if event_name == "workflow_dispatch" and isinstance(ref, str) and ref.startswith(("refs/heads/", "refs/tags/")):
        return True
    if event_name == "pull_request" and isinstance(ref, str) and re.fullmatch(r"refs/pull/[1-9][0-9]*/merge", ref):
        return False
    raise ValueError("Unsupported CI event or ref")


def jobs_for_event(root, event_name, ref, merge_sha, event):
    if not isinstance(event, dict):
        raise ValueError("CI event must be a JSON object")
    if full_event(event_name, ref):
        return dict.fromkeys(JOBS, True)
    request = event.get("pull_request")
    if (not isinstance(request, dict) or not isinstance(request.get("base"), dict)
            or not isinstance(request.get("head"), dict)):
        raise ValueError("Pull request event is missing commit metadata")
    paths = pr_changed_paths(root, request["base"].get("sha"), request["head"].get("sha"), merge_sha)
    return select_jobs(root, paths)


def check_results(event_name, ref, needs):
    full = full_event(event_name, ref)
    if not isinstance(needs, dict) or set(needs) != {*JOBS, "ci-selection"}:
        raise ValueError("CI results do not match the expected job inventory")
    selector = needs["ci-selection"]
    if not isinstance(selector, dict) or selector.get("result") != "success":
        raise ValueError("CI job selection did not succeed")
    outputs = selector.get("outputs")
    if not isinstance(outputs, dict) or not isinstance(outputs.get("jobs"), str):
        raise ValueError("CI job selection output is missing")
    selected = json.loads(outputs["jobs"], object_pairs_hook=json_object)
    if (not isinstance(selected, dict) or set(selected) != set(JOBS)
            or any(type(value) is not bool for value in selected.values())
            or not selected["operator-learning"] or full and not all(selected.values())):
        raise ValueError("CI job selection output is invalid")
    for job, required in selected.items():
        result = needs[job]
        allowed = ("success",) if required else ("success", "skipped")
        if not isinstance(result, dict) or result.get("result") not in allowed:
            raise ValueError("CI job did not complete as selected: " + job)


def main():
    parser = argparse.ArgumentParser(description="Select affected CI jobs and verify their outcomes.")
    parser.add_argument("command", choices=("select", "check"))
    arguments = parser.parse_args()
    try:
        event_name = os.environ["GITHUB_EVENT_NAME"]
        ref = os.environ["GITHUB_REF"]
        if arguments.command == "select":
            event = json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text(encoding="utf-8"),
                               object_pairs_hook=json_object)
            jobs = jobs_for_event(Path.cwd(), event_name, ref, os.environ["GITHUB_SHA"], event)
            encoded = json.dumps(jobs, separators=(",", ":"))
            with Path(os.environ["GITHUB_OUTPUT"]).open("a", encoding="utf-8") as output:
                output.write("jobs=" + encoded + "\n")
            print(json.dumps({"mode": "full" if all(jobs.values()) else "affected", "jobs": jobs}))
        else:
            needs = json.loads(os.environ["CI_NEEDS_JSON"], object_pairs_hook=json_object)
            check_results(event_name, ref, needs)
            print("All selected CI jobs completed successfully")
    except (OSError, UnicodeError, ValueError, KeyError, ET.ParseError, subprocess.CalledProcessError) as error:
        print("CI selection failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
