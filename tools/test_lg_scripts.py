"""The opentsx-lg Grafana script takes its password from the caller and passes it on standard input, the scripts
start with a shebang, guard every cd, are checked by CI, the scripts and Dockerfiles pin every image and connector,
and the module README names scripts that exist.

Run from the repository root: python -m pytest -q tools
"""
import os
import pathlib
import re
import shlex
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
LG = ROOT / "opentsx-lg"
GRAFANA = LG / "src" / "main" / "mqtt-example" / "influxdb-grafana" / "__run_container_01.sh"
GRAFANA_CONTAINER = "docker-influxdb-grafana"
GRAFANA_PASSWORD = "test-password-0x1"
CI = ROOT / ".github" / "workflows" / "ci.yml"
CD = re.compile(r"(?:^|[;&({]|(?<!>)\||\b(?:then|do|else)\s)\s*(?:(?:!|command|builtin|time)\s+)*cd(?=\s|$)")
CD_GUARD = re.compile(r"(?:[<>]&|&>|>\||[^;&|])*\|\|\s*(?:exit|return)\b")
SHELL = re.compile(r"""'[^']*'|"(?:\\.|[^"\\])*"|\\.|(?P<comment>(?<![^\s;&|()])#.*)""")
SEPARATOR = re.compile(r"&&|\|\||(?<![<>])&(?!>)|;|(?<!>)\|")
# unquoted() masks a closing backtick as ")"; views() shows the inside of a substitution as "_".
SUBSTITUTION = r"\$\([^()]*\)|`[^`()]*\)"
WORD_PIECE = rf"(?:{SUBSTITUTION}|(?!{SUBSTITUTION})\S)"
VALUE_PIECE = rf"(?:{SUBSTITUTION}|(?!{SUBSTITUTION})[^\s;&|()<>])"
DOCKER_GLOBAL = (r"(?:(?:--(?:config|context|host|log-level|tlscacert|tlscert|tlskey)|-[cHl])(?:=|\s+)" + WORD_PIECE
                 + r"+|(?:--(?:debug|tls|tlsverify)|-D)(?=\s))\s+")
DOCKER_INFO = {"-v", "--version", "-h", "--help"}
CASE_ARM = r"\(?(?:" + SUBSTITUTION + r"|[^\s;&|()`])+\)\s*"
NUMBER = r"[0-9][0-9.]*[smhd]?"
KEYWORD = (r"(?:!|time|command(?!(?:\s+-[pvV]+)*\s+-[pvV]*[vV][pvV]*\s)|builtin|exec|nohup|sudo|env|nice|ionice"
           r"|timeout|xargs|if|elif|then|else|do|while|until)")
ASSIGNMENT = r"[A-Za-z_][A-Za-z0-9_]*="
TIMEOUT_VALUE_OPTION = r"(?:-[ks]|--(?:kill-after|signal))"
OPTION = (r"(?:" + TIMEOUT_VALUE_OPTION + r"\s+" + VALUE_PIECE + r"+|(?!" + TIMEOUT_VALUE_OPTION + r"\s)-" + WORD_PIECE
          + r"+)")
VARIABLE = r"\$(?:[A-Za-z_][A-Za-z0-9_]*(?![A-Za-z0-9_])|[0-9]|\{[^{}\s]+\})"
# unquoted() masks a quoted word such as "$(cat t)" or "${T}" as "_" apart from a substitution.
TIMEOUT = (r"timeout(?:\s+" + OPTION + r")*\s+(?:_*(?:" + SUBSTITUTION + "|" + VARIABLE + r")_*|_+)[smhd]?")
# A word that another alternative reads is never an option value and a TIMEOUT match is no keyword, so each word has
# one reading and a line without a command word fails in linear time.
COMMAND_WORD = (r"(?:(?:^|(?<=[;&{])|(?<=\|)(?<!>\|)|(?<=\sin\s)(?=\s*" + CASE_ARM + r"))\s*(?:" + CASE_ARM + r")?"
                r"|(?<=[(`])\s*)"
                r"(?:(?:" + ASSIGNMENT + WORD_PIECE + r"*|" + TIMEOUT + r"|-" + WORD_PIECE + r"+(?:\s+(?!-|(?:"
                + NUMBER + "|" + KEYWORD + r")\s|" + ASSIGNMENT + r")" + VALUE_PIECE + r"+)?|" + NUMBER + "|(?!"
                + TIMEOUT + r"\s)" + KEYWORD + r")\s+)*")
INSTALL = re.compile(COMMAND_WORD + r"(?:[^\s;&|()<>]*/)?(?P<tool>docker\s+(?:" + DOCKER_GLOBAL + r")*(?P<word>"
                     + VALUE_PIECE + r"+)|confluent-hub\s+install\s|confluent\s+connect\s+plugin\s+install\s)")
DOCKER_INSTALL = r"(?:(?:container\s+)?(?:run|create)|(?:image\s+)?pull)(?=\s|[<>]|&>)"
SUBCOMMAND = re.compile(DOCKER_INSTALL)
OPTIONS_BEFORE_SUBCOMMAND = re.compile(r"(?:-[^\s=]+(?:=\S*|(?:\s+[^\s-]\S*)?)\s+)+" + DOCKER_INSTALL)
ARGUMENT_PIECE = re.compile(r"(?P<redirection>(?:(?<!\S)[0-9]+)?(?:&>>?|[<>]&|>>|>\||<<<?|<>|[<>])\s*" + VALUE_PIECE
                            + r"*)|" + SUBSTITUTION + r"|.", re.S)
LITERAL = re.compile(r"[\w.-]+")
VALUE_OPTIONS = {"-p", "--publish", "-v", "--volume", "-e", "--env", "--env-file", "--name", "--network", "--restart",
                 "-w", "--workdir", "-u", "--user", "-h", "--hostname", "--entrypoint", "--mount", "-l", "--label",
                 "--platform", "--add-host", "--component-dir", "--worker-configs", "--plugin-directory",
                 "--worker-configurations"}
FLAG_OPTIONS = {"-d", "--detach", "--rm", "-i", "--interactive", "-t", "--tty", "--init", "--privileged", "-q",
                "--quiet", "--no-prompt", "--dry-run", "--force"}
