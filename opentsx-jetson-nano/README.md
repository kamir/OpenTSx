We use this example to create a processor demo for the Jetson-Nano:

https://github.com/dusty-nv/jetson-inference/


1) We load images in BASE64 format from a topic with Strings as the value data type.

We simply consume the data as binary data using the REST Proxy from a Confluent cloud cluster.

2) Using the Jetson Nano, we implement a stateless image processor which extracts information from images.

3) Identified objects, loaction from where the image has been taken, and time will be stored in the Confluent cloud cluster.

