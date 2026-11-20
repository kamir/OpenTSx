import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile


ROOT = Path(__file__).resolve().parents[2]
XML = {"m": "http://maven.apache.org/POM/4.0.0"}
STABLE = re.compile(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)")


def text(element, name):
    values = element.findall("m:" + name.replace("/", "/m:"), XML)
    if len(values) > 1:
        raise ValueError("duplicate Maven release field")
    if not values:
        return None
    value = (values[0].text or "").strip()
    if not value:
        raise ValueError("empty Maven release field")
    return value


def staged_versions(staged, artifacts, version):
    staged = Path(staged)
    if staged.is_symlink():
        raise ValueError("staged artifact directories must not be symbolic links")
    staged = staged.resolve()
    internal = staged / "opt/opentsx/lib/m2/org/opentsx"
    if any(path.is_symlink() for path in (internal, *internal.parents)):
        raise ValueError("staged artifact directories must not be symbolic links")
    if not internal.is_dir():
        raise ValueError("staged internal artifacts are missing")
    jars = []
    for path in sorted(internal.rglob("*")):
        if path.is_symlink():
            raise ValueError("staged artifacts must not be symbolic links")
        if path.is_dir():
            continue
        parts = path.relative_to(internal).parts
        if (
            not path.is_file()
            or len(parts) != 3
            or parts[0] not in artifacts
            or parts[1] != version
            or parts[2] != f"{parts[0]}-{version}.jar"
        ):
            raise ValueError(
                "staged internal artifact identity does not match the reactor"
            )
        entry = f"META-INF/maven/org.opentsx/{parts[0]}/pom.properties"
        with zipfile.ZipFile(path) as archive:
            if archive.namelist().count(entry) != 1:
                raise ValueError("staged internal JAR must contain one Maven identity")
            properties = {}
            for line in archive.read(entry).decode("utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith(("#", "!")):
                    continue
                key, separator, value = line.partition("=")
                key, value = key.strip(), value.strip()
                if not separator or key in properties:
                    raise ValueError("invalid staged Maven identity")
                properties[key] = value
            if properties != {
                "groupId": "org.opentsx",
                "artifactId": parts[0],
                "version": version,
            }:
                raise ValueError(
                    "staged Maven identity does not match the stable release"
                )
        jars.append(path)
    if not jars:
        raise ValueError("staged internal artifacts are empty")


def verify_release(root, tag=None, staged=None):
    root = Path(root).resolve()
    project = ET.parse(root / "pom.xml").getroot()
    version = text(project, "version")
    if not version or not STABLE.fullmatch(version):
        raise ValueError("project version must be a stable numeric semantic version")
    if tag is not None and tag != "v" + version:
        raise ValueError("release tag does not match the project version")
    group, artifact = text(project, "groupId"), text(project, "artifactId")
    if group != "org.opentsx" or not artifact:
        raise ValueError("invalid Maven release reactor identity")
    modules = project.findall("m:modules/m:module", XML)
    if not modules:
        raise ValueError("Maven release reactor modules are missing")
    projects, artifacts, paths = [project], set(), set()
    for module in modules:
        name = (module.text or "").strip()
        if (
            not name
            or "\\" in name
            or any(part in ("", ".", "..") for part in name.split("/"))
            or Path(name).is_absolute()
            or name in paths
        ):
            raise ValueError("invalid Maven release module path")
        paths.add(name)
        pom = root / name / "pom.xml"
        if not pom.resolve().is_relative_to(root) or pom.is_symlink():
            raise ValueError("Maven release module escapes the source root")
        child = ET.parse(pom).getroot()
        own = text(child, "version")
        identity = text(child, "artifactId")
        parent = text(child, "parent/relativePath")
        if (
            text(child, "parent/groupId") != group
            or text(child, "parent/artifactId") != artifact
            or text(child, "parent/version") != version
            or text(child, "groupId") not in (None, group)
            or own not in (None, version)
            or not identity
            or identity in artifacts
            or identity == artifact
            or child.findall("m:modules/m:module", XML)
            or (pom.parent / (parent if parent is not None else "../pom.xml")).resolve()
            != root / "pom.xml"
        ):
            raise ValueError(
                "Maven module identity or version does not match the release"
            )
        artifacts.add(identity)
        projects.append(child)
    for child in projects:
        for dependency in child.findall(".//m:dependency", XML):
            dependency_group = text(dependency, "groupId")
            if dependency_group in ("${project.groupId}", "${pom.groupId}"):
                dependency_group = group
            if not dependency_group or "${" in dependency_group:
                raise ValueError("unresolved Maven dependency group")
            if dependency_group != group:
                continue
            if (
                text(dependency, "artifactId") not in artifacts
                or text(dependency, "version") not in (version, "${project.version}")
                or text(dependency, "scope") == "system"
                or dependency.find("m:systemPath", XML) is not None
            ):
                raise ValueError(
                    "internal Maven dependency does not match the stable release"
                )
    if staged is not None:
        staged_versions(staged, artifacts, version)
    return version


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="Verify the Java Tools stable release version."
    )
    parser.add_argument(
        "--tag", help="Require the stable tag to match the project version."
    )
    parser.add_argument(
        "--staged",
        type=Path,
        help="Verify internal JAR identities in a staged image context.",
    )
    args = parser.parse_args(argv)
    try:
        version = verify_release(ROOT, args.tag, args.staged)
    except (
        OSError,
        ValueError,
        ET.ParseError,
        zipfile.BadZipFile,
        RuntimeError,
    ) as error:
        parser.exit(1, f"tools release check failed: {error}\n")
    print(version)


if __name__ == "__main__":
    main()
