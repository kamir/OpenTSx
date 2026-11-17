import hashlib
import json
import math
import re
from datetime import timedelta

import httpx
from sqlalchemy import and_, delete, or_, select
from sqlalchemy.exc import IntegrityError

from app.core.config import settings
from app.models.flow import Flow
from app.models.wayang import WayangContinuous, WayangCycle, WayangDeployment, WayangTopicOwner, utc_now


class WayangError(Exception):
    def __init__(self, status, message):
        self.status = status
        self.message = message
        super().__init__(message)


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON field")
            result[key] = value
        return result

    def invalid(value):
        raise ValueError("non-finite JSON value")

    value = json.loads(text, object_pairs_hook=pairs, parse_constant=invalid)

    def scalars(item, depth=0):
        if depth > 64:
            raise ValueError("JSON nesting limit exceeded")
        if isinstance(item, str):
            item.encode("utf-8")
        elif isinstance(item, float) and not math.isfinite(item):
            raise ValueError("non-finite JSON number")
        elif isinstance(item, dict):
            for key, entry in item.items():
                key.encode("utf-8")
                scalars(entry, depth + 1)
        elif isinstance(item, list):
            for entry in item:
                scalars(entry, depth + 1)

    scalars(value)
    return value


def digest(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def decimal(value, minimum=0):
    if (not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,18}", value)
            or not minimum <= int(value) <= 9_223_372_036_854_775_807):
        raise WayangError(502, "The Wayang cycle has an invalid decimal counter or position.")
    return int(value)


def http_client():
    return httpx.AsyncClient(timeout=httpx.Timeout(1200.0, connect=5.0))


async def driver(path, body=None):
    if not settings.WAYANG_URL:
        raise WayangError(503, "This installation has no Wayang service configured.")
    try:
        async with http_client() as client:
            url = settings.WAYANG_URL.rstrip("/") + path
            response = await client.get(url) if body is None else await client.post(
                url, content=body.encode("utf-8"), headers={"Content-Type": "application/json"})
    except httpx.HTTPError as error:
        raise WayangError(502, "The Wayang service could not be reached; execution may need reconciliation.") from error
    if len(response.content) > 1_048_576:
        raise WayangError(502, "The Wayang response exceeds its byte limit.")
    try:
        text = response.content.decode("utf-8")
        value = strict_json(text)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise WayangError(502, "The Wayang service returned invalid JSON.") from error
    if response.status_code != 200:
        message = value.get("error") if isinstance(value, dict) else None
        raise WayangError(response.status_code if response.status_code in (400, 409) else 502,
                          message if isinstance(message, str) else "The Wayang service refused the request.")
    if not isinstance(value, dict):
        raise WayangError(502, "The Wayang service returned an invalid object.")
    return text, value


