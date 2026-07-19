"""
Run a live API evaluation for the V1 priority dispatch lifecycle.

The script resets Postgres and Redis for each strategy, loads the same simulator
dataset, dispatches requests through /dispatch/next, simulates refusals/retries,
and regenerates the priority radar chart from the live metrics.
"""

from __future__ import annotations

import argparse
import json
import random
import subprocess
import time
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import requests


ROOT = Path(__file__).resolve().parents[1]
STRATEGIES = ["S1", "S2", "S3", "S4"]
LABELS = {
    "S1": "S1 First Available",
    "S2": "S2 Tag Exact",
    "S3": "S3 Score Composite",
    "S4": "S4 Lexical IA",
}
COLORS = {
    "S1": "#2563EB",
    "S2": "#059669",
    "S3": "#D97706",
    "S4": "#7C3AED",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Evaluate live priority dispatch strategies.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--request-count", type=int, default=20)
    parser.add_argument("--accept-rate", type=float, default=0.85)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--max-attempt-factor", type=int, default=3)
    parser.add_argument("--skip-reset", action="store_true")
    return parser.parse_args()


def load_json(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def run_command(command: list[str]) -> None:
    subprocess.run(command, cwd=ROOT, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


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
            "TRUNCATE TABLE assignments, state_transitions, dispatch_requests, professionals RESTART IDENTITY CASCADE;",
        ]
    )
    run_command(["docker", "compose", "exec", "-T", "redis", "redis-cli", "FLUSHALL"])


def post(base_url: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any] | None:
    response = requests.post(f"{base_url}{path}", json=payload, timeout=10)
    response.raise_for_status()
    return response.json() if response.content else None


def get(base_url: str, path: str) -> dict[str, Any]:
    response = requests.get(f"{base_url}{path}", timeout=10)
    response.raise_for_status()
    return response.json()


def load_batch(base_url: str, professionals: list[dict[str, Any]], requests_data: list[dict[str, Any]]) -> int:
    for professional in professionals:
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

    created = 0
    for request in requests_data:
        post(
            base_url,
            "/requests",
            {
                "patientId": request["patient_id"],
                "patientText": request["patient_text"],
                "specialtyHint": request["specialty_hint"],
                "urgencyScore": request["urgency_score"],
            },
        )
        created += 1
        time.sleep(0.03)
    return created


def terminal_count(metrics: dict[str, Any]) -> int:
    return metrics.get("closedRequests", 0) + metrics.get("failedRequests", 0)


def run_strategy(
    base_url: str,
    strategy: str,
    professionals: list[dict[str, Any]],
    requests_data: list[dict[str, Any]],
    accept_rate: float,
    seed: int,
    max_attempt_factor: int,
    skip_reset: bool,
) -> tuple[dict[str, Any], dict[str, Any]]:
    if not skip_reset:
        reset_state()
    random.seed(seed)
    created = load_batch(base_url, professionals, requests_data)
    details: dict[str, Any] = {
        "strategy": strategy,
        "created": created,
        "attempts": 0,
        "proposals": 0,
        "accepted": 0,
        "refused": 0,
        "failedDispatchCalls": 0,
        "noProfessional": 0,
        "events": [],
    }

    max_attempts = created * max_attempt_factor
    while details["attempts"] < max_attempts:
        metrics = get(base_url, "/metrics/summary")
        if terminal_count(metrics) >= created:
            break

        details["attempts"] += 1
        response = requests.post(f"{base_url}/dispatch/next?strategy={strategy}", timeout=10)
        if response.status_code != 200:
            details["failedDispatchCalls"] += 1
            details["events"].append({"attempt": details["attempts"], "statusCode": response.status_code})
            time.sleep(0.04)
            continue

        data = response.json()
        request_id = data["id"]
        professional_id = data.get("assignedProfessionalId")
        status = data.get("status")
        if status == "FAILED" or professional_id is None:
            details["noProfessional"] += 1
            details["events"].append(
                {
                    "attempt": details["attempts"],
                    "requestId": request_id,
                    "status": status,
                    "professionalId": professional_id,
                }
            )
            time.sleep(0.04)
            continue

        details["proposals"] += 1
        time.sleep(0.04)
        if random.random() < accept_rate:
            requests.post(f"{base_url}/dispatch/{request_id}/accept", timeout=10).raise_for_status()
            time.sleep(random.uniform(0.04, 0.12))
            requests.post(f"{base_url}/dispatch/{request_id}/close", timeout=10).raise_for_status()
            details["accepted"] += 1
            outcome = "ACCEPTED_CLOSED"
        else:
            requests.post(f"{base_url}/dispatch/{request_id}/refuse", timeout=10).raise_for_status()
            details["refused"] += 1
            outcome = "REFUSED_REQUEUED"

        details["events"].append(
            {
                "attempt": details["attempts"],
                "requestId": request_id,
                "urgencyScore": data.get("urgencyScore"),
                "specialtyHint": data.get("specialtyHint"),
                "professionalId": professional_id,
                "outcome": outcome,
            }
        )

    metrics = get(base_url, "/metrics/summary")
    metrics["strategy"] = strategy
    return metrics, details


def inverse_score(values: dict[str, float], key: str) -> float:
    positive = [value for value in values.values() if value > 0]
    return min(positive) / values[key] if positive and values[key] > 0 else 0


def lower_is_better_minmax(values: dict[str, float], key: str) -> float:
    low = min(values.values())
    high = max(values.values())
    if high == low:
        return 1
    return 1 - ((values[key] - low) / (high - low))


def generate_radar(metrics_by_strategy: dict[str, dict[str, Any]], scenario: dict[str, Any]) -> None:
    service = {strategy: metrics_by_strategy[strategy].get("serviceRatePct") or 0 for strategy in STRATEGIES}
    ttfa = {strategy: metrics_by_strategy[strategy].get("avgTtfaMs") or 0 for strategy in STRATEGIES}
    ttr = {strategy: metrics_by_strategy[strategy].get("avgTtrMs") or 0 for strategy in STRATEGIES}
    gini = {strategy: metrics_by_strategy[strategy].get("giniFairness") or 0 for strategy in STRATEGIES}

    scores = {
        strategy: [
            service[strategy] / 100,
            inverse_score(ttfa, strategy),
            inverse_score(ttr, strategy),
            lower_is_better_minmax(gini, strategy),
        ]
        for strategy in STRATEGIES
    }

    categories = ["Service\nRate", "TTFA\nSpeed", "TTR\nSpeed", "Load\nFairness"]
    angles = np.linspace(0, 2 * np.pi, len(categories), endpoint=False).tolist()
    angles += angles[:1]

    fig, ax = plt.subplots(figsize=(9, 8), subplot_kw={"polar": True})
    for strategy in STRATEGIES:
        values = scores[strategy] + scores[strategy][:1]
        ax.plot(angles, values, "o-", linewidth=2.2, color=COLORS[strategy], label=LABELS[strategy])
        ax.fill(angles, values, alpha=0.10, color=COLORS[strategy])

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels(categories, fontsize=12)
    ax.set_ylim(0, 1.05)
    ax.set_yticks([0.25, 0.5, 0.75, 1.0])
    ax.set_yticklabels(["0.25", "0.50", "0.75", "1.00"], fontsize=9)
    ax.set_title("Pulsaride V1 - Priority Lifecycle Radar", fontsize=15, fontweight="bold", pad=24)
    ax.legend(loc="upper right", bbox_to_anchor=(1.34, 1.10), fontsize=10)
    ax.grid(True, alpha=0.35)

    note = (
        f"Live API run: {scenario['requests']} requests, refusal retry enabled, "
        f"seed {scenario['seed']}. Higher is better."
    )
    fig.text(0.5, 0.035, note, ha="center", fontsize=9, color="#374151")
    fig.tight_layout(rect=[0, 0.06, 1, 1])

    output_paths = [
        ROOT / "evaluator/reports/radar_chart_priority.png",
        ROOT / "docs/evaluation/radar_chart_priority.png",
        ROOT / "evaluator/reports/radar_chart.png",
        ROOT / "docs/evaluation/radar_chart.png",
    ]
    for output_path in output_paths:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(output_path, dpi=170, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def generate_comparison_charts(metrics_by_strategy: dict[str, dict[str, Any]], scenario: dict[str, Any]) -> None:
    strategy_labels = ["S1\nFirst\nAvailable", "S2\nTag\nExact", "S3\nScore\nComposite", "S4\nLexical\nIA"]
    colors = [COLORS[strategy] for strategy in STRATEGIES]
    service_rates = [metrics_by_strategy[strategy].get("serviceRatePct") or 0 for strategy in STRATEGIES]
    ttfa_ms = [metrics_by_strategy[strategy].get("avgTtfaMs") or 0 for strategy in STRATEGIES]
    ttr_ms = [metrics_by_strategy[strategy].get("avgTtrMs") or 0 for strategy in STRATEGIES]
    gini = [metrics_by_strategy[strategy].get("giniFairness") or 0 for strategy in STRATEGIES]

    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle(
        "Pulsaride V1 - Comparaison live des stratégies de dispatch\n"
        "Priority lifecycle: refusals return to queue",
        fontsize=16,
        fontweight="bold",
        y=1.02,
    )

    def annotate_bars(ax: plt.Axes, bars: Any, suffix: str = "", decimals: int = 0) -> None:
        for bar in bars:
            value = bar.get_height()
            if decimals:
                label = f"{value:.{decimals}f}{suffix}"
            else:
                label = f"{value:,.0f}{suffix}"
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                value + max(value * 0.02, 0.04),
                label,
                ha="center",
                va="bottom",
                fontweight="bold",
                fontsize=10,
            )

    ax1 = axes[0, 0]
    bars = ax1.bar(strategy_labels, service_rates, color=colors, edgecolor="white", linewidth=1.5)
    ax1.axhline(y=95, color="red", linestyle="--", linewidth=1.5, label="Cible 95%")
    ax1.set_title("Service Rate (%)", fontweight="bold", fontsize=13)
    ax1.set_ylabel("Pourcentage (%)")
    ax1.set_ylim(0, 115)
    ax1.legend(fontsize=10, loc="lower right")
    annotate_bars(ax1, bars, "%", 1)
    ax1.grid(axis="y", alpha=0.3)

    ax2 = axes[0, 1]
    bars = ax2.bar(strategy_labels, ttfa_ms, color=colors, edgecolor="white", linewidth=1.5)
    ax2.axhline(y=5000, color="red", linestyle="--", linewidth=1.5, label="Cible 5000ms")
    ax2.set_title("TTFA - Time To First Assignment (ms)", fontweight="bold", fontsize=13)
    ax2.set_ylabel("Millisecondes (ms)")
    ax2.set_ylim(0, max(5500, max(ttfa_ms) * 1.18))
    ax2.legend(fontsize=10)
    annotate_bars(ax2, bars, "ms")
    ax2.grid(axis="y", alpha=0.3)

    ax3 = axes[1, 0]
    bars = ax3.bar(strategy_labels, ttr_ms, color=colors, edgecolor="white", linewidth=1.5)
    ax3.axhline(y=30000, color="red", linestyle="--", linewidth=1.5, label="Cible 30000ms")
    ax3.set_title("TTR - Time To Resolution (ms)", fontweight="bold", fontsize=13)
    ax3.set_ylabel("Millisecondes (ms)")
    ax3.set_ylim(0, 32000)
    ax3.legend(fontsize=10)
    annotate_bars(ax3, bars, "ms")
    ax3.grid(axis="y", alpha=0.3)

    ax4 = axes[1, 1]
    bars = ax4.bar(strategy_labels, gini, color=colors, edgecolor="white", linewidth=1.5)
    ax4.axhline(y=0.15, color="red", linestyle="--", linewidth=1.5, label="Cible < 0.15")
    ax4.set_title("Gini Fairness (équité de charge)", fontweight="bold", fontsize=13)
    ax4.set_ylabel("Coefficient de Gini")
    ax4.set_ylim(0, max(0.5, max(gini) * 1.18))
    ax4.legend(fontsize=10)
    annotate_bars(ax4, bars, decimals=2)
    ax4.grid(axis="y", alpha=0.3)

    note = (
        f"Live API run: {scenario['requests']} requests, refusal retry enabled, "
        f"accept rate {scenario['acceptRate']}, seed {scenario['seed']}."
    )
    fig.text(0.5, 0.02, note, ha="center", fontsize=9, color="#374151")
    fig.tight_layout(rect=[0, 0.04, 1, 1])

    for output_path in [
        ROOT / "evaluator/reports/comparison_charts.png",
        ROOT / "docs/evaluation/comparison_charts.png",
    ]:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        fig.savefig(output_path, dpi=150, bbox_inches="tight", facecolor="white")
    plt.close(fig)


def write_results(
    metrics_by_strategy: dict[str, dict[str, Any]],
    details_by_strategy: dict[str, dict[str, Any]],
    scenario: dict[str, Any],
) -> None:
    comparison = {
        "generatedFrom": "live Spring Boot API /dispatch/next priority lifecycle",
        "scenario": scenario,
        "strategies": metrics_by_strategy,
    }
    for output_path in [
        ROOT / "evaluator/reports/comparison_data_priority.json",
        ROOT / "docs/evaluation/comparison_data_priority.json",
    ]:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(comparison, indent=2, ensure_ascii=False), encoding="utf-8")

    for output_path in [
        ROOT / "evaluator/reports/priority_run_details.json",
        ROOT / "docs/evaluation/priority_run_details.json",
    ]:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(json.dumps(details_by_strategy, indent=2, ensure_ascii=False), encoding="utf-8")

    for strategy, metrics in metrics_by_strategy.items():
        output_path = ROOT / f"simulator/data/api_metrics_{strategy}.json"
        output_path.write_text(json.dumps(metrics, indent=2, ensure_ascii=False), encoding="utf-8")


