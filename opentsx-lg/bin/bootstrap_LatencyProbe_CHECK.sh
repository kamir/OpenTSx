source env.sh

echo
echo "------------------------------------------------------------------------"
echo " Prepare ACLs for the LATENCY analysis toolbox on a new Ccloud cluster. "
echo "------------------------------------------------------------------------"
echo

export CCLOUDENV=t15875

export CLUSTER=lkc-8q1pq

export SERVICE_ACCOUNT=122239

########################################################################################################################
echo
echo ccloud login --save
echo

ccloud environment use $CCLOUDENV

ccloud kafka cluster use $CLUSTER

#
# Define the minimal ACLs for working with OpenTSx and KPING
#
ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --topic OpenTSx_ --prefix --operation CREATE
ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --topic OpenTSx_ --prefix --operation READ
ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --topic OpenTSx_ --prefix --operation WRITE
ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --topic OpenTSx_ --prefix --operation ALTER
ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --topic OpenTSx_ --prefix --operation DELETE

ccloud kafka acl create --allow --service-account $SERVICE_ACCOUNT --consumer-group KPING-Consumer --operation READ

########################################################################################################################













