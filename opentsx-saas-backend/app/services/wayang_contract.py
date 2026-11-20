import json
import math
import re
import struct
import time

from app.services.wayang_service import WayangError, digest, strict_json


SCHEMA = "opentsx.physical-deployment.v1"
MESSAGES = {code: f"Physical deployment refused: {code}." for code in (
    "deployment.json",
    "deployment.fields",
    "deployment.version",
    "deployment.identity",
    "deployment.hash",
    "deployment.computation",
    "deployment.assignment",
    "deployment.coverage",
    "deployment.channel",
    "deployment.capability",
    "deployment.freshness",
    "deployment.incarnation",
    "deployment.facts",
    "deployment.limit",
)}


def refuse(code, field, node=None):
    message = MESSAGES[code]
    raise WayangError(400, message, {"schema": "opentsx.plan-refusal.v1", "code": code,
                                   "node": node, "field": field, "message": message})


def public_refusal(value):
    if (not isinstance(value, dict) or set(value) != {"schema", "code", "node", "field", "message"}
            or value.get("schema") != "opentsx.plan-refusal.v1" or not isinstance(value.get("code"), str)
            or value["code"] not in MESSAGES
            or value.get("message") != MESSAGES[value["code"]]
            or not (value["node"] is None or isinstance(value["node"], str)
                    and re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", value["node"]))
            or not isinstance(value["field"], str)
            or not (value["field"] == "/" or re.fullmatch(r"(?:/[A-Za-z0-9_~.-]+)*", value["field"]))
            or len(value["field"]) > 512):
        return None
    return value


def canonical(value):
    if isinstance(value, str):
        return '"' + ''.join('\\"' if character == '"' else '\\\\' if character == '\\'
                             else f"\\u{ord(character):04x}" if ord(character) < 32 else character
                             for character in value) + '"'
    if value is None:
        return "null"
    if type(value) is bool:
        return "true" if value else "false"
    if isinstance(value, list):
        return "[" + ",".join(canonical(entry) for entry in value) + "]"
    if isinstance(value, dict):
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key]) for key in sorted(value)) + "}"
    refuse("deployment.fields", "")


def no_numbers(value, path):
    if type(value) in (int, float):
        refuse("deployment.fields", path)
    if isinstance(value, dict):
        for key, entry in value.items():
            no_numbers(entry, path + "/" + key.replace("~", "~0").replace("/", "~1"))
    elif isinstance(value, list):
        for index, entry in enumerate(value):
            no_numbers(entry, path + "/" + str(index))
    elif isinstance(value, str):
        try:
            value.encode("utf-8")
        except UnicodeError:
            refuse("deployment.json", path)


def parse_document(raw):
    try:
        return strict_json(raw)
    except UnicodeError:
        refuse("deployment.json", "/")
    except (ValueError, TypeError, RecursionError):
        refuse("deployment.json", "/")


def plan_hash(plan):
    return digest(canonical({key: value for key, value in plan.items() if key != "planHash"}))


def fields(value, names, path, code="deployment.fields"):
    if not isinstance(value, dict) or set(value) != set(names.split()):
        refuse(code, path)


def counter(value, path, minimum=0):
    if (not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,18}", value)
            or not minimum <= int(value) <= 9_223_372_036_854_775_807):
        refuse("deployment.fields", path)
    return int(value)


def text(value, path, maximum=256):
    if not isinstance(value, str) or not value.strip() or not 1 <= len(value.encode("utf-8")) <= maximum:
        refuse("deployment.fields", path)
    return value