WRAPPER_VALUE_OPTIONS = {
    "sudo": {"-C", "-D", "-g", "-p", "-R", "-r", "-T", "-t", "-U", "-u", "--close-from", "--chdir", "--group",
             "--host", "--prompt", "--chroot", "--role", "--command-timeout", "--type", "--other-user", "--user"},
    "env": {"-C", "-u", "--chdir", "--unset"},
    "nice": {"-n", "--adjustment"},
    "ionice": {"-c", "-n", "-P", "-p", "-u", "--class", "--classdata", "--pgid", "--pid", "--uid"},
    "timeout": {"-k", "-s", "--kill-after", "--signal"},
    "xargs": {"-a", "-d", "-E", "-I", "-L", "-n", "-P", "-s", "--arg-file", "--delimiter", "--max-args",
              "--max-procs", "--process-slot-var", "--max-chars"},
    "exec": {"-a"},
}
PINNED = re.compile(r"(?::[\w.-]*\d+\.\d+\.\d+[\w.-]*|@sha256:[0-9a-f]{64})$")


def tracked(pattern: str) -> list[str]:
    listing = subprocess.run(["git", "-C", str(ROOT), "ls-files", "-z", pattern], capture_output=True, check=True)
    return [p for p in listing.stdout.decode("utf-8").split("\0") if p]


def script_problems(root: pathlib.Path, name: str) -> list[str]:
    text = (root / name).read_text(encoding="utf-8")
    problems = [] if text.startswith("#!") else [f"{name}: no shebang; start the script with #!/usr/bin/env bash"]
    for number, line in enumerate(text.splitlines(), 1):
        line = uncommented(line)
        masked = unquoted(line)
        for cd in CD.finditer(masked):
            if not CD_GUARD.match(masked, cd.end()):
                stop = SEPARATOR.search(masked, cd.end())
                end = min(stop.start() if stop else len(line), substitution_end(line, cd.end()))
                target = line[cd.end():end].strip() or "<dir>"
                problems.append(f"{name}:{number}: cd without a guard; write cd {target} || exit 1")
    return problems


def uncommented(line: str) -> str:
    for piece in SHELL.finditer(line):
        if piece.group("comment") is not None:
            return line[:piece.start()]
    return line


def unquoted(line: str, literal: frozenset[int] = frozenset()) -> str:
    masked, stack, i = list(line), [], 0
    while i < len(line):
        c, top = line[i], stack[-1] if stack else None
        if top and top[0] == "'":
            if c == "'":
                stack.pop()
            masked[i] = "_"
        elif c == "\\":
            masked[i:i + 2] = "_" * len(line[i:i + 2])
            i += 1
        elif top and top[0] == '"':
            if line.startswith("$(", i):
                stack.append(["(", i, 0])
                i += 1
            elif c == "`":
                stack.append(["`", i])
            else:
                if c == '"':
                    stack.pop()
                masked[i] = "_"
        elif c in "\"'" and i not in literal:
            stack.append([c, i])
            masked[i] = "_"
        elif top and top[0] == "`" and c == "`":
            stack.pop()
            masked[i] = ")"
        elif c == "`" and i not in literal:
            stack.append(["`", i])
        elif top and top[0] == "(" and c in "()":
            top[2] += 1 if c == "(" else -1
            if top[2] < 0:
                stack.pop()
        i += 1
    # An unclosed quote stays a plain character, as SHELL treats it in uncommented().
    return unquoted(line, literal | {stack[0][1]}) if stack else "".join(masked)


def substitution_end(line: str, position: int) -> int:
    stack, i = [], 0
    while i < len(line):
        c, top = line[i], stack[-1][0] if stack else None
        if top == "'":
            if c == "'":
                stack.pop()
        elif c == "\\":
            i += 1
        elif top == '"' and c == '"':
            stack.pop()
        elif line.startswith("$(", i):
            stack.append(["(", i])
            i += 1
        elif c == "(" and top != '"':
            stack.append(["(", i])
        elif (c == ")" and top == "(") or (c == "`" and top == "`"):
            if stack.pop()[1] < position <= i:
                return i
        elif c == "`" or (c in "\"'" and top != '"'):
            stack.append([c, i])
        i += 1
    return len(line)


def nesting(text: str, literal: frozenset[int] = frozenset()) -> list[int]:
    levels, stack, depth, i = [0] * len(text), [], 0, 0
    while i < len(text):
        c, top = text[i], stack[-1][0] if stack else None
        levels[i:i + 2] = [depth] * len(text[i:i + 2])
        if top == "'":
            if c == "'":
                stack.pop()
        elif c == "\\":
            i += 1
        elif top == '"' and c == '"':
            stack.pop()
        elif text.startswith("$(", i) or (c == "(" and top != '"'):
            i += c == "$"
            if i not in literal:
                stack.append(("(", i))
                depth += 1
        elif (c == ")" and top == "(") or (c == "`" and top == "`"):
            stack.pop()
            depth -= 1
            levels[i] = depth
        elif (c == "`" or (c in "\"'" and top != '"')) and i not in literal:
            stack.append((c, i))
            depth += c == "`"
        i += 1
    # An unclosed quote, backtick or parenthesis stays a plain character, as in unquoted().
    return nesting(text, literal | {position for _, position in stack}) if stack else levels


def join_substitutions(text: str) -> str:
    return "".join("\0" if c.isspace() and level else c for c, level in zip(text, nesting(text)))


def views(masked: str, levels: list[int]) -> list[tuple[str, list[int], int]]:
    """The top level and the inside of each substitution, each as text in which a substitution inside it is "_", with
    the position in the line of each character and of the end, and the index where the inside starts (after the
    opening "(" or backtick, kept for the lookbehinds)."""
    ends, runs = {}, []
    for i, level in enumerate(levels + [0]):
        while runs and level < levels[runs[-1]]:
            ends[runs.pop()] = i
        if i < len(levels) and level > (levels[i - 1] if i else 0):
            runs.append(i)
    result, spans = [], [(0, len(masked), 0)]
    while spans:
        start, stop, depth = spans.pop()
        text, positions, i = [masked[start - 1:start]], [start - 1] if start else [], start
        while i < stop:
            positions.append(i)
            if levels[i] > depth:
                text.append("_")
                spans.append((i, ends[i], depth + 1))
                i = ends[i]
            else:
                text.append(masked[i])
                i += 1
        result.append(("".join(text), positions + [stop], len(text[0])))
    return result


