# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

FROM ubuntu:20.04

LABEL maintainer="zhanghao@4paradigm.com"
VOLUME ["/mnt/mem", "/mnt/hdd"]

# Set the timezone.
ENV TZ="/usr/share/zoneinfo/Asia/Singapore"

# Do not ask for confirmations when running apt-get, etc.
ENV DEBIAN_FRONTEND noninteractive

# Set the docker.creator label so that we know that this is a docker image.  This will make it
# visible to 'docker purge'.  The docker.creator label also lets us know what UNIX user built this
# image.
ARG docker_creator=default
LABEL docker.creator=$docker_creator

# Update Linux and install necessary utilities.
# we have to install git since it is included in openjdk:8 but not openjdk:11
RUN apt update && apt install -y sudo git netcat iptables rsync unzip wget curl jq coreutils openssh-server net-tools vim python3-pip python3-dev libffi-dev libssl-dev cmake pkg-config libfuse-dev iperf traceroute && apt-get -y clean
RUN python3 -m pip install -U pip==20.2.2;
RUN pip3 install --upgrade cffi virtualenv pyasn1 boto3 pycrypto pywinrm ipaddress enum34 && pip3 install --upgrade ducktape==0.8.1
RUN apt install -y openjdk-14-jdk
RUN apt install -y libpmem1 librpmem1 libpmemblk1 libpmemlog1 libpmemobj1 libpmempool1
RUN apt install -y openssh-server
RUN pip3 install setuptools==46.4.0 && pip3 install jupyterlab

COPY ./ /opt/pafka-dev

# set workdir
WORKDIR /opt/pafka-dev

# build the pafka
RUN ./gradlew jar

ENTRYPOINT ["docker/entrypoint.sh"]
CMD ["bash"]