def decode_computation(encoded):
    def decode(value):
        if not isinstance(value, list) or not value:
            refuse("deployment.computation", "/computationText")
        tag = value[0]
        if tag == "null" and len(value) == 1:
            return None
        if len(value) != 2:
            refuse("deployment.computation", "/computationText")
        entry = value[1]
        if tag == "boolean" and type(entry) is bool or tag == "string" and isinstance(entry, str):
            return entry
        if tag == "f64" and isinstance(entry, str) and re.fullmatch(r"[0-9a-f]{16}", entry):
            number = struct.unpack(">d", bytes.fromhex(entry))[0]
            if math.isfinite(number):
                return number
        if tag == "array" and isinstance(entry, list):
            return [decode(item) for item in entry]
        if tag == "object" and isinstance(entry, list):
            result = {}
            previous = None
            for item in entry:
                if (not isinstance(item, list) or len(item) != 2 or not isinstance(item[0], str)
                        or previous is not None and item[0] <= previous):
                    refuse("deployment.computation", "/computationText")
                previous = item[0]
                result[item[0]] = decode(item[1])
            return result
        refuse("deployment.computation", "/computationText")

    try:
        value = strict_json(encoded)
        if canonical(value) != encoded:
            refuse("deployment.computation", "/computationText")
        return decode(value)
    except (ValueError, UnicodeError, TypeError, RecursionError):
        refuse("deployment.computation", "/computationText")


def encode_computation(value):
    def encode(item):
        if item is None:
            return ["null"]
        if type(item) is bool:
            return ["boolean", item]
        if isinstance(item, str):
            return ["string", item]
        if type(item) in (int, float):
            try:
                number = float(item)
                if not math.isfinite(number) or type(item) is int and int(number) != item:
                    refuse("deployment.computation", "/computationText")
                return ["f64", struct.pack(">d", number).hex()]
            except OverflowError:
                refuse("deployment.computation", "/computationText")
        if isinstance(item, list):
            return ["array", [encode(entry) for entry in item]]
        if isinstance(item, dict):
            return ["object", [[key, encode(item[key])] for key in sorted(item)]]
        refuse("deployment.computation", "/computationText")

    return canonical(encode(value))


def assignment_inventory(plan):
    result = {}
    values = plan.get("assignmentTexts")
    if not isinstance(values, list) or not 1 <= len(values) <= 3:
        refuse("deployment.assignment", "/assignmentTexts")
    for index, value in enumerate(values):
        path = "/assignmentTexts"
        try:
            assignment = strict_json(value)
            fields(assignment, "schema deployment generation assignment attempt fence fragment site node runtime catalog "
                   "artifactDigest specHash sourceDagText fragmentHash fragmentText mode inputs", path, "deployment.assignment")
            if (assignment["schema"] != "opentsx.execution-assignment.v1" or assignment["mode"] != "bounded"
                    or assignment["runtime"] != "rust" or assignment["catalog"] != "tsx-catalog/1"
                    or any(assignment[key] != plan[key] for key in ("deployment", "generation", "specHash", "sourceDagText"))
                    or digest(assignment["fragmentText"]) != assignment["fragmentHash"]
                    or assignment["assignment"] in result):
                refuse("deployment.assignment", path)
            for key in ("assignment", "attempt", "fragment", "site", "node", "artifactDigest"):
                text(assignment[key], path)
            counter(assignment["fence"], path, 1)
            if not isinstance(assignment["inputs"], list) or len(assignment["inputs"]) != 1:
                refuse("deployment.assignment", path)
            for source in assignment["inputs"]:
                fields(source, "binding topic partitionCount positions", path, "deployment.assignment")
                text(source["binding"], path)
                text(source["topic"], path, 249)
                count = counter(source["partitionCount"], path, 1)
                if count > 128 or not isinstance(source["positions"], list) or len(source["positions"]) != count:
                    refuse("deployment.assignment", path)
                for partition, position in enumerate(source["positions"]):
                    fields(position, "partition from to", path, "deployment.assignment")
                    if (position["partition"] != str(partition)
                            or counter(position["from"], path) > counter(position["to"], path)):
                        refuse("deployment.assignment", path)
            result[assignment["assignment"]] = assignment
        except (ValueError, TypeError, KeyError, UnicodeError, RecursionError):
            refuse("deployment.assignment", path)
    return result


def assignments(plan):
    inventory = assignment_inventory(plan)
    result = []
    for identifier, assignment in inventory.items():
        sinks = [channel for channel in plan["channels"] if channel["assignment"] == identifier and channel["to"] == "$sink"]
        if len(sinks) != 1:
            refuse("deployment.coverage", "/channels")
        source = assignment["inputs"][0]
        result.append({"node": assignment["node"], "inputTopic": source["topic"],
                       "partitionCount": source["partitionCount"], "outputTopic": sinks[0]["topic"],
                       "assignmentHash": digest(plan["assignmentTexts"][len(result)]), "specHash": assignment["specHash"]})
    return result


