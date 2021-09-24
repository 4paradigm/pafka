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

# ************ configuration *****************
# total records to consume for all clients
num_records=500000000

# broker address
brokers=172.29.100.24:9094

# number of threads per host
threads=16

# all hosts as client machines
hosts=(node-1 node-4)

# java home
# consumer benchmark require jdk 11 for best performance
java_home=/4pd/home/zhanghao/install/jdk-11.0.8

# timeout in ms
timeout=1000000

# wait for all clients
wait_for_all=false

# ************ end of configuration *****************

base_dir=$(dirname $0)
bin=`cd $base_dir; pwd`
bin=$bin/../
echo "Pafka home: $bin"

pids=()
records_per_thread=`echo "$num_records/$threads/${#hosts[@]}" | bc`

if [[ $wait_for_all = true ]]; then
  com_count=`echo "$threads * ${#hosts[@]}" | bc`
else
  com_count=1
fi

count=0
for host in ${hosts[@]}
do
  logdir=consumer-$host
  mkdir $logdir > /dev/null 2>&1
  rm $logdir/*

  cmdprefix=''
  # if [[ $host = localhost ]]; then
  #   cmdprefix=numactl_n1
  # fi

  for i in `seq 1 $threads`
  do
    log=$logdir/th-$i.log
    ssh $host "cd $bin; JAVA_HOME=$java_home $cmdprefix ./bin/kafka-consumer-perf-test.sh --topic test-$count --consumer.config config/consumer.properties --bootstrap-server $brokers --messages $records_per_thread --reporting-interval 500 --show-detailed-stats --timeout $timeout" > $log 2>&1 &
    pid=$!
    pids[$count]=$pid
    count=$((count+1))
    sleep 0.1
  done
done

len=${#pids[*]}
curr_len=$len
echo "started $len consumers, pids = ${pids[@]}"

sleep 1

count=0
while [ True ];
do
  total_rec=0
  total_thr=0
  thr_unit='MB/sec'
  total_avg_lat=0
  total_max_lat=0
  total_aggr_records=0

  rec=0
  thr=0
  for host in ${hosts[@]}
  do
    # ssh $host "sudo bash -c 'echo 1 > /proc/sys/vm/drop_caches'"
    logdir=consumer-$host
    for i in `seq 1 $threads`
    do
      log=$logdir/th-$i.log
      last_line=`tail -1 $log`
      thr_unit="MB/s"
      if [[ $last_line == "2021"* ]]; then
        rec=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 6`
        thr=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 4`
        avg_lat=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 14`
        max_lat=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 13`
        aggr_records=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 5`
      elif [[ $last_line == *"records received"* ]]; then
        rec=`echo $last_line | cut -d ',' -f 2 | cut -d ' ' -f2`
        thr=`echo $last_line | cut -d ',' -f 2 | cut -d ' ' -f4 | cut -d '(' -f2`
        avg_lat=`echo $last_line | cut -d ',' -f 3 | cut -d ' ' -f2`
        max_lat=`echo $last_line | cut -d ',' -f 4 | cut -d ' ' -f2`
        aggr_records=`echo $last_line | cut -d ' ' -f 1`
      fi

      total_rec=`echo "$rec + $total_rec" | bc`
      total_thr=`echo "$thr + $total_thr" | bc`

      total_avg_lat=`echo "$total_avg_lat + $avg_lat * $aggr_records" | bc`
      total_max_lat=`echo "$total_max_lat $max_lat" | tr ' ' '\n' | sort -nr | tr '\n' ' ' | cut -d ' ' -f1`
      total_aggr_records=`echo "$total_aggr_records + $aggr_records" | bc`
    done
  done

  if [[ $total_rec = 0 ]]; then
    echo "No throughput record"
    if [[ $count -ge 5 ]]; then
      echo "You may check the logs in ./consumer-${hosts[@]}"
    fi
    sleep 2
    count=$((count+1))
  else
    total_avg_lat=`echo "scale=2; $total_avg_lat / $total_aggr_records" | bc`
    echo "Aggregated Performance: $total_rec records/sec ($total_thr $thr_unit), $total_avg_lat  ms avg latency, $total_max_lat ms max latency"
    sleep 1
  fi

  completed=0
  for (( i = 0; i < len; i++ ))
  do
    if ! kill -s 0 ${pids[$i]} > /dev/null 2>&1; then
      completed=$((completed+1))
    fi
  done

  if [[ $completed -gt 0 ]]; then
    echo "$completed Consumers completed"
  fi

  if [[ $completed -ge $com_count ]]; then
    echo "$completed Consumers Completed"
    break
  fi
done

if [[ $completed -lt $len ]]; then
  for host in ${hosts[@]}
  do
    remaining_pids=`ssh $host "jps | grep ConsumerPerformance | cut -d ' ' -f1" | sed ':a;N;$!ba;s/\n/\t/g'`
    if [[ ! -z $remaining_pids ]]; then
      echo "Kill all remaining ConsumerPerformance @ $host: $remaining_pids"
      ssh $host "kill $remaining_pids" > /dev/null 2>&1
      ssh $host "kill -9 $remaining_pids" > /dev/null 2>&1
    fi
  done
fi

wait
