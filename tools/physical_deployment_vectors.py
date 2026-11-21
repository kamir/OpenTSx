#!/usr/bin/env python3
import argparse
import copy
import hashlib
import json
import math
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs/datamodel-2026/testvectors/physical-deployment.v1.json"


def canonical(value):
    if isinstance(value, dict):
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key]) for key in sorted(value)) + "}"
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, str):
        return '"' + "".join('\\"' if c == '"' else "\\\\" if c == "\\" else f"\\u{ord(c):04x}" if ord(c) < 32 else c for c in value) + '"'
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    raise ValueError("Physical canonical JSON does not admit number tokens")


def encoded(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def computation(value):
    return ["opentsx.computation.v1", tagged(value)]


def tagged(value):
    if value is None:
        return ["null"]
    if isinstance(value, bool):
        return ["boolean", value]
    if isinstance(value, str):
        return ["string", value]
    if isinstance(value, (int, float)):
        number = float(value)
        if not math.isfinite(number) or isinstance(value, int) and int(number) != value:
            raise ValueError("The logical number has no exact finite binary64 identity")
        return ["f64", struct.pack(">d", number).hex()]
    if isinstance(value, list):
        return ["array", [tagged(item) for item in value]]
    if isinstance(value, dict):
        return ["object", [[key, tagged(value[key])] for key in sorted(value)]]
    raise TypeError(type(value))


def plan_hash(value):
    return digest(canonical({key: item for key, item in value.items() if key != "planHash"}))


def refusal(code, field, node=None):
    return {"schema": "opentsx.plan-refusal.v1", "code": code, "node": node, "field": field,
            "message": f"Physical deployment refused: {code}."}


def base():
    dag = {"schema": "opentsx.dag.v2", "dag": "physical-vector", "version": 1,
           "source": {"kind": "kafscale-broker", "topics": ["tsx.wind.episodes.v2"], "series": ["0000000000000001"]},
           "nodes": [{"id": "decode", "op": "decode.avro-soe"},
                     {"id": "convert", "op": "convert.unit", "in": ["decode"],
                      "params": {"factor": 0.001, "offset": -0.0, "unit": "MW"}}],
           "sinks": [{"from": "convert", "topic": "tsx.wind.clean.v2", "schema": "opentsx.episode.v2",
                      "signal": {"system": "wind", "id": "wind-clean", "grade": "clean"}}]}
    raw = encoded(dag)
    assignment = {"schema": "opentsx.execution-assignment.v1", "deployment": "wind-physical", "generation": "1",
                  "assignment": "assignment-1", "attempt": "attempt-1", "fence": "1", "fragment": "fragment-1",
                  "site": "compose", "node": "worker-1", "runtime": "rust", "catalog": "tsx-catalog/1",
                  "artifactDigest": "sha256:" + "a" * 64, "specHash": digest(raw), "sourceDagText": raw,
                  "fragmentHash": digest(raw), "fragmentText": raw, "mode": "bounded",
                  "inputs": [{"binding": "local", "topic": "tsx.wind.episodes.v2", "partitionCount": "2",
                              "positions": [{"partition": str(index), "from": "0", "to": "2"} for index in range(2)]}]}
    report = {"schema": "opentsx.local-worker.v1", "node": "worker-1", "site": "compose", "version": "0.1.0",
              "artifactDigest": assignment["artifactDigest"], "catalog": "tsx-catalog/1",
              "requestSchema": "opentsx.local-bounded-run.v1", "stateAbi": "opentsx.rust.clean-state.v1",
              "fingerprint": "8985f02b48c77195", "operators": ["decode.avro-soe", "dq.checks", "convert.unit", "resample"],
              "limits": {"records": 10000, "payloadBytes": 1048576, "inputBytes": 16777216, "samples": 100000,
                         "outputBytes": 16777216, "series": 64, "stateBytes": 1048576, "partitions": 128,
                         "requestBytes": 65536, "episodeSamples": 25000}}
    capability = {"assignment": "assignment-1", "digest": digest(encoded(report)), "observedAtMicros": "100",
                  "validUntilMicros": "200", "reportText": encoded(report)}
    topics = [{"binding": "local", "cluster": "cluster-a", "topic": name, "incarnation": "topic-" + str(index),
               "partitionCount": "2"} for index, name in enumerate(["tsx.wind.episodes.v2", "tsx.wind.clean.v2"])]
    owner = {"assignment": "assignment-1", **topics[0]}
    channels = []
    for start, stop, external, topic in [("$source", "decode", True, topics[0]), ("decode", "convert", False, None),
                                         ("convert", "$sink", True, topics[1])]:
        channel = {"assignment": "assignment-1", "from": start, "fromPort": "0", "to": stop, "toPort": "0",
                   "dataType": "episode" if external else "series", "transport": "topic" if external else "memory",
                   "binding": None, "cluster": None, "topic": None, "incarnation": None, "partitionCount": "0",
                   "residency": ["compose"], "schema": "opentsx.episode.v2" if external else "opentsx.series.v1",
                   "key": "seriesId", "partitioner": "murmur2" if external else "none", "bufferRecords": "10000",
                   "backpressure": "block", "completion": "closed-cut"}
        if external:
            channel.update(topic)
        channels.append(channel)
    text = canonical(computation(dag))
    plan = {"schema": "opentsx.physical-deployment.v1", "deployment": "wind-physical", "generation": "1",
            "sourceDagText": raw, "specHash": digest(raw), "computationText": text, "computationHash": digest(text),
            "createdAtMicros": "110", "expiresAtMicros": "190", "assignmentTexts": [encoded(assignment)],
            "placements": [{"assignment": "assignment-1", "nodes": ["decode", "convert"]}],
            "ownership": [owner], "channels": channels, "capabilities": [capability]}
    plan["planHash"] = plan_hash(plan)
    facts = {"observedAtMicros": "100", "validUntilMicros": "200", "topics": topics, "capabilities": [copy.deepcopy(capability)],
             "residency": [{"assignment": "assignment-1", "sites": ["compose"]}]}
    return plan, facts


def vectors():
    cases = []
    for name, value in [("string-escapes-and-unicode-order", {"\ue000": "BMP", "😀": "supplementary", "s": "\"\\/\b\t\n\f\r\x00\x1fµ"}),
                        ("all-control-escapes", ["".join(chr(index) for index in range(32))]),
                        ("array-order", ["b", "a", None, True])]:
        text = canonical(value)
        cases.append({"name": name, "kind": "canonical", "input": value, "canonical": text, "sha256": digest(text)})
    for name, value in [("signed-zero-bits", [0.0, -0.0]), ("adjacent-binary64-bits", [1.0, math.nextafter(1.0, 2.0)]),
                        ("logical-order-and-escapes", {"in": ["right", "left"], "unit": "µW\n\"\\", "flag": True})]:
        text = canonical(computation(value))
        cases.append({"name": name, "kind": "computation", "input": value, "canonical": text, "sha256": digest(text)})

    def case(name, mutate=None, error=None, field=None, node=None, now="120", alter_hash=True, mutate_facts=None):
        plan, facts = copy.deepcopy(base())
        if mutate:
            mutate(plan)
        if mutate_facts:
            mutate_facts(facts)
        if alter_hash:
            plan["planHash"] = plan_hash(plan)
        cases.append({"name": name, "kind": "physical", "input": encoded(plan), "facts": facts, "nowMicros": now,
                      "refusal": None if error is None else refusal("deployment." + error, field, node)})

    def altered_fragment(plan, mutate):
        assignment = json.loads(plan["assignmentTexts"][0])
        fragment = json.loads(assignment["fragmentText"])
        mutate(fragment)
        text = encoded(fragment)
        assignment.update(fragmentText=text, fragmentHash=digest(text))
        plan["assignmentTexts"][0] = encoded(assignment)

    def yaml_original(plan):
        text = """schema: opentsx.dag.v2
dag: physical-vector
version: 1
source:
  kind: kafscale-broker
  topics: [tsx.wind.episodes.v2]
  series: ['0000000000000001']
nodes:
  - id: decode
    op: decode.avro-soe
  - id: convert
    op: convert.unit
    in: [decode]
    params: {factor: 0.001, offset: -0.0, unit: MW}
sinks:
  - from: convert
    topic: tsx.wind.clean.v2
    schema: opentsx.episode.v2
    signal: {system: wind, id: wind-clean, grade: clean}
"""
        plan.update(sourceDagText=text, specHash=digest(text))
        assignment = json.loads(plan["assignmentTexts"][0])
        assignment.update(sourceDagText=text, specHash=digest(text))
        plan["assignmentTexts"][0] = encoded(assignment)

    def computation_frame(plan, frame):
        value = json.loads(plan["computationText"])
        text = canonical(value[1] if frame is None else [frame, value[1]])
        plan.update(computationText=text, computationHash=digest(text))

    case("unknown-computation-frame-version", lambda p: computation_frame(p, "opentsx.computation.v99"),
         "computation", "/computationText")
    case("unversioned-bare-computation", lambda p: computation_frame(p, None), "computation", "/computationText")
    case("complete-bounded-deployment")
    case("opaque-yaml-original-with-list", yaml_original)
    case("inexact-ignored-fragment-integer", lambda p: altered_fragment(p, lambda f: f.update(version=18446744073709551617)),
         "computation", "/computationText")
    case("rehashed-fragment-factor", lambda p: altered_fragment(p, lambda f: f["nodes"][1]["params"].update(factor=2.0)),
         "coverage", "/assignmentTexts/fragmentText/nodes", "convert")
    case("rehashed-fragment-signed-zero", lambda p: altered_fragment(p, lambda f: f["nodes"][1]["params"].update(offset=0.0)),
         "coverage", "/assignmentTexts/fragmentText/nodes", "convert")
    case("rehashed-fragment-source-series", lambda p: altered_fragment(p, lambda f: f["source"].update(series=["0000000000000002"])),
         "coverage", "/assignmentTexts/fragmentText/source")
    case("rehashed-fragment-sink", lambda p: altered_fragment(p, lambda f: f["sinks"][0]["signal"].update(id="different")),
         "coverage", "/assignmentTexts/fragmentText/sinks")
    def continuous_assignment(plan):
        assignment = json.loads(plan["assignmentTexts"][0])
        assignment["mode"] = "continuous"
        for position in assignment["inputs"][0]["positions"]:
            position.pop("to")
        plan["assignmentTexts"][0] = encoded(assignment)

    def java_assignment(plan):
        assignment = json.loads(plan["assignmentTexts"][0])
        assignment["runtime"] = "java"
        plan["assignmentTexts"][0] = encoded(assignment)

    case("unsupported-java-assignment", java_assignment, "assignment", "/assignmentTexts")
    case("unsupported-continuous-deployment", continuous_assignment, "assignment", "/assignmentTexts")
    case("null-assignment-inventory", lambda p: p.update(assignmentTexts=None), "assignment", "/assignmentTexts")
    case("nonarray-placements", lambda p: p.update(placements={}), "coverage", "/placements")
    case("nonarray-channels", lambda p: p.update(channels={}), "channel", "/channels")

    def malformed_nodes(plan, value):
        logical = json.loads(plan["sourceDagText"])
        logical["nodes"] = value
        text = canonical(computation(logical))
        plan.update(computationText=text, computationHash=digest(text))

    case("scalar-logical-node", lambda p: malformed_nodes(p, ["decode"]), "computation", "/computationText/nodes")
    case("nonstring-logical-node-id", lambda p: malformed_nodes(p, [{"id": True, "op": "decode.avro-soe"}]), "computation", "/computationText/nodes")
    case("duplicate-logical-node-id", lambda p: malformed_nodes(p, [{"id": "decode", "op": "decode.avro-soe"}] * 2), "computation", "/computationText/nodes")
    case("missing-original-node-placement", lambda p: p["placements"][0]["nodes"].pop(), "coverage", "/placements/nodes")
    case("missing-owner", lambda p: p.update(ownership=[]), "coverage", "/ownership")
    case("missing-internal-edge", lambda p: p["channels"].pop(1), "channel", "/channels", "convert")
    case("untrusted-channel-endpoint", lambda p: p["channels"][0].update(to="https://should-not-appear.invalid/private"),
         "channel", "/channels/0")
    case("wrong-original-port", lambda p: p["channels"][1].update(toPort="1"), "channel", "/channels/1", "convert")
    case("wrong-payload-schema", lambda p: p["channels"][0].update(schema="opentsx.signal.v1"), "channel", "/channels/0", "decode")
    case("unbounded-channel-buffer", lambda p: p["channels"][1].update(bufferRecords="10001"), "channel", "/channels/1/bufferRecords", "convert")
    case("unsupported-partitioner", lambda p: p["channels"][0].update(partitioner="random"), "channel", "/channels/0", "decode")
    case("unsupported-backpressure", lambda p: p["channels"][1].update(backpressure="drop"), "channel", "/channels/1", "convert")
    case("unsupported-completion", lambda p: p["channels"][1].update(completion="unbounded"), "channel", "/channels/1", "convert")
    case("changed-capability-report", lambda p: p["capabilities"][0].update(reportText=p["capabilities"][0]["reportText"] + " "), "capability", "/capabilities/digest")
    case("expired-physical-envelope", now="191", error="freshness", field="/facts/validUntilMicros")
    case("stale-host-observation", mutate_facts=lambda f: f.update(validUntilMicros="119"), error="freshness", field="/facts/validUntilMicros")
    case("future-host-observation", mutate_facts=lambda f: f.update(observedAtMicros="121"), error="freshness", field="/facts/validUntilMicros")
    case("changed-topic-incarnation", mutate_facts=lambda f: f["topics"][0].update(incarnation="recreated"), error="incarnation", field="/channels/incarnation", node="decode")
    case("missing-trusted-topic", mutate_facts=lambda f: f["topics"].pop(0), error="incarnation", field="/channels", node="decode")
    case("changed-trusted-capability", mutate_facts=lambda f: f["capabilities"][0].update(digest="b" * 64), error="capability", field="/facts/capabilities")
    case("changed-host-residency", mutate_facts=lambda f: f["residency"][0].update(sites=["other-site"]), error="facts", field="/channels/residency", node="decode")
    case("corrupt-physical-hash", lambda p: p.update(planHash="0" * 64), "hash", "/planHash", alter_hash=False)
    case("unknown-field", lambda p: p.update(extra="forbidden"), "fields", "/")
    case("unknown-version", lambda p: p.update(schema="opentsx.physical-deployment.v99"), "version", "/schema")
    plan, facts = base()
    number = encoded(plan).replace('"generation":"1"', '"generation":1', 1)
    cases.append({"name": "numeric-outer-counter", "kind": "physical", "input": number, "facts": facts, "nowMicros": "120",
                  "refusal": refusal("deployment.fields", "/generation")})
    for name, text, code, field in [
            ("null-outer-document", "null", "fields", "/"),
            ("array-outer-document", "[]", "fields", "/"),
            ("numeric-outer-document", "7", "fields", "/"),
            ("duplicate-outer-key", encoded(plan)[:-1] + ',"schema":"opentsx.physical-deployment.v1"}', "json", "/"),
            ("unpaired-surrogate", encoded({**plan, "sourceDagText": "@@surrogate@@"}).replace("@@surrogate@@", "\\ud800"), "json", "/")]:
        cases.append({"name": name, "kind": "physical", "input": text, "facts": copy.deepcopy(facts), "nowMicros": "120",
                      "refusal": refusal("deployment." + code, field)})
    def replicated_fragment(name, fragment, expected_refusal):
        replica_plan, replica_facts = copy.deepcopy(base())
        replica = json.loads(replica_plan["assignmentTexts"][0])
        replica.update(assignment="assignment-2", node="worker-2", fragment=fragment)
        replica["inputs"][0]["binding"] = "alias"
        replica_plan["assignmentTexts"].append(encoded(replica))
        replica_plan["placements"].append({"assignment": "assignment-2", "nodes": ["decode", "convert"]})
        replica_owner = copy.deepcopy(replica_plan["ownership"][0])
        replica_owner.update(assignment="assignment-2", binding="alias")
        replica_plan["ownership"].append(replica_owner)
        for channel in copy.deepcopy(replica_plan["channels"]):
            channel["assignment"] = "assignment-2"
            if channel["from"] == "$source":
                channel["binding"] = "alias"
            replica_plan["channels"].append(channel)
        capability = copy.deepcopy(replica_plan["capabilities"][0])
        report = json.loads(capability["reportText"])
        report["node"] = "worker-2"
        capability.update(assignment="assignment-2", reportText=encoded(report), digest=digest(encoded(report)))
        replica_plan["capabilities"].append(capability)
        replica_facts["capabilities"].append(copy.deepcopy(capability))
        alias = copy.deepcopy(replica_facts["topics"][0])
        alias["binding"] = "alias"
        replica_facts["topics"].append(alias)
        replica_facts["residency"].append({"assignment": "assignment-2", "sites": ["compose"]})
        replica_plan["planHash"] = plan_hash(replica_plan)
        cases.append({"name": name, "kind": "physical", "input": encoded(replica_plan), "facts": replica_facts,
                      "nowMicros": "120", "refusal": expected_refusal})

    replicated_fragment("alias-resolves-duplicate-whole-topic-owner", "fragment-1", refusal("deployment.coverage", "/ownership"))
    replicated_fragment("distinct-fragment-preserves-original-nodes-and-ports", "replica-2", None)

    split_plan, split_facts = copy.deepcopy(base())
    logical = json.loads(split_plan["sourceDagText"])
    logical["nodes"][1]["id"] = "a"
    downstream = copy.deepcopy(logical["nodes"][1])
    downstream.update({"id": "b", "in": ["a"]})
    logical["nodes"].append(downstream)
    logical["sinks"][0]["from"] = "b"
    source_text = encoded(logical)
    computation_text = canonical(computation(logical))
    split_plan.update(sourceDagText=source_text, specHash=digest(source_text),
                      computationText=computation_text, computationHash=digest(computation_text))
    first = json.loads(split_plan["assignmentTexts"][0])
    first.update(sourceDagText=source_text, specHash=digest(source_text),
                 fragmentText=source_text, fragmentHash=digest(source_text))
    fragment = copy.deepcopy(logical)
    second = copy.deepcopy(first)
    second.update(assignment="assignment-2", node="worker-2", fragment="fragment-2")
    fragment["nodes"] = [logical["nodes"][0], logical["nodes"][2]]
    fragment_text = encoded(fragment)
    second.update(fragmentText=fragment_text, fragmentHash=digest(fragment_text))
    split_plan["assignmentTexts"] = [encoded(first), encoded(second)]
    split_plan["placements"] = [{"assignment": "assignment-1", "nodes": ["decode", "a", "b"]},
                                {"assignment": "assignment-2", "nodes": ["decode", "b"]}]
    owner = copy.deepcopy(split_plan["ownership"][0])
    owner["assignment"] = "assignment-2"
    split_plan["ownership"].append(owner)
    capability = copy.deepcopy(split_plan["capabilities"][0])
    report = json.loads(capability["reportText"])
    report["node"] = "worker-2"
    capability.update(assignment="assignment-2", reportText=encoded(report), digest=digest(encoded(report)))
    split_plan["capabilities"].append(capability)
    split_facts["capabilities"] = copy.deepcopy(split_plan["capabilities"])
    split_facts["residency"].append({"assignment": "assignment-2", "sites": ["compose"]})
    channels = split_plan["channels"]
    channels[1]["to"] = "a"
    channels[2]["from"] = "b"
    second_channels = copy.deepcopy(channels)
    for channel in second_channels:
        channel["assignment"] = "assignment-2"
    second_channels[1].update({"from": "a", "to": "b"})
    internal = copy.deepcopy(channels[1])
    internal.update({"from": "a", "to": "b"})
    channels.insert(2, internal)
    split_plan["channels"].extend(second_channels)
    split_plan["planHash"] = plan_hash(split_plan)
    cases.append({"name": "cross-fragment-memory-producer", "kind": "physical", "input": encoded(split_plan),
                  "facts": split_facts, "nowMicros": "120", "refusal": refusal("deployment.channel", "/channels", "b")})

    original = copy.deepcopy(plan)
    assignment = json.loads(plan["assignmentTexts"][0])
    assignment["node"] = "worker-2"
    plan["assignmentTexts"][0] = encoded(assignment)
    report = json.loads(plan["capabilities"][0]["reportText"])
    report["node"] = "worker-2"
    plan["capabilities"][0].update(reportText=encoded(report), digest=digest(encoded(report)))
    facts["capabilities"] = copy.deepcopy(plan["capabilities"])
    plan["planHash"] = plan_hash(plan)
    assert plan["computationHash"] == original["computationHash"] and plan["planHash"] != original["planHash"]
    cases.append({"name": "placement-invariant-computation", "kind": "physical", "input": encoded(plan), "facts": facts,
                  "nowMicros": "120", "refusal": None})
    return {"schema": "opentsx.physical-deployment-vectors.v1",
            "description": "Shared bounded physical deployment identity, complete ownership and channel admission with separately trusted facts.",
            "cases": cases}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    content = json.dumps(vectors(), ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.check:
        if OUTPUT.read_text(encoding="utf-8") != content:
            raise SystemExit("Physical deployment vectors differ from generator output")
        print(f"Verified {len(vectors()['cases'])} physical deployment vectors")
    else:
        OUTPUT.write_text(content, encoding="utf-8")
        print(f"Wrote {len(vectors()['cases'])} physical deployment vectors")


if __name__ == "__main__":
    main()
