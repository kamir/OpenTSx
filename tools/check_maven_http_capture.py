import os
from pathlib import Path
import subprocess
import sys
import tempfile
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

from check_maven_downloads import ROOT, distribution_libraries


def verify_extension(jar, directory):
    (directory / ".mvn").mkdir()
    (directory / ".mvn/maven.config").write_bytes((ROOT / ".mvn/maven.config").read_bytes())
    (directory / "pom.xml").write_text(
        '<project><modelVersion>4.0.0</modelVersion><groupId>org.opentsx.test</groupId>'
        '<artifactId>capture</artifactId><version>1.0.0</version></project>', encoding="utf-8")
    remote = directory / "remote"
    remote.mkdir()
    settings = directory / "settings.xml"
    settings.write_text('<settings><mirrors><mirror><id>fixture</id><mirrorOf>*</mirrorOf><url>'
                        + remote.as_uri() + '</url></mirror></mirrors></settings>', encoding="utf-8")
    result = subprocess.run(["mvn", "--offline", "--batch-mode", "--settings", str(settings),
                             "--global-settings", str(settings), "-Daether.offline.protocols=file",
                             "-Dmaven.repo.local=" + str(directory / "repository"),
                             "-Dmaven.ext.class.path=" + str(jar),
                             "org.opentsx.test:missing-plugin:1.0.0:missing"],
                            cwd=directory, capture_output=True, text=True)
    output = result.stdout + result.stderr
    if (result.returncode != 1 or "Maven HTTP response capture enabled" not in output
            or "Using transporter FileTransporter" not in output
            or "org.apache.maven.plugin.PluginResolutionException" not in output):
        raise ValueError("The capture extension must load in the isolated offline Maven fixture")


def enable_ci(jar):
    if "GITHUB_ENV" not in os.environ:
        return
    if os.environ.get("MAVEN_OPTS"):
        raise ValueError("Existing Maven JVM options must be reviewed before enabling capture")
    if any(character.isspace() or character == os.pathsep for character in str(jar)):
        raise ValueError("The capture extension path must not contain whitespace or classpath separators")
    with Path(os.environ["GITHUB_ENV"]).open("a", encoding="utf-8") as output:
        output.write("MAVEN_OPTS=-Dmaven.ext.class.path=" + str(jar) + "\n")


def verify():
    libraries = distribution_libraries()
    jar = ROOT / "target/maven-downloads/http-capture.jar"
    if jar.is_symlink() or jar.parent.is_symlink() or jar.parent.parent.is_symlink():
        raise ValueError("Capture output paths must not be symbolic links")
    jar.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="opentsx-http-capture-") as temporary:
        directory = Path(temporary).resolve()
        classes = directory / "classes"
        classes.mkdir()
        subprocess.run(["javac", "-cp", str(libraries / "*"), "-d", str(classes),
                        "--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-Werror",
                        str(ROOT / "tools/DiagnosticHttpTransporterFactory.java"),
                        str(ROOT / "tools/MavenHttpCaptureTest.java")], check=True)
        tests = directory / "tests"
        tests.mkdir()
        subprocess.run(["java", "-cp", os.pathsep.join((str(classes), str(libraries / "*"))),
                        "org.opentsx.build.MavenHttpCaptureTest", str(tests),
                        str(ROOT / ".mvn/maven.config")], cwd=directory, check=True)
        with ZipFile(jar, "w", compression=ZIP_DEFLATED) as archive:
            for file in sorted(classes.rglob("*.class")):
                if "MavenHttpCaptureTest" not in file.name:
                    entry = ZipInfo(file.relative_to(classes).as_posix())
                    entry.compress_type = ZIP_DEFLATED
                    archive.writestr(entry, file.read_bytes())
            archive.writestr(ZipInfo("META-INF/sisu/javax.inject.Named"),
                             "org.opentsx.build.DiagnosticHttpTransporterFactory\n")
        fixture = directory / "fixture"
        fixture.mkdir()
        verify_extension(jar, fixture)
    responses = jar.with_name("http-responses.jsonl")
    if responses.is_symlink() or responses.exists() and not responses.is_file():
        raise ValueError("HTTP response output must be a regular file")
    responses.touch(exist_ok=True)
    enable_ci(jar)
    print("Maven HTTP response capture verified without network or sleeps", flush=True)


def main():
    try:
        verify()
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print("Maven HTTP response capture verification failed: " + str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
