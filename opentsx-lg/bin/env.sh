#!/bin/bash

cd .. || exit 1

export OPENTSX_TOPIC_MAP_FILE_NAME=./config/topiclist_kping.def
# local profile (git-ignored); falls back to the checked-in localhost template
export CLUSTER_CONFIG_FILE=./../config/opentsx-local.props
[ -f "$CLUSTER_CONFIG_FILE" ] || export CLUSTER_CONFIG_FILE=./../config/opentsx-local.props.example

WP=$(pwd)
export WP

export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=$CLUSTER_CONFIG_FILE
export OPENTSX_SHOW_GUI=false

echo "OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME      : $OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME"
echo "OPENTSX_TOPIC_MAP_FILE_NAME                       : $OPENTSX_TOPIC_MAP_FILE_NAME"
echo "OPENTSX_SHOW_GUI                                  : $OPENTSX_SHOW_GUI"
echo "==================================================="
echo "Working directory :  $WP"
echo "Cluster config    :  $CLUSTER_CONFIG_FILE"
echo "==================================================="
echo
echo
echo "==================================================="