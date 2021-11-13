#! /usr/bin/env python3
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

import argparse
import os
import subprocess
import time
import mmap
import threading
from datetime import datetime
import csv
import random

out_f = None

arg_parser = argparse.ArgumentParser()
arg_parser.add_argument("--type", help="producer/consumer", type=str, default="producer")
arg_parser.add_argument("--timeout", help="timeout in ms (for consumer only)", type=int, default=1000000)
arg_parser.add_argument("--num_records", help="num of records", type=int, default=5000000)
arg_parser.add_argument("--record_size", help="record size (for producer only)", type=int, default=1024)
arg_parser.add_argument("--throughput", help="max throughput (for producer only)", type=int, default=12000000)
arg_parser.add_argument("--brokers", help="broker list", type=str, default="localhost:9092")
arg_parser.add_argument("--threads", help="threads per host", type=int, default=16)
arg_parser.add_argument("--hosts", help="the hosts where clients will run", type=str, default="localhost")
arg_parser.add_argument("--java_home", help="java home. default: $JAVA_HOME", type=str, default=os.environ.get("JAVA_HOME"))
arg_parser.add_argument("--wait_for_all", help="wait for all clients to complete", action="store_true", default=False)
arg_parser.add_argument("--reporting_interval", help="report interval in miliseconds", type=int, default=2000)
arg_parser.add_argument("--output_file", help="result output file", type=str, default="")
arg_parser.add_argument("--topic_config", help="topic configs sep by ';'", type=str, default="")
arg_parser.add_argument("--dynamic", help="dynamic throughput (for producer only): min:max:avg", type=str, default="100000:500000:8000000")
arg_parser.add_argument("--use_dynamic", help="use dynamic throughput control", action="store_true", default=False)
arg_parser.add_argument("--throttler_config", help="throttler config (applicable to dynamic throughput only)", type=str, default="/tmp/producer-throttler")
arg_parser.add_argument("--sleept", help="min sleep time for every step (applicable to dynamic throughput only)", type=int, default=10)
arg_parser.add_argument("--steps", help="total steps (applicable to dynamic throughput only)", type=int, default=5)
arg_parser.add_argument("--control_type", help="control type: sleep; step (applicable to dynamic throughput only)", type=str, default="sleep")
arg_parser.add_argument("--only_min_max", help="only use the min and max throughut (applicable to dynamic throughput only)", action="store_true", default=False)

start_timestamp = 0

def execute_cmd_realtime(cmd, shell=True):
    child = subprocess.Popen(
        cmd, stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        shell=shell,
        bufsize=0)

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


def get_base_dir():
    dir_path = os.path.dirname(os.path.realpath(__file__))
    return dir_path + "/.."


def launch_clients(args, later=10):
    print("launch {} clients for every host [{}]".format(args.threads, args.hosts))
    start = time.time()
    host_sep = " "
    bin = PAFKA_HOME
    count = 0
    hosts = args.hosts.split(host_sep)
    records_per_thread = int(args.num_records / args.threads / len(hosts))
    throughput_per_thread = int(args.throughput / args.threads / len(hosts))
    outs = {}
    start_ts=int(start + later)
    for host in hosts:
        host_cmdprefix = host.split("/")
        host = host_cmdprefix[0]
        if len(host_cmdprefix) < 2:
            cmdprefix = ""
        else:
            cmdprefix = host_cmdprefix[1]

        cmdprefix = "BENCH_START_TS={} ".format(start_ts) + cmdprefix

        outs[host] = []
        for i in range(args.threads):
            topic = "test-{}".format(count)
            if args.type == "producer":
                if args.use_dynamic:
                    cmdprefix = "PRODUCER_THROTTLER_CONFIG={} ".format(args.throttler_config) + cmdprefix
                cmd='ssh {} "cd {}; JAVA_HOME={} {} ./bin/kafka-producer-perf-test.sh --topic {} --num-records {} --record-size {} --producer.config config/producer.properties --throughput {} --producer-props bootstrap.servers={} --reporting-interval {}" 2>&1'.format(host, bin, args.java_home, cmdprefix, topic, records_per_thread, args.record_size, throughput_per_thread, args.brokers, args.reporting_interval)
            else:
                cmd='ssh {} "cd {}; JAVA_HOME={} {} ./bin/kafka-consumer-perf-test.sh --topic {} --messages {} --consumer.config config/consumer.properties --bootstrap-server {} --show-detailed-stats --timeout {} --reporting-interval {}" 2>&1'.format(host, bin, args.java_home, cmdprefix, topic, records_per_thread, args.brokers, args.timeout, args.reporting_interval)
            outs[host].append(execute_cmd_realtime(cmd))
            count += 1

    end = time.time()
    print("launch clients in {} seconds".format(end - start))
    to_sleep = later - (end - start)
    if to_sleep > 0:
        time.sleep(to_sleep)
    return outs


