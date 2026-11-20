#!/usr/bin/env python3
"""a simple sensor data generator that sends to an MQTT broker via paho"""
import sys
import json
import time
import random

import paho.mqtt.client as mqtt

JSON_TYPES = ((dict, "object"), (list, "array"), (str, "string"), (bool, "boolean"), ((int, float), "number"))


def json_object(config_path, key, value, shape="a JSON object"):
    if not isinstance(value, dict):
        found = next((name for kind, name in JSON_TYPES if isinstance(value, kind)), "null")
        sys.exit("%s: %s: a JSON %s is not allowed; expected %s" % (config_path, key, found, shape))
    return value


def whole_range(value):
    return (isinstance(value, list) and len(value) == 2
            and all(isinstance(bound, int) and not isinstance(bound, bool) for bound in value)
            and value[0] <= value[1])


def generate(host, port, username, password, topic, sensors, interval_ms, verbose):
    """generate data and send it to an MQTT broker"""
    mqttc = mqtt.Client()

    if username:
        mqttc.username_pw_set(username, password)

    try:
        mqttc.connect(host, port)
    except OSError as error:
        sys.exit("%s:%s: cannot connect to the MQTT broker (%s); start the broker first, for example with "
                 "02_run_mqtt_broker.sh" % (host, port, error))
    mqttc.loop_start()

    keys = list(sensors.keys())
    interval_secs = interval_ms / 1000.0

    while True:
        sensor_id = random.choice(keys)
        sensor = sensors[sensor_id]
        min_val, max_val = sensor.get("range", [0, 100])
        val = random.randint(min_val, max_val)

        data = {
            "id": sensor_id,
            "value": val
        }

        for key in ["lat", "lng", "unit", "type", "description"]:
            value = sensor.get(key)

            if value is not None:
                data[key] = value

        payload = json.dumps(data)

        if verbose:
            print("%s: %s" % (topic, payload))

        mqttc.publish(topic, payload)
        time.sleep(interval_secs)


def main(config_path):

    """main entry point, load and validate config and call generate"""
    try:
        with open(config_path) as handle:
            config = json.load(handle)
    except (OSError, ValueError) as error:
        cause = error.strerror if isinstance(error, OSError) else error
        sys.exit("%s: cannot read the configuration file (%s); pass the path of a JSON configuration file such as "
                 "config.json" % (config_path, cause))

    config = json_object(config_path, "top level", config, "a JSON object with mqtt, misc and sensors")
    mqtt_config = json_object(config_path, "mqtt", config.get("mqtt", {}))
    misc_config = json_object(config_path, "misc", config.get("misc", {}))
    sensors = json_object(config_path, "sensors", config.get("sensors", {}),
                          "a JSON object with one object per sensor")
    for sensor_id, sensor in sensors.items():
        bounds = json_object(config_path, "sensors.%s" % sensor_id, sensor).get("range", [0, 100])
        if not whole_range(bounds):
            sys.exit("%s: sensors.%s.range: %s is not allowed; expected two whole numbers [low, high] with "
                     "low <= high" % (config_path, sensor_id, json.dumps(bounds)))

    interval_ms = misc_config.get("interval_ms", 500)
    verbose = misc_config.get("verbose", False)

    if not sensors:
        print("no sensors specified in config, nothing to do")
        return

    host = mqtt_config.get("host", "localhost")
    port = mqtt_config.get("port", 1883)
    username = mqtt_config.get("username")
    password = mqtt_config.get("password")
    topic = mqtt_config.get("topic", "mqttgen")

    print("MQTT broker %s:%s, user %s" % (host, port, username))

    generate(host, port, username, password, topic, sensors, interval_ms, verbose)






if __name__ == '__main__':
    if len(sys.argv) == 2:
        print("Start %s with %s" % (sys.argv[0],sys.argv[1]) )
        main(sys.argv[1])
    else:
        print("%s: %d arguments given; expected 1, the path of a JSON configuration file such as config.json"
              % (sys.argv[0], len(sys.argv) - 1), file=sys.stderr)
        sys.exit(2)
