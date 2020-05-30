# OpenTSx-Cloud-Bridge

This module contains components for bridging applications to cloud based data planes, implemented using 
Confluent Platform or Confluent cloud.

# cp-all-in-one
The cp-all-in-one repository provides examples for running components of the 
Confluent platform locally or in a dedicated environment.

## REST-Proxy
We use the rest proxy component to build a bridge for clients which have
no native Kafka client available.

Some clusters are not accessible for native Kafka clients, but a HTTP Proxy is allowed.
In this case, our Kafka REST Proxy gives us the required connectivity.

We use the REST Proxy in our example module *opentsx-jetson-nano*.
The DEMO is inspired by this demo on Github (https://github.com/dusty-nv/jetson-inference/).
