#!/usr/bin/python

# Copyright: (c) 2019, Confluent Inc

#
# This script reproduces the connector creation process in the way how ANSIBLE
# works internally.
#
# The equivalent CURL command is this one:
#
#    curl --insecure -XPOST -H 'Content-Type:application/json' -d @/tmp/csm_future_upsert.json https://kafka_connect-1.cdh-dev-1:8083/connectors
#
#    curl --insecure -XPOST -H 'Content-Type:application/json' -d @./connector_Generator-001_config.json http://127.0.0.1:8083/connectors
#
#
import json

from ansible.module_utils.urls import open_url


def create_new_connector(connect_url, name, config):
    data = json.dumps({'name': name, 'config': config})
    headers = {'Content-Type': 'application/json'}
    r = open_url(method='POST', url=connect_url, data=data, headers=headers)
    return r.getcode() in (200, 201, 409)

def main():

    #fn = 'connector_Generator-001_config.json'
    fn = 'cfg.json'

    with open( fn, 'r') as f:
        connector_config = json.load(f)

    connector_name = "debug_c1"
    connect_url = "http://127.0.0.1:8083/connectors"

    print( ">>> Connector deployment test ... ")
    print( " -> CONFIG file : " +  fn)
    print( " -> URL         : " +  connect_url)
    print( " -> NAME        : " +  connector_name)
    print( " ")

    for x in connector_config:
        print("%s: %s" % (x, connector_config[x]))

    print(" ")

    result = create_new_connector(
        connect_url=connect_url,
        name=connector_name,
        config=connector_config
    )

    print( "New connector {} created.".format(connector_name))

if __name__ == "__main__":
    main()