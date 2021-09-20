<img src="docs/plot/logo.png" alt="logo" width="250"/>

[![Slack](https://img.shields.io/badge/slack-pafka--help-blue?logo=slack)](https://join.slack.com/t/memarkworkspace/shared_invite/zt-o1wa5wqt-euKxFgyrUUrQCqJ4rE0oPw)
[![Release](https://img.shields.io/github/v/release/4paradigm/pafka)](https://github.com/4paradigm/pafka/releases)
[![Docker Pulls](https://img.shields.io/docker/pulls/4pdopensource/pafka-dev)](https://hub.docker.com/r/4pdopensource/pafka-dev)
[![Stars](https://img.shields.io/github/stars/4paradigm/pafka)](https://github.com/4paradigm/pafka/stargazers)
[![Fork](https://img.shields.io/github/forks/4paradigm/pafka)](https://github.com/4paradigm/pafka/network/members)
[![License](https://img.shields.io/github/license/4paradigm/pafka)](https://github.com/4paradigm/pafka/blob/main/LICENSE)


Pafka: Persistent Memory (PMem) Accelerated Kafka
===

## Introduction

Pafka is an evolved version of Apache Kafka developed by [MemArk](https://memark.io/en). Kafka is an open-source distributed event streaming/message queue system for handling real-time data feeds efficiently and reliably. However, its performance (e.g., throughput and latency) is constrained by the slow disk. Pafka enhances Kafka using Intel® Optane™ Persistent Memory (PMem) that can achieve more efficient persistence performance compared with HDD/SSD. With careful design and implementation, Pafka can achieve 7.5 GB/s write throughput and 10 GB/s read throughput on a single CPU socket.


## Pafka vs Kafka

### Performance

We conducted some preliminary experiments on our in-house servers. One server is used as the Kafka broker server, and another two servers as the clients. Each of the client servers run 16 clients to saturate the server throughput. We're using the `ProducerPerformance` and `ConsumerPerformance` shipped by Kafka and the record size of 1024 for the benchmark.

#### Server Specification

The server spec is as follows:

|Item|Spec|
|---|----|
|CPU|Intel(R) Xeon(R) Gold 6252 Processor (24 cores/48 threads) * 2|
|Memory|376 GB|
|Network|Mellanox ConnectX-5 100 Gbps|
|PMem|128 GB x 6 = 768 GB|

The storage spec and performance:

|Storage Type|Write (MB/s)|Read (MB/s)|
|---|---|---|
|HDD|32k: 5.7 <br/> 320k: 37.5 <br/> 3200k: 78.3 <br/>|86.5|
|HDD RAID|530|313|
|Sata SSD|458|300|
|NVMe SSD|2,421|2,547|
|PMem|9,500|37,120|

For `HDD`, we use batch size of 32k, 320k and 3200k for write, respectively, while read does not change much as we increase the batch size. For other storage types, we use batch size of 32k, as increasing to larger batch size does not increase the performance much. For `PMem`, we use `PersistentMemoryBlock` of [pmdk llpl](https://github.com/4paradigm/llpl) for the performance benchmark.

#### Performance Results

<p float="left">
    <img src="docs/plot/perf.png" alt="throughput" width="400"/>
    <img src="docs/plot/latency.png" alt="latency" width="400"/>
</p>
As we can see, the consumer throughput of Pafka with PMem has almost reached the network bottleneck (100 Gbps ~= 12.5 GB/s). Compared with NVMe SSD, Pafka boosts the producer throughput by 275% to 7508.68 MB/sec. In terms of latency, Pafka can achieve an average latency of 0.1 seconds for both producer and consumer.


## Get Started

For complete documentation of Kafka, refer to [here](README.kafka.md).

### Docker Image
The easiest way to try Pafka is to use the docker image: https://hub.docker.com/r/4pdopensource/pafka-dev

```
docker run -it -v $YOUR_PMEM_PATH:/mnt/mem 4pdopensource/pafka-dev bash
```

where $YOUR_PMEM_PATH is the mount point of PMem (DAX file system) in the host system.

If you use the docker image, you can skip the following `Compile` step.

### Compile

#### Dependencies

- [pmdk pcj](https://github.com/4paradigm/pcj)
- [pmdk llpl](https://github.com/4paradigm/llpl)

> :warning: **We have done some modifications on the original pmdk source codes. 
> Please download the source code from the two repositories provided above.**

**We have already shipped pcj and llpl jars in `libs` folder in the Pafka repository. They are compiled with java 8 and g++ 4.8.5. In general, you are not required to compile the two libraries by yourself. However, if you encounter any compilation/running error caused by these two libraries, you can download the source codes and compile on your own environment.**

##### Compile pmdk libraries

After cloning the source code:

    # compile pcj
    cd pcj
    make && make jar
    cp target/pcj.jar $PAFKA_HOME/libs
    
    # compile llpl
    cd llpl
    make && make jar
    cp target/llpl.jar $PAFKA_HOME/libs

#### Build Pafka jar

    ./gradlew jar

### Run

#### Environmental setup
To see whether it works or not, you can use any file system with normal hard disk. For the best performance, it requires the availability of PMem hardware mounted as a DAX file system. 


#### Config

In order to support PMem storage, we add some more config fields to the Kafka [server config](config/server.properties). 

|Config|Default Value|Note|
|------|-------------|----|
|storage.pmem.path|/pmem|pmem mount path. first-layer storage <br /> (Only applicable if log.channel.type=mix or pmem)|
|storage.pmem.size|-1|pmem capacity in bytes; -1 means use all the space <br />(Only applicable if log.channel.type=mix or pmem)|
|storage.hdd.path|/hdd|hdd mount path. second-layer storage <br />(Only applicable if log.channel.type=mix)|
|storage.migrate.threads|1|the number of threads used for migration <br />(Only applicable if log.channel.type=mix)|
|storage.migrate.threshold|0.6|the threshold used to control when to start the migration <br />(Only applicable if log.channel.type=mix)|
|log.channel.type|file|log file channel type. <br /> Options: "file", "pmem", "mix".<br />"file": use normal FileChannel as vanilla Kafka does <br />"pmem": use PMemChannel, which will use pmem as the log storage<br />"mix": use MixChannel, which will use pmem as the first-layer storage and hdd as the second-layer storage|
|log.pmem.pool.ratio|0.8|A pool of log segments will be pre-allocated. This is the proportion of total pmem size. Pre-allocation will increase the first startup time, but can eliminate the dynamic allocation cost when serving requests.<br />(Only applicable if log.channel.type=mix or pmem)|

> :warning: **`log.preallocate` has to be set to `true` if pmem is used, as PMem MemoryBlock does not support `append`-like operations.**

Sample config in config/server.properties is as follows:

    log.dirs=/mnt/pmem/kafka/
    storage.pmem.path=/mnt/pmem/kafka/
    storage.pmem.size=600000000000
    storage.hdd.path=/mnt/hdd/kafka
    log.pmem.pool.ratio=0.8
    log.channel.type=mix
    # log.preallocate have to set to true if pmem is used
    log.preallocate=true

#### Start Pafka
Follow instructions in https://kafka.apache.org/quickstart. Basically:

    bin/zookeeper-server-start.sh config/zookeeper.properties > zk.log 2>&1 &
    bin/kafka-server-start.sh config/server.properties > pafka.log 2>&1 &

 #### Benchmark Pafka

 ##### Producer

###### Single Client
```bash
# bin/kafka-producer-perf-test.sh --topic $TOPIC --throughput $MAX_THROUGHPUT --num-records $NUM_RECORDS --record-size $RECORD_SIZE --producer.config config/producer.properties --producer-props bootstrap.servers=$BROKER_IP:$PORT
bin/kafka-producer-perf-test.sh --topic test --throughput 1000000 --num-records 1000000 --record-size 1024 --producer.config config/producer.properties --producer-props bootstrap.servers=localhost:9092
```

###### Multiple Clients
We provide a script to let you run multiple clients on multiple hosts.
For example, if you want to run 16 producers in each of the hosts, `node-1` and `node-2`, you can run the following command:
```bash
python3 bin/bench.py --threads 16 --hosts "node-1 node-2" --num_records 100000000 --type producer
```
In total, there are 32 clients, which will generate 100000000 records. Each client is responsible for populating one topic.

> In order to make it work, you have to configure password-less login from the running machine
> to the client machines.


You can run `python3 bin/bench.py --help` to see other benchmark options.

 ##### Consumer

###### Single Client
```bash
# bin/kafka-consumer-perf-test.sh --topic $TOPIC --consumer.config config/consumer.properties --bootstrap-server $BROKER_IP:$PORT --messages $NUM_RECORDS --show-detailed-stats --reporting-interval $REPORT_INTERVAL --timeout $TIMEOUT_IN_MS
bin/kafka-consumer-perf-test.sh --topic test --consumer.config config/consumer.properties --bootstrap-server localhost:9092 --messages 1000000 --show-detailed-stats --reporting-interval 1000 --timeout 100000
```

###### Multiple Clients
Similarly, you can use the same script as producer benchmark to launch multiple clients.
```bash
python3 bin/bench.py --threads 16 --hosts "node-1 node-2" --num_records 100000000 --type consumer
```

## Limitations

- pmdk llpl `MemoryPool` does not provide a `ByteBuffer` API.
We did some hacking to provide a zero-copy ByteBuffer API. You may see some warnings from JRE with version >= 9.
We've tested on Java 8, Java 11 and Java 15.

   > WARNING: An illegal reflective access operation has occurred
   > WARNING: Illegal reflective access by com.intel.pmem.llpl.MemoryPoolImpl to field java.nio.Buffer.address
   > WARNING: Please consider reporting this to the maintainers of com.intel.pmem.llpl.MemoryPoolImpl
   > WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations
   > WARNING: All illegal access operations will be denied in a future release


- Currently, only the log files are stored in PMem, while the indexes are still kept as normal files, as we do not see much performance gain if we move the indexes to PMem.
- The current released version (`v0.1.x`) uses PMem as the only storage device, which may limit the use for some scenarios that require a large capacity for log storage. The next release (`v0.2.0`) will address this issue by introducing a tiered storage strategy.
=======
- Currently, only the log files are stored in PMem, while the indexes are still kept as normal files,
as we do not see much performance gain if we move the indexes to PMem.
>>>>>>> add mix channel type and update some config fields


## Roadmap

| Version |	Status | Features |
|---|---|---|
|v0.1.1|Released|- Use PMem for data storage <br /> - Significant performance boost compared with Kafka |
|v0.2.0|To be released in September 2021|- A layered storage strategy to utilize the total capacity of all storage devices (HDD/SSD/PMem) while maintaining the efficiency by our cold-hot data migration algorithms<br /> - Further PMem performance improvement by using `libpmem` |

## Community

Pafka is developed by MemArk (https://memark.io/en), which is a tech community focusing on leveraging modern storage architecture for system enhancement. MemArk is led by 4Paradigm (https://www.4paradigm.com/) and other sponsors (such as Intel). Please join our community for:

- Chatting: For any feedback, suggestions, issues, and anything about using Pafka or other storage related topics, you can join our interactive discussion channel at **Slack** [#pafka-help](https://join.slack.com/t/memarkworkspace/shared_invite/zt-o1wa5wqt-euKxFgyrUUrQCqJ4rE0oPw)
- Development discussion: If you would like to formally report a bug or suggestion, please use the **GitHub Issues**; if you would like to propose a new feature for some discussion, or would like to start a pull request, please use the **GitHub Discussions**, and our developers will respond promptly.

You can also contact the authors directly for any feedback:
- ZHANG Hao: zhanghao@4paradigm.com
- LU Mian: lumian@4paradigm.com



