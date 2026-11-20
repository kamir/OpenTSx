#!/usr/bin/env bash
cd mqtt-generator-gist || exit 1

python3 mqttgen.py config.json
