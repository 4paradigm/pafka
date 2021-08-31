#! /usr/bin/env python

import argparse
import os
import subprocess
import time
import mmap
import threading
from datetime import datetime
import csv

arg_parser = argparse.ArgumentParser()
arg_parser.add_argument("--type", help="producer/consumer", type=str, default="producer")
arg_parser.add_argument("--timeout", help="timeout for consumer in ms", type=int, default=1000000)
arg_parser.add_argument("--num_records", help="num of records", type=int, default=5000000)
arg_parser.add_argument("--record_size", help="record size (for producer only)", type=int, default=1024)
arg_parser.add_argument("--throughput", help="throughput (for producer only)", type=int, default=12000000)
arg_parser.add_argument("--dynamic", help="dynamic throughput (for producer only): min:max:avg", type=str, default="100000:500000:10000000")
arg_parser.add_argument("--use_dynamic", help="use dynamic throughput control", action="store_true", default=False)
arg_parser.add_argument("--brokers", help="broker list", type=str, default="172.29.100.24:9094")
arg_parser.add_argument("--threads", help="threads per host", type=int, default=16)
arg_parser.add_argument("--hosts", help="host list", type=str, default="node-1 node-4")
arg_parser.add_argument("--java_home", help="java home", type=str, default=os.environ.get("JAVA_HOME"))
arg_parser.add_argument("--wait_for_all", help="wait for all clients to complete", action="store_true", default=False)
arg_parser.add_argument("--reporting_interval", help="report interval in miliseconds", type=int, default=2000)
arg_parser.add_argument("--throttler_config", help="throttler config", type=str, default="/tmp/producer-throttler")
arg_parser.add_argument("--output_file", help="result output file", type=str, default="")
arg_parser.add_argument("--sleept", help="min sleep time for every step (dynamic throughput)", type=int, default=10)
arg_parser.add_argument("--steps", help="total steps (dynamic throughput)", type=int, default=5)
arg_parser.add_argument("--control_type", help="control type: sleep; step", type=str, default="sleep")

start_timestamp = 0

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


def get_base_dir():
    dir_path = os.path.dirname(os.path.realpath(__file__))
    return dir_path + "/.."


def launch_clients(args):
    print("launch {} clients for every host [{}]".format(args.threads, args.hosts))
    start = time.time()
    host_sep = " "
    bin = PAFKA_HOME
    count = 0
    hosts = args.hosts.split(host_sep)
    records_per_thread = int(args.num_records / args.threads / len(hosts))
    throughput_per_thread = int(args.throughput / args.threads / len(hosts))
    outs = {}
    for host in hosts:
        host_cmdprefix = host.split("/")
        host = host_cmdprefix[0]
        if len(host_cmdprefix) < 2:
            cmdprefix = ""
        else:
            cmdprefix = host_cmdprefix[1]

        outs[host] = []
        for i in range(args.threads):
            if args.type == "producer":
                if args.use_dynamic:
                    cmdprefix = "PRODUCER_THROTTLER_CONFIG={} ".format(args.throttler_config) + cmdprefix
                cmd='ssh {} "cd {}; JAVA_HOME={} {} ./bin/kafka-producer-perf-test.sh --topic test-{} --num-records {} --record-size {} --producer.config config/producer.properties --throughput {} --producer-props bootstrap.servers={} --reporting-interval {}" 2>&1'.format(host, bin, args.java_home, cmdprefix, count, records_per_thread, args.record_size, throughput_per_thread, args.brokers, args.reporting_interval)
            else:
                cmd='ssh {} "cd {}; JAVA_HOME={} {} ./bin/kafka-consumer-perf-test.sh --topic test-{} --messages {} --consumer.config config/consumer.properties --bootstrap-server {} --show-detailed-stats --timeout {} --reporting-interval {}" 2>&1'.format(host, bin, args.java_home, cmdprefix, count, records_per_thread, args.brokers, args.timeout, args.reporting_interval)
            outs[host].append(execute_cmd_realtime(cmd))
            count += 1

    end = time.time()
    print("launch clients in {} seconds".format(end - start))
    return outs


def control_clients(hosts, threads, throughput=0, init=False):
    print("Throttle throughput = {} records/s".format(throughput))

    bin = PAFKA_HOME
    throughput_per_thread = int(throughput / threads / len(hosts))
    for host in hosts:
        if init:
            cmd='ssh {} "cd {}; python3 ./bin/throttler.py --action init"'.format(host, bin)
        else:
            cmd='ssh {} "cd {}; python3 ./bin/throttler.py --action set --throughput {}"'.format(host, bin, throughput_per_thread)

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

    if "records sent" in line and "99th" not in line:
        toks = line.split(",")
        rec_per_s = float(toks[1].strip().split(" ")[0])
        thr_a_unit = toks[1].strip().split("(")[1].strip().split(" ")
        thr_per_s = float(thr_a_unit[0])
        thr_unit = thr_a_unit[1].strip(')')

        avg_lat = float(toks[4].strip().split(" ")[0])
        max_lat = float(toks[5].strip().split(" ")[0])

        aggr_records = int(toks[0].strip().split(" ")[0])

    return rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records


