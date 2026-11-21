#!/usr/bin/env python3
"""
Processor scalability benchmark. For each configuration the processor tier is limited to a number of CPU cores
(instances x cores each, applied with `docker update --cpus`), then:

  * capacity (drain): processors are stopped, a backlog of DRAIN_EVENTS is produced, processors are started and the
    rate at which the backlog comes out of the pipeline is measured (first to last alert update). This measures what
    the processors can do, not how fast the load generator can send. Run twice: "spread" (every event's alert key
    differs, load spreads over all partitions) and "all" (the original mix, where one third of events belong to a
    single settlement alert and therefore one partition / one thread).
  * latency: a fixed BELOW_RATE events/s run with processors running, for ingest -> alert latency when not saturated.

Every run is cross-checked: the benchmark's own tally and, independently, the sum of alert counters in Postgres
(written by the API's projector) must both equal the number of events sent.

Usage (stack must be up):  python3 scripts/bench_scaling.py [--quick]
Only the processor tier is limited; brokers, Postgres, API, dispatcher and the load generator share the rest of the
machine, so this shows how the processing tier scales on one host, not a multi-machine deployment.
"""
import json, os, re, subprocess, sys, threading, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
QUICK = "--quick" in sys.argv
# (instances, cpus per instance (0 = no limit), stream threads per instance)
CONFIGS = [(2, 0, 3), (1, 1, 2), (2, 1, 2), (3, 1, 2), (4, 1, 2), (6, 1, 2)]
if "--configs" in sys.argv:
    CONFIGS = [tuple(float(x) if i == 1 else int(x) for i, x in enumerate(c.split("x"))) for c in sys.argv[sys.argv.index("--configs") + 1].split(",")]
# --cpuset: give each processor instance dedicated cores (no CFS quota, so no throttling stalls) and pin every other
# container to the remaining OTHER_CORES, identical for all configurations. Without it, --cpus quotas are used.
CPUSET = "--cpuset" in sys.argv
OTHER_CORES = "6-10"
# --broker-cores 1,2,4: single-broker stack (docker-compose.single-broker.yml); the broker gets dedicated cores 0..N-1,
# while the processor tier (2 instances x 3 threads) and everything else stay fixed on OTHER_CORES.
BROKER_CORES = [int(x) for x in sys.argv[sys.argv.index("--broker-cores") + 1].split(",")] if "--broker-cores" in sys.argv else None
if BROKER_CORES:
    os.environ["COMPOSE_FILE"] = "docker-compose.single-broker.yml"
# --burst: measure capacity with the processors left running and stable: the backlog is produced as one burst much
# faster than it can be processed, and the output rate is timed. Avoids timing the start-up and rebalances that a
# stop / produce / start drain includes (which penalises configurations with more instances).
BURST = "--burst" in sys.argv
DRAIN_EVENTS = int(os.environ.get("CAPACITY_EVENTS", 100_000 if QUICK else 300_000))
BELOW_EVENTS, BELOW_RATE = (20_000, 1_000) if QUICK else (45_000, 1_500)