def parts(view: str, start: int) -> list[tuple[int, int]]:
    found = []
    for separator in SEPARATOR.finditer(view, start):
        found.append((start, separator.start()))
        start = separator.end()
    return found + [(start, len(view))]


def takes_the_next_word(prefix: str) -> bool:
    prefix = re.split(r"[(`]", re.sub(SUBSTITUTION, "_", prefix))[-1]
    words = prefix.split()
    if words and not prefix[-1].isspace():
        words.pop()
    wrapper, value = None, False
    for word in words:
        if value:
            value = False
        elif word in WRAPPER_VALUE_OPTIONS:
            wrapper = word
        elif not word.startswith("-") or wrapper is None:
            wrapper = None
        elif word.startswith("--"):
            value = word in WRAPPER_VALUE_OPTIONS[wrapper]
        else:
            for end, letter in enumerate(word[1:], 2):
                if "-" + letter in WRAPPER_VALUE_OPTIONS[wrapper]:
                    value = end == len(word)
                    break
    return value


def commands(line: str) -> list[tuple[int, str]]:
    view, positions, _ = views(unquoted(line), nesting(line))[0]
    return [(positions[start], line[positions[start]:positions[stop]]) for start, stop in parts(view, 0)]


def logical_lines(text: str):
    start, parts = 0, []
    for number, line in enumerate(text.splitlines(), 1):
        start = start or number
        parts.append(line.removesuffix("\\"))
        if not line.endswith("\\"):
            yield start, " ".join(parts)
            start, parts = 0, []
    if parts:
        yield start, " ".join(parts)


def first_operand(words: list[str]) -> tuple[str, str]:
    rest = iter(words)
    for word in rest:
        if not word.startswith("-"):
            return word, ""
        if word.startswith("--"):
            option, attached, _ = word.partition("=")
            if option in VALUE_OPTIONS and not attached:
                next(rest, None)
            elif option not in VALUE_OPTIONS | FLAG_OPTIONS:
                return "", option
            continue
        for end, letter in enumerate(word[1:], 2):
            if "-" + letter in VALUE_OPTIONS:
                if end == len(word):
                    next(rest, None)
                break
            if "-" + letter not in FLAG_OPTIONS:
                return "", "-" + letter
    return "", ""


def image_problems(root: pathlib.Path, name: str) -> list[str]:
    text = (root / name).read_text(encoding="utf-8")
    dockerfile = pathlib.PurePosixPath(name).name.startswith("Dockerfile")
    problems = []
    for number, line in logical_lines(text):
        line = line.strip()
        if line.startswith("#"):
            continue
        if dockerfile:
            instruction, _, rest = line.partition(" ")
            run_line = instruction.upper() == "RUN"
            references = [first_operand(rest.split())] if instruction.upper() == "FROM" else []
            if run_line and "apt-get update" in rest and "apt-get install" not in rest:
                problems.append(f"{name}:{number}: apt-get update: no apt-get install in the same RUN; "
                                "run update and install in one RUN")
            if run_line and "--force-yes" in rest:
                problems.append(f"{name}:{number}: apt-get install: --force-yes is deprecated; "
                                "write apt-get install -y --no-install-recommends")
            if run_line and "apt-get install" in rest and "/var/lib/apt/lists" not in rest:
                problems.append(f"{name}:{number}: apt-get install: the package lists stay in the image; "
                                "end the RUN with rm -rf /var/lib/apt/lists/*")
        else:
            references = []
            line = uncommented(line)
            installs = {}
            for view, positions, inside in views(unquoted(line), nesting(line)):
                for start, stop in parts(view, inside):
                    position = start
                    while install := INSTALL.search(view, position, stop):
                        installs.setdefault(positions[install.start("tool")], (install, view, positions, stop))
                        position = install.start() + 1
            for _, (install, view, positions, end) in sorted(installs.items()):
                if takes_the_next_word(view[install.start():install.start("tool")]):
                    continue
                begin = install.end()
                if install.group("word") is not None:
                    first, last = install.span("word")
                    word = line[positions[first]:positions[last]]
                    subcommand = SUBCOMMAND.match(view, first, end)
                    if not subcommand:
                        if word.startswith("-"):
                            joined = unquoted(join_substitutions(line[positions[first]:positions[end]]))
                            if word not in DOCKER_INFO and OPTIONS_BEFORE_SUBCOMMAND.match(joined):
                                problems.append(f"{name}:{number}: {word}: unknown docker option before the "
                                                "subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py")
                        elif not LITERAL.fullmatch(word):
                            problems.append(f"{name}:{number}: {word}: the check cannot read a docker command whose "
                                            "subcommand is not a literal word; write the docker options and the "
                                            "subcommand literally")
                        continue
                    begin = subcommand.end()
                offset = positions[begin]
                arguments = list(line[offset:positions[end]])
                for piece in ARGUMENT_PIECE.finditer(view, begin, end):
                    if piece.group("redirection") is not None:
                        blank = slice(positions[piece.start()] - offset, positions[piece.end()] - offset)
                        arguments[blank] = " " * (blank.stop - blank.start)
                try:
                    words = shlex.split(join_substitutions("".join(arguments)))
                    references.append(first_operand([word.replace("\0", " ") for word in words]))
                except ValueError as error:
                    tool = line[positions[install.start("tool")]:offset].strip()
                    problems.append(f"{name}:{number}: {tool}: cannot split the arguments into words ({error}); "
                                    "close every quote on the line")
        for reference, option in references:
            if option:
                problems.append(f"{name}:{number}: {option}: unknown option before the image; "
                                "add it to the option lists of tools/test_lg_scripts.py")
            elif not PINNED.search(reference):
                problems.append(f"{name}:{number}: {reference}: no exact version; "
                                "pin a version such as 1.2.3 or a sha256 digest")
    return problems


