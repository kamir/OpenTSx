#!/usr/bin/env python3
import argparse
import copy
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/datamodel-2026/testvectors/execution-contracts.v1.json"


def encoded(value):
    return json.dumps(value, ensure_ascii=True, separators=(",", ":"), allow_nan=False)


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def assignment():
    dag = {
        "schema": "opentsx.dag.v2",
        "name": "shared-contract",
        "source": {
            "kind": "kafscale-broker",
            "topics": ["tsx.wind.episodes.v2"],
            "series": ["0000000000000001"],
        },
        "nodes": [
            {"id": "decode", "op": "decode.avro-soe"},
            {"id": "convert", "op": "convert.unit", "in": ["decode"],
             "params": {"factor": 0.001, "unit": "MW"}},
        ],
        "sinks": [{"from": "convert", "topic": "tsx.wind.clean.v2", "schema": "opentsx.episode.v2"}],
    }
    text = encoded(dag)
    return {
        "schema": "opentsx.execution-assignment.v1",
        "deployment": "synthetic-wind",
        "generation": "1",
        "assignment": "convert-p0",
        "attempt": "attempt-1",
        "fence": "1",
        "fragment": "convert",
        "site": "site-a",
        "node": "rust-a",
        "runtime": "rust",
        "catalog": "tsx-catalog/1",
        "artifactDigest": "sha256:" + "11" * 32,
        "specHash": digest(text),
        "sourceDagText": text,
        "fragmentHash": digest(text),
        "fragmentText": text,
        "mode": "bounded",
        "inputs": [{"binding": "wind-source", "topic": "tsx.wind.episodes.v2",
                    "partition": "0", "from": "9007199254740993", "to": "9007199254741000"}],
    }


