import hashlib
import json
import math
import re
import uuid
from datetime import timedelta

import httpx
from sqlalchemy import and_, delete, or_, select, text
from sqlalchemy.exc import IntegrityError

from app.core.config import settings
from app.models.flow import Flow
from app.models.wayang import WayangContinuous, WayangCycle, WayangDeployment, WayangPublication, WayangSwitch, WayangTopicOwner, utc_now


class WayangError(Exception):
    def __init__(self, status, message, refusal=None):
        self.status = status
        self.message = message
        self.refusal = refusal
        super().__init__(message)

    @property
    def detail(self):
        return self.refusal if self.refusal is not None else self.message


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


def legacy_plan_hash(plan):
    def encode(value):
        if isinstance(value, dict):
            return "{" + ",".join(encode(key) + ":" + encode(value[key])
                                  for key in sorted(value, key=lambda key: key.encode("utf-16-be"))) + "}"
        if isinstance(value, list):
            return "[" + ",".join(encode(entry) for entry in value) + "]"
        text = json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
        return re.sub(r"\\u00[0-9a-f]{2}", lambda match: match.group().upper().replace("\\U", "\\u"), text)

    return digest(encode({key: value for key, value in plan.items() if key != "planHash"}))


def decimal(value, minimum=0):
    if (not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,18}", value)
            or not minimum <= int(value) <= 9_223_372_036_854_775_807):
        raise WayangError(502, "The Wayang cycle has an invalid decimal counter or position.")
    return int(value)


def http_client():
    return httpx.AsyncClient(timeout=httpx.Timeout(1200.0, connect=5.0))


async def driver(path, body=None, physical=False):
    if not settings.WAYANG_URL:
        raise WayangError(503, "This installation has no Wayang service configured.")
    try:
        async with http_client() as client:
            url = settings.WAYANG_URL.rstrip("/") + path
            headers = {"X-OpenTSx-Run-Token": settings.DAG_RUN_TOKEN} if settings.DAG_RUN_TOKEN else {}
            response = await client.get(url, headers=headers) if body is None else await client.post(
                url, content=body.encode("utf-8"), headers={**headers, "Content-Type": "application/json"})
    except httpx.HTTPError as error:
        raise WayangError(502, "The Wayang service could not be reached; execution may need reconciliation.") from error
    if len(response.content) > 1_048_576:
        raise WayangError(502, "The Wayang response exceeds its byte limit.")
    try:
        text = response.content.decode("utf-8")
        if physical:
            from app.services.wayang_contract import parse_document
            value = parse_document(text)
        else:
            value = strict_json(text)
    except (ValueError, UnicodeError, RecursionError) as error:
        raise WayangError(502, "The Wayang service returned invalid JSON.") from error
    if physical and not isinstance(value, dict):
        from app.services.wayang_contract import refuse
        refuse("deployment.fields", "/")
    if response.status_code != 200:
        if isinstance(value, dict) and "refusal" in value:
            from app.services.wayang_contract import public_refusal
            refusal = public_refusal(value["refusal"])
            if refusal is None:
                raise WayangError(502, "The Wayang service returned an invalid located refusal.")
            raise WayangError(response.status_code if response.status_code in (400, 409) else 502,
                              refusal["message"], refusal)
        message = value.get("error") if isinstance(value, dict) else None
        raise WayangError(response.status_code if response.status_code in (400, 409) else 502,
                          message if isinstance(message, str) else "The Wayang service refused the request.")
    if not isinstance(value, dict):
        raise WayangError(502, "The Wayang service returned an invalid object.")
    return text, value


async def publication_fencing():
    if not settings.DAG_RUN_TOKEN:
        return False
    _, capabilities = await driver("/v1/capabilities")
    enabled = capabilities.get("publicationFencing", False)
    modes = capabilities.get("switchModes", [])
    if type(enabled) is not bool or modes != (["resume", "restart"] if enabled else []):
        raise WayangError(502, "The Wayang service returned incompatible publication fencing capabilities.")
    return enabled


def plan_assignments(plan):
    if plan.get("schema") == "opentsx.physical-deployment.v1":
        from app.services.wayang_contract import assignments
        return assignments(plan)
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
    if profile == "physical":
        from app.services.wayang_contract import validate
        return validate(plan, deployment, dag_text, created_at)
    schema = "opentsx.local-continuous-rollout.v1" if profile == "continuous" else "opentsx.local-rollout.v1"
    if (plan.get("schema") != schema or plan.get("deployment") != deployment
            or plan.get("sourceDagText") != dag_text or plan.get("specHash") != digest(dag_text)
            or plan.get("createdAtMicros") != created_at
            or not isinstance(plan.get("planHash"), str) or not re.fullmatch(r"[0-9a-f]{64}", plan["planHash"])
            or plan["planHash"] != legacy_plan_hash(plan)):
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


