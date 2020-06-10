FROM ubuntu:18.04

RUN apt update

RUN apt install -y git vim build-essential libssl1.0 libffi-dev mc
RUN apt install -y python3 python3-six python3-pip python3-dev python-cryptography python3-cryptography
RUN pip3 install --upgrade pip
RUN pip3 install cryptography==2.8

RUN git clone https://gitlab.com/rak-n-rok/krake.git /root/krake
WORKDIR "/root/krake"

RUN pip3 install --editable "rok"
RUN pip3 install --editable "krake"

ADD generate_krake_config.sh /root/generate_krake_config.sh
ADD run_krake_services.sh /root/run_krake_services.sh
ADD create_kube_clusters.sh /root/create_kube_clusters.sh

RUN chmod 777 /root/run_krake_services.sh
RUN chmod 777 /root/generate_krake_config.sh
RUN chmod 777 /root/create_kube_clusters.sh

WORKDIR "/root"

#
# Was used to generate CONFIG files from templates.
#
#RUN /root/generate_krake_config.sh
#RUN mkdir /etc/krake
#RUN mv /root/api.yaml /etc/krake
#RUN mv /root/garbage_collector.yaml /etc/krake
#RUN mv /root/kubernetes.yaml /etc/krake
#RUN mv /root/magnum.yaml /etc/krake
#RUN mv /root/scheduler.yaml /etc/krake

EXPOSE 8080

RUN apt install -y iputils-ping telnet

RUN mkdir /root/clusters/

RUN mkdir /root/clusters/mdc1
###RUN mkdir /root/clusters/mdc2
RUN mkdir /root/clusters/mdc3

RUN mkdir /root/clusters/mdc1/certs
RUN mkdir /root/clusters/mdc1/config
ADD config-mdc1 /root/clusters/mdc1/config

RUN mkdir /root/apps
ADD echo-demo.yaml /root/apps/echo-demo.yaml

#RUN mkdir /root/clusters/mdc2/certs
#RUN mkdir /root/clusters/mdc2/config
#ADD config-mdc2 /root/clusters/mdc2/config

RUN mkdir /root/clusters/mdc3/certs
RUN mkdir /root/clusters/mdc3/config
ADD config-mdc3 /root/clusters/mdc3/config

# CMD [./run_krake_services.sh]