def config_topic(topic_num, brokers, topic_config):
    if topic_config is None or len(topic_config) == 0:
        return

    print("config topic: {}".format(topic_config))
    configs = topic_config.split(";")
    for i in range(topic_num):
        topic = "test-{}".format(i)
        for config in configs:
            cmd='cd {}; bin/kafka-topics.sh --bootstrap-server {} --create --topic {} --config {}'.format(PAFKA_HOME, brokers, topic, config)
            out, err = execute_cmd(cmd)
            # print("cmd1:", out, err)

            cmd='cd {}; bin/kafka-configs.sh --bootstrap-server {} --entity-type topics --entity-name {} --alter --add-config {}'.format(PAFKA_HOME, brokers, topic, config)
            out, err = execute_cmd(cmd)
            # print("cmd2:", out, err)


def control_clients(hosts, threads, throughput=0, init=False, config="/tmp/producer-throttler"):
    print("Throttle throughput = {} records/s".format(throughput))

    bin = PAFKA_HOME
    throughput_per_thread = int(throughput / threads / len(hosts))
    for host in hosts:
        if init:
            cmd='ssh {} "cd {}; python3 ./bin/throttler.py --action init --config {}"'.format(host, bin, config)
        else:
            cmd='ssh {} "cd {}; python3 ./bin/throttler.py --action set --throughput {} --config {}"'.format(host, bin, throughput_per_thread, config)

        out, err = execute_cmd(cmd)


def kill_clients(hosts, typ="producer"):
    if typ == "producer":
        name = "ProducerPerformance"
    else:
        name = "ConsumerPerformance"

    for host in hosts:
        print("Killing all clients @{}".format(host))
        cmd = 'ssh {} "jps | grep {}"'.format(host, name)
        out, err = execute_cmd(cmd)
        lines = [l.strip() for l in out.split("\n")]
        pids = [line.split(" ")[0] for line in lines if len(line) > 0]
        cmd = 'ssh {} "kill {}"'.format(host, " ".join(pids))
        execute_cmd(cmd)
        cmd = 'ssh {} "kill -9 {}"'.format(host, " ".join(pids))
        execute_cmd(cmd)


def parse_producer(line):
    rec_per_s = 0
    thr_per_s = 0
    thr_unit = "MB/s"
    avg_lat = 0
    max_lat = 0
    aggr_records = 0

    throughput_limit = 0
    if "records sent" in line and "99th" not in line:
        toks = line.split(",")
        try:
            rec_per_s = float(toks[1].strip().split(" ")[0])
            thr_a_unit = toks[1].strip().split("(")[1].strip().split(" ")
            thr_per_s = float(thr_a_unit[0])
            thr_unit = thr_a_unit[1].strip(')')

            avg_lat = float(toks[4].strip().split(" ")[0])
            max_lat = float(toks[5].strip().split(" ")[0])
            if len(toks) >= 7:
                throughput_limit = int(toks[6].strip().split(" ")[0])

            aggr_records = int(toks[0].strip().split(" ")[0])
        except ValueError:
            print("ValueError error: {}".format(line))
        except:
            print("Parse Error: {}".format(line))

    return rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records, throughput_limit


def parse_consumer(line):
    rec_per_s = 0
    thr_per_s = 0
    thr_unit = "MB/s"
    avg_lat = 0
    max_lat = 0
    aggr_records = 0

    line = line.strip()
    toks = line.split(",")
    if len(toks) == 4 and "records received" in toks[0]:
        r_t = toks[1].strip().split(" ")
        rec_per_s = float(r_t[0].strip())
        thr_per_s = float(r_t[2].strip('('))

        avg_lat = float(toks[2].strip().split(' ')[0].strip())
        max_lat = float(toks[3].strip().split(' ')[0].strip())

        aggr_records = int(toks[0].strip().split(' ')[0])
    elif len(toks) >= 4 and toks[1].strip() == '0':
        rec_per_s = float(toks[5].strip())
        thr_per_s = float(toks[3].strip())

        avg_lat = float(toks[-1].strip())
        max_lat = float(toks[-2].strip())

        aggr_records = int(toks[4].strip())

    return rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records