def run(script: pathlib.Path, tmp_path: pathlib.Path, tools: tuple[str, ...], **variables: str):
    fake = tmp_path / "fake"
    fake.mkdir()
    calls = tmp_path / "calls"
    calls.touch()
    stdin = tmp_path / "stdin"
    stdin.touch()
    for tool in tools:
        (fake / tool).write_text(f'#!/bin/bash\necho "{tool} $*" >> "{calls}"\ncat >> "{stdin}"\nexit 0\n',
                                 encoding="utf-8")
        (fake / tool).chmod(0o755)
    environment = {k: v for k, v in os.environ.items() if k != "GRAFANA_ADMIN_PASSWORD"}
    environment.update(variables, PATH=f"{fake}{os.pathsep}{os.environ['PATH']}")
    result = subprocess.run(["bash", str(script)], cwd=fake, stdin=subprocess.DEVNULL, capture_output=True,
                            text=True, env=environment, check=False)
    return result, calls.read_text(encoding="utf-8").splitlines(), stdin.read_text(encoding="utf-8")


def test_the_grafana_script_refuses_to_run_without_the_admin_password(tmp_path):
    result, calls, _ = run(GRAFANA, tmp_path, ("docker", "grafana-cli"))
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr.splitlines() == [
        "GRAFANA_ADMIN_PASSWORD: missing; export the Grafana admin password to set before you run the script"]
    assert calls == []


def test_the_grafana_script_passes_the_password_on_standard_input_inside_the_named_container(tmp_path):
    result, calls, stdin = run(GRAFANA, tmp_path, ("docker", "grafana-cli"), GRAFANA_ADMIN_PASSWORD=GRAFANA_PASSWORD)
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls[0].startswith(f"docker run -d --name {GRAFANA_CONTAINER} ")
    assert [c for c in calls if c.startswith("docker exec")] == [
        f"docker exec -i {GRAFANA_CONTAINER} grafana-cli admin reset-admin-password --password-from-stdin"]
    assert stdin == GRAFANA_PASSWORD + "\n"
    assert [c for c in calls if GRAFANA_PASSWORD in c] == []
    assert not [c for c in calls if c.startswith("grafana-cli")]


def test_the_readme_names_only_scripts_that_exist():
    text = (LG / "README.md").read_text(encoding="utf-8")
    names = {name.removeprefix("./") for name in re.findall(r"[\w./-]+\.sh\b", text)}
    scripts = [p.relative_to(LG).as_posix() for p in LG.rglob("*.sh") if "target" not in p.parts]
    dangling = sorted(n for n in names if not any(s == n or s.endswith("/" + n) for s in scripts))
    assert names
    assert not dangling, dangling


def ci_script_lists() -> dict[str, list[str]]:
    text = CI.read_text(encoding="utf-8")
    syntax = re.search(r'^\s*for f in (.+?); do bash -n "\$f"; done$', text, re.M)
    lint = re.search(r"^\s*shellcheck -x -P SCRIPTDIR (.+)$", text, re.M)
    assert syntax and lint
    return {"bash -n": syntax.group(1).split(), "shellcheck": lint.group(1).split()}


def expand(pattern: str, files: list[str]) -> list[str]:
    parts = len(pathlib.PurePosixPath(pattern).parts)
    return [f for f in files
            if len(pathlib.PurePosixPath(f).parts) == parts and pathlib.PurePosixPath(f).match(pattern)]


def test_a_script_without_a_shebang_or_with_a_plain_cd_fails_the_check(tmp_path):
    (tmp_path / "opentsx-lg").mkdir()
    (tmp_path / "opentsx-lg" / "run.sh").write_text(
        "set -e\ncd ..\n(cd lib && ls)\ncd bin || exit 1\n"
        'echo "step #2"; cd out\ncommand cd out\nbuiltin cd out\n! cd out\ntime cd out\n'
        "# cd old\ncd x || exit 1 # note\nif true; then cd x || exit 1; fi\n", encoding="utf-8")
    assert script_problems(tmp_path, "opentsx-lg/run.sh") == [
        "opentsx-lg/run.sh: no shebang; start the script with #!/usr/bin/env bash",
        "opentsx-lg/run.sh:2: cd without a guard; write cd .. || exit 1",
        "opentsx-lg/run.sh:3: cd without a guard; write cd lib || exit 1",
        "opentsx-lg/run.sh:5: cd without a guard; write cd out || exit 1",
        "opentsx-lg/run.sh:6: cd without a guard; write cd out || exit 1",
        "opentsx-lg/run.sh:7: cd without a guard; write cd out || exit 1",
        "opentsx-lg/run.sh:8: cd without a guard; write cd out || exit 1",
        "opentsx-lg/run.sh:9: cd without a guard; write cd out || exit 1",
    ]


def test_a_script_with_a_shebang_and_guarded_cds_passes_the_check(tmp_path):
    (tmp_path / "run.sh").write_text('#!/usr/bin/env bash\ncd .. || exit 1\ncd "$(dirname "$0")" || return\n'
                                     "# cd old\nif true; then cd x || exit 1; fi\nabcd ..\n", encoding="utf-8")
    assert script_problems(tmp_path, "run.sh") == []


def test_a_cd_inside_quotes_is_no_cd_for_the_check(tmp_path):
    (tmp_path / "run.sh").write_text(
        '#!/usr/bin/env bash\necho "a; cd b"\necho \'x && cd y\'\ncd "a;b" || exit 1\necho "a; cd b"; cd c\n'
        'cd "$DIR;x" && ls\n', encoding="utf-8")
    assert script_problems(tmp_path, "run.sh") == [
        "run.sh:5: cd without a guard; write cd c || exit 1",
        'run.sh:6: cd without a guard; write cd "$DIR;x" || exit 1',
    ]


def test_the_ampersand_of_a_redirection_does_not_end_a_cd(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\ncd x 2>&1 || exit 1\ncd y &>/dev/null || exit 1\ncd z 2>&1\ncd w >&2 & wait\n",
        encoding="utf-8")
    assert script_problems(tmp_path, "run.sh") == [
        "run.sh:4: cd without a guard; write cd z 2>&1 || exit 1",
        "run.sh:5: cd without a guard; write cd w >&2 || exit 1",
    ]


