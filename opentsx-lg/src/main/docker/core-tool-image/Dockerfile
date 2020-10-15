FROM confluentinc/cp-base

RUN apt-get update
RUN apt-get install --force-yes -y apt-utils xvfb x11vnc x11-xkb-utils xfonts-100dpi xfonts-75dpi xfonts-scalable xfonts-cyrillic x11-apps

ENV OPENTSX_PRIMARY_CLUSTER_CLIENT_CFG_FILE_NAME /opentsx-lg/config/ccloud.props
ENV OPENTSX_SHOW_GUI false
ENV OPENTSX_TOPIC_MAP_FILE_NAME /opentsx-lg/config/topiclist.def
ENV DISPLAY :99

COPY opentsx-lg /opentsx-lg/

CMD java -jar /opentsx-lg/opentsx-lg-3.0.1.jar