def sh(cmd, check=True, env=None, timeout=900):
    r = subprocess.run(cmd, shell=True, cwd=ROOT, capture_output=True, text=True, env={**os.environ, **(env or {})}, timeout=timeout)
    if check and r.returncode != 0:
        raise RuntimeError(f"{cmd}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def processors():
    return [c for c in sh("docker compose ps -a -q processor").split() if c]


def group_state(expected_members):
    out = sh("docker compose exec -T kafka1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 "
             "--describe --group alerting-processor --state", check=False)
    m = re.search(r"(\w+)\s+(\d+)\s*$", out.strip())
    if not m or m.group(1) != "Stable" or int(m.group(2)) != expected_members:
        return False, (out.strip().splitlines() or ["no group"])[-1]
    out = sh("docker compose exec -T kafka1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:9092 "
             "--describe --group alerting-processor", check=False)
    lag = sum(int(c[5]) for c in (l.split() for l in out.splitlines()) if len(c) > 5 and c[0] == "alerting-processor" and c[5].isdigit())
    return lag == 0, f"lag {lag}"


def await_ready(members):
    deadline, why = time.time() + 300, ""
    while time.time() < deadline:
        ok, why = group_state(members)
        if ok:
            time.sleep(5)
            if group_state(members)[0]:
                return
        time.sleep(3)
    raise RuntimeError(f"processor group not stable with zero lag: {why}")


def configure(instances, cpus, threads):
    label = f"{instances} instance(s) x {'unlimited' if cpus == 0 else f'{cpus:g} core(s)'}, {threads} stream threads each"
    if os.environ.get("COMMIT_INTERVAL_MS", "100") != "100":
        label += f", commit {os.environ['COMMIT_INTERVAL_MS']} ms"
    if os.environ.get("PROCESSING_GUARANTEE", "exactly_once_v2") != "exactly_once_v2":
        label += f", {os.environ['PROCESSING_GUARANTEE']}"
    print(f"\n=== {label} ===", flush=True)
    sh(f"docker compose up -d --no-deps --scale processor={instances} processor", env={"STREAM_THREADS": str(threads)})
    ids = processors()
    assert len(ids) == instances, ids
    if CPUSET:
        k = int(cpus)
        assert instances * k <= 6, "cpuset mode has cores 0-5 for processors"
        for i, c in enumerate(ids):
            sh(f"docker update --cpus 0 --cpuset-cpus {i * k}-{i * k + k - 1} {c}")
        label = label.replace("core(s)", "dedicated core(s)")
    else:
        for c in ids:
            sh(f"docker update --cpus {cpus} {c}")
    await_ready(instances * threads)
    return label


def pin_others(cores):
    for svc in ("kafka1", "kafka2", "kafka3", "postgres", "api", "dispatcher", "mailpit", "ui"):
        for c in sh(f"docker compose ps -q {svc}").split():
            sh(f"docker update --cpuset-cpus {cores} {c}", check=False)


def sample_cpu(stop, samples):
    while not stop.is_set():
        out = sh("docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}'", check=False)
        snap = {}
        for line in out.splitlines():
            name, pct = line.rsplit(" ", 1)
            role = "processor" if "processor" in name else "kafka" if "kafka" in name else "bench" if "bench" in name else "other"
            snap[role] = snap.get(role, 0.0) + float(pct.rstrip("%") or 0)
        samples.append(snap)


def open_counts():
    out = sh("docker compose exec -T postgres psql -U alerting -d alerting -tAc \"SELECT alert_id, count FROM alerts WHERE status <> 'RESOLVED'\"")
    return {a: int(c) for a, c in (l.split("|") for l in out.split() if "|" in l)}


def delivery_latency(run_id):
    """Ingest -> delivered (email to the mock SMTP server, SMS/phone to the mock gateway), as stored by the dispatcher
    for every delivery of this run's alerts. This is the end-to-end figure the brief asks for."""
    start_ms = int(run_id[1:], 36)
    q = ("SELECT count(*), coalesce(percentile_disc(0.5) WITHIN GROUP (ORDER BY d.latency_ms), -1), "
         "coalesce(percentile_disc(0.99) WITHIN GROUP (ORDER BY d.latency_ms), -1), coalesce(max(d.latency_ms), -1) "
         "FROM deliveries d JOIN alerts a ON a.alert_id = d.alert_id "
         f"WHERE a.last_event::text LIKE '%{run_id}%' AND d.status = 'SENT' AND d.created_at >= {start_ms}")
    for _ in range(20):
        n, p50, p99, mx = (int(x) for x in sh(f"docker compose exec -T postgres psql -U alerting -d alerting -tAc \"{q}\"").strip().split("|"))
        if n > 0:
            time.sleep(2)   # let the last deliveries land, then read once more
            n, p50, p99, mx = (int(x) for x in sh(f"docker compose exec -T postgres psql -U alerting -d alerting -tAc \"{q}\"").strip().split("|"))
            return {"deliveries": n, "p50": p50, "p99": p99, "max": mx}
        time.sleep(1)
    return {"deliveries": 0}


def postgres_check(run_id, sent, before):
    """Independent tally from the API projector's copy in Postgres (minus what already-open alerts had counted)."""
    last, stable, total, groups = None, 0, 0, 0
    for _ in range(90):
        out = sh("docker compose exec -T postgres psql -U alerting -d alerting -tAc "
                 f"\"SELECT alert_id, count FROM alerts WHERE last_event::text LIKE '%{run_id}%'\"")
        rows = [l.split("|") for l in out.split() if "|" in l]
        total, groups = sum(int(c) - before.get(a, 0) for a, c in rows), len(rows)
        if total == sent:
            return {"sumOfAlertCounts": total, "alerts": groups, "matchesEventsSent": True}
        stable = stable + 1 if (total, groups) == last else 0
        last = (total, groups)
        if stable >= 8:
            break
        time.sleep(1)
    return {"sumOfAlertCounts": total, "alerts": groups, "matchesEventsSent": False}


def run_bench(env, drain=False, instances=0, threads=0):
    before = open_counts()
    stop, samples = threading.Event(), []
    cmd = "docker compose --profile bench run --rm bench"
    full_env = {**os.environ, "BENCH_TIMEOUT_S": "900", "BENCH_DRAIN": "true" if drain else "false", **env}
    stop_first = drain and not BURST
    if stop_first:
        sh("docker compose stop processor")
    p = subprocess.Popen(cmd, shell=True, cwd=ROOT, env=full_env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    out, t = [], None
    for line in p.stdout:
        out.append(line)
        if stop_first and line.startswith("PRODUCED") and t is None:
            sh("docker compose start processor")
        if (line.startswith("PRODUCED") or line.startswith("Benchmark ")) and t is None and (stop_first == line.startswith("PRODUCED")):
            t = threading.Thread(target=sample_cpu, args=(stop, samples), daemon=True)
            t.start()
    p.wait()
    stop.set()
    if t:
        t.join()
    text = "".join(out)
    m = re.search(r"Written to /results/(\S+\.json)", text)
    if not m:
        raise RuntimeError("benchmark failed:\n" + text[-3000:])
    res = json.load(open(os.path.join(ROOT, "bench", "results", m.group(1))))
    busy = [s for s in samples if s.get("processor", 0) > 5] or samples
    res["cpuPercentAvgWhileBusy"] = {k: round(sum(s.get(k, 0) for s in busy) / max(1, len(busy))) for k in ("processor", "kafka", "bench", "other")}
    res["postgresCheck"] = postgres_check(res["runId"], res["eventsSent"], before)
    res["ingestToDeliveryMs"] = delivery_latency(res["runId"])
    if drain:
        await_ready(instances * threads)
    if drain and BURST and res.get("produceRatePerSec", -1) < 2 * res.get("drainRatePerSec", 0):
        res["warning"] = "load generator produced less than 2x faster than processing; capacity may be understated"
        print("  WARNING: " + res["warning"], flush=True)
    return res


def broker_sweep():
    host_cpus = int(sh("docker info --format '{{.NCPU}}'").strip())
    print("Starting the single-broker stack", flush=True)
    sh("docker compose up -d", env={"STREAM_THREADS": "3"}, timeout=600)
    configure(2, 0, 3)
    results = []
    try:
        for c in processors():
            sh(f"docker update --cpuset-cpus {OTHER_CORES} {c}")
        for svc in ("postgres", "api", "dispatcher", "mailpit"):
            for c in sh(f"docker compose ps -q {svc}").split():
                sh(f"docker update --cpuset-cpus {OTHER_CORES} {c}")
        broker = sh("docker compose ps -q kafka1").strip()
        for n in BROKER_CORES:
            assert n <= 6
            label = f"1 broker on {n} dedicated core(s)"
            print(f"\n=== {label} ===", flush=True)
            sh(f"docker update --cpus 0 --cpuset-cpus 0-{n - 1} {broker}")
            await_ready(6)
            spread = run_bench({"BENCH_EVENTS": str(DRAIN_EVENTS), "BENCH_MIX": "spread"}, True, 2, 3)
            print(f"  capacity, spread keys : {spread['drainRatePerSec']:>7,}/s over {spread['drainSec']}s, lost {spread['lost']}, "
                  f"postgres ok {spread['postgresCheck']['matchesEventsSent']}, cpu% {spread['cpuPercentAvgWhileBusy']}, produced at {spread.get('produceRatePerSec', -1):,}/s", flush=True)
            hot = run_bench({"BENCH_EVENTS": str(DRAIN_EVENTS), "BENCH_MIX": "all"}, True, 2, 3)
            print(f"  capacity, original mix: {hot['drainRatePerSec']:>7,}/s over {hot['drainSec']}s, lost {hot['lost']}, "
                  f"postgres ok {hot['postgresCheck']['matchesEventsSent']}, cpu% {hot['cpuPercentAvgWhileBusy']}", flush=True)
            low = run_bench({"BENCH_EVENTS": str(BELOW_EVENTS), "BENCH_RATE": str(BELOW_RATE), "BENCH_MIX": "spread"})
            print(f"  latency at {BELOW_RATE:,}/s: p50 {low['ingestToAlertMs']['p50']} / p95 {low['ingestToAlertMs']['p95']} / "
                  f"p99 {low['ingestToAlertMs']['p99']} ms, delivery {low['ingestToDeliveryMs']}, lost {low['lost']}, postgres ok {low['postgresCheck']['matchesEventsSent']}", flush=True)
            results.append({"config": label, "brokerCores": n, "capacitySpread": spread, "capacityOriginalMix": hot, "latency": low})
    finally:
        print("\nStopping the single-broker stack (main stack untouched; start it again with: docker compose start)", flush=True)
        sh("docker compose stop", check=False, timeout=600)

    base = results[0]["capacitySpread"]["drainRatePerSec"] if results else 1
    print(f"\nDocker host: {host_cpus} CPUs. One broker (replication factor 1) on dedicated cores; processors 2 x 3 threads and all "
          f"other services on cores {OTHER_CORES}. Capacity = {'burst into running processors' if BURST else 'drain after start'}, {DRAIN_EVENTS:,} events.")
    print(f"{'broker cores':>12} {'spread/s':>9} {'speed-up':>8} {'orig mix/s':>10} {'kafka CPU%':>10} {'proc CPU%':>9} {'p50':>5} {'p99':>5} {'lost':>4} {'pg':>5}")
    for r in results:
        sp, h, l = r["capacitySpread"], r["capacityOriginalMix"], r["latency"]
        print(f"{r['brokerCores']:>12} {sp['drainRatePerSec']:>9,} {sp['drainRatePerSec'] / base:>7.2f}x {h['drainRatePerSec']:>10,} "
              f"{sp['cpuPercentAvgWhileBusy']['kafka']:>10} {sp['cpuPercentAvgWhileBusy']['processor']:>9} {l['ingestToAlertMs']['p50']:>5} "
              f"{l['ingestToAlertMs']['p99']:>5} {sp['lost'] + h['lost'] + l['lost']:>4} "
              f"{str(all(x['postgresCheck']['matchesEventsSent'] for x in (sp, h, l))):>5}")
    out = os.path.join(ROOT, "bench", "results", f"scaling-broker-{time.strftime('%Y%m%d-%H%M%S')}.json")
    json.dump({"hostCpus": host_cpus, "setup": "single broker, RF 1, dedicated cores 0..N-1; processors 2x3 threads + other services on " + OTHER_CORES,
               "drainEvents": DRAIN_EVENTS, "latencyRun": {"events": BELOW_EVENTS, "rate": BELOW_RATE}, "runs": results}, open(out, "w"), indent=2)
    print("Written to", out)


def main():
    if BROKER_CORES:
        return broker_sweep()
    host_cpus = int(sh("docker info --format '{{.NCPU}}'").strip())
    results = []
    if CPUSET:
        pin_others(OTHER_CORES)
    try:
        for inst, cpus, threads in CONFIGS:
            label = configure(inst, cpus, threads)
            spread = run_bench({"BENCH_EVENTS": str(DRAIN_EVENTS), "BENCH_MIX": "spread"}, True, inst, threads)
            print(f"  capacity, spread keys : {spread['drainRatePerSec']:>7,}/s over {spread['drainSec']}s, lost {spread['lost']}, "
                  f"postgres ok {spread['postgresCheck']['matchesEventsSent']}, cpu% {spread['cpuPercentAvgWhileBusy']}, produced at {spread.get('produceRatePerSec', -1):,}/s", flush=True)
            hot = run_bench({"BENCH_EVENTS": str(DRAIN_EVENTS), "BENCH_MIX": "all"}, True, inst, threads)
            print(f"  capacity, original mix: {hot['drainRatePerSec']:>7,}/s over {hot['drainSec']}s, lost {hot['lost']}, "
                  f"postgres ok {hot['postgresCheck']['matchesEventsSent']}, cpu% {hot['cpuPercentAvgWhileBusy']}", flush=True)
            low = run_bench({"BENCH_EVENTS": str(BELOW_EVENTS), "BENCH_RATE": str(BELOW_RATE), "BENCH_MIX": "spread"})
            print(f"  latency at {BELOW_RATE:,}/s: p50 {low['ingestToAlertMs']['p50']} / p95 {low['ingestToAlertMs']['p95']} / "
                  f"p99 {low['ingestToAlertMs']['p99']} ms, delivery {low['ingestToDeliveryMs']}, lost {low['lost']}, postgres ok {low['postgresCheck']['matchesEventsSent']}", flush=True)
            results.append({"config": label, "instances": inst, "cpusPerInstance": cpus, "processorCores": inst * cpus if cpus else None,
                            "streamThreadsPerInstance": threads, "capacitySpread": spread, "capacityOriginalMix": hot, "latency": low})
    finally:
        print("\nRestoring the default deployment (2 instances, 3 threads, no CPU limit)", flush=True)
        sh("docker compose start processor", check=False)
        sh("docker compose up -d --no-deps --scale processor=2 processor", env={"STREAM_THREADS": "3"})
        all_cores = f"0-{host_cpus - 1}"
        for c in processors():
            sh(f"docker update --cpus 0 --cpuset-cpus {all_cores} {c}", check=False)
        if CPUSET:
            pin_others(all_cores)

    print(f"\nDocker host: {host_cpus} CPUs. Capacity = {'burst into running processors' if BURST else 'drain after start'}, {DRAIN_EVENTS:,} events. Latency at {BELOW_RATE:,} events/s.")
    print(f"{'processor tier':<70} {'spread/s':>9} {'per core':>8} {'orig mix/s':>10} {'proc CPU%':>9} {'kafka CPU%':>10} {'p50':>5} {'p99':>5} {'lost':>4} {'pg':>5}")
    for r in results:
        s, h, l = r["capacitySpread"], r["capacityOriginalMix"], r["latency"]
        per_core = f"{s['drainRatePerSec'] / r['processorCores']:,.0f}" if r["processorCores"] else "-"
        print(f"{r['config']:<70} {s['drainRatePerSec']:>9,} {per_core:>8} {h['drainRatePerSec']:>10,} {s['cpuPercentAvgWhileBusy']['processor']:>9} "
              f"{s['cpuPercentAvgWhileBusy']['kafka']:>10} {l['ingestToAlertMs']['p50']:>5} {l['ingestToAlertMs']['p99']:>5} "
              f"{s['lost'] + h['lost'] + l['lost']:>4} {str(all(x['postgresCheck']['matchesEventsSent'] for x in (s, h, l))):>5}")
    out = os.path.join(ROOT, "bench", "results", f"scaling-{'burst-' if BURST else ''}{'cpuset-' if CPUSET else ''}{time.strftime('%Y%m%d-%H%M%S')}.json")
    json.dump({"hostCpus": host_cpus, "capacityMethod": "burst into running processors" if BURST else "stop / produce backlog / start", "coreLimit": "cpuset (dedicated cores; other containers on " + OTHER_CORES + ")" if CPUSET else "CFS quota (docker --cpus)", "drainEvents": DRAIN_EVENTS, "latencyRun": {"events": BELOW_EVENTS, "rate": BELOW_RATE}, "runs": results},
              open(out, "w"), indent=2)
    print("Written to", out)


if __name__ == "__main__":
    main()
