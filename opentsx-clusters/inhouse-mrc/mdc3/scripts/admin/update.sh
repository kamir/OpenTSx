cd /home/ubuntu/OpenTSx
sudo git pull
cd opentsx-clusters/mmdc-mrc/mdc3
source .env
sudo docker-compose -f /home/ubuntu/OpenTSx/opentsx-clusters/mmdc-mrc/mdc3/docker-compose.yml up -d