def test_a_cd_or_an_install_in_a_command_substitution_is_read_up_to_the_end_of_the_substitution(tmp_path):
    (tmp_path / "run.sh").write_text(
        '#!/usr/bin/env bash\nX="$(cd /tmp)"\nCID="$(docker run -d toke/mosquitto:latest)"\n'
        'ID="`docker pull toke/mosquitto`"\nCID=$(docker run img:1.2.3)\n"$(docker pull img:1.2.3)"\n'
        '"$(docker pull img)"\n(docker pull img:1.2.3)\nID=`docker pull img:1.2.3`\n'
        'CID="$(docker run -e "A=(x)" img:1.2.3)"\nX="$(docker pull img:1.2.3; docker pull toke/mosquitto)"\n'
        'X="$(docker run -e "A=1;2" img:1.2.3 && echo ";")"\n'
        'echo "$(docker pull img:1.2.3)" "$(docker pull toke/mosquitto)"\n'
        'echo $(docker pull img:1.2.3) $(docker pull toke/mosquitto)\n'
        'echo `docker pull img:1.2.3` `docker pull toke/mosquitto`\n', encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert script_problems(tmp_path, "run.sh") == ["run.sh:2: cd without a guard; write cd /tmp || exit 1"]
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:3: toke/mosquitto:latest: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:7: img: {rest}",
        f"run.sh:11: toke/mosquitto: {rest}",
        f"run.sh:13: toke/mosquitto: {rest}",
        f"run.sh:14: toke/mosquitto: {rest}",
        f"run.sh:15: toke/mosquitto: {rest}",
    ]


def test_a_guard_after_nested_quotes_in_a_command_substitution_counts(tmp_path):
    (tmp_path / "run.sh").write_text(
        '#!/usr/bin/env bash\nDIR="$(cd "$(dirname "$0")" || exit 1; pwd)"\necho "a; cd b $(pwd)"\n'
        'Y="$(cd "$1")"\n', encoding="utf-8")
    assert script_problems(tmp_path, "run.sh") == ['run.sh:4: cd without a guard; write cd "$1" || exit 1']


def test_every_lg_script_starts_with_a_shebang_and_guards_each_cd():
    scripts = tracked("opentsx-lg/*.sh")
    assert scripts
    assert [p for s in scripts for p in script_problems(ROOT, s)] == []


def test_ci_runs_bash_n_and_shellcheck_on_every_lg_script():
    files = tracked(".")
    scripts = tracked("opentsx-lg/*.sh")
    for command, words in ci_script_lists().items():
        matched = {word: expand(word, files) for word in words}
        assert [w for w, m in matched.items() if not m] == [], command
        covered = {f for m in matched.values() for f in m}
        assert [s for s in scripts if s not in covered] == [], command


def test_an_image_or_connector_without_an_exact_version_fails_the_check(tmp_path):
    digest = "@sha256:" + "0" * 64
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        'docker run -d --name grafana -p 3003:3003 -v "$WP/a:/a" philhawthorne/docker-influxdb-grafana:latest\n'
        "docker run --name mqtt --restart=always -tid \\\n -p 1883:1883 \\\n toke/mosquitto\n"
        "confluent-hub install confluentinc/kafka-connect-mqtt:latest\n"
        "# docker run unpinned/in-a-comment\n"
        f"docker run --rm -e A=1 toke/mosquitto{digest} mosquitto -v\n"
        "confluent-hub install --no-prompt --component-dir /plugins confluentinc/kafka-connect-influxdb:1.2.3\n"
        "docker pull eclipse-temurin:21.0.8_9-jre-noble\n"
        "docker run --add-host h:127.0.0.1 toke/mosquitto\n"
        "docker container run -d toke/mosquitto\n"
        "docker image pull toke/mosquitto\n"
        "docker pull a:1.0.0 && docker pull toke/mosquitto\n"
        "confluent connect plugin install confluentinc/kafka-connect-mqtt:latest\n"
        "docker run --cpus 2 toke/mosquitto\n"
        "docker run -e 'A=x && y' -tid toke/mosquitto:1.2.3 # && docker pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: philhawthorne/docker-influxdb-grafana:latest: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:6: confluentinc/kafka-connect-mqtt:latest: {rest}",
        f"run.sh:11: toke/mosquitto: {rest}",
        f"run.sh:12: toke/mosquitto: {rest}",
        f"run.sh:13: toke/mosquitto: {rest}",
        f"run.sh:14: toke/mosquitto: {rest}",
        f"run.sh:15: confluentinc/kafka-connect-mqtt:latest: {rest}",
        "run.sh:16: --cpus: unknown option before the image; add it to the option lists of tools/test_lg_scripts.py",
    ]


def test_a_command_after_a_single_ampersand_or_after_docker_global_options_is_read(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker pull a:1.0.0 & docker pull toke/mosquitto\n"
        "docker --context x pull toke/mosquitto\n"
        "docker -H unix:///tmp/docker.sock --config /tmp/c run -d toke/mosquitto\n"
        "docker --tls --context=x -D -l debug container create toke/mosquitto\n"
        "docker pull a:1.0.0 2>&1 | tee pull.log\n"
        "docker pull a:1.0.0 &>pull.log\n"
        "docker run -d a:1.0.0 >&2 &\n"
        "docker --foo pull toke/mosquitto\n"
        "docker --version && docker -H x ps && docker -v\n"
        "docker --tlsverify=true pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    unknown = "unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:9: --foo: {unknown}",
        f"run.sh:11: --tlsverify=true: {unknown}",
    ]
    split = {line: [c for _, c in commands(line)] for line in
             ["docker pull a:1.0.0 2>&1 | tee pull.log", "docker pull a:1.0.0 &>pull.log & wait", "cat <&3 >&- &>>log"]}
    assert split == {"docker pull a:1.0.0 2>&1 | tee pull.log": ["docker pull a:1.0.0 2>&1 ", " tee pull.log"],
                     "docker pull a:1.0.0 &>pull.log & wait": ["docker pull a:1.0.0 &>pull.log ", " wait"],
                     "cat <&3 >&- &>>log": ["cat <&3 >&- &>>log"]}


