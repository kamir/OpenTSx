# OpenTSx in the notebook of a stack

Puts the Python package `opentsx` and the OpenTSx notebooks into a Jupyter deployment that a stack already runs,
and lets that notebook reach the retrieval server. Concept: [`17-NOTEBOOK-ARBEITSPLATZ.md`](../../../docs/datamodel-2026/17-NOTEBOOK-ARBEITSPLATZ.md) (German).

OpenTSx does not deploy the notebook. The two files here change a notebook that belongs to the stack, so the people
who run the stack apply them, and can take them back:

| File | What it does |
|------|--------------|
| `jupyter-patch.yaml` | strategic merge patch for the Deployment `jupyter`: an init container `opentsx` copies `/opentsx` from the image `opentsx/notebook-content` into `/home/jovyan/opentsx` on every start; the notebook gets `PYTHONPATH` and `OPENTSX_RETRIEVAL` |
| `networkpolicy-retrieval.yaml` | lets the pods labelled `app.kubernetes.io/name: jupyter` reach the retrieval server (`opentsx` namespace, port 8080). Additive: it allows one more target and changes no other rule |

```bash
# image (on a host with docker; import it into k3s there)
docker build -f deploy/docker/notebook-content.Dockerfile -t opentsx/notebook-content:<tag> python-package
docker save opentsx/notebook-content:<tag> | sudo k3s ctr images import -

# in the namespace of the notebook (NS); set the image tag in the patch first
kubectl -n "$NS" apply -f deploy/k3s/notebook/networkpolicy-retrieval.yaml
kubectl -n "$NS" patch deployment jupyter --type strategic --patch-file deploy/k3s/notebook/jupyter-patch.yaml

# take it back
kubectl -n "$NS" delete networkpolicy allow-opentsx-retrieval
kubectl -n "$NS" rollout undo deployment jupyter
```

**Who sees what.** The retrieval server has no accounts. With the network rule, everyone who can open the notebook
can read every series the server holds. That is acceptable for a demonstration; before a notebook serves several
people of an operator, the server needs accounts or the notebook a reader of its own.

**What is copied.** `/home/jovyan/opentsx` is replaced on every start. Copy a notebook out of it before changing it.
