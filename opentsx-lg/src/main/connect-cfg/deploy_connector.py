#!/usr/bin/env python3

# Copyright: (c) 2019, Confluent Inc

import http.client
import json
import sys
import urllib.error
import urllib.request


def body(response):
    try:
        data = response.read()
    except http.client.IncompleteRead as error:
        data = error.partial
    except ConnectionError as error:
        return '({})'.format(error)
    return data.decode('utf-8', 'replace')


def create_new_connector(connect_url, name, config):
    data = json.dumps({'name': name, 'config': config}).encode('utf-8')
    headers = {'Content-Type': 'application/json'}
    request = urllib.request.Request(connect_url, data=data, headers=headers, method='POST')
    try:
        with urllib.request.urlopen(request, timeout=10) as r:
            return r.getcode(), body(r)
    except urllib.error.HTTPError as error:
        return error.code, body(error)

def main():

    fn = 'cfg.json'

    try:
        with open( fn, 'r') as f:
            connector_config = json.load(f)
    except (OSError, ValueError) as error:
        cause = error.strerror if isinstance(error, OSError) else error
        sys.exit("{}: cannot read the configuration file ({}); run the script in a folder with a JSON file {}, such "
                 "as src/main/connect-cfg/".format(fn, cause, fn))

    config = connector_config.get('config') if isinstance(connector_config, dict) else None
    if not isinstance(config, dict):
        sys.exit('{}: config: missing or not a JSON object; write the connector settings as the object '
                 '"config": {{...}}'.format(fn))

    connector_name = "debug_c1"
    connect_url = "http://127.0.0.1:8083/connectors"

    print( ">>> Connector deployment test ... ")
    print( " -> CONFIG file : " +  fn)
    print( " -> URL         : " +  connect_url)
    print( " -> NAME        : " +  connector_name)
    print( " ")

    for x in config:
        print("%s: %s" % (x, config[x]))

    print(" ")

    try:
        status, answer = create_new_connector(
            connect_url=connect_url,
            name=connector_name,
            config=config
        )
    except urllib.error.URLError as error:
        sys.exit("{}: cannot connect ({}); start Kafka Connect on 127.0.0.1:8083 first".format(
            connect_url, error.reason))
    except TimeoutError:
        sys.exit("{}: no answer within 10 s; check the log of the Kafka Connect worker".format(connect_url))
    except (http.client.HTTPException, ConnectionError) as error:
        sys.exit("{}: no HTTP answer ({}); check that Kafka Connect listens on {} and read the log of its "
                 "worker".format(connect_url, str(error).strip(), connect_url))
    if status not in (200, 201):
        sys.exit("{}: POST {} answered HTTP {} {}; the connector was not created, check the log of the Kafka Connect "
                 "worker".format(connector_name, connect_url, status, answer.strip()))

    print( "New connector {} created.".format(connector_name))

if __name__ == "__main__":
    main()