def test_an_unknown_docker_option_is_a_finding_only_when_a_subcommand_it_reads_follows_it(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker -x foo; echo pull\n"
        "docker -x ps && docker run img:1.2.3\n"
        "docker -x foo bar pull img:1.2.3\n"
        "docker --x=1 ps pull img:1.2.3\n"
        "docker --foo pull img:1.2.3\n"
        "docker --foo bar pull img:1.2.3\n"
        'docker --label="a b" pull img:1.2.3\n'
        "docker -x --context c pull img:1.2.3\n", encoding="utf-8")
    unknown = "unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:6: --foo: {unknown}",
        f"run.sh:7: --foo: {unknown}",
        f'run.sh:8: --label="a b": {unknown}',
        f"run.sh:9: -x: {unknown}",
    ]


def test_a_substitution_is_one_word_before_the_docker_subcommand_and_as_the_duration_of_timeout(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker --newopt $(cat v) pull toke/mosquitto\n"
        'docker --foo "$(echo a b)" pull toke/mosquitto\n'
        "docker --foo `date +%s` pull toke/mosquitto\n"
        "nice -n 5 docker --foo $(date +%s) run toke/mosquitto\n"
        "docker --config $(mktemp -d) pull toke/mosquitto\n"
        "timeout $(cat t) docker pull toke/mosquitto\n"
        "timeout -k 5 -s KILL $(cat t) docker pull toke/mosquitto\n"
        'timeout "$(cat t)" docker pull toke/mosquitto\n'
        'timeout -k 5 "`cat t`" docker pull toke/mosquitto\n'
        "$(cat t) docker pull x\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    unknown = "unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: --newopt: {unknown}",
        f"run.sh:3: --foo: {unknown}",
        f"run.sh:4: --foo: {unknown}",
        f"run.sh:5: --foo: {unknown}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
        f"run.sh:10: toke/mosquitto: {rest}",
    ]


def test_a_redirection_is_no_part_of_the_image(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker pull img:1.2.3>out\n"
        "docker pull toke/mosquitto>out\n"
        "docker run >log -d img:1.2.3\n"
        "docker run 2>/dev/null -d img:1.2.3 >&2\n"
        'docker run -e "A=1>2" img:1.2.3<<<x\n', encoding="utf-8")
    assert image_problems(tmp_path, "run.sh") == [
        "run.sh:3: toke/mosquitto: no exact version; pin a version such as 1.2.3 or a sha256 digest"]


def test_a_redirection_right_after_the_subcommand_ends_it_and_is_no_part_of_the_image(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker pull>/dev/null toke/mosquitto\n"
        "docker run>cid.txt -d toke/mosquitto\n"
        "docker run</dev/null -d toke/mosquitto\n"
        "docker pull&>/dev/null toke/mosquitto\n"
        "docker --context x pull>/dev/null toke/mosquitto\n"
        "docker info>/dev/null\n"
        "docker pull>/dev/null img:1.2.3\n"
        "docker --foo pull>/dev/null img:1.2.3\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    unknown = "unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:9: --foo: {unknown}",
    ]


def test_a_substitution_may_hold_further_substitutions(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "X=`echo $(date)` docker pull toke/mosquitto\n"
        "sudo -u `basename $(pwd)` docker pull toke/mosquitto\n"
        'X=`cat "$(dirname $0)/v"` docker pull toke/mosquitto\n'
        "$(echo `date`) docker pull x\n"
        "docker --foo $(echo $(id -u)) pull toke/mosquitto\n"
        "docker --context $(cat $(echo f)) pull toke/mosquitto\n"
        "X=$(echo $(date)) docker pull toke/mosquitto\n"
        "sudo -u $(basename $(pwd)) docker pull toke/mosquitto\n"
        "timeout $(echo $(cat t)) docker pull toke/mosquitto\n"
        "echo $(echo $(date)) docker pull x\n"
        "X=$(echo `date`) docker pull toke/mosquitto\n"
        "X=$(a $(b $(c))) docker pull toke/mosquitto\n"
        "$(a $(b `c`)) docker pull x\n"
        "X=$(a $(docker pull e) $(b `docker pull f`)) docker pull img:1.2.3\n"
        "echo $(a $(b $(c | $(docker pull g; echo))))\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    unknown = "unknown docker option before the subcommand; add it to DOCKER_GLOBAL in tools/test_lg_scripts.py"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:6: --foo: {unknown}",
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
        f"run.sh:10: toke/mosquitto: {rest}",
        f"run.sh:12: toke/mosquitto: {rest}",
        f"run.sh:13: toke/mosquitto: {rest}",
        f"run.sh:15: e: {rest}",
        f"run.sh:15: f: {rest}",
        f"run.sh:16: g: {rest}",
    ]


def test_a_separator_inside_a_substitution_does_not_end_the_command_around_it(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "X=$(cat f | head -1) docker pull toke/mosquitto\n"
        "sudo -u $(getent passwd 1000 | cut -d: -f1) docker pull toke/mosquitto\n"
        "X=$(cat f; echo) docker pull toke/mosquitto\n"
        'X="$(cat f && echo)" docker pull toke/mosquitto\n'
        "X=$(docker pull a | tee x) docker pull b\n"
        "X=$(docker pull c; docker pull d) docker pull img:1.2.3\n"
        "X=$(echo a; docker pull e | tee x; echo b) && docker pull f\n"
        "X=$(a;b docker pull x) docker pull img:1.2.3\n"
        "X=`cat f | head -1` docker pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: a: {rest}",
        f"run.sh:6: b: {rest}",
        f"run.sh:7: c: {rest}",
        f"run.sh:7: d: {rest}",
        f"run.sh:8: e: {rest}",
        f"run.sh:8: f: {rest}",
        f"run.sh:10: toke/mosquitto: {rest}",
    ]
    assert [c for _, c in commands("X=$(cat f | head -1) docker pull a && b; c")] == [
        "X=$(cat f | head -1) docker pull a ", " b", " c"]


def test_a_variable_or_a_substitution_with_a_unit_is_the_duration_of_timeout(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "timeout $T docker pull toke/mosquitto\n"
        "timeout ${T} docker pull toke/mosquitto\n"
        'timeout "${T}" docker pull toke/mosquitto\n'
        'timeout "$T" docker pull toke/mosquitto\n'
        "timeout $(cat t)s docker pull toke/mosquitto\n"
        "timeout -k 5 ${T}m docker pull toke/mosquitto\n"
        "$T docker pull x\n"
        "echo $T docker pull x\n"
        "timeout ${T:-30} docker pull toke/mosquitto\n"
        "timeout $1 docker pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:10: toke/mosquitto: {rest}",
        f"run.sh:11: toke/mosquitto: {rest}",
    ]