class Throttler (threading.Thread):
    def __init__(self, hosts: list, threads: int, dynamic_thr: str, sleept=10, steps=5, control_type="sleep", name="Throttler", only_min_max=False, throttler_config="/tmp/producer-throttler"):
        threading.Thread.__init__(self)
        self.name = name
        toks = dynamic_thr.split(":")

        self.min_thr = int(toks[0])
        self.avg_thr = int(toks[1])
        self.max_thr = int(toks[2])
        self.hosts = hosts
        self.threads = threads
        self.sleept = sleept
        self.steps = steps
        self.control_type = control_type
        self.stopped = False
        self.only_min_max = only_min_max
        self.throttler_config = throttler_config

    def run(self):
        print("Starting " + self.name)
        factor = float(self.max_thr + self.avg_thr) / (self.avg_thr + self.min_thr)
        run_steps = []
        delay_high = 20
        delay_low = 20

        if self.only_min_max:
            run_steps = [self.max_thr, self.min_thr]
            factor = float(self.max_thr - self.avg_thr) / (self.avg_thr - self.min_thr)
            sleep_low = factor * self.sleept - delay_low
            sleep_high = self.sleept - delay_high
        else:
            if self.control_type == "step":
                steps_high = self.steps
                steps_low = int(self.steps * factor)
                sleep_high = self.sleept
                sleep_low = self.sleept
                print("steps in range [{} {}]: {}, steps in range [{} {}]: {}".format(self.min_thr, self.avg_thr, steps_low, self.avg_thr, self.max_thr, steps_high))
            else:
                steps_high = self.steps
                steps_low = self.steps
                sleep_high = self.sleept
                sleep_low = self.sleept * factor
                print("sleep time in range [{} {}]: {}, sleep time in range [{} {}]: {}".format(self.min_thr, self.avg_thr, self.sleept * factor, self.avg_thr, self.max_thr, self.sleept))

            unit_high = int((self.max_thr - self.avg_thr) / steps_high)
            unit_low = int((self.avg_thr - self.min_thr) / steps_low)

            thr = self.min_thr
            run_steps.append(thr)
            while thr < self.max_thr:
                if thr < self.avg_thr:
                    thr += unit_low
                else:
                    thr += unit_high

                run_steps.append(thr)

            shuffled_run_steps = []
            sj = 0
            si = len(run_steps) - 1
            for i in range(steps_high):
                shuffled_run_steps.append(run_steps[si])
                for j in range(int(steps_low / steps_high)):
                    shuffled_run_steps.append(run_steps[sj])
                    sj += 1

                si -= 1

            for i in run_steps:
                if i not in shuffled_run_steps:
                    shuffled_run_steps.append(i)

            # print("Run steps: {}".format(run_steps))
            # print("shuffled_steps: {}".format(shuffled_run_steps))
            assert(len(shuffled_run_steps) == len(run_steps))
            assert(sorted(shuffled_run_steps) == sorted(run_steps))
            # run_steps = shuffled_run_steps

            # random.shuffle(run_steps)

        print("Run steps: {}".format(run_steps))
        print("Sleep windows: low: {}, high: {}".format(sleep_low, sleep_high))

        control_clients(self.hosts, self.threads, self.min_thr, config=self.throttler_config)
        time.sleep(10)

        count = 0
        while not self.stopped:
            for thr in run_steps:
                if thr < self.avg_thr:
                    sleep_curr = sleep_low
                else:
                    sleep_curr = sleep_high

                control_clients(self.hosts, self.threads, thr, config=self.throttler_config)
                time.sleep(sleep_curr)

        print("Exiting " + self.name)

    def stop(self):
        self.stopped = True


def print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, res_count, total_throughput_limit, typ="producer", writer=None):
    curr_timestamp = int(time.time())
    global start_timestamp
    if start_timestamp == 0:
        start_timestamp = int(curr_timestamp)

    rel_timestamp = curr_timestamp - start_timestamp

    printable_time = datetime.now().strftime("%d/%m/%Y %H:%M:%S")
    if typ == "producer":
        print("[%s][%d] %d records sent, %.2f records/sec (%.2f %s), %.2f ms avg latency, %.2f ms max latency, %d records/sec max (aggregated from %d clients)" % (printable_time, rel_timestamp, cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, total_throughput_limit, res_count), flush=True)
    elif typ == "consumer":
        print("[%s][%d] %d records received, %.2f records/sec (%.2f %s), %.2f ms avg latency, %.2f ms max latency (aggregated from %d clients)" % (printable_time, rel_timestamp, cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, res_count), flush=True)

    if writer is not None:
        writer.writerow([printable_time, curr_timestamp, rel_timestamp, cum_records, "%.2f" % total_rec_per_s, "%.2f" % total_thr_per_s, "%.2f" % total_avg_lat, "%.2f" % total_max_lat])
        out_f.flush()


