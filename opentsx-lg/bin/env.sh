cd ..

#export CLUSTER_CONFIG_FILE=./config/private/ccloud_defaultMK_c3.props
#export CLUSTER_CONFIG_FILE=./config/private/ccloud_defaultMK_c2.props
export CLUSTER_CONFIG_FILE=./config/private/ccloud_defaultMK_c1.props
export OPENTSX_TOPIC_MAP_FILE_NAME=./config/topiclist_kping_cloud.def

#export CLUSTER_CONFIG_FILE=./../config/cpl_iMac.props
#export OPENTSX_TOPIC_MAP_FILE_NAME=./config/topiclist_kping.def

export WP=$(pwd)

export OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME=$CLUSTER_CONFIG_FILE
export OPENTSX_SHOW_GUI=false

echo "OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME      :" $OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME
echo "OPENTSX_TOPIC_MAP_FILE_NAME                       :" $OPENTSX_TOPIC_MAP_FILE_NAME
echo "OPENTSX_SHOW_GUI                                  :" $OPENTSX_SHOW_GUI
echo "==================================================="
echo "Working directory : " $WP
echo "Cluster config    : " $CLUSTER_CONFIG_FILE
echo "==================================================="
echo
cat $CLUSTER_CONFIG_FILE
echo
echo
echo "==================================================="