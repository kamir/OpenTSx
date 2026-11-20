import json
import time
import uuid
from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, ConfigDict, Field, StrictInt, StrictStr, ValidationError

from app.api.dags import may_run
from app.core.config import settings
from app.core.deps import get_current_user
from app.db.session import get_db
from app.models.user import User
from app.models.wayang import WayangDeployment
from app.services.wayang_service import Journal, WayangError, decimal, digest, driver, publication_fencing, public_deployment, strict_json, validate_plan, validate_replacement


router = APIRouter(prefix="/dags/wayang-rust", tags=["Wayang execution"])


class DeployRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    flowId: StrictInt = Field(gt=0, le=2_147_483_647)
    dagText: StrictStr = Field(min_length=1)
    profile: Literal["bounded", "continuous", "physical"] = "bounded"


class ReplaceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    expectedEpoch: StrictStr
    mode: Literal["resume", "restart"]
    flowId: StrictInt | None = Field(default=None, gt=0, le=2_147_483_647)
    dagText: StrictStr | None = Field(default=None, min_length=1)


async def get_journal(db=Depends(get_db)):
    return Journal(db)


def allowed(user):
    if not may_run(user):
        raise HTTPException(403, "Only administrators may run Wayang deployments on this installation.")


@router.get("/capabilities")
async def capabilities(user: User = Depends(get_current_user)):
    if not may_run(user):
        return {"canRun": False, "reason": "Only administrators may run Wayang deployments on this installation."}
    if not settings.WAYANG_URL:
        return {"canRun": False, "reason": "This installation has no Wayang service configured."}
    try:
        _, value = await driver("/v1/capabilities")
        if (value.get("schema") != "opentsx.local-rollout-capabilities.v1" or value.get("profile") != "bounded"
                or not isinstance(value.get("workers"), list) or not 1 <= len(value["workers"]) <= 3
                or any(not isinstance(worker, dict) or not isinstance(worker.get("node"), str)
                       for worker in value["workers"])
                or len({worker["node"] for worker in value["workers"]}) != len(value["workers"])
                or not isinstance(value.get("operators"), list)
                or not all(isinstance(operator, str) for operator in value["operators"])
                or not isinstance(value.get("profiles", ["bounded"]), list)
                or value.get("profiles", ["bounded"]) not in (
                    ["bounded"], ["bounded", "continuous"], ["bounded", "physical"], ["bounded", "continuous", "physical"])):
            raise WayangError(502, "The Wayang service returned incompatible capabilities.")
        fenced = value.get("publicationFencing", False)
        modes = value.get("switchModes", [])
        if type(fenced) is not bool or modes != (["resume", "restart"] if fenced else []):
            raise WayangError(502, "The Wayang service returned incompatible publication fencing capabilities.")
        workers = [{key: worker[key] for key in ("node", "site", "artifactDigest", "operators", "limits") if key in worker}
                   for worker in value["workers"]]
        return {"canRun": True, "reason": None, "profile": "bounded", "profiles": value.get("profiles", ["bounded"]),
                "workers": workers, "operators": value["operators"], "publicationFencing": fenced, "switchModes": modes}
    except WayangError as error:
        return {"canRun": False, "reason": error.message, **({"refusal": error.refusal} if error.refusal else {})}