def test_a_clobber_redirection_is_no_separator(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker pull img:1.2.3 >| out\n"
        "docker pull >| out toke/mosquitto\n"
        "docker pull>|out toke/mosquitto\n"
        "echo >|docker pull x\n"
        "echo >| docker pull x\n", encoding="utf-8")
    assert image_problems(tmp_path, "run.sh") == [
        "run.sh:3: toke/mosquitto: no exact version; pin a version such as 1.2.3 or a sha256 digest",
        "run.sh:4: toke/mosquitto: no exact version; pin a version such as 1.2.3 or a sha256 digest",
    ]
    assert [c for _, c in commands("docker pull img >| out")] == ["docker pull img >| out"]
    (tmp_path / "cd.sh").write_text("#!/usr/bin/env bash\ncd x >| out || exit 1\ncd y >| out\necho a >|cd z\n",
                                    encoding="utf-8")
    assert script_problems(tmp_path, "cd.sh") == ["cd.sh:3: cd without a guard; write cd y >| out || exit 1"]


def test_option_and_number_words_without_a_command_word_are_read_in_linear_time(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\nnice " + "-n 5 " * 22 + "foo\nsudo " + "-u env " * 22 + "foo\n"
        + "timeout -k $(cat t) " * 22 + "foo\n" + "timeout -k $T " * 22 + "foo\n" + "X=$(a | b; c && d) " * 22
        + "foo\n" + "$(a " * 22 + "foo\n" + "timeout $Ts " * 22 + "foo\n" + "timeout -k $T -s $T " * 22 + "foo\n"
        + 'timeout -k "a" -s "b" ' * 22 + "foo\n", encoding="utf-8")
    start = time.perf_counter()
    assert image_problems(tmp_path, "run.sh") == []
    assert time.perf_counter() - start < 0.1


def test_a_docker_subcommand_that_is_not_a_literal_word_is_a_finding(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker $OPTS pull toke/mosquitto\n"
        'docker --context x "$CMD" run -d img:1.2.3\n'
        'docker "pull" toke/mosquitto\n'
        "docker network create net1\n"
        'docker exec "$C" ls\n'
        "echo $(docker ps -q) $(docker pull img:1.2.3)\n", encoding="utf-8")
    rest = ("the check cannot read a docker command whose subcommand is not a literal word; "
            "write the docker options and the subcommand literally")
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: $OPTS: {rest}",
        f'run.sh:3: "$CMD": {rest}',
        f'run.sh:4: "pull": {rest}',
    ]


def test_a_literal_docker_subcommand_ends_at_a_shell_metacharacter(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        '[ "$(docker info)" ]\n'
        'echo "$(docker ps)"\n'
        "IDS=`docker ps`\n"
        "(docker version)\n"
        "while ! docker info>/dev/null 2>&1; do sleep 1; done\n"
        "X=$(docker pull toke/mosquitto)\n"
        "docker ${CMD} run img:1.2.3\n"
        "docker $(echo pull) img:1.2.3\n"
        "docker --context $(cat c) pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    literal = ("the check cannot read a docker command whose subcommand is not a literal word; "
               "write the docker options and the subcommand literally")
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:8: ${{CMD}}: {literal}",
        f"run.sh:9: $(echo pull): {literal}",
        f"run.sh:10: toke/mosquitto: {rest}",
    ]


def test_an_install_in_a_substitution_inside_an_argument_of_another_install_is_read(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        'docker run -e "X=$(docker pull toke/mosquitto)" img:1.2.3\n'
        'docker run -e "X=`docker pull toke/mosquitto`" -d img:1.2.3\n'
        'docker run -e "X=$(confluent-hub install a/b:latest)" img:1.2.3\n'
        'docker run -e "X=$(docker pull img:1.2.3)" toke/mosquitto\n', encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: a/b:latest: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
    ]


def test_only_a_docker_or_confluent_command_word_is_read(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "echo docker pull x\n"
        "echo run confluent-hub install a/b and docker run x\n"
        "printf '%s\\n' docker pull x\n"
        "sudo -E docker pull toke/mosquitto\n"
        "IMG=x docker pull toke/mosquitto\n"
        "if docker pull toke/mosquitto; then echo ok; fi\n"
        "{ docker pull toke/mosquitto; }\n"
        "! time docker pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
    ]


def test_a_check_that_docker_is_installed_is_no_docker_command(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "command -v docker >/dev/null 2>&1 || exit 1\n"
        "if ! command -v docker &>/dev/null; then exit 1; fi\n"
        "command -V docker > /dev/null\n"
        "command -pv docker >/dev/null\n", encoding="utf-8")
    assert image_problems(tmp_path, "run.sh") == []


def test_a_docker_command_behind_a_wrapper_with_option_values_is_read(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "sudo -u bob docker pull toke/mosquitto\n"
        "timeout 10 docker pull toke/mosquitto\n"
        "nice -n 5 docker pull toke/mosquitto\n"
        "xargs docker pull toke/mosquitto\n"
        "command -p -v docker >/dev/null\n"
        "command -vp docker >/dev/null\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
    ]


def test_the_split_string_of_env_and_the_max_lines_of_xargs_are_the_command(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "env -S docker pull toke/mosquitto\n"
        "env --split-string docker pull toke/mosquitto\n"
        "sudo -E env -S docker pull toke/mosquitto\n"
        "env -iS docker pull toke/mosquitto\n"
        "xargs --max-lines docker pull toke/mosquitto\n"
        "env -u docker pull x\n"
        "env -C /tmp docker pull toke/mosquitto\n"
        "xargs --max-lines=2 docker pull toke/mosquitto\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
    ]