def main() -> None:
    args = parse_args()
    wait_for_api(args.base_url)
    professionals = load_json(ROOT / "simulator/data/professionals.json")
    requests_data = load_json(ROOT / "simulator/data/requests.json")[: args.request_count]
    scenario = {
        "requests": len(requests_data),
        "professionals": len(professionals),
        "acceptRate": args.accept_rate,
        "seed": args.seed,
        "maxAttemptsPerStrategy": len(requests_data) * args.max_attempt_factor,
        "refusalsReturnToQueue": True,
    }

    metrics_by_strategy: dict[str, dict[str, Any]] = {}
    details_by_strategy: dict[str, dict[str, Any]] = {}
    for strategy in STRATEGIES:
        print(f"Running {strategy}...")
        metrics, details = run_strategy(
            args.base_url,
            strategy,
            professionals,
            requests_data,
            args.accept_rate,
            args.seed,
            args.max_attempt_factor,
            args.skip_reset,
        )
        metrics_by_strategy[strategy] = metrics
        details_by_strategy[strategy] = details

    write_results(metrics_by_strategy, details_by_strategy, scenario)
    generate_comparison_charts(metrics_by_strategy, scenario)
    generate_radar(metrics_by_strategy, scenario)
    print(json.dumps(metrics_by_strategy, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
