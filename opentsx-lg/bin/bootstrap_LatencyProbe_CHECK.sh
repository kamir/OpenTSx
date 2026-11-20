#!/usr/bin/env bash
#
# Needs CCLOUDENV, CLUSTER and SERVICE_ACCOUNT, the ids of your Confluent Cloud environment, cluster and service
# account; exits 2 without one of them.
#
missing=0
if [[ -z "${CCLOUDENV:-}" ]]; then
  echo "CCLOUDENV: missing; export the id of your Confluent Cloud environment before you run the script" >&2
  missing=1
fi
if [[ -z "${CLUSTER:-}" ]]; then
  echo "CLUSTER: missing; export the id of your Confluent Cloud cluster before you run the script" >&2
  missing=1
fi
if [[ -z "${SERVICE_ACCOUNT:-}" ]]; then
  echo "SERVICE_ACCOUNT: missing; export the id of your Confluent Cloud service account before you run the script" >&2
  missing=1
fi
if [[ "$missing" -ne 0 ]]; then
  exit 2
fi

source env.sh

echo
echo "------------------------------------------------------------------------"
echo " Prepare ACLs for the LATENCY analysis toolbox on a new Ccloud cluster. "
echo "------------------------------------------------------------------------"
echo

echo
echo ccloud login --save
echo

ccloud environment use "$CCLOUDENV" || exit 1

ccloud kafka cluster use "$CLUSTER" || exit 1

#
# Define the minimal ACLs for working with OpenTSx and KPING
#
ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --topic OpenTSx_ --prefix --operation CREATE || exit 1
ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --topic OpenTSx_ --prefix --operation READ || exit 1
ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --topic OpenTSx_ --prefix --operation WRITE || exit 1
ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --topic OpenTSx_ --prefix --operation ALTER || exit 1
ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --topic OpenTSx_ --prefix --operation DELETE || exit 1

ccloud kafka acl create --allow --service-account "$SERVICE_ACCOUNT" --consumer-group KPING-Consumer --operation READ || exit 1
