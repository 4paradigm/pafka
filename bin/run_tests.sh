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

declare -A blacklist=(
  ['SslSelectorTest']=1
  ['MetricsDuringTopicCreationDeletionTest']=1
  ['KafkaAdminClientTest']=1
  ['CustomQuotaCallbackTest']=1
  ['DeleteTopicTest']=1
  ['ReassignPartitionsIntegrationTest']=1
)

echo "start time: " `date`

for sub in core tools log4j-appender generator metadata streams raft shell connect/api connect/file connect/mirror-client connect/transforms connect/basic-auth-extension connect/json connect/mirror connect/runtime clients
do
for typ in java scala
do
  folder=$sub/src/test/$typ
  if [ ! -d $folder ]; then
    continue
  fi

  javas=`cd $folder; find -name "*Test.$typ"`
  for j in $javas
  do
    file=$folder/$j
    echo "find test file: $file"
    test=`cat $file | grep "abstract class"`
    if [[ ! -z $test ]]; then
      echo "skip abstract $j"
      continue
    fi

    test=`cat $file | grep "trait"`
    if [[ ! -z $test ]]; then
      echo "skip trait $j"
      continue
    fi

    test=`cat $file | grep -v '@TestTemplate' | grep '@Test'`
    class=`echo ${j:2:1000} | sed 's/\//./g'`
    suffix=`echo $typ | wc -m`
    class=${class:0:-$suffix}
    class=${class##*.}
    if [[ -z $test ]]; then
      echo "skip $j"
      continue
    elif [[ ! -z ${blacklist[$class]} ]]; then
      echo "skip $j as it is in blacklist"
      continue
    else
      echo "Run tests in $sub:test --tests $class" @ `date`
    fi
    sub=`echo $sub | sed -e "s/\//:/g"`
    ./gradlew $sub:test --tests $class
    exit_status=$?

    if [[ $exit_status != 0 ]]; then
      echo "Test $sub:test --tests $class failed"
      exit $exit_status
    fi
  done
done
done

echo "end time: " `date`
echo "Successfully run all the tests"
exit 0
