"""Run isolated robustness and load scenarios against the live V1 API."""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "evaluator/reports"
PROFESSIONALS_FILE = ROOT / "simulator/data/professionals.json"
REQUESTS_FILE = ROOT / "simulator/data/requests.json"

SCENARIOS = [
    {
        "name": "nominal",
        "kind": "nominal",
        "requests": 20,
        "professionals": 20,
        "acceptRate": 0.98,
        "creationWorkers": 2,
        "dispatchWorkers": 2,
        "seed": 42,
    },
    {
        "name": "peak_night",
        "kind": "degraded",
        "requests": 40,
        "professionals": 20,
        "acceptRate": 0.65,
        "creationWorkers": 8,
        "dispatchWorkers": 6,
        "seed": 43,
    },
    {
        "name": "refusal_cascade",
        "kind": "degraded",
        "requests": 30,
        "professionals": 20,
        "acceptRate": 0.20,
        "creationWorkers": 6,
        "dispatchWorkers": 4,
        "seed": 44,
    },
    {
        "name": "load_20",
        "kind": "load",
        "requests": 20,
        "professionals": 20,
        "acceptRate": 0.98,
        "creationWorkers": 4,
        "dispatchWorkers": 4,
        "seed": 45,
    },
    {
        "name": "load_40",
        "kind": "load",
        "requests": 40,
        "professionals": 20,
        "acceptRate": 0.98,
        "creationWorkers": 8,
        "dispatchWorkers": 8,
        "seed": 46,
    },
    {
        "name": "load_80",
        "kind": "load",
        "requests": 80,
        "professionals": 20,
        "acceptRate": 0.98,
        "creationWorkers": 12,
        "dispatchWorkers": 12,
        "seed": 47,
    },
    {
        "name": "load_160",
        "kind": "load",
        "requests": 160,
        "professionals": 20,
        "acceptRate": 0.98,
        "creationWorkers": 20,
        "dispatchWorkers": 16,
        "seed": 48,
    },
]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run isolated V1 robustness scenarios.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--strategy", choices=["S1", "S2", "S3", "S4"], default="S3")
    parser.add_argument("--scenario", action="append", help="Run only the named scenario; may be repeated.")
    parser.add_argument("--timeout-seconds", type=float, default=60.0)
    parser.add_argument("--skip-reset", action="store_true")
    return parser.parse_args()


