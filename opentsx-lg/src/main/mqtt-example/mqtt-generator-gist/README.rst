MQTT Generator
==============

A simple python 3 script to generate sensor data from a config file and send it
to an MQTT broker.

Usage
-----

Download mqttgen.py and config.json files (click on the **Raw** button at the top right and then save the content), edit config.json to fit your needs, if you are using it to run the Event Fabric sensors dashboard then don't change the topic in config.json unless you want to change it in the dashboard too.

The script uses the 1.x client API of the `python paho-mqtt library <https://pypi.python.org/pypi/paho-mqtt/>`_.
``../01_bootstrap.sh`` installs version 1.6.1 with ``pip3 install --user paho-mqtt==1.6.1``. paho-mqtt 2.0.0 stops the
script with ``TypeError: Client.__init__() missing 1 required positional argument: 'callback_api_version'``.


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