def parse_consumer(line):
    rec_per_s = 0
    thr_per_s = 0
    thr_unit = "MB/s"
    avg_lat = 0
    max_lat = 0
    aggr_records = 0

    line = line.strip()
    toks = line.split(",")
    if len(toks) >= 4 and toks[1].strip() == '0':
        toks = line.split(",")
        rec_per_s = float(toks[5].strip())
        thr_per_s = float(toks[3].strip())
        thr_unit = "MB/s"

        avg_lat = float(toks[-1].strip())
        max_lat = float(toks[-2].strip())

        aggr_records = int(toks[4].strip())

    return rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records


class Throttler (threading.Thread):
    def __init__(self, hosts: list, threads: int, dynamic_thr: str, sleept=10, steps=5, control_type="sleep", name="Throttler"):
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

    def run(self):
        print("Starting " + self.name)
        factor = float(self.max_thr + self.avg_thr) / (self.avg_thr + self.min_thr)

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

        control_clients(self.hosts, self.threads, self.min_thr)
        time.sleep(10)

        count = 0
        while not self.stopped:
            thr = self.min_thr
            while thr < self.max_thr:
                if thr < self.avg_thr:
                    thr += unit_low
                    sleep_curr = sleep_low
                else:
                    thr += unit_high
                    sleep_curr = sleep_high

                control_clients(self.hosts, self.threads, thr)
                time.sleep(sleep_curr)

        print("Exiting " + self.name)

    def stop(self):
        self.stopped = True


def print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, res_count, typ="producer", writer=None):
    curr_timestamp = int(time.time())
    global start_timestamp
    if start_timestamp == 0:
        start_timestamp = int(curr_timestamp)

    rel_timestamp = curr_timestamp - start_timestamp

    printable_time = datetime.now().strftime("%d/%m/%Y %H:%M:%S")
    if typ == "producer":
        print("[%s][%d] %d records sent, %.2f records/sec (%.2f %s), %.2f ms avg latency, %.2f ms max latency (aggregated from %d clients)" % (printable_time, rel_timestamp, cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, res_count))
    elif typ == "consumer":
        print("[%s][%d] %d records received, %.2f records/sec (%.2f %s), %.2f ms avg latency, %.2f ms max latency (aggregated from %d clients)" % (printable_time, rel_timestamp, cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat, total_max_lat, res_count))

    if writer is not None:
        writer.writerow([printable_time, curr_timestamp, rel_timestamp, cum_records, "%.2f" % total_rec_per_s, "%.2f" % total_thr_per_s, "%.2f" % total_avg_lat, "%.2f" % total_max_lat])



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
        control_clients(hosts=hosts, threads=threads, init=True)
        throttler = Throttler(hosts=hosts, threads=threads, dynamic_thr=args.dynamic, sleept=args.sleept, steps=args.steps, control_type=args.control_type)
        throttler.start()

    # launch all the clients
    outs = launch_clients(args)
    assert(len(hosts) == len(outs))

    out_writer = None
    if args.output_file is not None and len(args.output_file) > 0:
        f = open(args.output_file, "w+", encoding="UTF8")
        out_writer = csv.writer(f)

    while True:
        completed = 0

        total_rec_per_s = 0
        total_thr_per_s = 0
        rec_per_s = 0
        thr_per_s = 0
        total_avg_lat = 0
        total_max_lat = 0
        total_aggr_records = 0

        res_count = 0
        for host, outs_per_host in outs.items():
            for out in outs_per_host:
                try:
                    line = next(out)
                    if args.type == "producer":
                        rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records = parse_producer(line)
                    else:
                        rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records = parse_consumer(line)

                    res_count += 1
                    total_rec_per_s = rec_per_s + total_rec_per_s
                    total_thr_per_s = thr_per_s + total_thr_per_s
                    total_avg_lat = total_avg_lat + avg_lat * aggr_records
                    total_max_lat = max(total_max_lat, max_lat)
                    total_aggr_records = total_aggr_records + aggr_records
                    # print("{}:{} {}".format(host, res_count, line))
                    # print(rec_per_s, thr_per_s, thr_unit, avg_lat, max_lat, aggr_records, total_rec_per_s, total_thr_per_s, total_avg_lat / total_aggr_records, total_max_lat, total_aggr_records)
                except StopIteration:
                    completed += 1
                    if args.wait_for_all and completed >= total_clients:
                        print("All " + str(completed) + " clients completed")
                        break
                    elif not args.wait_for_all:
                        print("{} clients completed. Kill the {} other clients".format(completed, (total_clients - completed)))
                        kill_clients(hosts, name=args.type)
                        break
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

                print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat / total_aggr_records, total_max_lat, res_count, typ=args.type, writer=out_writer)
            continue


        if total_rec_per_s != 0:
            if args.type == "producer":
                cum_records += total_aggr_records
            else:
                cum_records = total_aggr_records

            print_res(cum_records, total_rec_per_s, total_thr_per_s, thr_unit, total_avg_lat / total_aggr_records, total_max_lat, res_count, typ=args.type, writer=out_writer)
        break

    if args.use_dynamic and args.type == "producer":
        throttler.stop()
        throttler.join()
