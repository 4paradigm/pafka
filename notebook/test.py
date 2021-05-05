from command import start_kafka, start_pafka, stop_kafka, stop_pafka, bench_write, bench_read, stop_service
import time

stop_service()

start_kafka()
bench_write()
bench_read()
stop_kafka()

start_pafka()
bench_write()
bench_read()
stop_pafka()