def admission_facts(plan, now):
    refuse("deployment.incarnation", "/facts/topics")


def validate(plan, deployment, dag_text, created_at, now=None):
    try:
        return validate_document(plan, deployment, dag_text, created_at, now)
    except (KeyError, ValueError, TypeError, UnicodeError, RecursionError):
        refuse("deployment.fields", "/")


def validate_document(plan, deployment, dag_text, created_at, now):
    fields(plan, "schema deployment generation sourceDagText specHash computationText computationHash createdAtMicros "
           "expiresAtMicros assignmentTexts placements ownership channels capabilities planHash", "/")
    if plan["schema"] != SCHEMA:
        refuse("deployment.version", "/schema")
    no_numbers(plan, "")
    if (plan["deployment"] != deployment or plan["sourceDagText"] != dag_text or plan["specHash"] != digest(dag_text)
            or plan["createdAtMicros"] != created_at):
        refuse("deployment.identity", "")
    counter(plan["generation"], "/generation", 1)
    if not isinstance(plan["planHash"], str) or plan["planHash"] != plan_hash(plan):
        refuse("deployment.hash", "/planHash")
    if not isinstance(plan["computationText"], str) or digest(plan["computationText"]) != plan["computationHash"]:
        refuse("deployment.hash", "/computationHash")
    computation = decode_computation(plan["computationText"])
    if not isinstance(computation, dict) or not isinstance(computation.get("nodes"), list):
        refuse("deployment.computation", "/computationText")
    inventory = assignment_inventory(plan)
    nodes = {}
    for node in computation["nodes"]:
        if (not isinstance(node, dict) or not isinstance(node.get("id"), str)
                or not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,62}", node["id"])
                or node["id"] in nodes or not isinstance(node.get("op"), str)
                or "in" in node and (not isinstance(node["in"], list)
                                     or any(not isinstance(source, str) for source in node["in"]))):
            refuse("deployment.computation", "/computationText/nodes")
        nodes[node["id"]] = node
    source = computation.get("source")
    sinks = computation.get("sinks")
    if (not nodes or not isinstance(source, dict) or not isinstance(source.get("topics"), list)
            or not source["topics"] or any(not isinstance(topic, str) for topic in source["topics"])
            or not isinstance(sinks, list) or not sinks
            or any(not isinstance(sink, dict) or not isinstance(sink.get("from"), str)
                   or not isinstance(sink.get("topic"), str) for sink in sinks)):
        refuse("deployment.computation", "/computationText")
    placement = {}
    if not isinstance(plan["placements"], list):
        refuse("deployment.coverage", "/placements")
    for index, item in enumerate(plan["placements"]):
        fields(item, "assignment nodes", f"/placements/{index}")
        identifier = item["assignment"]
        if identifier not in inventory or identifier in placement or not isinstance(item["nodes"], list):
            refuse("deployment.coverage", "/placements")
        fragment = strict_json(inventory[identifier]["fragmentText"])
        encode_computation(fragment)
        source = fragment.get("source")
        logical_source = computation.get("source")
        if (not isinstance(source, dict) or not isinstance(logical_source, dict)
                or encode_computation({key: value for key, value in source.items() if key != "topics"})
                != encode_computation({key: value for key, value in logical_source.items() if key != "topics"})):
            refuse("deployment.coverage", "/assignmentTexts/fragmentText/source")
        if source.get("topics") != [entry["topic"] for entry in inventory[identifier]["inputs"]]:
            refuse("deployment.coverage", "/assignmentTexts/fragmentText/source/topics")
        if encode_computation(fragment.get("sinks")) != encode_computation(computation.get("sinks")):
            refuse("deployment.coverage", "/assignmentTexts/fragmentText/sinks")
        if item["nodes"] != [node["id"] for node in fragment["nodes"]]:
            refuse("deployment.coverage", "/placements/nodes")
        for node in fragment["nodes"]:
            if encode_computation(node) != encode_computation(nodes.get(node["id"])):
                refuse("deployment.coverage", "/assignmentTexts/fragmentText/nodes", node["id"])
        placement[identifier] = item["nodes"]
    if set(placement) != set(inventory) or set().union(*(set(value) for value in placement.values())) != set(nodes):
        refuse("deployment.coverage", "/placements")
    ownership = set()
    claims = set()
    if not isinstance(plan["ownership"], list):
        refuse("deployment.coverage", "/ownership")
    for index, item in enumerate(plan["ownership"]):
        path = f"/ownership/{index}"
        fields(item, "assignment binding cluster topic incarnation partitionCount", path)
        if item["assignment"] not in inventory:
            refuse("deployment.coverage", path)
        assignment = inventory[item["assignment"]]
        if not any(all(source[key] == item[key] for key in ("binding", "topic", "partitionCount"))
                   for source in assignment["inputs"]):
            refuse("deployment.coverage", path)
        for key in ("cluster", "incarnation"):
            text(item[key], path + "/" + key)
        key = (assignment["fragment"], item["cluster"], item["incarnation"], item["topic"])
        own = (item["assignment"], item["binding"], item["topic"])
        if key in claims or own in ownership:
            refuse("deployment.coverage", "/ownership")
        claims.add(key)
        ownership.add(own)
    expected_owners = {(identifier, source["binding"], source["topic"])
                       for identifier, item in inventory.items() for source in item["inputs"]}
    if ownership != expected_owners:
        refuse("deployment.coverage", "/ownership")
    if {item["topic"] for item in plan["ownership"]} != set(computation["source"]["topics"]):
        refuse("deployment.coverage", "/ownership")
    capabilities = {}
    if not isinstance(plan["capabilities"], list):
        refuse("deployment.capability", "/capabilities")
    start = counter(plan["createdAtMicros"], "/createdAtMicros")
    end = counter(plan["expiresAtMicros"], "/expiresAtMicros")
    clock = counter(str(time.time_ns() // 1000) if now is None else now, "/createdAtMicros")
    if start > end:
        refuse("deployment.freshness", "/expiresAtMicros")
    for index, item in enumerate(plan["capabilities"]):
        path = f"/capabilities/{index}"
        fields(item, "assignment digest observedAtMicros validUntilMicros reportText", path)
        if (item["assignment"] not in inventory or item["assignment"] in capabilities
                or not isinstance(item["reportText"], str) or digest(item["reportText"]) != item["digest"]):
            refuse("deployment.capability", "/capabilities/digest")
        if not counter(item["observedAtMicros"], path) <= start <= end <= counter(item["validUntilMicros"], path):
            refuse("deployment.freshness", path)
        report = strict_json(item["reportText"])
        assignment = inventory[item["assignment"]]
        if not isinstance(report, dict):
            refuse("deployment.capability", "/capabilities/reportText")
        for field in ("node", "site", "artifactDigest", "catalog"):
            if report.get(field) != assignment[field]:
                refuse("deployment.capability", "/capabilities/reportText/" + field)
        limits = report.get("limits")
        if not isinstance(limits, dict):
            refuse("deployment.capability", "/capabilities/reportText/limits")
        limit = limits.get("records")
        if report.get("schema") != "opentsx.local-worker.v1" or type(limit) is not int or not 0 < limit <= 2_147_483_647:
            refuse("deployment.capability", "/capabilities/reportText/limits/records")
        for field in ("samples", "partitions"):
            if type(limits.get(field)) is not int or not 0 < limits[field] <= 9_223_372_036_854_775_807:
                refuse("deployment.capability", "/capabilities/reportText/limits/" + field)
        count = sum(int(position["to"]) - int(position["from"])
                    for source in assignment["inputs"] for position in source["positions"])
        if count > limit or any(int(source["partitionCount"]) > limits["partitions"] for source in assignment["inputs"]):
            refuse("deployment.limit", "/assignmentTexts/inputs")
        capabilities[item["assignment"]] = report
    if set(capabilities) != set(inventory):
        refuse("deployment.capability", "/capabilities")
    expected_edges = {}
    for identifier, assignment in inventory.items():
        fragment = strict_json(assignment["fragmentText"])
        for node in fragment["nodes"]:
            for port, source in enumerate(node.get("in", [])):
                expected_edges[(identifier, source, "0", node["id"], str(port))] = None
            if node["op"] == "decode.avro-soe":
                for source in assignment["inputs"]:
                    source_index = computation["source"]["topics"].index(source["topic"])
                    expected_edges[(identifier, "$source", str(source_index), node["id"], "0")] = source["topic"]
        for sink_index, sink in enumerate(computation["sinks"]):
            if sink["from"] in placement[identifier]:
                expected_edges[(identifier, sink["from"], "0", "$sink", str(sink_index))] = sink["topic"]
    if not isinstance(plan["channels"], list):
        refuse("deployment.channel", "/channels")
    for index, item in enumerate(plan["channels"]):
        path = f"/channels/{index}"
        fields(item, "assignment from fromPort to toPort dataType transport binding cluster topic incarnation "
               "partitionCount residency schema key partitioner bufferRecords backpressure completion", path, "deployment.channel")
        identifier = item["assignment"]
        endpoint = item["to"] if item["to"] != "$sink" else item["from"]
        node = endpoint if endpoint in nodes else None
        edge = (identifier, item["from"], item["fromPort"], item["to"], item["toPort"])
        if identifier not in inventory or edge not in expected_edges:
            refuse("deployment.channel", path, node)
        target = expected_edges.pop(edge)
        assignment = inventory[identifier]
        for port in ("fromPort", "toPort"):
            counter(item[port], path + "/" + port)
        external = target is not None
        if (item["dataType"] != ("episode" if external else "series")
                or item["transport"] != ("topic" if external else "memory")
                or item["schema"] != ("opentsx.episode.v2" if external else "opentsx.series.v1")
                or item["key"] != "seriesId" or item["partitioner"] != ("murmur2" if external else "none")
                or item["backpressure"] != "block" or item["completion"] != "closed-cut"):
            refuse("deployment.channel", path, node)
        if (not isinstance(item["residency"], list) or not item["residency"]
                or any(not isinstance(site, str) or not site for site in item["residency"])
                or len(set(item["residency"])) != len(item["residency"])
                or assignment["site"] not in item["residency"]):
            refuse("deployment.channel", path + "/residency", node)
        limit = capabilities[identifier]["limits"]["records"]
        if type(limit) is not int or counter(item["bufferRecords"], path + "/bufferRecords", 1) > limit:
            refuse("deployment.channel", path + "/bufferRecords", node)
        if external:
            for key in ("binding", "cluster", "topic", "incarnation"):
                text(item[key], path + "/" + key)
            counter(item["partitionCount"], path + "/partitionCount", 1)
            if item["topic"] != target:
                refuse("deployment.channel", path, node)
            if item["from"] == "$source":
                owner = next((owner for owner in plan["ownership"] if owner["assignment"] == identifier
                              and owner["binding"] == item["binding"] and owner["topic"] == item["topic"]), None)
                if owner is None:
                    refuse("deployment.channel", path, node)
                for key in ("binding", "cluster", "topic", "incarnation", "partitionCount"):
                    if item[key] != owner[key]:
                        refuse("deployment.channel", path + "/" + key, node)
        elif (any(item[key] is not None for key in ("binding", "cluster", "topic", "incarnation"))
              or item["partitionCount"] != "0"):
            refuse("deployment.channel", path, node)
    if expected_edges:
        missing = next(iter(expected_edges))
        refuse("deployment.channel", "/channels", missing[3] if missing[3] != "$sink" else missing[1])
    facts = admission_facts(plan, str(clock))
    fields(facts, "observedAtMicros validUntilMicros topics capabilities residency", "/facts", "deployment.facts")
    no_numbers(facts, "/facts")
    if not counter(facts["observedAtMicros"], "") <= start <= clock <= end <= counter(facts["validUntilMicros"], ""):
        refuse("deployment.freshness", "/facts/validUntilMicros")
    if facts["capabilities"] != plan["capabilities"]:
        refuse("deployment.capability", "/facts/capabilities")
    if not isinstance(facts["topics"], list) or not isinstance(facts["residency"], list):
        refuse("deployment.facts", "/facts")
    topics = {}
    for item in facts["topics"]:
        fields(item, "binding cluster topic incarnation partitionCount", "/facts/topics", "deployment.facts")
        key = (item["binding"], item["topic"])
        if key in topics:
            refuse("deployment.facts", "/facts/topics")
        topics[key] = item
    used = set()
    residency = {}
    for item in facts["residency"]:
        fields(item, "assignment sites", "/facts/residency", "deployment.facts")
        if item["assignment"] in residency:
            refuse("deployment.facts", "/facts/residency")
        residency[item["assignment"]] = item["sites"]
    if set(residency) != set(inventory):
        refuse("deployment.facts", "/facts/residency")
    for item in plan["channels"]:
        node = item["to"] if item["to"] != "$sink" else item["from"]
        if item["transport"] == "topic":
            key = (item["binding"], item["topic"])
            topic = topics.get(key)
            if topic is None:
                refuse("deployment.incarnation", "/channels", node)
            for field in ("binding", "cluster", "topic", "incarnation", "partitionCount"):
                if item[field] != topic[field]:
                    refuse("deployment.incarnation" if field == "incarnation" else "deployment.facts",
                           "/channels/" + field, node)
            used.add(key)
        if item["residency"] != residency[item["assignment"]]:
            refuse("deployment.facts", "/channels/residency", node)
    if used != set(topics):
        refuse("deployment.facts", "/facts/topics")
    return assignments(plan)


def ownership_claims(plan):
    inventory = assignment_inventory(plan)
    return sorted({(inventory[item["assignment"]]["fragment"], item["cluster"], item["incarnation"], item["topic"])
                   for item in plan["channels"] if item["transport"] == "topic"})


def validate_receipts(plan, statuses):
    inventory = {item["node"]: item for item in assignment_inventory(plan).values()}
    reports = {item["assignment"]: strict_json(item["reportText"]) for item in plan["capabilities"]}
    outputs = {item["node"]: item["outputTopic"] for item in assignments(plan)}
    for status in statuses:
        if status["state"] != "completed":
            continue
        assignment = inventory[status["node"]]
        receipt = status.get("receipt")
        source = assignment["inputs"][0]
        positions = [{"partition": position["partition"], "next": position["to"]} for position in source["positions"]]
        records = sum(int(position["to"]) - int(position["from"]) for position in source["positions"])
        fragment = strict_json(assignment["fragmentText"])
        buffer = min(int(item["bufferRecords"]) for item in plan["channels"]
                     if item["assignment"] == assignment["assignment"])
        ceiling = records if len(fragment["nodes"]) == 1 else min(buffer, reports[assignment["assignment"]]["limits"]["samples"])
        if (not isinstance(receipt, dict) or set(receipt) != {
                "schema", "requestHash", "artifactDigest", "deployment", "generation", "assignment", "node", "inputTopic",
                "outputTopic", "positions", "inputRecords", "outputRecords", "outputDigest", "stateDigest"}
                or receipt["schema"] != "opentsx.local-receipt.v1"
                or receipt["requestHash"] != status.get("requestHash")
                or any(receipt[field] != assignment[field] for field in
                       ("artifactDigest", "deployment", "generation", "assignment", "node"))
                or receipt["inputTopic"] != source["topic"] or receipt["outputTopic"] != outputs[status["node"]]
                or receipt["positions"] != positions or type(receipt["inputRecords"]) is not int
                or receipt["inputRecords"] != records or type(receipt["outputRecords"]) is not int
                or type(ceiling) is not int or not 0 <= receipt["outputRecords"] <= ceiling
                or any(not isinstance(receipt[field], str) or not re.fullmatch(r"[0-9a-f]{64}", receipt[field])
                       for field in ("requestHash", "outputDigest", "stateDigest"))):
            raise WayangError(502, "The physical worker receipt does not match the admitted assignment and positions.")
