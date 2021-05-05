#! /usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "must specify the tag"
  exit 1
fi

tag=$1
repo=pafka/pafka-dev
docker build -t $repo:$tag -f Dockerfile .
docker tag $repo:$tag $repo:latest
docker push $repo:$tag
docker push $repo:latest
