# Example image as BASE64 string:
export write_topic=images
export read_topic=images

#
# Both topics have to be created before the example code can be executed.
#
curl http://localhost:8082/topics

#
# Please check if write_topic and read_topic are available in the destination cluster.
#



#
# Send data to a REST Proxy
#
curl -X POST -H "Content-Type: application/vnd.kafka.binary.v2+json" -H "Accept: application/vnd.kafka.v2+json" --data '{"records":[{"value":"S2Fma2E=S2Fma2E="}]}' "http://localhost:8082/topics/images"





#
# Register a consumer
#
curl -X POST  -H "Content-Type: application/vnd.kafka.v2+json" \
      --data '{"name": "opentsx_image_consumer_instance", "format": "binary", "auto.offset.reset": "earliest"}' \
      http://localhost:8082/consumers/opentsx_image_consumer

# {"instance_id":"opentsx_image_consumer_instance","base_uri":"http://rest-proxy:8082/consumers/opentsx_image_consumer/instances/opentsx_image_consumer_instance"}%

#
# Subscribe to the image topic
#
curl -X POST -H "Content-Type: application/vnd.kafka.v2+json" --data '{"topics":["images"]}' \
      http://localhost:8082/consumers/opentsx_image_consumer/instances/opentsx_image_consumer_instance/subscription


#
# Read image data
#
curl -X GET -H "Accept: application/vnd.kafka.binary.v2+json" \
      http://localhost:8082/consumers/opentsx_image_consumer/instances/opentsx_image_consumer_instance/records


#
# Delete the consumer instance
#
curl -X DELETE -H "Content-Type: application/vnd.kafka.v2+json" \
      http://localhost:8082/consumers/opentsx_image_consumer/instances/opentsx_image_consumer_instance