@router.post("/deploy", status_code=202)
async def deploy(request: Request, user: User = Depends(get_current_user), journal=Depends(get_journal)):
    allowed(user)
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 262_144:
            raise HTTPException(413, "The bounded deployment request exceeds its byte limit.")
    try:
        data = DeployRequest.model_validate(strict_json(body.decode("utf-8")))
        if len(data.dagText.encode("utf-8")) > 32_768:
            raise HTTPException(413, "The DAG exceeds the bounded source-text limit.")
    except (ValueError, UnicodeError, RecursionError, ValidationError) as error:
        raise HTTPException(422, "Expected an exact flowId and UTF-8 dagText object.") from error
    if await journal.own_flow(user.id, data.flowId) is None:
        raise HTTPException(404, "Flow not found")
    spec_hash = digest(data.dagText)
    existing = await journal.existing(user.id, spec_hash)
    if existing:
        if existing.flow_id != data.flowId or existing.dag_text != data.dagText:
            raise HTTPException(409, "This source identity belongs to another flow.")
        if public_deployment(existing).get("profile", "bounded") != data.profile:
            raise HTTPException(409, "This source identity already uses another Wayang profile.")
        if data.profile == "physical":
            try:
                validate_plan(strict_json(existing.plan_text), existing.id, existing.dag_text,
                              existing.created_at_micros, "physical")
            except WayangError as error:
                raise HTTPException(error.status, error.detail) from error
        return public_deployment(existing)
    deployment, created_at = "local-" + str(uuid.uuid4()), str(time.time_ns() // 1000)
    try:
        fenced = await publication_fencing() if data.profile != "physical" else False
        payload = {"deployment": deployment, "dagText": data.dagText, "createdAtMicros": created_at}
        if data.profile != "bounded":
            payload["profile"] = data.profile
        plan_text, plan = await driver("/v1/plan", json.dumps(payload), physical=data.profile == "physical")
        assignments = validate_plan(plan, deployment, data.dagText, created_at, data.profile)
        row = WayangDeployment(id=deployment, owner_id=user.id, flow_id=data.flowId, spec_hash=spec_hash,
                               dag_text=data.dagText, created_at_micros=created_at, plan_hash=plan["planHash"],
                               plan_text=plan_text, state="queued")
        return public_deployment(await journal.accept(row, assignments, data.profile, fenced))
    except WayangError as error:
        raise HTTPException(error.status, error.detail) from error


@router.post("/deployments/{deployment}/replace", status_code=202)
async def replace(deployment: str, request: Request, user: User = Depends(get_current_user), journal=Depends(get_journal)):
    allowed(user)
    row = await journal.get(user.id, deployment)
    if row is None:
        raise HTTPException(404, "Deployment not found")
    body = bytearray()
    async for chunk in request.stream():
        body.extend(chunk)
        if len(body) > 262_144:
            raise HTTPException(413, "The replacement request exceeds its byte limit.")
    try:
        data = ReplaceRequest.model_validate(strict_json(body.decode("utf-8")))
        expected = decimal(data.expectedEpoch, 1)
        if data.mode == "resume" and (data.flowId is not None or data.dagText is not None):
            raise ValueError()
        if data.mode == "restart" and (data.flowId is None or data.dagText is None):
            raise ValueError()
        if data.dagText is not None and len(data.dagText.encode("utf-8")) > 32_768:
            raise HTTPException(413, "The replacement DAG exceeds its source-text limit.")
    except (ValueError, UnicodeError, RecursionError, ValidationError, WayangError) as error:
        raise HTTPException(422, "Expected a canonical expectedEpoch, replacement mode and matching flowId/dagText fields.") from error
    body_hash = digest(json.dumps(data.model_dump(exclude_none=True), sort_keys=True, separators=(",", ":")))
    existing = await journal.switch(row, expected)
    if existing is not None:
        if existing.body_hash != body_hash:
            raise HTTPException(409, "This publication epoch already has another replacement request.")
        return public_deployment(row, existing)
    if (row.continuous is None or row.publication is None or row.publication.retired
            or row.publication.epoch != expected or row.continuous.completed_cycles < 1
            or row.state not in ("idle", "queued", "running")):
        raise HTTPException(409, "Replacement requires an active fenced deployment and its current publication epoch.")
    try:
        replacement = None
        if data.mode == "restart":
            if await journal.own_flow(user.id, data.flowId) is None:
                raise HTTPException(404, "Flow not found")
            if data.dagText == row.dag_text or digest(data.dagText) == row.spec_hash:
                raise HTTPException(409, "Restart requires changed DAG text and a distinct fresh output topic.")
            identifier, clock = "local-" + str(uuid.uuid4()), str(time.time_ns() // 1000)
            plan_text, plan = await driver("/v1/plan", json.dumps({"deployment": identifier, "dagText": data.dagText,
                                                                  "createdAtMicros": clock, "profile": "continuous"}))
            validate_plan(plan, identifier, data.dagText, clock, "continuous")
            output = validate_replacement(strict_json(row.plan_text), plan)
            if not await journal.output_available(output):
                raise HTTPException(409, "The replacement output topic is already owned.")
            replacement = WayangDeployment(id=identifier, owner_id=user.id, flow_id=data.flowId,
                                           spec_hash=digest(data.dagText), dag_text=data.dagText,
                                           created_at_micros=clock, plan_hash=plan["planHash"], plan_text=plan_text, state="waiting")
        operation = await journal.replace(row, expected, data.mode, body_hash, replacement)
        return public_deployment(row, operation)
    except WayangError as error:
        raise HTTPException(error.status, error.detail) from error


@router.get("/deployments")
async def deployments(user: User = Depends(get_current_user), journal=Depends(get_journal)):
    allowed(user)
    return {"deployments": [public_deployment(row) for row in await journal.list(user.id)]}


@router.get("/deployments/{deployment}")
async def deployment(deployment: str, user: User = Depends(get_current_user), journal=Depends(get_journal)):
    allowed(user)
    row = await journal.get(user.id, deployment)
    if row is None:
        raise HTTPException(404, "Deployment not found")
    return public_deployment(row)


@router.post("/deployments/{deployment}/stop", status_code=202)
async def stop(deployment: str, user: User = Depends(get_current_user), journal=Depends(get_journal)):
    allowed(user)
    row = await journal.get(user.id, deployment)
    if row is None:
        raise HTTPException(404, "Deployment not found")
    try:
        return public_deployment(await journal.stop(row))
    except WayangError as error:
        raise HTTPException(error.status, error.detail) from error
