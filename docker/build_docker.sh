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


if [[ $# -lt 1 ]]; then
  echo "must specify the tag"
  exit 1
fi

tag=$1
repo=4pdopensource/pafka-dev
cache_image="$repo:latest"
docker pull ${cache_image} || true
docker build --cache-from ${cache_image} -t $repo:$tag -f docker/Dockerfile .
docker tag $repo:$tag $repo:latest
docker push $repo:$tag
docker push $repo:latest
