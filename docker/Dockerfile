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

FROM centos:7.3.1611

LABEL maintainer="zhanghao@4paradigm.com"
VOLUME ["/hdd", "/pmem", "/nvme", "/ssd"]

# Set the timezone.
ENV TZ="/usr/share/zoneinfo/Asia/Singapore"
ENV PYTHONPATH="/opt/pafka-dev/notebook/.cmd"

# Set the docker.creator label so that we know that this is a docker image.  This will make it
# visible to 'docker purge'.  The docker.creator label also lets us know what UNIX user built this
# image.
ARG docker_creator=default
LABEL docker.creator=$docker_creator

# Update Linux and install necessary utilities.
# we have to install git since it is included in openjdk:8 but not openjdk:11
RUN yum install -y sudo git openssh-server net-tools vim python3-pip python3-devel dstat fio
RUN python3 -m pip install -U pip==20.2.2
RUN yum install -y java-11-openjdk-devel
RUN yum install -y libpmem librpmem libpmemblk libpmemlog libpmemobj libpmempool
RUN pip3 install setuptools==46.4.0 && pip3 install jupyterlab tabulate

# or clone directly
# git clone https://github.com/4paradigm/pafka.git /opt/pafka-dev
COPY ./ /opt/pafka-dev

# set workdir
WORKDIR /opt/pafka-dev

# build the pafka
RUN ./gradlew jar

# set workdir to notebook
WORKDIR /opt/pafka-dev

CMD ["bash"]
