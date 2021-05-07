#! /usr/bin/env bash
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

# get the latest kafka code
# but skip the compling as it takes quite long

git log -1 > DOCKER_COMMIT
git fetch origin
git pull origin $(git rev-parse --abbrev-ref HEAD)
git log -1 > LATEST_COMMIT

if [[ $# -ge 1 ]]; then
  arg=$1

  if [[ $arg = "start" ]]; then
    echo "Starting zookeeper and pafka services"
    bin/zookeeper-server-start.sh config/zookeeper.properties > zk.log 2>&1 &
    bin/kafka-server-start.sh config/server.properties > pafka.log 2>&1 &
    tail -f pafka.log
  elif [[ $arg = "notebook" ]]; then
    echo "Starting jupyter notebook"
    jupyter lab --allow-root --ip="0.0.0.0" --no-browser
  else
    echo "Running $@"
    $@
  fi
fi