def plan_assignments(plan):
    assignments = plan.get("assignments")
    if not isinstance(assignments, list) or not 1 <= len(assignments) <= 3:
        raise WayangError(502, "The Wayang plan has an invalid worker inventory.")
    nodes, topics, result = set(), set(), []
    for item in assignments:
        try:
            node, topic, request_text = item["node"], item["inputTopic"], item["requestText"]
            if not all(isinstance(value, str) and value for value in (node, topic, request_text)):
                raise ValueError()
            request = strict_json(request_text)
            assignment = strict_json(request["assignmentText"])
            inputs = assignment["inputs"]
            if (node in nodes or topic in topics or len(inputs) != 1 or inputs[0]["topic"] != topic
                    or assignment["node"] != node or assignment["runtime"] != "rust"
                    or assignment["mode"] != "bounded" or assignment["specHash"] != plan["specHash"]
                    or assignment["sourceDagText"] != plan["sourceDagText"]
                    or request["createdAtMicros"] != plan["createdAtMicros"]):
                raise ValueError()
            count = inputs[0]["partitionCount"]
            if not isinstance(count, str) or not re.fullmatch(r"[1-9][0-9]*", count) or int(count) > 128:
                raise ValueError()
            output = request["sink"]["topic"]
            if not isinstance(output, str) or not output:
                raise ValueError()
            if plan.get("schema") == "opentsx.local-continuous-rollout.v1":
                if (request.get("schema") != "opentsx.local-increment.v1"
                        or request.get("sequence") != plan.get("sequence")
                        or "previousRequestHash" not in request
                        or (request.get("previousRequestHash") is not None and
                            (not isinstance(request["previousRequestHash"], str)
                             or not re.fullmatch(r"[0-9a-f]{64}", request["previousRequestHash"])))):
                    raise ValueError()
                positions = inputs[0]["positions"]
                if not isinstance(positions, list) or len(positions) != int(count):
                    raise ValueError()
                for index, position in enumerate(positions):
                    if (not isinstance(position, dict) or set(position) != {"partition", "from", "to"}
                            or position["partition"] != str(index)
                            or decimal(position["from"]) > decimal(position["to"])):
                        raise ValueError()
        except (KeyError, TypeError, ValueError, RecursionError) as error:
            raise WayangError(502, "The Wayang plan has inconsistent whole-topic assignments.") from error
        nodes.add(node)
        topics.add(topic)
        result.append({"node": node, "inputTopic": topic, "partitionCount": count, "outputTopic": output,
                       "requestHash": digest(request_text), "specHash": assignment["specHash"]})
    if topics.intersection(item["outputTopic"] for item in result):
        raise WayangError(502, "The Wayang source and output topics overlap.")
    return result


def validate_plan(plan, deployment, dag_text, created_at, profile="bounded", previous=None):
    schema = "opentsx.local-continuous-rollout.v1" if profile == "continuous" else "opentsx.local-rollout.v1"
    if (plan.get("schema") != schema or plan.get("deployment") != deployment
            or plan.get("sourceDagText") != dag_text or plan.get("specHash") != digest(dag_text)
            or plan.get("createdAtMicros") != created_at
            or not isinstance(plan.get("planHash"), str) or not re.fullmatch(r"[0-9a-f]{64}", plan["planHash"])):
        raise WayangError(502, "The Wayang plan does not match the submitted graph and clock.")
    assignments = plan_assignments(plan)
    if profile == "continuous":
        expected = decimal(previous["sequence"], 1) + 1 if previous else 1
        if (decimal(plan.get("sequence"), 1) != expected or "previousPlanHash" not in plan
                or plan.get("previousPlanHash") != (previous["planHash"] if previous else None)):
            raise WayangError(502, "The Wayang cycle does not match its durable predecessor.")
        if previous:
            old = {item["node"]: item for item in plan_assignments(previous)}
            if set(old) != {item["node"] for item in assignments}:
                raise WayangError(502, "The Wayang continuation changed whole-topic placement.")
            for item in assignments:
                if any(item[key] != old[item["node"]][key] for key in
                       ("inputTopic", "outputTopic", "partitionCount", "specHash")):
                    raise WayangError(502, "The Wayang continuation changed whole-topic placement.")
        old_items = {item["node"]: item for item in previous["assignments"]} if previous else {}
        for item in plan["assignments"]:
            request = strict_json(item["requestText"])
            if not previous:
                if request["previousRequestHash"] is not None:
                    raise WayangError(502, "The first Wayang cycle has a predecessor request.")
                continue
            old_item = old_items[item["node"]]
            old_request = strict_json(old_item["requestText"])
            old_assignment = strict_json(old_request["assignmentText"])
            assignment = strict_json(request["assignmentText"])
            old_positions = old_assignment["inputs"][0]["positions"]
            if (item.get("endpoint") != old_item.get("endpoint")
                    or request["previousRequestHash"] != digest(old_item["requestText"])
                    or {key: value for key, value in assignment.items() if key != "inputs"}
                    != {key: value for key, value in old_assignment.items() if key != "inputs"}
                    or {key: value for key, value in assignment["inputs"][0].items() if key != "positions"}
                    != {key: value for key, value in old_assignment["inputs"][0].items() if key != "positions"}
                    or any(position["from"] != old_position["to"] for position, old_position in
                           zip(assignment["inputs"][0]["positions"], old_positions))):
                raise WayangError(502, "The Wayang continuation changed its checkpoint or placement.")
    return assignments