def vectors():
    cases = []

    def single(name, value, error=None):
        cases.append({"name": name, "kind": "assignment", "input": encoded(value), "error": error})

    def changed(name, mutate, error=None):
        value = assignment()
        mutate(value)
        single(name, value, error)

    def group(name, values, error=None):
        cases.append({"name": name, "kind": "set", "inputs": [encoded(v) for v in values], "error": error})

    single("bounded-rust-large-offset", assignment())
    changed("java-same-contract", lambda v: v.update(runtime="java", node="java-a"))
    changed("continuous-fixed-start", lambda v: (v.update(mode="continuous"), v["inputs"][0].pop("to")))
    changed("bounded-empty-range", lambda v: v["inputs"][0].update(to=v["inputs"][0]["from"]))
    changed("signed-int64-boundary", lambda v: (v.update(generation="9223372036854775807",
                                                        fence="9223372036854775807"),
                                               v["inputs"][0].update(partition="2147483647",
                                                                     **{"from": "9223372036854775807",
                                                                        "to": "9223372036854775807"})))
    changed("underscore-domain", lambda v: v["inputs"][0].update(topic="tsx.wind_farm.episodes.v2"))
    changed("opaque-original-yaml", lambda v: v.update(sourceDagText="name: wind\nunit: µW\n",
                                                        specHash=digest("name: wind\nunit: µW\n")))
    pretty = json.dumps(json.loads(assignment()["fragmentText"]), indent=2) + "\n"
    changed("fragment-whitespace-preserved", lambda v: v.update(fragmentText=pretty, fragmentHash=digest(pretty)))
    changed("unknown-field", lambda v: v.update(shell="forbidden"), "assignment.fields")
    changed("missing-node", lambda v: v.pop("node"), "assignment.fields")
    changed("unknown-version", lambda v: v.update(schema="opentsx.execution-assignment.v99"), "assignment.version")
    changed("invalid-node-id", lambda v: v.update(node="../node"), "assignment.identity")
    changed("numeric-generation", lambda v: v.update(generation=1), "assignment.epoch")
    changed("zero-fence", lambda v: v.update(fence="0"), "assignment.epoch")
    changed("epoch-leading-zero", lambda v: v.update(generation="01"), "assignment.epoch")
    changed("epoch-overflow", lambda v: v.update(fence="9223372036854775808"), "assignment.epoch")
    changed("unknown-runtime", lambda v: v.update(runtime="shell"), "assignment.runtime")
    changed("unknown-catalog", lambda v: v.update(catalog="tsx-catalog/2"), "assignment.runtime")
    changed("invalid-artifact", lambda v: v.update(artifactDigest="latest"), "assignment.runtime")
    changed("source-hash-mismatch", lambda v: v.update(sourceDagText=v["sourceDagText"] + "\n"), "assignment.hash")
    changed("fragment-hash-mismatch", lambda v: v.update(fragmentText=v["fragmentText"] + " "), "assignment.hash")
    changed("uppercase-hash", lambda v: v.update(specHash=v["specHash"].upper()), "assignment.hash")
    changed("unknown-mode", lambda v: v.update(mode="automatic"), "assignment.mode")
    changed("source-text-not-string", lambda v: v.update(sourceDagText={}), "assignment.dag")
    changed("source-text-too-large", lambda v: v.update(sourceDagText="a" * 32769), "assignment.dag")
    bad = '{"source":{},"nodes":[],"sinks":[]}'
    changed("empty-fragment-graph", lambda v: v.update(fragmentText=bad, fragmentHash=digest(bad)), "assignment.dag")
    bad = '{"source":{},"nodes":[{}],"sinks":[{}],"nodes":[{}]}'
    changed("duplicate-fragment-key", lambda v: v.update(fragmentText=bad, fragmentHash=digest(bad)), "assignment.dag")
    changed("empty-inputs", lambda v: v.update(inputs=[]), "assignment.input")
    changed("missing-bounded-end", lambda v: v["inputs"][0].pop("to"), "assignment.input")
    changed("continuous-with-end", lambda v: v.update(mode="continuous"), "assignment.input")
    changed("input-extra-field", lambda v: v["inputs"][0].update(password="forbidden"), "assignment.input")
    changed("invalid-topic", lambda v: v["inputs"][0].update(topic="tsx.-wind.episodes.v2"), "assignment.input")
    changed("numeric-offset-rounding-risk", lambda v: v["inputs"][0].update(**{"from": 9007199254740993}),
            "assignment.offset")
    changed("negative-offset", lambda v: v["inputs"][0].update(**{"from": "-1"}), "assignment.offset")
    changed("offset-leading-zero", lambda v: v["inputs"][0].update(**{"from": "00"}), "assignment.offset")
    changed("offset-overflow", lambda v: v["inputs"][0].update(to="9223372036854775808"), "assignment.offset")
    changed("reversed-range", lambda v: v["inputs"][0].update(to="0"), "assignment.offset")
    changed("partition-overflow", lambda v: v["inputs"][0].update(partition="2147483648"), "assignment.offset")
    changed("duplicate-input-ownership", lambda v: v["inputs"].append(copy.deepcopy(v["inputs"][0])),
            "assignment.ownership")
    cases.append({"name": "duplicate-outer-key", "kind": "assignment",
                  "input": encoded(assignment())[:-1] + ',"node":"rust-b"}', "error": "assignment.json"})
    cases.append({"name": "escaped-duplicate-key", "kind": "assignment",
                  "input": encoded(assignment())[:-1] + ',"n\\u006fde":"rust-b"}', "error": "assignment.json"})
    cases.append({"name": "trailing-document", "kind": "assignment",
                  "input": encoded(assignment()) + "{}", "error": "assignment.json"})
    cases.append({"name": "unpaired-surrogate", "kind": "assignment",
                  "input": encoded(assignment()).replace('"rust-a"', '"\\ud800"'), "error": "assignment.json"})
    cases.append({"name": "document-byte-limit", "kind": "assignment", "input": " " * 65537,
                  "error": "assignment.limit"})
    cases.append({"name": "document-depth-limit", "kind": "assignment",
                  "input": '{"nested":' + "[" * 65 + "0" + "]" * 65 + "}", "error": "assignment.json"})
    cases.append({"name": "ordinary-private-number-key", "kind": "assignment",
                  "input": '{"$serde_json::private::Number":"0"}', "error": "assignment.fields"})
    private_source = '{"source":{"$serde_json::private::Number":"0"},"nodes":[{}],"sinks":[{}]}'
    changed("private-number-key-preserves-object", lambda v: v.update(
        fragmentText=private_source, fragmentHash=digest(private_source)))
    cases.append({"name": "nonfinite-outer-number", "kind": "assignment",
                  "input": encoded(assignment())[:-1] + ',"number":1e999}', "error": "assignment.json"})
    nonfinite_fragment = '{"source":{},"nodes":[{"params":{"number":1e999}}],"sinks":[{}]}'
    changed("nonfinite-fragment-number", lambda v: v.update(
        fragmentText=nonfinite_fragment, fragmentHash=digest(nonfinite_fragment)), "assignment.dag")
    opaque_source = '{"number":1e999}'
    changed("source-text-remains-opaque", lambda v: v.update(
        sourceDagText=opaque_source, specHash=digest(opaque_source)))
    group("empty-deployment", [], "assignment.context")
    one = assignment()
    two = copy.deepcopy(one)
    two.update(assignment="convert-p1", node="rust-b")
    two["inputs"][0]["partition"] = "1"
    group("two-rust-nodes-disjoint-partitions", [one, two])
    three = copy.deepcopy(two)
    three.update(runtime="java", node="java-b")
    group("mixed-java-rust-disjoint-partitions", [one, three])
    two["assignment"] = one["assignment"]
    group("duplicate-assignment-id", [one, two], "assignment.duplicate")
    two["assignment"] = "convert-p1"
    two["inputs"][0]["partition"] = "0"
    group("two-nodes-overlapping-ownership", [one, two], "assignment.ownership")
    two["fragment"] = "independent"
    group("different-fragments-same-partition", [one, two])
    two["generation"] = "2"
    group("mixed-generations", [one, two], "assignment.context")
    two["generation"] = "1"
    two["deployment"] = "another-job"
    group("mixed-deployments", [one, two], "assignment.context")
    two["deployment"] = one["deployment"]
    two.update(sourceDagText=one["sourceDagText"] + "\n", specHash=digest(one["sourceDagText"] + "\n"))
    group("mixed-source-identities", [one, two], "assignment.context")
    two = copy.deepcopy(one)
    two.update(assignment="convert-p1", node="rust-b", fragmentText=pretty, fragmentHash=digest(pretty))
    two["inputs"][0]["partition"] = "1"
    group("inconsistent-fragment-definition", [one, two], "assignment.context")
    for name, text in [("empty-utf8", ""), ("ascii", "OpenTSx"), ("unicode", "µW Ω 😀"),
                       ("line-feed", "a\nb\n"), ("carriage-return-line-feed", "a\r\nb\r\n"),
                       ("decimal-text-above-js-limit", "9007199254740993")]:
        cases.append({"name": name, "kind": "hash", "text": text,
                      "utf8Hex": text.encode("utf-8").hex(), "sha256": digest(text)})
    return {"schema": "opentsx.execution-contract-vectors.v1",
            "description": "Synthetic offline assignment and exact UTF-8 identity cases shared by Java and Rust.",
            "cases": cases}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = (json.dumps(vectors(), indent=2, ensure_ascii=True, allow_nan=False) + "\n").encode("utf-8")
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != content:
            raise SystemExit("execution contract vectors differ from the generator")
    else:
        OUTPUT.write_bytes(content)
    print(f"Verified {len(vectors()['cases'])} execution contract vectors" if args.check
          else f"Wrote {len(vectors()['cases'])} execution contract vectors")


if __name__ == "__main__":
    main()
