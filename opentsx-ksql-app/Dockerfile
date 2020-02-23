FROM confluentinc/cp-ksql-server:5.4.0

#
# Where to place the JAR file within the image?
#
RUN mkdir -p /etc/ksql/ext
RUN mkdir -p /scripts

COPY ./CP_HOME/etc/ksql/ext/*.jar /usr/ext
COPY ./CP_HOME/scripts/demo_1.sql /scripts/demo_1.sql