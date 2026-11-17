"""MLflow metadata and data-only fitted artifacts for the local workbench."""

import json
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit
from urllib.request import Request, urlopen


class RegistryUnavailable(RuntimeError):
    pass


class RegistryError(RuntimeError):
    def __init__(self, status, code):
        super().__init__(f"MLflow request failed: HTTP {status}, {code}")
        self.status = status
        self.code = code


class MLflowRegistry:
    def __init__(self, base_url, opener=urlopen):
        parsed = urlsplit(base_url)
        if (
            parsed.scheme != "http"
            or not parsed.hostname
            or parsed.username
            or parsed.password
            or parsed.query
            or parsed.fragment
        ):
            raise ValueError("registry URL must be an HTTP service URL without credentials")
        self.base_url = base_url.rstrip("/")
        self.opener = opener

    def request(self, method, path, body=None, raw=False):
        data = (
            None
            if body is None
            else (body if isinstance(body, bytes) else json.dumps(body, allow_nan=False).encode())
        )
        request = Request(
            self.base_url + "/api/" + path,
            data=data,
            method=method,
            headers={
                "Content-Type": (
                    "application/octet-stream" if isinstance(body, bytes) else "application/json"
                )
            },
        )
        try:
            with self.opener(request, timeout=15) as response:
                value = response.read(8 * 1024 * 1024 + 1)
        except HTTPError as error:
            try:
                payload = json.loads(error.read(16384))
                code = (
                    payload.get("error_code", "UNKNOWN_ERROR")
                    if isinstance(payload, dict)
                    else "UNKNOWN_ERROR"
                )
                if not isinstance(code, str) or not code.replace("_", "").isalnum():
                    code = "UNKNOWN_ERROR"
            except (ValueError, UnicodeError):
                code = "UNKNOWN_ERROR"
            raise RegistryError(error.code, code) from error
        except (URLError, TimeoutError, OSError) as error:
            raise RegistryUnavailable(
                "MLflow registry is unavailable; check the registry service."
            ) from error
        if len(value) > 8 * 1024 * 1024:
            raise RegistryUnavailable("MLflow response exceeds the local size limit")
        if raw:
            return value
        try:
            result = json.loads(value)
        except (ValueError, UnicodeError) as error:
            raise RegistryUnavailable("MLflow returned invalid JSON") from error
        if not isinstance(result, dict):
            raise RegistryUnavailable("MLflow returned an invalid record")
        return result

    def register(self, name, registration_id, artifact, result, review):
        name = "opentsx-" + name
        try:
            self.request("GET", "2.0/mlflow/registered-models/get?" + urlencode({"name": name}))
        except RegistryError as error:
            if error.code != "RESOURCE_DOES_NOT_EXIST":
                raise
            self.request("POST", "2.0/mlflow/registered-models/create", {"name": name})
        versions = self.request(
            "GET",
            "2.0/mlflow/model-versions/search?"
            + urlencode({"filter": f"name = '{name}'", "max_results": 1000}),
        )
        if versions.get("next_page_token"):
            raise RegistryUnavailable(
                "model version history exceeds the local 1000-version lookup limit"
            )
        for version in versions.get("model_versions", []):
            tags = {tag["key"]: tag["value"] for tag in version.get("tags", [])}
            if tags.get("opentsx.registration_id") == registration_id:
                if (
                    tags.get("opentsx.artifact_id") != artifact["artifact_id"]
                    or version.get("status") != "READY"
                ):
                    raise RegistryUnavailable(
                        "existing MLflow registration is incompatible or not ready"
                    )
                self.verify(
                    {
                        "name": name.removeprefix("opentsx-"),
                        "artifact_id": artifact["artifact_id"],
                        "artifact": artifact,
                        "mlflow_version": str(version["version"]),
                    }
                )
                return {"run_id": version["run_id"], "version": str(version["version"])}
        try:
            experiment = self.request(
                "GET",
                "2.0/mlflow/experiments/get-by-name?"
                + urlencode({"experiment_name": "OpenTSx Workbench"}),
            )["experiment"]["experiment_id"]
        except RegistryError as error:
            if error.code != "RESOURCE_DOES_NOT_EXIST":
                raise
            experiment = self.request(
                "POST", "2.0/mlflow/experiments/create", {"name": "OpenTSx Workbench"}
            )["experiment_id"]
        run = self.request(
            "POST",
            "2.0/mlflow/runs/create",
            {
                "experiment_id": experiment,
                "run_name": registration_id,
                "tags": [
                    {"key": "opentsx.run_id", "value": result["run_id"]},
                    {"key": "opentsx.artifact_id", "value": artifact["artifact_id"]},
                    {"key": "opentsx.registration_id", "value": registration_id},
                ],
            },
        )["run"]["info"]
        run_id, uri = run["run_id"], run["artifact_uri"]
        if not uri.startswith("mlflow-artifacts:/"):
            raise RegistryUnavailable("MLflow must use its artifact proxy for this profile")
        path = uri.removeprefix("mlflow-artifacts:/").strip("/") + "/model"
        timestamp = int(time.time() * 1000)
        metric_values = {
            "candidate_mae": result["backtest"]["result"]["metrics"]["arima"]["mae"],
            "baseline_mae": result["backtest"]["result"]["metrics"]["naive"]["mae"],
        }
        self.request(
            "POST",
            "2.0/mlflow/runs/log-batch",
            {
                "run_id": run_id,
                "metrics": [
                    {"key": key, "value": value, "timestamp": timestamp, "step": 0}
                    for key, value in metric_values.items()
                ],
                "params": [
                    {"key": key, "value": json.dumps(value, allow_nan=False)}
                    for key, value in artifact["configuration"].items()
                ],
                "tags": [{"key": "opentsx.review", "value": json.dumps(review, allow_nan=False)}],
            },
        )
        for filename, document in (
            ("fitted.json", artifact),
            ("evaluation.json", result),
            ("review.json", review),
        ):
            self.request(
                "PUT",
                "2.0/mlflow-artifacts/artifacts/" + quote(path + "/" + filename, safe="/"),
                json.dumps(
                    document, sort_keys=True, separators=(",", ":"), allow_nan=False
                ).encode(),
                raw=True,
            )
        version = self.request(
            "POST",
            "2.0/mlflow/model-versions/create",
            {
                "name": name,
                "source": "mlflow-artifacts:/" + path,
                "run_id": run_id,
                "tags": [
                    {"key": "opentsx.artifact_id", "value": artifact["artifact_id"]},
                    {"key": "opentsx.registration_id", "value": registration_id},
                ],
                "description": "Fixed-parameter JSON statistical fit for the OpenTSx runtime.",
            },
        )["model_version"]
        if version.get("status") != "READY":
            raise RegistryUnavailable("MLflow model version is not ready")
        self.request(
            "POST",
            "2.0/mlflow/registered-models/alias",
            {"name": name, "alias": "candidate", "version": str(version["version"])},
        )
        self.request(
            "POST",
            "2.0/mlflow/runs/update",
            {"run_id": run_id, "status": "FINISHED", "end_time": int(time.time() * 1000)},
        )
        return {"run_id": run_id, "version": str(version["version"])}

    def verify(self, model):
        from opentsx.algorithms.fitted import validate_artifact

        name = "opentsx-" + model["name"]
        query = urlencode({"name": name, "version": model["mlflow_version"]})
        version = self.request("GET", "2.0/mlflow/model-versions/get?" + query)["model_version"]
        tags = {tag["key"]: tag["value"] for tag in version.get("tags", [])}
        if (
            version.get("status") != "READY"
            or tags.get("opentsx.artifact_id") != model["artifact_id"]
        ):
            raise RegistryUnavailable(
                "registered version is not ready or its artifact identity changed"
            )
        uri = self.request("GET", "2.0/mlflow/model-versions/get-download-uri?" + query)[
            "artifact_uri"
        ]
        if not uri.startswith("mlflow-artifacts:/"):
            raise RegistryUnavailable("registered artifact does not use the configured proxy")
        raw = self.request(
            "GET",
            "2.0/mlflow-artifacts/artifacts/"
            + quote(uri.removeprefix("mlflow-artifacts:/").strip("/") + "/fitted.json", safe="/"),
            raw=True,
        )
        try:
            artifact = json.loads(raw)
            validate_artifact(artifact)
        except (ValueError, UnicodeError) as error:
            raise RegistryUnavailable("registered artifact is invalid or incompatible") from error
        if artifact != model["artifact"]:
            raise RegistryUnavailable("registered artifact differs from the verified local fit")

    def promote(self, model):
        self.verify(model)
        self.request(
            "POST",
            "2.0/mlflow/registered-models/alias",
            {
                "name": "opentsx-" + model["name"],
                "alias": "champion",
                "version": model["mlflow_version"],
            },
        )
