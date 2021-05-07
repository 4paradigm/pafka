#! /usr/bin/env bash

# get the latest kafka code
# but skip the compling as it takes quite long
git log -1 > DOCKER_COMMIT
git fetch origin
git pull origin $(git rev-parse --abbrev-ref HEAD)
git log -1 > LATEST_COMMIT

if [[ $# -ge 1 ]];
  arg=$1

  if [[ $cmd = "run" ]]; then
    cmd="bin/zookeeper-server-start.sh config/zookeeper.properties > zk.log 2>&1 &; bin/kafka-server-start.sh config/server.properties > pafka.log 2>&1 &"
  else
    cmd=$@
  fi
fi

echo "Running $cmd"
$cmd