if __name__ == "__main__":
    args = arg_parser.parse_args()
    print("args = {}".format(args))
    PAFKA_HOME = get_base_dir()

    hosts = args.hosts.split(" ")
    hosts = [host.split("/")[0] for host in hosts]
    threads = args.threads
    total_clients = len(hosts) * threads
    cum_records = 0

    if args.use_dynamic and args.type == "producer":
        control_clients(hosts=hosts, threads=threads, init=True, config=args.throttler_config)
        throttler = Throttler(hosts=hosts, threads=threads, dynamic_thr=args.dynamic, sleept=args.sleept, steps=args.steps, control_type=args.control_type, only_min_max=args.only_min_max, throttler_config=args.throttler_config)
        throttler.daemon = True
        throttler.start()

    if args.type == "producer":
        config_topic(total_clients, args.brokers, args.topic_config)

    # launch all the clients
    outs = launch_clients(args)
    assert(len(hosts) == len(outs))

    out_writer = None
    if args.output_file is not None and len(args.output_file) > 0:
        out_f = open(args.output_file, "w+", encoding="UTF8")
        out_writer = csv.writer(out_f)

    hist_aggr_records = {}
    for host in hosts:
        hist_aggr_records[host] = [0] * threads

    while True:
        completed = 0

        total_rec_per_s = 0
        total_thr_per_s = 0
        rec_per_s = 0
        thr_per_s = 0
        total_avg_lat = 0
        total_max_lat = 0
        total_aggr_records = 0
        throughput_limit = 0
        total_throughput_limit = 0

        res_count = 0
        for host, outs_per_host in outs.items():
            i = 0
            for out in outs_per_host:
                try:
                    line = next(out)
                    # print("{}:{} {}".format(host, res_count, line))
                    if args.type == "producer":
                        rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records, throughput_limit = parse_producer(line)
                        total_throughput_limit += throughput_limit
                    else:
                        rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records = parse_consumer(line)

                    res_count += 1
                    total_rec_per_s = rec_per_s + total_rec_per_s
                    total_thr_per_s = thr_per_s + total_thr_per_s
                    total_avg_lat = total_avg_lat + avg_lat * aggr_records
                    total_max_lat = max(total_max_lat, max_lat)
                    total_aggr_records = total_aggr_records + aggr_records

                    hist_aggr_records[host][i] = aggr_records

                    # print("{}:{} {}".format(host, res_count, throughput_limit))
                    # print(rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records, total_rec_per_s, total_thr_per_s, total_avg_lat / total_aggr_records, total_max_lat, total_aggr_records)
                except StopIteration:
                    completed += 1
                    if args.type == "consumer":
                        total_aggr_records = total_aggr_records + hist_aggr_records[host][i]

                    if args.wait_for_all and completed >= total_clients:
                        print("All " + str(completed) + " clients completed")
                        break
                    elif not args.wait_for_all:
                        print("{} clients completed. Kill the {} other clients".format(completed, (total_clients - completed)))
                        kill_clients(hosts, typ=args.type)
                        break

                i += 1
            else:
                continue
            break
        else:
            if completed > 0:
                print("{} clients completed. Wait for {} other clients".format(completed, total_clients - completed))
            if total_rec_per_s != 0:
                if args.type == "producer":
                    cum_records += total_aggr_records
                else:
                    cum_records = total_aggr_records

                print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat / total_aggr_records, total_max_lat, res_count, total_throughput_limit, typ=args.type, writer=out_writer)
            continue


        if total_rec_per_s != 0:
            if args.type == "producer":
                cum_records += total_aggr_records
            else:
                cum_records = total_aggr_records

            print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat / total_aggr_records, total_max_lat, res_count, total_throughput_limit, typ=args.type, writer=out_writer)
        break

    if args.use_dynamic and args.type == "producer":
        throttler.stop()
        # throttler.join()
