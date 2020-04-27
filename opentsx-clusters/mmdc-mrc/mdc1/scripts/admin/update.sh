cd /home/ubuntu/OpenTSx
git pull
cd opentsx-clusters/mmdc-mrc/mdc1
source .env
sudo docker-compose -f /home/ubuntu/OpenTSx/opentsx-clusters/mmdc-mrc/mdc1/docker-compose.yml up -d