def public_deployment(row):
    assignments = plan_assignments(strict_json(row.plan_text))
    statuses = row.result.get("assignments", []) if row.result else []
    by_node = {item.get("node"): item for item in statuses if isinstance(item, dict)}
    for item in assignments:
        status = by_node.get(item["node"], {})
        item.update({key: status[key] for key in ("state", "receipt", "error") if key in status})
    result = {"id": row.id, "flowId": row.flow_id, "specHash": row.spec_hash, "planHash": row.plan_hash,
            "createdAtMicros": row.created_at_micros, "state": row.state, "error": row.error,
            "assignments": assignments}
    control = row.continuous
    if control is not None:
        result.update(profile="continuous", currentCycle=str(control.current_cycle),
                      completedCycles=str(control.completed_cycles), stopRequested=control.stop_requested,
                      inputRecords=control.totals["inputRecords"], outputRecords=control.totals["outputRecords"])
        for item in assignments:
            total = control.totals["assignments"].get(item["node"], {})
            item.update(cumulativeInputRecords=total.get("inputRecords", "0"),
                        cumulativeOutputRecords=total.get("outputRecords", "0"), positions=total.get("positions", []))
    return result


class Journal:
    def __init__(self, db):
        self.db = db

    async def own_flow(self, owner, flow_id):
        return (await self.db.execute(select(Flow).where(Flow.id == flow_id, Flow.owner_id == owner))).scalar_one_or_none()

    async def existing(self, owner, spec_hash):
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.owner_id == owner, WayangDeployment.spec_hash == spec_hash)
            .execution_options(populate_existing=True))).scalar_one_or_none()

    async def get(self, owner, deployment):
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.owner_id == owner, WayangDeployment.id == deployment)
            .execution_options(populate_existing=True))).scalar_one_or_none()

    async def list(self, owner):
        return (await self.db.execute(select(WayangDeployment).where(WayangDeployment.owner_id == owner)
                                    .order_by(WayangDeployment.created_at.desc()).limit(100)
                                    .execution_options(populate_existing=True))).scalars().all()

    async def accept(self, row, assignments, profile="bounded"):
        topics = {topic for item in assignments for topic in (item["inputTopic"], item["outputTopic"])}
        row.continuous = WayangContinuous(deployment_id=row.id) if profile == "continuous" else None
        self.db.add(row)
        try:
            await self.db.flush()
            if profile == "continuous":
                self.db.add(WayangCycle(deployment_id=row.id, number=1, plan_hash=row.plan_hash,
                                       plan_text=row.plan_text, state="queued"))
            for topic in sorted(topics):
                self.db.add(WayangTopicOwner(topic=topic, deployment_id=row.id))
            await self.db.commit()
        except IntegrityError as error:
            await self.db.rollback()
            existing = await self.existing(row.owner_id, row.spec_hash)
            if (existing and existing.flow_id == row.flow_id and existing.dag_text == row.dag_text
                    and ("continuous" if existing.continuous is not None else "bounded") == profile):
                return existing
            raise WayangError(409, "The deployment conflicts with an existing source identity or whole-topic owner.") from error
        return row

    async def next(self):
        return (await self.db.execute(select(WayangDeployment).outerjoin(
            WayangContinuous, WayangContinuous.deployment_id == WayangDeployment.id).where(or_(
                WayangDeployment.state.in_(["queued", "running", "stopping"]),
                and_(WayangDeployment.state == "idle", WayangContinuous.next_poll_at <= utc_now())))
            .order_by(WayangDeployment.updated_at, WayangDeployment.id).limit(1)
            .execution_options(populate_existing=True))).scalar_one_or_none()

    async def control(self, row):
        return (await self.db.execute(select(WayangContinuous).where(
            WayangContinuous.deployment_id == row.id).with_for_update()
            .execution_options(populate_existing=True))).scalar_one()

    async def cycle(self, row):
        return (await self.db.execute(select(WayangCycle).where(
            WayangCycle.deployment_id == row.id, WayangCycle.number == row.continuous.current_cycle)
            .execution_options(populate_existing=True))).scalar_one()

    async def stop(self, row):
        if row.continuous is None:
            raise WayangError(409, "Only continuous Wayang deployments support this stop operation.")
        control = await self.control(row)
        control.stop_requested = True
        if row.state != "stopped":
            row.state = "stopping"
        await self.save(row)
        return row

    async def save(self, row, terminal=False):
        row.updated_at = utc_now()
        if terminal:
            await self.db.execute(delete(WayangTopicOwner).where(WayangTopicOwner.deployment_id == row.id))
        await self.db.commit()


