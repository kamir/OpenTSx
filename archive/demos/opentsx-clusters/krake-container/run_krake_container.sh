docker build . -t krake-container

docker run -p 8080:8080 -it --name krake-container --mount type=bind,source="$(pwd)"/config,target=/etc --entrypoint /bin/bash krake-container

