## KSQL DEMO Application

This module provides:
 - the scripts to execute KSQL queries in a local confluent platform installation.
 - the scripts to create a Docker based KSQL-Server which can be deployed in a distributed environment.
 
## Local Testing with KSQL REST-API
The scripts:
- deploy_query_via_rest.sh

is used for local demos of the OpenTSx processing pipeline.

### Run the local demo: 

Executing the script:

'''bash'''
  deploy_query_via_rest.sh
'''

## Distributed Testing with KSQL-Docker Images
The two scripts:
- run_container.sh 
- build_container_and_deploy.sh

are used to generate a Docker image with our OpenTSx UDF library.
This can be used to demo the OpenTSx processing pipeline in the cloud.