def validate_replacement(old, new):
    previous = {item["inputTopic"]: item for item in plan_assignments(old)}
    replacement = {item["inputTopic"]: item for item in plan_assignments(new)}
    if set(previous) != set(replacement):
        raise WayangError(409, "A replacement must retain the complete input-topic set.")
    for topic, item in replacement.items():
        if any(item[key] != previous[topic][key] for key in ("node", "partitionCount")):
            raise WayangError(409, "A replacement must retain whole-topic placement and partition inventory.")
    before = {item["outputTopic"] for item in previous.values()}
    after = {item["outputTopic"] for item in replacement.values()}
    if len(before) != 1 or len(after) != 1 or before == after:
        raise WayangError(409, "A changed DAG requires a distinct fresh output topic.")
    endpoints = {item["inputTopic"]: item["endpoint"] for item in old["assignments"]}
    if any(item["endpoint"] != endpoints[item["inputTopic"]] for item in new["assignments"]):
        raise WayangError(409, "A replacement cannot change its worker endpoints.")
    return next(iter(after))


def public_deployment(row, operation=None):
    plan = strict_json(row.plan_text)
    assignments = plan_assignments(plan)
    statuses = row.result.get("assignments", []) if row.result else []
    by_node = {item.get("node"): item for item in statuses if isinstance(item, dict)}
    for item in assignments:
        status = by_node.get(item["node"], {})
        item.update({key: status[key] for key in ("state", "receipt", "error") if key in status})
        if plan.get("schema") == "opentsx.physical-deployment.v1" and isinstance(status.get("receipt"), dict):
            item["requestHash"] = status["receipt"]["requestHash"]
    result = {"id": row.id, "flowId": row.flow_id, "specHash": row.spec_hash, "planHash": row.plan_hash,
            "createdAtMicros": row.created_at_micros, "state": row.state, "error": row.error,
            "assignments": assignments}
    if plan.get("schema") == "opentsx.physical-deployment.v1":
        result["profile"] = "physical"
    control = row.continuous
    if control is not None:
        result.update(profile="continuous", currentCycle=str(control.current_cycle),
                      completedCycles=str(control.completed_cycles), stopRequested=control.stop_requested,
                      inputRecords=control.totals["inputRecords"], outputRecords=control.totals["outputRecords"])
        for item in assignments:
            total = control.totals["assignments"].get(item["node"], {})
            item.update(cumulativeInputRecords=total.get("inputRecords", "0"),
                        cumulativeOutputRecords=total.get("outputRecords", "0"), positions=total.get("positions", []))
    publication = row.publication
    if publication is not None:
        result["publicationEpoch"] = str(publication.epoch)
        result["publicationRetired"] = publication.retired
        current = operation if operation is not None else publication.operation
        if current is not None:
            result["switch"] = {"operation": current.id, "mode": current.mode, "state": current.state,
                                "expectedEpoch": str(current.expected_epoch), "deployment": current.replacement_id or row.id,
                                "publicationEpoch": str(current.publication_epoch) if current.publication_epoch is not None else None,
                                "error": current.error}
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

    async def accept(self, row, assignments, profile="bounded", fenced=False):
        topics = {topic for item in assignments for topic in (item["inputTopic"], item["outputTopic"])}
        await self.lock_owners()
        owners = (await self.db.execute(select(WayangTopicOwner).where(
            WayangTopicOwner.topic.in_(sorted(topics))))).scalars().all()
        if profile == "physical":
            from app.services.wayang_contract import ownership_claims
            claims = ownership_claims(strict_json(row.plan_text))
            if any(owner.scope == "legacy" or (owner.fragment, owner.cluster, owner.incarnation, owner.topic) in claims
                   for owner in owners):
                raise WayangError(409, "The deployment conflicts with an existing source identity or whole-topic owner.")
        elif owners:
            raise WayangError(409, "The deployment conflicts with an existing source identity or whole-topic owner.")
        row.continuous = WayangContinuous(deployment_id=row.id) if profile == "continuous" else None
        row.publication = WayangPublication(deployment_id=row.id, operation=None) if fenced else None
        self.db.add(row)
        try:
            await self.db.flush()
            if profile == "continuous":
                self.db.add(WayangCycle(deployment_id=row.id, number=1, plan_hash=row.plan_hash,
                                       plan_text=row.plan_text, state="queued"))
            if profile == "physical":
                for fragment, cluster, incarnation, topic in claims:
                    self.db.add(WayangTopicOwner(scope="physical", fragment=fragment, cluster=cluster,
                                                incarnation=incarnation, topic=topic, deployment_id=row.id))
            else:
                for topic in sorted(topics):
                    self.db.add(WayangTopicOwner(topic=topic, deployment_id=row.id))
            await self.db.commit()
        except IntegrityError as error:
            await self.db.rollback()
            existing = await self.existing(row.owner_id, row.spec_hash)
            if (existing and existing.flow_id == row.flow_id and existing.dag_text == row.dag_text
                    and public_deployment(existing).get("profile", "bounded") == profile):
                return existing
            raise WayangError(409, "The deployment conflicts with an existing source identity or whole-topic owner.") from error
        return row

    async def lock_owners(self):
        bind = self.db.get_bind() if hasattr(self.db, "get_bind") else self.db.session.get_bind()
        if bind.dialect.name == "postgresql":
            await self.db.execute(text("LOCK TABLE wayang_topic_owners IN SHARE ROW EXCLUSIVE MODE"))
        elif bind.dialect.name == "sqlite":
            await self.db.execute(text("UPDATE wayang_topic_owners SET deployment_id = deployment_id WHERE 0"))
        else:
            raise WayangError(503, "The Wayang ownership database does not support admission locking.")

    async def next(self):
        return (await self.db.execute(select(WayangDeployment).outerjoin(
            WayangContinuous, WayangContinuous.deployment_id == WayangDeployment.id).where(or_(
                WayangDeployment.state.in_(["queued", "running", "stopping", "switching"]),
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

    async def publication(self, row):
        if row.publication is None:
            return None
        return (await self.db.execute(select(WayangPublication).where(
            WayangPublication.deployment_id == row.id).with_for_update(of=WayangPublication)
            .execution_options(populate_existing=True))).scalar_one()

    async def switch(self, row, expected_epoch):
        return (await self.db.execute(select(WayangSwitch).where(
            WayangSwitch.deployment_id == row.id, WayangSwitch.expected_epoch == expected_epoch)
            .execution_options(populate_existing=True))).scalar_one_or_none()

    async def replacement(self, operation):
        if operation.replacement_id is None:
            return None
        return (await self.db.execute(select(WayangDeployment).where(
            WayangDeployment.id == operation.replacement_id).execution_options(populate_existing=True))).scalar_one()

    async def output_available(self, topic):
        return (await self.db.execute(select(WayangTopicOwner).where(WayangTopicOwner.topic == topic).limit(1))).scalar_one_or_none() is None

    async def replace(self, row, expected_epoch, mode, body_hash, replacement=None):
        await self.lock_owners()
        control = await self.control(row) if row.continuous is not None else None
        publication = await self.publication(row)
        existing = await self.switch(row, expected_epoch)
        if existing is not None:
            if existing.body_hash != body_hash:
                raise WayangError(409, "This publication epoch already has another replacement request.")
            return existing
        if (publication is None or publication.retired or publication.epoch != expected_epoch
                or control is None or control.stop_requested or control.completed_cycles < 1
                or row.state not in ("idle", "queued", "running")):
            raise WayangError(409, "Replacement requires an active fenced deployment and its current publication epoch.")
        if publication.operation is not None and publication.operation.state == "pending":
            raise WayangError(409, "A deployment replacement is already pending.")
        deployment_id, owner_id = row.id, row.owner_id
        operation = WayangSwitch(id="switch-" + str(uuid.uuid4()), deployment_id=row.id,
                                expected_epoch=expected_epoch, mode=mode, body_hash=body_hash,
                                replacement_id=replacement.id if replacement is not None else None, state="pending")
        try:
            if replacement is not None:
                replacement.continuous = WayangContinuous(deployment_id=replacement.id)
                replacement.publication = WayangPublication(deployment_id=replacement.id, epoch=0, operation=None)
                self.db.add(replacement)
                await self.db.flush()
                replacement.publication.operation = operation
                self.db.add(WayangCycle(deployment_id=replacement.id, number=1, plan_hash=replacement.plan_hash,
                                        plan_text=replacement.plan_text, state="queued"))
                output = plan_assignments(strict_json(replacement.plan_text))[0]["outputTopic"]
                if not await self.output_available(output):
                    raise WayangError(409, "The replacement output topic is already owned.")
                self.db.add(WayangTopicOwner(topic=output, deployment_id=replacement.id))
            publication.operation = operation
            row.state, row.error = "switching", None
            self.db.add(operation)
            await self.save(row)
        except IntegrityError as error:
            await self.db.rollback()
            reloaded = await self.get(owner_id, deployment_id)
            existing = await self.switch(reloaded, expected_epoch) if reloaded is not None else None
            if existing is not None and existing.body_hash == body_hash:
                return existing
            raise WayangError(409, "The replacement conflicts with another source identity or pending switch.") from error
        return operation

    async def stop(self, row):
        if row.continuous is None:
            raise WayangError(409, "Only continuous Wayang deployments support this stop operation.")
        control = await self.control(row)
        publication = await self.publication(row)
        if row.state == "replaced":
            raise WayangError(409, "This deployment has been replaced.")
        if publication is not None and publication.operation is not None and publication.operation.state == "pending":
            raise WayangError(409, "A deployment replacement is still pending.")
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
    if (not isinstance(result, dict) or result.get("deployment") != plan["deployment"] or result.get("planHash") != plan["planHash"]
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
    if plan.get("schema") == "opentsx.physical-deployment.v1":
        from app.services.wayang_contract import validate_receipts
        validate_receipts(plan, statuses)


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


def pending_switch(publication):
    return publication is not None and publication.operation is not None and publication.operation.state == "pending"


def publication_reply(value, operation, deployment, state, previous):
    if (set(value) != {"operation", "publicationEpoch", "state", "deployment"}
            or value["operation"] != operation or value["deployment"] != deployment or value["state"] != state):
        raise WayangError(502, "The Wayang publication transition response is invalid.")
    epoch = decimal(value["publicationEpoch"], 1)
    if epoch <= previous:
        raise WayangError(502, "The Wayang publication epoch did not advance.")
    return epoch


async def reconcile_switch(journal, row, cycle, publication):
    operation = publication.operation
    replacement = await journal.replacement(operation)
    body = {"operation": operation.id, "mode": operation.mode, "expectedEpoch": str(operation.expected_epoch),
            "plan": strict_json(cycle.plan_text), "result": cycle.result,
            "replacement": strict_json(replacement.plan_text) if replacement is not None else None}
    await journal.save(row)
    _, value = await driver("/v1/switch", json.dumps(body))
    epoch = publication_reply(value, operation.id, replacement.id if replacement is not None else row.id,
                              "active", operation.expected_epoch)
    control = await journal.control(row)
    publication = await journal.publication(row)
    if publication.epoch != operation.expected_epoch or publication.retired:
        raise WayangError(502, "The durable publication authority changed during replacement.")
    if replacement is None:
        publication.epoch = epoch
        row.state, row.error = "idle", None
        control.next_poll_at = utc_now() + timedelta(seconds=2)
    else:
        old_assignments = plan_assignments(strict_json(row.plan_text))
        new_assignments = plan_assignments(strict_json(replacement.plan_text))
        topics = {item["inputTopic"] for item in old_assignments}
        owners = (await journal.db.execute(select(WayangTopicOwner).where(
            WayangTopicOwner.topic.in_(sorted(topics))).with_for_update())).scalars().all()
        if len(owners) != len(topics) or any(owner.deployment_id != row.id for owner in owners):
            raise WayangError(502, "The replacement lost its complete input-topic ownership.")
        output = new_assignments[0]["outputTopic"]
        reserved = (await journal.db.execute(select(WayangTopicOwner).where(
            WayangTopicOwner.topic == output).with_for_update())).scalar_one_or_none()
        if reserved is None or reserved.deployment_id != replacement.id:
            raise WayangError(502, "The replacement lost its reserved output topic.")
        for owner in owners:
            owner.deployment_id = replacement.id
        await journal.db.execute(delete(WayangTopicOwner).where(
            WayangTopicOwner.deployment_id == row.id, WayangTopicOwner.topic.not_in(sorted(topics))))
        publication.retired = True
        replacement.publication.epoch = epoch
        replacement.state, replacement.error = "queued", None
        row.state, row.error = "replaced", None
    operation.state, operation.publication_epoch, operation.error = "active", epoch, None
    operation.updated_at = utc_now()
    await journal.save(row)


async def retire_publication(journal, row):
    publication = await journal.publication(row)
    if pending_switch(publication):
        raise WayangError(409, "A deployment replacement is still pending.")
    if publication is not None and not publication.retired:
        if publication.retire_operation is None:
            publication.retire_operation = "retire-" + str(uuid.uuid4())
        body = {"operation": publication.retire_operation, "deployment": row.id, "expectedEpoch": str(publication.epoch)}
        await journal.save(row)
        _, value = await driver("/v1/retire", json.dumps(body))
        epoch = publication_reply(value, publication.retire_operation, row.id, "retired", publication.epoch)
        publication = await journal.publication(row)
        publication.epoch, publication.retired = epoch, True


async def finish_stop(journal, row, cycle):
    await journal.save(row)
    await retire_publication(journal, row)
    if cycle.state == "queued":
        cycle.state = "cancelled"
    row.state = "stopped"
    row.error = None
    await journal.save(row, terminal=True)


async def execute_publication(journal, row, result):
    publication = await journal.publication(row)
    if publication is not None:
        epoch = decimal(result.get("publicationEpoch"), 1)
        if publication.epoch not in (0, epoch) or publication.retired:
            raise WayangError(502, "The Wayang completion has another publication epoch.")
        publication.epoch = epoch
    elif "publicationEpoch" in result:
        raise WayangError(502, "Fenced execution requires persisted publication authority.")
    return publication


async def reconcile_continuous(journal, row):
    cycle = await journal.cycle(row)
    control = await journal.control(row)
    publication = await journal.publication(row)
    if pending_switch(publication) and cycle.state == "completed":
        await reconcile_switch(journal, row, cycle, publication)
        return
    if control.stop_requested and cycle.state != "running":
        await finish_stop(journal, row, cycle)
        return
    if cycle.state == "completed":
        await journal.save(row)
        _, next_value = await driver("/v1/next", json.dumps({"plan": strict_json(cycle.plan_text), "result": cycle.result}))
        control = await journal.control(row)
        publication = await journal.publication(row)
        if pending_switch(publication):
            row.state = "switching"
            await journal.save(row)
            return
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
    row.state = "switching" if pending_switch(publication) else "stopping" if control.stop_requested else "running"
    await journal.save(row)
    _, result = await driver("/v1/execute", cycle.plan_text)
    plan = strict_json(cycle.plan_text)
    validate_result(plan, result)
    control = await journal.control(row)
    publication = await execute_publication(journal, row, result)
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
    if pending_switch(publication):
        if result["state"] == "failed":
            replacement = await journal.replacement(publication.operation)
            if replacement is not None:
                replacement.state = "failed"
                replacement.error = "The preceding deployment failed before the replacement boundary."
            publication.operation.state = "failed"
            publication.operation.error = row.error or "The admitted cycle failed before the replacement boundary."
            row.state = "failed"
        else:
            row.state = "switching"
        await journal.save(row)
    elif control.stop_requested:
        await finish_stop(journal, row, cycle)
    else:
        row.state = "idle" if result["state"] == "completed" else "failed"
        await journal.save(row)


async def reconcile_once(journal):
    row = await journal.next()
    if row is None:
        return False
    try:
        plan = strict_json(row.plan_text)
        physical = plan.get("schema") == "opentsx.physical-deployment.v1"
        if physical:
            validate_plan(plan, row.id, row.dag_text, row.created_at_micros, "physical")
        if not physical and row.publication is None and await publication_fencing():
            row.publication = WayangPublication(deployment_id=row.id, epoch=0, operation=None)
            await journal.save(row)
        if row.continuous is not None:
            await reconcile_continuous(journal, row)
            return True
        row.state = "running"
        if row.result is None:
            await journal.save(row)
            _, result = await driver("/v1/execute", row.plan_text, physical=physical)
        else:
            result = row.result
        validate_result(strict_json(row.plan_text), result)
        await execute_publication(journal, row, result)
        row.result = result
        row.error = result.get("error") if isinstance(result.get("error"), str) else None
        await journal.save(row)
        await retire_publication(journal, row)
        row.state = result["state"]
        await journal.save(row, terminal=True)
    except WayangError as error:
        row.error = error.message
        if row.continuous is not None:
            control = await journal.control(row)
            control.next_poll_at = utc_now() + timedelta(seconds=2)
            if control.stop_requested:
                row.state = "stopping"
            elif pending_switch(row.publication):
                row.state = "switching"
                row.publication.operation.error = error.message
            elif error.status in (400, 409):
                row.state = "failed"
        await journal.save(row)
    return True
