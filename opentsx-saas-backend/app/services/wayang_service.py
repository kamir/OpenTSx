import hashlib
import json
import math
import re

import httpx
from sqlalchemy import delete, select
from sqlalchemy.exc import IntegrityError

from app.core.config import settings
from app.models.flow import Flow
from app.models.wayang import WayangDeployment, WayangTopicOwner, utc_now


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
        except (KeyError, TypeError, ValueError, RecursionError) as error:
            raise WayangError(502, "The Wayang plan has inconsistent whole-topic assignments.") from error
        nodes.add(node)
        topics.add(topic)
        result.append({"node": node, "inputTopic": topic, "partitionCount": count, "outputTopic": output,
                       "requestHash": digest(request_text), "specHash": assignment["specHash"]})
    if topics.intersection(item["outputTopic"] for item in result):
        raise WayangError(502, "The Wayang source and output topics overlap.")
    return result


def validate_plan(plan, deployment, dag_text, created_at):
    if (plan.get("schema") != "opentsx.local-rollout.v1" or plan.get("deployment") != deployment
            or plan.get("sourceDagText") != dag_text or plan.get("specHash") != digest(dag_text)
            or plan.get("createdAtMicros") != created_at
            or not isinstance(plan.get("planHash"), str) or not re.fullmatch(r"[0-9a-f]{64}", plan["planHash"])):
        raise WayangError(502, "The Wayang plan does not match the submitted graph and clock.")
    return plan_assignments(plan)


def public_deployment(row):
    assignments = plan_assignments(strict_json(row.plan_text))
    statuses = row.result.get("assignments", []) if row.result else []
    by_node = {item.get("node"): item for item in statuses if isinstance(item, dict)}
    for item in assignments:
        status = by_node.get(item["node"], {})
        item.update({key: status[key] for key in ("state", "receipt", "error") if key in status})
    return {"id": row.id, "flowId": row.flow_id, "specHash": row.spec_hash, "planHash": row.plan_hash,
            "createdAtMicros": row.created_at_micros, "state": row.state, "error": row.error,
            "assignments": assignments}


class Journal:
    def __init__(self, db):
        self.db = db

    async def own_flow(self, owner, flow_id):
        return (await self.db.execute(select(Flow).where(Flow.id == flow_id, Flow.owner_id == owner))).scalar_one_or_none()

    async def existing(self, owner, spec_hash):
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.owner_id == owner, WayangDeployment.spec_hash == spec_hash))).scalar_one_or_none()

    async def get(self, owner, deployment):
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.owner_id == owner, WayangDeployment.id == deployment))).scalar_one_or_none()

    async def list(self, owner):
        return (await self.db.execute(select(WayangDeployment).where(WayangDeployment.owner_id == owner)
                                    .order_by(WayangDeployment.created_at.desc()).limit(100))).scalars().all()

    async def accept(self, row, assignments):
        topics = {topic for item in assignments for topic in (item["inputTopic"], item["outputTopic"])}
        self.db.add(row)
        try:
            await self.db.flush()
            for topic in sorted(topics):
                self.db.add(WayangTopicOwner(topic=topic, deployment_id=row.id))
            await self.db.commit()
        except IntegrityError as error:
            await self.db.rollback()
            existing = await self.existing(row.owner_id, row.spec_hash)
            if existing and existing.flow_id == row.flow_id and existing.dag_text == row.dag_text:
                return existing
            raise WayangError(409, "The deployment conflicts with an existing source identity or whole-topic owner.") from error
        return row

    async def next(self):
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.state.in_(["queued", "running"]))
            .order_by(WayangDeployment.updated_at, WayangDeployment.id).limit(1))).scalar_one_or_none()

    async def save(self, row, terminal=False):
        row.updated_at = utc_now()
        if terminal:
            await self.db.execute(delete(WayangTopicOwner).where(WayangTopicOwner.deployment_id == row.id))
        await self.db.commit()


async def reconcile_once(journal):
    row = await journal.next()
    if row is None:
        return False
    row.state = "running"
    await journal.save(row)
    try:
        _, result = await driver("/v1/execute", row.plan_text)
        if (result.get("deployment") != row.id or result.get("planHash") != row.plan_hash
                or result.get("state") not in ("completed", "failed") or not isinstance(result.get("assignments"), list)):
            raise WayangError(502, "The Wayang completion does not match the persisted plan.")
        expected = {item["node"] for item in plan_assignments(strict_json(row.plan_text))}
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
        row.state, row.result = result["state"], result
        row.error = result.get("error") if isinstance(result.get("error"), str) else None
        await journal.save(row, terminal=True)
    except WayangError as error:
        row.error = error.message
        await journal.save(row)
    return True
