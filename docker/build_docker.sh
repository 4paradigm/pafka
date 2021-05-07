#! /usr/bin/env bash

if [[ $# -lt 1 ]]; then
  echo "must specify the tag"
  exit 1
fi

tag=$1
repo=pafka/pafka-dev
cache_image="$repo:latest"
docker pull ${cache_image} || true
docker build --cache-from ${cache_image} -t $repo:$tag -f docker/Dockerfile .
docker tag $repo:$tag $repo:latest
docker push $repo:$tag
docker push $repo:latest

# also push to repo2
repo2=4pdopensource/pafka
docker tag $repo:$tag $repo2:$tag
docker tag $repo2:$tag $repo2:latest
docker push $repo2:$tag
docker push $repo2:latest