def validate_result(plan, result):
    if (result.get("deployment") != plan["deployment"] or result.get("planHash") != plan["planHash"]
            or result.get("state") not in ("completed", "failed") or not isinstance(result.get("assignments"), list)):
        raise WayangError(502, "The Wayang completion does not match the persisted plan.")
    expected = {item["node"] for item in plan_assignments(plan)}
    statuses = result["assignments"]
    if (any(not isinstance(item, dict) or not isinstance(item.get("node"), str) for item in statuses)
            or len({item["node"] for item in statuses}) != len(statuses)
            or any(item["node"] not in expected for item in statuses)):
        raise WayangError(502, "The Wayang completion has an invalid worker inventory.")
    if any(item.get("state") not in ("completed", "failed", "cancelled")
           or item.get("activationRetryable") is True
           or (item.get("error") is not None and not isinstance(item["error"], str)) for item in statuses):
        raise WayangError(502, "The Wayang worker outcome still requires reconciliation.")
    if result["state"] == "completed":
        if (len(statuses) != len(expected) or {item.get("node") for item in statuses} != expected
                or any(item.get("state") != "completed" or not isinstance(item.get("receipt"), dict) for item in statuses)):
            raise WayangError(502, "The Wayang completion omits a verified worker receipt.")


def accumulate(control, plan, result):
    totals = strict_json(json.dumps(control.totals))
    requests = {item["node"]: item for item in plan["assignments"]}
    for status in result["assignments"]:
        item = requests[status["node"]]
        request = strict_json(item["requestText"])
        assignment = strict_json(request["assignmentText"])
        positions = assignment["inputs"][0]["positions"]
        receipt = status["receipt"]
        expected_positions = [{"partition": p["partition"], "next": p["to"]} for p in positions]
        expected_count = sum(decimal(p["to"]) - decimal(p["from"]) for p in positions)
        if (receipt.get("schema") != "opentsx.local-receipt.v1" or receipt.get("requestHash") != digest(item["requestText"])
                or receipt.get("deployment") != plan["deployment"] or receipt.get("node") != status["node"]
                or receipt.get("inputTopic") != item["inputTopic"] or receipt.get("outputTopic") != request["sink"]["topic"]
                or receipt.get("positions") != expected_positions or type(receipt.get("inputRecords")) is not int
                or receipt["inputRecords"] != expected_count or type(receipt.get("outputRecords")) is not int
                or not 0 <= receipt["outputRecords"] <= 9_223_372_036_854_775_807):
            raise WayangError(502, "The Wayang cycle receipt does not match its frozen request and positions.")
        total = totals["assignments"].setdefault(status["node"], {"inputRecords": "0", "outputRecords": "0"})
        for key in ("inputRecords", "outputRecords"):
            total[key] = str(decimal(str(decimal(total[key]) + receipt[key])))
            totals[key] = str(decimal(str(decimal(totals[key]) + receipt[key])))
        total["positions"] = expected_positions
    return totals


