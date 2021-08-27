#! /usr/bin/env bash

# ************ configuration *****************
# total records to produce for all clients
# estimated disk usage = $num_records * $record_size
num_records=16000000

# size per record
record_size=1024

# max total throughput (request/s) for all clients
throughput=16000000

# broker address
brokers=172.29.100.24:9094

# number of threads per host
threads=4

# all hosts as client machines
hosts=(localhost)

# java home
java_home=$JAVA_HOME

# wait for all clients
wait_for_all=true

# ************ end of configuration *****************

base_dir=$(dirname $0)
bin=`cd $base_dir; pwd`
bin=$bin/../
echo "Pafka home: $bin"

pids=()
records_per_thread=`echo "$num_records/$threads/${#hosts[@]}" | bc`
throughput_per_thread=`echo "$throughput/$threads/${#hosts[@]}" | bc`

if [[ $wait_for_all = true ]]; then
  com_count=`echo "$threads * ${#hosts[@]}" | bc`
else
  com_count=1
fi

count=0
for host in ${hosts[@]}
do
  logdir=producer-$host
  mkdir $logdir > /dev/null 2>&1
  rm $logdir/*
for i in `seq 1 $threads`
do
  log=$logdir/th-$i.log
  ssh $host "cd $bin; JAVA_HOME=$java_home ./bin/kafka-producer-perf-test.sh --topic test-$count --num-records $records_per_thread  --record-size $record_size --producer.config config/producer.properties  --throughput $throughput_per_thread --producer-props bootstrap.servers=$brokers" > $log 2>&1 &
  pid=$!
  pids[$count]=$pid
  count=$((count+1))
  sleep 0.1
done
done

len=${#pids[*]}
echo "started $len producers, pids = ${pids[@]}"

sleep 1

count=0
while [ True ];
do
  total_rec=0
  total_thr=0
  thr_unit='MB/sec'
  for host in ${hosts[@]}
  do
    # ssh $host "sudo bash -c 'echo 1 > /proc/sys/vm/drop_caches'"
    logdir=producer-$host
    for i in `seq 1 $threads`
    do
      log=$logdir/th-$i.log
      last_line=`tail -1 $log`
      if [[ $last_line == *"records sent"* ]]; then
        rec=`echo $last_line | cut -d ' ' -f 4`
        thr=`echo $last_line | cut -d ' ' -f 6 | cut -d '(' -f2`
        thr_unit=`echo $last_line | cut -d ' ' -f 7 | cut -d ')' -f1`

        total_rec=`echo "$rec + $total_rec" | bc`
        total_thr=`echo "$thr + $total_thr" | bc`
      fi
    done
  done

  if [[ $total_rec = 0 ]]; then
    echo "No throughput record"
    if [[ $count -ge 5 ]]; then
      echo "You may check the logs in ./producer-${hosts[@]}"
    fi
    sleep 2
    count=$((count+1))
  else
    echo "Aggregated Throughput: $total_rec records/sec ($total_thr $thr_unit)"
    sleep 1
  fi

  completed=0
  for (( i = 0; i < len; i++ ))
  do
    if ! kill -s 0 ${pids[$i]} > /dev/null 2>&1; then
      echo "Producer No. $i completed"
      completed=$((completed+1))
    fi
  done

  if [[ $completed -ge $com_count ]]; then
    echo "$completed Producers Completed"
    break
  fi
done

if [[ $completed -lt $len ]]; then
  for host in ${hosts[@]}
  do
    remaining_pids=`ssh $host "jps | grep ProducerPerformance | cut -d ' ' -f1" | sed ':a;N;$!ba;s/\n/\t/g'`
    if [[ ! -z $remaining_pids ]]; then
      echo "Kill all remaining ProducerPerformance @ $host: $remaining_pids"
      ssh $host "kill $remaining_pids" > /dev/null 2>&1
      ssh $host "kill -9 $remaining_pids" > /dev/null 2>&1
    fi
  done
fi

wait