def test_a_substitution_with_blanks_is_one_word_and_the_value_of_a_wrapper_option_is_no_command_word(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "docker run -e X=$(date +%s) img:1.2.3\n"
        "sudo -u docker pull x\n"
        "docker run -e X=`date +%s` -e Y=$(echo $(id -u) 'a b') img:1.2.3\n"
        "docker run --name a$(date +%s) img:$(cat version file)\n"
        "sudo -Eu docker pull x\n"
        "sudo --user docker pull x\n"
        "xargs -E docker pull x\n"
        "sudo -u docker docker pull toke/mosquitto\n"
        "sudo -ubob docker pull toke/mosquitto\n"
        "sudo --user=bob docker pull toke/mosquitto\n"
        "sudo -D /tmp/ docker pull toke/mosquitto\n"
        "sudo -u bob command -p docker pull toke/mosquitto\n"
        'X="$(sudo -u docker pull x)" Y=`sudo -u docker pull x`\n'
        "echo $(sudo -u bob docker pull toke/mosquitto)\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:5: img:$(cat version file): {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
        f"run.sh:10: toke/mosquitto: {rest}",
        f"run.sh:11: toke/mosquitto: {rest}",
        f"run.sh:12: toke/mosquitto: {rest}",
        f"run.sh:13: toke/mosquitto: {rest}",
        f"run.sh:15: toke/mosquitto: {rest}",
    ]


def test_an_install_in_a_case_arm_or_with_a_path_is_read(tmp_path):
    (tmp_path / "run.sh").write_text(
        '#!/usr/bin/env bash\n'
        'case "$1" in\n'
        '  start) docker run toke/mosquitto ;;\n'
        '  "pull"|stop) docker pull toke/mosquitto ;;\n'
        '  (up) docker pull toke/mosquitto ;;\n'
        '  *) docker pull toke/mosquitto ;;\n'
        'esac\n'
        'case "$1" in start) docker pull toke/mosquitto ;; esac\n'
        '/usr/bin/docker pull toke/mosquitto\n'
        'sudo /usr/local/bin/confluent-hub install a/b:latest\n'
        'echo $(date) docker pull x\n'
        'echo $( date) docker pull x\n'
        'echo /usr/bin/docker pull x\n'
        'for word in docker pull x; do echo "$word"; done\n', encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:9: toke/mosquitto: {rest}",
        f"run.sh:10: a/b:latest: {rest}",
    ]


def test_a_prefix_value_may_hold_a_command_substitution_and_a_closing_backtick_starts_no_command(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "X=$(cat f) docker pull toke/mosquitto\n"
        "env X=$(cat f) docker pull toke/mosquitto\n"
        "sudo -u $(whoami) docker pull toke/mosquitto\n"
        "X=$(docker pull a) docker pull b\n"
        "sudo --user=$(echo a b) docker pull toke/mosquitto\n"
        "X=`cat f` docker pull toke/mosquitto\n"
        "X=$((1+2)) docker pull toke/mosquitto\n"
        "echo `date` docker pull x\n"
        "echo $(cat f) docker pull x\n"
        "X=$(cat f) echo docker pull x\n"
        'log "step" `whoami` docker pull x\n'
        "sudo -u $(whoami) -g docker pull x\n"
        'case "$1" in `hostname`) docker pull toke/mosquitto ;; esac\n'
        "`date` docker pull x\n", encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:2: toke/mosquitto: {rest}",
        f"run.sh:3: toke/mosquitto: {rest}",
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: a: {rest}",
        f"run.sh:5: b: {rest}",
        f"run.sh:6: toke/mosquitto: {rest}",
        f"run.sh:7: toke/mosquitto: {rest}",
        f"run.sh:8: toke/mosquitto: {rest}",
        f"run.sh:14: toke/mosquitto: {rest}",
    ]


def test_an_option_after_the_word_docker_without_an_install_subcommand_is_no_finding(tmp_path):
    (tmp_path / "run.sh").write_text(
        "#!/usr/bin/env bash\n"
        "pip install docker --user\n"
        "echo start && python -m pip install docker -q\n", encoding="utf-8")
    assert image_problems(tmp_path, "run.sh") == []


def test_an_install_inside_quotes_is_no_install_and_an_open_quote_is_a_finding(tmp_path):
    (tmp_path / "run.sh").write_text(
        '#!/usr/bin/env bash\necho "use docker pull x"\necho \'run confluent-hub install a/b\'\n'
        'docker pull toke/mosquitto\necho "x" && docker run -d "toke/mosquitto"\ndocker pull "toke/mosquitto\n',
        encoding="utf-8")
    rest = "no exact version; pin a version such as 1.2.3 or a sha256 digest"
    assert image_problems(tmp_path, "run.sh") == [
        f"run.sh:4: toke/mosquitto: {rest}",
        f"run.sh:5: toke/mosquitto: {rest}",
        "run.sh:6: docker pull: cannot split the arguments into words (No closing quotation); "
        "close every quote on the line",
    ]


def test_a_dockerfile_with_an_unpinned_base_or_apt_get_update_in_its_own_run_fails_the_check(tmp_path):
    (tmp_path / "Dockerfile").write_text(
        "FROM eclipse-temurin:21\nRUN apt-get update\nRUN apt-get install --force-yes -y xvfb \\\n  x11-apps\n",
        encoding="utf-8")
    assert image_problems(tmp_path, "Dockerfile") == [
        "Dockerfile:1: eclipse-temurin:21: no exact version; pin a version such as 1.2.3 or a sha256 digest",
        "Dockerfile:2: apt-get update: no apt-get install in the same RUN; run update and install in one RUN",
        "Dockerfile:3: apt-get install: --force-yes is deprecated; write apt-get install -y --no-install-recommends",
        "Dockerfile:3: apt-get install: the package lists stay in the image; "
        "end the RUN with rm -rf /var/lib/apt/lists/*",
    ]


def test_a_dockerfile_that_installs_in_one_run_passes_the_check(tmp_path):
    (tmp_path / "Dockerfile").write_text(
        "FROM eclipse-temurin:21.0.8_9-jre-noble\n"
        "RUN apt-get update \\\n && apt-get install -y --no-install-recommends xvfb \\\n"
        " && rm -rf /var/lib/apt/lists/*\n", encoding="utf-8")
    assert image_problems(tmp_path, "Dockerfile") == []


def test_every_lg_script_and_dockerfile_pins_its_images_and_connectors():
    files = tracked("opentsx-lg/*.sh") + tracked("opentsx-lg/*Dockerfile")
    assert any(f.endswith("Dockerfile") for f in files)
    assert [p for f in files for p in image_problems(ROOT, f)] == []