async def finish_stop(journal, row, cycle):
    if cycle.state == "queued":
        cycle.state = "cancelled"
    row.state = "stopped"
    row.error = None
    await journal.save(row, terminal=True)


async def reconcile_continuous(journal, row):
    cycle = await journal.cycle(row)
    control = await journal.control(row)
    if control.stop_requested and cycle.state != "running":
        await finish_stop(journal, row, cycle)
        return
    if cycle.state == "completed":
        await journal.save(row)
        _, next_value = await driver("/v1/next", json.dumps({"plan": strict_json(cycle.plan_text), "result": cycle.result}))
        control = await journal.control(row)
        if control.stop_requested:
            await finish_stop(journal, row, cycle)
            return
        if set(next_value) != {"state", "plan"} or next_value["state"] not in ("idle", "ready"):
            raise WayangError(502, "The Wayang continuation response is invalid.")
        if next_value["state"] == "idle":
            if next_value["plan"] is not None:
                raise WayangError(502, "An idle Wayang continuation must not contain a plan.")
            control.next_poll_at = utc_now() + timedelta(seconds=2)
            row.state, row.error = "idle", None
        else:
            plan = next_value["plan"]
            if not isinstance(plan, dict):
                raise WayangError(502, "The Wayang continuation must contain a plan.")
            validate_plan(plan, row.id, row.dag_text, row.created_at_micros, "continuous", strict_json(cycle.plan_text))
            control.current_cycle = decimal(plan["sequence"], 1)
            row.plan_hash, row.plan_text = plan["planHash"], json.dumps(plan, ensure_ascii=False, separators=(",", ":"))
            row.state, row.result, row.error = "queued", None, None
            journal.db.add(WayangCycle(deployment_id=row.id, number=control.current_cycle,
                                      plan_hash=row.plan_hash, plan_text=row.plan_text, state="queued"))
        await journal.save(row)
        return
    if cycle.state not in ("queued", "running"):
        raise WayangError(502, "The continuous Wayang cycle requires an explicit stop after failure.")
    cycle.state = "running"
    row.state = "stopping" if control.stop_requested else "running"
    await journal.save(row)
    _, result = await driver("/v1/execute", cycle.plan_text)
    plan = strict_json(cycle.plan_text)
    validate_result(plan, result)
    control = await journal.control(row)
    if result["state"] == "completed":
        totals = accumulate(control, plan, result)
        control.totals = totals
        control.completed_cycles += 1
        control.next_poll_at = utc_now() + timedelta(seconds=2)
        await journal.db.execute(delete(WayangCycle).where(WayangCycle.deployment_id == row.id,
            WayangCycle.state == "completed", WayangCycle.number < cycle.number - 1))
    cycle.state, cycle.result = result["state"], result
    cycle.error = result.get("error") if isinstance(result.get("error"), str) else None
    cycle.updated_at = utc_now()
    row.result = result
    row.error = result.get("error") if isinstance(result.get("error"), str) else None
    if control.stop_requested:
        await finish_stop(journal, row, cycle)
    else:
        row.state = "idle" if result["state"] == "completed" else "failed"
        await journal.save(row)


async def reconcile_once(journal):
    row = await journal.next()
    if row is None:
        return False
    try:
        if row.continuous is not None:
            await reconcile_continuous(journal, row)
            return True
        row.state = "running"
        await journal.save(row)
        _, result = await driver("/v1/execute", row.plan_text)
        validate_result(strict_json(row.plan_text), result)
        row.state, row.result = result["state"], result
        row.error = result.get("error") if isinstance(result.get("error"), str) else None
        await journal.save(row, terminal=True)
    except WayangError as error:
        row.error = error.message
        if row.continuous is not None:
            control = await journal.control(row)
            control.next_poll_at = utc_now() + timedelta(seconds=2)
            if control.stop_requested:
                row.state = "stopping"
            elif error.status in (400, 409):
                row.state = "failed"
        await journal.save(row)
    return True
