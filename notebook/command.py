import subprocess
import os

os.environ["COLUMNS"] = "10000"

kafka_home = "../"

data = {
    "pafka": ["/mnt/mem/kafka", "/mnt/mem/zookeeper"],
    "kafka": ["/mnt/hdd/kafka", "/mnt/hdd/zookeeper"]
}

config = {
    "pafka": ["{}/config/server-pmem.properties".format(kafka_home), "{}/config/zookeeper-pmem.properties".format(kafka_home)],
    "kafka": ["{}/config/server-hdd.properties".format(kafka_home), "{}/config/zookeeper-hdd.properties".format(kafka_home)]
}


def execute_cmd_realtime(cmd, shell=True):
    child = subprocess.Popen(
        cmd, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=shell)

    while True:
        out = child.stdout.readline()
        pout = child.poll()
        if (not out) and pout is not None:
            break

        out = out.strip()
        if out:
            yield out.decode('utf-8')


def execute_cmd(cmd, shell=True):
    child = subprocess.Popen(
        cmd, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=shell)

    out, err = child.communicate()
    if out is not None:
        out = out.decode('utf-8')
    if err is not None:
        err = err.decode('utf-8')

    return out, err


def contain_text(text, log_file):
    contained, _ = execute_cmd("cat {} | grep '{}'".format(log_file, text))
    if contained:
        return True
    else:
        return False


def stop_service(name="pafka", delete_data=False):
    print("stopping {} ...".format(name))
    out, err = execute_cmd("{}/bin/kafka-server-stop.sh".format(kafka_home))
    if out:
        print(out)
    if err:
        print(err)

    if not (out + err).strip():
        while not contain_text("shut down completed", "{}.log".format(name)):
            execute_cmd("sleep 1")

    out, err = execute_cmd("{}/bin/zookeeper-server-stop.sh".format(kafka_home))
    if out:
        print(out)
    if err:
        print(err)

    if not err:
        print("{} stopped".format(name))

    if delete_data:
        execute_cmd("rm -rf {}".format(data[name][0]))
        execute_cmd("rm -rf {}".format(data[name][1]))
        print("data deleted")


def start_service(name="pafka", log_lines=10):
    out, _ = execute_cmd("jps | grep 'Kafka'")
    if out:
        print("service already started. stop first")
        return

    print("starting zookeeper ...")
    execute_cmd("{}/bin/zookeeper-server-start.sh {} > zk-{}.log 2>&1 &".format(kafka_home, config[name][1], name))
    execute_cmd("sleep 2")
    out, _ = execute_cmd("tail -{} zk-{}.log".format(log_lines, name))
    print(out)
    print("starting {} ...".format(name))
    execute_cmd("{}/bin/kafka-server-start.sh {} > {}.log 2>&1 &".format(kafka_home, config[name][0], name))

    log_file = "{}.log".format(name)
    while (not contain_text("started", log_file)) and (not contain_text("ERROR", log_file)):
        execute_cmd("sleep 1")
    out, _ = execute_cmd("tail -{} {}.log".format(log_lines, name))
    print(out)
    print("{} started".format(name))


def start_kafka():
    start_service("kafka")


def start_pafka():
    start_service("pafka")


def stop_kafka(delete_data=False):
    stop_service("kafka", delete_data)


def stop_pafka(delete_data=False):
    stop_service("pafka", delete_data)


def bench_producer(topic="test", max_throughput=1000000, num_records=10000000, record_size=1024):
    producer_config = "{}/config/producer.properties".format(kafka_home)
    cmd = "{}/bin/kafka-producer-perf-test.sh --topic {} --throughput {} --num-records {} --record-size {} --producer.config {} 2>&1".format(kafka_home, topic, max_throughput, num_records, record_size, producer_config)
    for line in execute_cmd_realtime(cmd):
        if ("SLF4J" not in line) and ("LEADER_NOT_AVAILABLE" not in line):
            print(line)

    print("Benchmark producer done")


def bench_consumer(topic="test", num_records=10000000, report_interval=1000, port=9092, timeout=100000, exclude_init=True):
    consumer_config = "{}/config/consumer.properties".format(kafka_home)

    if exclude_init:
        # call consume 1 record first to avoid the initialization cost
        cmd_init = "{}/bin/kafka-consumer-perf-test.sh --topic {} --consumer.config {} --bootstrap-server localhost:{} --messages {} --show-detailed-stats --reporting-interval {} --timeout {} 2>&1".format(kafka_home, topic, consumer_config, port, 1, report_interval, timeout)
        execute_cmd(cmd_init)

    cmd = "{}/bin/kafka-consumer-perf-test.sh --topic {} --consumer.config {} --bootstrap-server localhost:{} --messages {} --show-detailed-stats --reporting-interval {} --timeout {} 2>&1".format(kafka_home, topic, consumer_config, port, num_records, report_interval, timeout)
    for line in execute_cmd_realtime(cmd):
        if ("SLF4J" not in line) and ("LEADER_NOT_AVAILABLE" not in line):
            print(line)

    print("Benchmark consumer done")