def load_json(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def run_command(command: list[str]) -> None:
    subprocess.run(
        command,
        cwd=ROOT,
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )


def wait_for_api(base_url: str) -> None:
    for _ in range(60):
        try:
            response = requests.get(f"{base_url}/actuator/health", timeout=3)
            if response.ok and response.json().get("status") == "UP":
                return
        except requests.RequestException:
            pass
        time.sleep(1)
    raise RuntimeError(f"API did not become healthy at {base_url}")


def reset_state() -> None:
    run_command(
        [
            "docker",
            "compose",
            "exec",
            "-T",
            "postgres",
            "psql",
            "-U",
            "pulsaride",
            "-d",
            "pulsaride",
            "-c",
            "TRUNCATE TABLE assignments, state_transitions, dispatch_requests, availability_slots, professionals "
            "RESTART IDENTITY CASCADE;",
        ]
    )
    run_command(["docker", "compose", "exec", "-T", "redis", "redis-cli", "FLUSHALL"])


def post(base_url: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    response = requests.post(f"{base_url}{path}", json=payload, timeout=15)
    response.raise_for_status()
    return response.json()


def get_metrics(base_url: str) -> dict[str, Any]:
    response = requests.get(f"{base_url}/metrics/summary", timeout=10)
    response.raise_for_status()
    return response.json()


def load_professionals(base_url: str, source: list[dict[str, Any]], count: int) -> None:
    if count > len(source):
        raise ValueError(f"Requested {count} professionals but the dataset only contains {len(source)}")
    for professional in source[:count]:
        post(
            base_url,
            "/professionals",
            {
                "id": professional["id"],
                "name": professional["name"],
                "specialtyTag": professional["specialty_tag"],
                "experienceYears": professional["experience_years"],
                "profileText": professional["profile_text"],
                "quotaMaxPerHour": professional["quota_max_per_hour"],
                "status": "AVAILABLE",
            },
        )


def request_payload(source: dict[str, Any], scenario: str, index: int) -> dict[str, Any]:
    return {
        "patientId": f"{scenario}_{index:04d}_{source['patient_id']}",
        "patientText": source["patient_text"],
        "specialtyHint": source["specialty_hint"],
        "urgencyScore": source["urgency_score"],
    }


def create_requests(
    base_url: str,
    source: list[dict[str, Any]],
    scenario: dict[str, Any],
) -> tuple[list[str], list[str], float]:
    started = time.perf_counter()
    created: list[str] = []
    errors: list[str] = []

    with ThreadPoolExecutor(max_workers=scenario["creationWorkers"]) as executor:
        futures = {
            executor.submit(
                post,
                base_url,
                "/requests",
                request_payload(source[index % len(source)], scenario["name"], index),
            ): index
            for index in range(scenario["requests"])
        }
        for future in as_completed(futures):
            try:
                created.append(future.result()["id"])
            except (requests.RequestException, KeyError) as error:
                errors.append(f"request {futures[future]}: {error}")

    return created, errors, time.perf_counter() - started


def terminal_count(metrics: dict[str, Any]) -> int:
    return int(metrics.get("closedRequests", 0)) + int(metrics.get("failedRequests", 0))


def dispatch_until_terminal(
    base_url: str,
    scenario: dict[str, Any],
    strategy: str,
    created_count: int,
    timeout_seconds: float,
) -> tuple[dict[str, int], dict[str, Any], float, bool]:
    counters = {
        "attempts": 0,
        "proposals": 0,
        "acceptedAndClosed": 0,
        "refusedAndRequeued": 0,
        "failedNoAvailability": 0,
        "contentionRetries": 0,
        "noPendingRetries": 0,
        "httpErrors": 0,
    }
    counter_lock = threading.Lock()
    stop = threading.Event()
    max_attempts = max(created_count * 12, 50)
    started = time.perf_counter()

    def increment(key: str) -> None:
        with counter_lock:
            counters[key] += 1

    def worker(worker_id: int) -> None:
        rng = random.Random(scenario["seed"] * 1000 + worker_id)
        while not stop.is_set():
            with counter_lock:
                if counters["attempts"] >= max_attempts:
                    return

            try:
                response = requests.post(
                    f"{base_url}/dispatch/next?strategy={strategy}",
                    timeout=15,
                )
                if response.status_code == 404:
                    increment("noPendingRetries")
                    time.sleep(0.01)
                    continue
                response.raise_for_status()
                increment("attempts")
                dispatched = response.json()
                status = dispatched.get("status")
                request_id = dispatched.get("id")

                if status == "FAILED":
                    increment("failedNoAvailability")
                    continue
                if status != "PROPOSED" or not dispatched.get("assignedProfessionalId"):
                    increment("contentionRetries")
                    time.sleep(0.005)
                    continue

                increment("proposals")
                if rng.random() <= scenario["acceptRate"]:
                    requests.post(
                        f"{base_url}/dispatch/{request_id}/accept",
                        timeout=15,
                    ).raise_for_status()
                    time.sleep(rng.uniform(0.005, 0.02))
                    requests.post(
                        f"{base_url}/dispatch/{request_id}/close",
                        timeout=15,
                    ).raise_for_status()
                    increment("acceptedAndClosed")
                else:
                    requests.post(
                        f"{base_url}/dispatch/{request_id}/refuse",
                        timeout=15,
                    ).raise_for_status()
                    increment("refusedAndRequeued")
            except (requests.RequestException, ValueError):
                increment("httpErrors")
                time.sleep(0.01)

    with ThreadPoolExecutor(max_workers=scenario["dispatchWorkers"]) as executor:
        futures = [executor.submit(worker, worker_id) for worker_id in range(scenario["dispatchWorkers"])]
        completed = False
        while time.perf_counter() - started < timeout_seconds:
            metrics = get_metrics(base_url)
            if terminal_count(metrics) >= created_count:
                completed = True
                break
            with counter_lock:
                if counters["attempts"] >= max_attempts:
                    break
            time.sleep(0.05)
        stop.set()
        for future in futures:
            future.result()

    duration = time.perf_counter() - started
    metrics = get_metrics(base_url)
    return counters, metrics, duration, completed


def target_checks(metrics: dict[str, Any], completed: bool, created: int) -> dict[str, bool]:
    def under(value: Any, limit: float) -> bool:
        return value is not None and float(value) < limit

    return {
        "allRequestsTerminal": completed and terminal_count(metrics) == created,
        "serviceRateAtLeast95Pct": float(metrics.get("serviceRatePct", 0)) >= 95.0,
        "p95TtfaUnder5s": under(metrics.get("p95TtfaMs"), 5_000.0),
        "p95TtrUnder30s": under(metrics.get("p95TtrMs"), 30_000.0),
        "giniUnder0_15": float(metrics.get("giniFairness", 1)) < 0.15,
        "p95DegradedReassignmentUnder10s": (
            metrics.get("p95DegradedReassignmentMs") is None
            or under(metrics.get("p95DegradedReassignmentMs"), 10_000.0)
        ),
    }


def run_scenario(
    base_url: str,
    scenario: dict[str, Any],
    professionals: list[dict[str, Any]],
    requests_data: list[dict[str, Any]],
    strategy: str,
    timeout_seconds: float,
    skip_reset: bool,
) -> dict[str, Any]:
    if not skip_reset:
        reset_state()
    load_professionals(base_url, professionals, scenario["professionals"])
    created_ids, creation_errors, creation_duration = create_requests(base_url, requests_data, scenario)
    counters, metrics, processing_duration, completed = dispatch_until_terminal(
        base_url,
        scenario,
        strategy,
        len(created_ids),
        timeout_seconds,
    )
    closed = int(metrics.get("closedRequests", 0))
    terminal = terminal_count(metrics)

    result = {
        **scenario,
        "strategy": strategy,
        "created": len(created_ids),
        "creationErrors": creation_errors,
        "creationDurationSeconds": round(creation_duration, 3),
        "creationThroughputRps": round(len(created_ids) / creation_duration, 2) if creation_duration else 0.0,
        "processingDurationSeconds": round(processing_duration, 3),
        "terminalThroughputRps": round(terminal / processing_duration, 2) if processing_duration else 0.0,
        "successfulThroughputRps": round(closed / processing_duration, 2) if processing_duration else 0.0,
        "completedWithinTimeout": completed,
        "dispatch": counters,
        "apiMetrics": metrics,
    }
    result["targets"] = target_checks(metrics, completed, len(created_ids))
    print(
        f"{scenario['name']:<20} created={len(created_ids):>3} terminal={terminal:>3} "
        f"service={metrics.get('serviceRatePct', 0):>6}% "
        f"throughput={result['successfulThroughputRps']:>6.2f}/s"
    )
    return result


def analyze_load(results: list[dict[str, Any]]) -> dict[str, Any]:
    load_results = sorted(
        (result for result in results if result["kind"] == "load"),
        key=lambda result: result["requests"],
    )
    sustainable = [
        result
        for result in load_results
        if result["targets"]["allRequestsTerminal"]
        and result["targets"]["serviceRateAtLeast95Pct"]
        and result["targets"]["p95TtfaUnder5s"]
        and result["targets"]["p95TtrUnder30s"]
    ]
    degraded = [result for result in load_results if result not in sustainable]
    max_sustainable_load = max(sustainable, key=lambda result: result["requests"], default=None)
    max_sustainable_throughput = max(
        sustainable,
        key=lambda result: result["successfulThroughputRps"],
        default=None,
    )
    max_observed = max(load_results, key=lambda result: result["successfulThroughputRps"], default=None)
    breakpoint = degraded[0] if degraded else None
    return {
        "maxSustainableRequests": max_sustainable_load["requests"] if max_sustainable_load else None,
        "maxSustainableThroughputRps": (
            max_sustainable_throughput["successfulThroughputRps"]
            if max_sustainable_throughput
            else None
        ),
        "maxObservedThroughputRps": max_observed["successfulThroughputRps"] if max_observed else None,
        "firstDegradedLoadRequests": breakpoint["requests"] if breakpoint else None,
        "firstDegradedLoadScenario": breakpoint["name"] if breakpoint else None,
    }


def main() -> None:
    args = parse_args()
    wait_for_api(args.base_url)
    professionals = load_json(PROFESSIONALS_FILE)
    requests_data = load_json(REQUESTS_FILE)
    selected = [
        scenario for scenario in SCENARIOS if not args.scenario or scenario["name"] in args.scenario
    ]
    if not selected:
        raise SystemExit(f"No matching scenarios. Available: {', '.join(s['name'] for s in SCENARIOS)}")

    REPORTS.mkdir(parents=True, exist_ok=True)
    results = [
        run_scenario(
            args.base_url,
            scenario,
            professionals,
            requests_data,
            args.strategy,
            args.timeout_seconds,
            args.skip_reset,
        )
        for scenario in selected
    ]
    payload = {
        "runAt": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url,
        "strategy": args.strategy,
        "isolatedScenarios": not args.skip_reset,
        "scenarios": results,
        "loadAnalysis": analyze_load(results),
    }
    output = REPORTS / "robustness_results.json"
    with output.open("w", encoding="utf-8") as file:
        json.dump(payload, file, indent=2, ensure_ascii=False)
    print(f"Results written to {output}")


if __name__ == "__main__":
    main()
