import functools
from pathlib import Path
import re
import unittest
from urllib.parse import unquote


ROOT = Path(__file__).resolve().parents[1]
CONF = ROOT / "opentsx-saas-frontend/nginx.conf"
RETRIEVAL = "http://opentsx-retrieval:8080/"
BACKEND = "http://opentsx-saas-backend:8000"
WRITES = (
    "/retrieval/v1/dags/deploy",
    "/retrieval/v1/dags/stop?name=wind-exp2-smart",
    "/retrieval/v1/dags//deploy",
    "/retrieval/v1//dags/deploy",
    "/retrieval//v1/dags/stop",
    "/retrieval/v1/dags%2Fdeploy",
    "/retrieval/v1/%64ags/deploy",
    "/retrieval/v1/dags/./deploy",
    "/retrieval/v1/x/../dags/deploy",
    "/retrieval/v1/systems/h2-chain/generate",
    "/retrieval/v1/systems/h2-chain/generate?from=2026-10-01T00:00:00Z",
    "/retrieval/v1/systems/h2-chain/generate/",
    "/retrieval/v1/systems/h2-chain/generate/?from=2026-10-01T00:00:00Z",
    "/retrieval/v1/systems/h2-chain/generate//",
    "/retrieval/v1/systems/h2-chain/generate%2F",
    "/retrieval/v1/systems/h2-chain/generate%2f",
    "/retrieval/v1/systems/h2-chain/generate/.",
    "/retrieval/v1/systems/h2-chain/generate/x/..",
    "/retrieval/v1/systems/h2-chain/%67enerate/",
    "/retrieval/v1/systems/h2-chain/./generate/",
    "/retrieval/v1/systems/x/../h2-chain/generate/",
    "/retrieval/v1/systems/h2-chain//generate",
    "/retrieval/v1/systems//h2-chain/generate",
    "/retrieval//v1/systems/h2-chain/generate/",
    "/retrieval/v1/systems/h2-chain%2Fgenerate",
)
OTHERS = (
    "/retrieval/v1/DAGS/deploy",
    "/retrieval/v1/dags/Deploy",
    "/retrieval/V1/dags/deploy",
    "/retrieval/v1/systems/h2-chain/Generate",
    "/retrieval/v1/systems/h2-chain/GENERATE/",
    "/retrieval/V1/systems/h2-chain/generate/",
    "/Retrieval/v1/systems/h2-chain/generate/",
    "/retrieval/v1/systems/h2-chain/generate;x",
    "/retrieval/v1/systems/h2-chain/generate%3F",
    "/retrieval/v1/systems/h2-chain/generate.json",
    "/retrieval/v1/systems/h2-chain/generator",
    "/retrieval/v1/systems/h2-chain/generate/x",
)
READS = {
    "/retrieval/v1/health": "/v1/health",
    "/retrieval/v1/inventory": "/v1/inventory",
    "/retrieval/v1/dags": "/v1/dags",
    "/retrieval/v1/episodes?metric=power": "/v1/episodes",
    "/retrieval/v1/systems": "/v1/systems",
    "/retrieval/v1/systems/h2-chain/signals": "/v1/systems/h2-chain/signals",
    "/retrieval/v1/systems/h2-chain/plan": "/v1/systems/h2-chain/plan",
    "/retrieval/v1/systems/h2-chain/decisions": "/v1/systems/h2-chain/decisions",
    "/retrieval/v1/systems/h2-chain/runs": "/v1/systems/h2-chain/runs",
}


def tokens(text):
    for match in re.finditer(r"#[^\n]*|'[^']*'|\"[^\"]*\"|[{};]|[^\s{};'\"]+", text):
        if not match.group(0).startswith("#"):
            yield match.group(0)


def parse(stream):
    block, words = [], []
    for token in stream:
        if token in (";", "{"):
            block.append((words[0], words[1:], parse(stream) if token == "{" else None))
            words = []
        elif token == "}":
            return block
        else:
            words.append(token[1:-1] if token[0] in "'\"" else token)
    return block


@functools.cache
def locations():
    servers = [body for name, _, body in parse(tokens(CONF.read_text(encoding="utf-8"))) if name == "server"]
    if len(servers) != 1:
        raise ValueError(f"expected one server block in {CONF}")
    result = []
    for name, args, body in servers[0]:
        if name == "location":
            modifier, pattern = (args[0], args[1]) if len(args) == 2 else ("", args[0])
            result.append((modifier, pattern, {directive: values for directive, values, _ in body}))
    return result


def normalize(target):
    parts = unquote(target.partition("?")[0], errors="strict").split("/")
    path = []
    for index, part in enumerate(parts[1:], 1):
        last = index == len(parts) - 1
        if part == "..":
            if not path:
                raise ValueError(f"{target} leaves the root")
            path.pop()
        elif part not in ("", "."):
            path.append(part)
            continue
        if last:
            path.append("")
    return "/" + "/".join(path)


def select(path):
    found = locations()
    for modifier, pattern, directives in found:
        if modifier == "=" and pattern == path:
            return pattern, directives
    prefixes = [(pattern, directives, modifier) for modifier, pattern, directives in found
                if modifier in ("", "^~") and path.startswith(pattern)]
    longest = max(prefixes, key=lambda prefix: len(prefix[0]), default=None)
    if longest is not None and longest[2] == "^~":
        return longest[:2]
    for modifier, pattern, directives in found:
        if (modifier == "~" and re.search(pattern, path)
                or modifier == "~*" and re.search(pattern, path, re.IGNORECASE)):
            return pattern, directives
    if longest is None:
        raise ValueError(f"no location for {path}")
    return longest[:2]


def route(target):
    path = normalize(target)
    pattern, directives = select(path)
    if "return" in directives:
        return int(directives["return"][0]), None
    upstream = directives.get("proxy_pass", ["static"])[0]
    if upstream == RETRIEVAL:
        return upstream, "/" + path[len(pattern):]
    return upstream, path


def retrieval_write(path):
    if path in ("/v1/dags/deploy", "/v1/dags/stop"):
        return True
    if not path.startswith("/v1/systems/"):
        return False
    # Java String.split, as in RetrievalServer.systemsPost, drops trailing empty parts.
    parts = path[len("/v1/systems"):].split("/")
    while parts and parts[-1] == "":
        parts.pop()
    return len(parts) == 3 and parts[2] == "generate"


class SaasFrontendNginxTest(unittest.TestCase):
    def test_every_spelling_of_a_retrieval_write_route_answers_403(self):
        for target in WRITES:
            with self.subTest(target=target):
                self.assertTrue(retrieval_write("/" + normalize(target)[len("/retrieval/"):]))
                self.assertEqual((403, None), route(target))

    def test_no_path_reaches_a_retrieval_write_route(self):
        for target in WRITES + OTHERS + tuple(READS):
            with self.subTest(target=target):
                upstream, path = route(target)
                if upstream == RETRIEVAL:
                    self.assertFalse(retrieval_write(path))

    def test_reads_reach_the_retrieval_server(self):
        for target, path in READS.items():
            with self.subTest(target=target):
                self.assertEqual((RETRIEVAL, path), route(target))

    def test_generators_run_through_the_backend(self):
        self.assertEqual((BACKEND, "/api/v1/systems/h2-chain/generate"), route("/api/v1/systems/h2-chain/generate"))


if __name__ == "__main__":
    unittest.main()
