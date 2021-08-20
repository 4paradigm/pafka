#! /usr/bin/env bash

# ************ configuration *****************
# total records to consume for all clients
num_records=16000000

# broker address
brokers=172.29.100.24:9094

# number of threads per host
threads=4

# all hosts as client machines
hosts=(localhost)

# java home
java_home=$JAVA_HOME

# ************ end of configuration *****************

base_dir=$(dirname $0)
bin=`cd $base_dir; pwd`
bin=$bin/../
echo "Pafka home: $bin"

pids=()
records_per_thread=`echo "$num_records/$threads/${#hosts[@]}" | bc`

lockfile=started.lock
rm $lockfile

count=0
for host in ${hosts[@]}
do
  mkdir $host > /dev/null 2>&1
  rm $host/*
for i in `seq 1 $threads`
do
  log=$host/th-$i.log
  ssh $host "cd $bin; while [[ true ]]; do if [[ -f "$lockfile" ]]; then date; break; fi; sleep 1; done; JAVA_HOME=$java_home ./bin/kafka-consumer-perf-test.sh --topic test-$count --consumer.config config/consumer.properties --bootstrap-server $brokers --messages $records_per_thread --reporting-interval 500 --show-detailed-stats" > $log 2>&1 &
  pid=$!
  pids[$count]=$pid
  count=$((count+1))
  sleep 0.1
done
done

len=${#pids[*]}
curr_len=$len
echo "started $len consumers, pids = ${pids[@]}"

touch $lockfile

sleep 1

count=0
while [ True ];
do
  total_rec=0
  total_thr=0
  thr_unit='MB/sec'
  rec=0
  thr=0
  for host in ${hosts[@]}
  do
    ssh $host "sudo bash -c 'echo 1 > /proc/sys/vm/drop_caches'"
    for i in `seq 1 $threads`
    do
      log=$host/th-$i.log
      last_line=`tail -1 $log`
      thr_unit="MB/s"
      if [[ $last_line == "2021"* ]]; then
        rec=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 6`
        thr=`echo $last_line | sed 's/  *//g' | cut -d ',' -f 4`
      elif [[ $last_line == *"records received"* ]]; then
        rec=`echo $last_line | cut -d ',' -f 2 | cut -d ' ' -f2`
        thr=`echo $last_line | cut -d ',' -f 2 | cut -d ' ' -f4 | cut -d '(' -f2`
      fi

      total_rec=`echo "$rec + $total_rec" | bc`
      total_thr=`echo "$thr + $total_thr" | bc`
    done
  done

  if [[ $total_rec = 0 ]]; then
    echo "No throughput record"
    if [[ $count -ge 5 ]]; then
      echo "You may check the logs in ./${hosts[@]}"
    fi
    sleep 2
    count=$((count+1))
  else
    echo "Aggregated Throughput: $total_rec records/sec ($total_thr $thr_unit)"
    # sleep 1
  fi

  completed=0
  for (( i = 0; i < len; i++ ))
  do
    if ! kill -s 0 ${pids[$i]} > /dev/null 2>&1; then
      echo "Consumer No. $i completed"
      completed=$((completed+1))
    fi
  done

  if [[ $completed -ge 1 ]]; then
    echo "$completed Consumers Completed"
    break
  fi
done

if [[ $completed -lt $len ]]; then
  for host in ${hosts[@]}
  do
    remaining_pids=`ssh $host "jps | grep ConsumerPerformance | cut -d ' ' -f1" | sed ':a;N;$!ba;s/\n/\t/g'`
    echo "Kill all remaining ConsumerPerformance @ $host: $remaining_pids"
    ssh $host "kill $remaining_pids"
    ssh $host "kill -9 $remaining_pids"
  done
fi

wait
