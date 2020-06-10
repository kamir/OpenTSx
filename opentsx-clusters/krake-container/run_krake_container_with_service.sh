docker build . -t krake-container
docker run --name krake-container -p 8080:8080 --mount type=bind,source="$(pwd)"/config,target=/etc --entrypoint /bin/bash krake-container
#-d

