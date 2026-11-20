#!/usr/bin/env python3
"""a simple sensor data generator that sends to an MQTT broker via paho"""
import os
import sys
import json
import time
import random

import paho.mqtt.client as mqtt

JSON_TYPES = ((dict, "object"), (list, "array"), (str, "string"), (bool, "boolean"), ((int, float), "number"))


def json_type(value):
    return next((name for kind, name in JSON_TYPES if isinstance(value, kind)), "null")


def json_object(config_path, key, value, shape="a JSON object"):
    if not isinstance(value, dict):
        sys.exit("%s: %s: a JSON %s is not allowed; expected %s" % (config_path, key, json_type(value), shape))
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

        try:
            mqttc.publish(topic, payload)
        except OSError as error:
            sys.exit("%s:%s: cannot publish to topic %s (%s); check the MQTT broker" % (host, port, topic, error))
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
    host = mqtt_config.get("host", "localhost")
    port = mqtt_config.get("port", 1883)
    topic = mqtt_config.get("topic", "mqttgen")
    username = mqtt_config.get("username")
    password = mqtt_config.get("password")
    for key, value, allowed, expected in (
            ("misc.interval_ms", interval_ms, isinstance(interval_ms, (int, float))
             and not isinstance(interval_ms, bool) and 0 <= interval_ms <= 86400000,
             "a number of milliseconds from 0 to 86400000"),
            ("misc.verbose", verbose, isinstance(verbose, bool), "true or false"),
            ("mqtt.host", host, isinstance(host, str) and host != "", "a non-empty JSON string"),
            ("mqtt.port", port, isinstance(port, int) and not isinstance(port, bool) and 1 <= port <= 65535,
             "a whole number from 1 to 65535"),
            ("mqtt.topic", topic, isinstance(topic, str) and topic != "" and "+" not in topic and "#" not in topic,
             "a non-empty JSON string without + or #"),
            ("mqtt.username", username, "username" not in mqtt_config or isinstance(username, str), "a JSON string")):
        if not allowed:
            sys.exit("%s: %s: %s is not allowed; expected %s" % (config_path, key, json.dumps(value), expected))
    if "password" in mqtt_config and not isinstance(password, str):
        sys.exit("%s: mqtt.password: a JSON %s is not allowed; expected a JSON string"
                 % (config_path, json_type(password)))

    if not sensors:
        print("no sensors specified in config, nothing to do")
        return

    print("MQTT broker %s:%s, user %s" % (host, port, username))

    generate(host, port, username, password, topic, sensors, interval_ms, verbose)






if __name__ == '__main__':
    if len(sys.argv) == 2:
        try:
            # Line buffering makes a closed stdout fail at the print, inside this handler, not in the flush at exit.
            sys.stdout.reconfigure(line_buffering=True)
            print("Start %s with %s" % (sys.argv[0],sys.argv[1]) )
            main(sys.argv[1])
        except BrokenPipeError:
            # Python flushes stdout again at exit; point it at devnull so that flush cannot fail a second time.
            os.dup2(os.open(os.devnull, os.O_WRONLY), sys.stdout.fileno())
            sys.exit("stdout: closed by its reader; read the whole output or set misc.verbose to false")
    else:
        print("%s: %d arguments given; expected 1, the path of a JSON configuration file such as config.json"
              % (sys.argv[0], len(sys.argv) - 1), file=sys.stderr)
        sys.exit(2)
