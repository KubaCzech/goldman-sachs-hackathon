#!/usr/bin/env bash
# Reference point for the benchmark: what Kafka alone does on this machine with the same durability settings
# (3 brokers, replication factor 3, min ISR 2, acks=all, idempotent, lz4, 6 partitions) and the same event payloads.
# Our pipeline writes each event four times (ingest, match, state changelog, alert) under exactly-once, so its
# events/s are compared against this raw produce rate.
#
# Usage (main stack up):  scripts/kafka_baseline.sh [records]
set -euo pipefail
cd "$(dirname "$0")/.."
N=${1:-2000000}
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' "$(docker compose ps -q kafka1)")
TOOLS="docker run --rm -i ${BASELINE_TOOL_CPUS:+--cpuset-cpus $BASELINE_TOOL_CPUS} --network $NET -v $PWD/scripts:/scripts:ro apache/kafka:3.9.0"
# Single-broker stack (COMPOSE_FILE=docker-compose.single-broker.yml): one broker, replication factor 1.
if [ -n "$(docker compose ps -q kafka2 2>/dev/null)" ]; then BOOT=kafka1:9092,kafka2:9092,kafka3:9092; RF=3; ISR=2; else BOOT=kafka1:9092; RF=1; ISR=1; fi

# Payload file: the three sample event shapes, one per line (the perf tool picks lines at random).
cat > scripts/.baseline-payloads.txt <<'EOF'
{"type":"WORKFLOW_FAILED","workflowId":"WF-20261023-00042","workflowName":"order-settlement","step":"confirm-counterparty","severity":"ERROR","environment":"prod","timestamp":"2026-10-23T09:14:02.331Z","error":{"code":"TIMEOUT","message":"Counterparty service did not respond in 5000 ms"}}
{"alertname":"HighErrorRate","labels":{"service":"payments-api","region":"eu-west","severity":"critical"},"annotations":{"summary":"Error rate 12% over the last 5 minutes"},"startsAt":"2026-10-23T09:13:00Z","status":"firing"}
{"meta":{"schema":"trade.rejected.v2","producer":"trade-gateway","ts":1792833245000},"payload":{"tradeId":"T-99812","desk":"rates","notional":25000000,"currency":"EUR","reason":"LIMIT_BREACH"}}
EOF

docker compose exec -T kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic bench.raw >/dev/null 2>&1 || true
sleep 2
docker compose exec -T kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --topic bench.raw \
  --partitions 6 --replication-factor $RF --config min.insync.replicas=$ISR >/dev/null

echo "== Raw produce: $N events, acks=all, RF $RF, idempotent, lz4 =="
$TOOLS /opt/kafka/bin/kafka-producer-perf-test.sh --topic bench.raw --num-records "$N" --throughput -1 \
  --payload-file /scripts/.baseline-payloads.txt \
  --producer-props bootstrap.servers=$BOOT acks=all enable.idempotence=true linger.ms=5 compression.type=lz4 2>/dev/null | tail -1

echo "== Raw consume: $N events, read_committed =="
$TOOLS /opt/kafka/bin/kafka-consumer-perf-test.sh --bootstrap-server $BOOT --topic bench.raw --messages "$N" \
  --timeout 60000 --consumer.config /dev/stdin <<< "isolation.level=read_committed" 2>/dev/null | tail -2

docker compose exec -T kafka1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic bench.raw >/dev/null
rm -f scripts/.baseline-payloads.txt
