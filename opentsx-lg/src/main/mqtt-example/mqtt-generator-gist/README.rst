MQTT Generator
==============

A simple python 3 script to generate sensor data from a config file and send it
to an MQTT broker.

Usage
-----

Download mqttgen.py and config.json files (click on the **Raw** button at the top right and then save the content), edit config.json to fit your needs, if you are using it to run the Event Fabric sensors dashboard then don't change the topic in config.json unless you want to change it in the dashboard too.

The script uses the `python paho-mqtt library <https://pypi.python.org/pypi/paho-mqtt/>`_ you can install it with something like `sudo pip3 install paho-mqtt`.


::
    
    python3 mqttgen.py config.json

Configuration
-------------

Edit config.json, you can add as many sensors in the "sensors" object as you wish.

Change the values in "mqtt" section to match your MQTT broker settings.

* password is optional if you don't need password
* if username is missing, no authentication will be used.

Author
------

Mariano Guerra from `Event Fabric <http://event-fabric.com>`_

License
-------

Public Domain