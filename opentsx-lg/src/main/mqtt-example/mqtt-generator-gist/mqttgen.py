#!/usr/bin/env python3
"""a simple sensor data generator that sends to an MQTT broker via paho"""
import sys
import json
import time
import random
import traceback

import paho.mqtt.client as mqtt

def generate(host, port, username, password, topic, sensors, interval_ms, verbose):
    """generate data and send it to an MQTT broker"""
    mqttc = mqtt.Client()

    if username:
        mqttc.username_pw_set(username, password)

    mqttc.connect(host, port)
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
            mqtt_config = config.get("mqtt", {})
            misc_config = config.get("misc", {})
            sensors = config.get("sensors")

            interval_ms = misc_config.get("interval_ms", 500)
            verbose = misc_config.get("verbose", False)


            try:
                if not sensors:
                    print("no sensors specified in config, nothing to do")
                    return

                host = mqtt_config.get("host", "localhost")
                port = mqtt_config.get("port", 1883)
                username = mqtt_config.get("username")
                password = mqtt_config.get("password")
                topic = mqtt_config.get("topic", "mqttgen")

                print( "%s %s %s %s %s %s %s" % (host, port, username, password, topic, sensors, interval_ms) )

                generate(host, port, username, password, topic, sensors, interval_ms, verbose)

            except IOError as error1:
                print("Error connecting to service '%s:%s'" % host, port)

    except IOError as error2:
        print("Error opening config file '%s'" % config_path, error2)






if __name__ == '__main__':
    if len(sys.argv) == 2:
        print("Start %s with %s" % (sys.argv[0],sys.argv[1]) )
        main(sys.argv[1])
    else:
        print("usage %s config.json" % sys.argv[0] )