"""Generate robustness charts and a text report from the latest live run."""

from __future__ import annotations

import json
import shutil
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
REPORTS = ROOT / "evaluator/reports"
DOCS = ROOT / "docs/evaluation"


def metric(result: dict[str, Any], name: str, default: float = 0.0) -> float:
    value = result["apiMetrics"].get(name)
    return default if value is None else float(value)


def format_value(value: Any, suffix: str = "") -> str:
    return "n/a" if value is None else f"{value}{suffix}"


def generate_chart(payload: dict[str, Any]) -> Path:
    results = payload["scenarios"]
    labels = [result["name"].replace("_", "\n") for result in results]
    service_rates = [metric(result, "serviceRatePct") for result in results]
    throughputs = [float(result["successfulThroughputRps"]) for result in results]
    colors = ["#15803D" if result["kind"] in {"nominal", "load"} else "#C2410C" for result in results]

    fig, axes = plt.subplots(1, 3, figsize=(18, 6))
    fig.suptitle("Pulsaride V1 - Isolated Robustness and Load Evaluation", fontsize=15, fontweight="bold")

    bars = axes[0].bar(labels, service_rates, color=colors)
    axes[0].axhline(95, color="#B91C1C", linestyle="--", label="V1 target 95%")
    axes[0].set_title("Service rate")
    axes[0].set_ylabel("Percent")
    axes[0].set_ylim(0, 110)
    axes[0].tick_params(axis="x", labelsize=8)
    axes[0].legend(loc="lower left")
    for bar, value in zip(bars, service_rates):
        axes[0].text(bar.get_x() + bar.get_width() / 2, value + 1, f"{value:.1f}%", ha="center", fontsize=8)

    bars = axes[1].bar(labels, throughputs, color="#2563EB")
    axes[1].set_title("Successful processing throughput")
    axes[1].set_ylabel("Closed requests / second")
    axes[1].tick_params(axis="x", labelsize=8)
    for bar, value in zip(bars, throughputs):
        axes[1].text(bar.get_x() + bar.get_width() / 2, value + 0.1, f"{value:.2f}", ha="center", fontsize=8)

    load_results = sorted(
        (result for result in results if result["kind"] == "load"),
        key=lambda result: result["requests"],
    )
    loads = [result["requests"] for result in load_results]
    load_ttfa = [metric(result, "p95TtfaMs") for result in load_results]
    axes[2].plot(loads, load_ttfa, "o-", color="#0F766E", linewidth=2.5, label="P95 TTFA")
    axes[2].axhline(5_000, color="#B91C1C", linestyle="--", label="V1 target 5,000 ms")
    breakpoint = payload["loadAnalysis"].get("firstDegradedLoadRequests")
    if breakpoint is not None:
        result = next(item for item in load_results if item["requests"] == breakpoint)
        axes[2].annotate(
            f"First degraded load: {breakpoint}",
            xy=(breakpoint, metric(result, "p95TtfaMs")),
            xytext=(breakpoint - 55, metric(result, "p95TtfaMs") + 500),
            arrowprops={"arrowstyle": "->", "color": "#B91C1C"},
            color="#B91C1C",
        )
    axes[2].set_title("Load ramp latency")
    axes[2].set_xlabel("Submitted requests")
    axes[2].set_ylabel("P95 TTFA (ms)")
    axes[2].set_ylim(0, max(6_000, max(load_ttfa, default=0) * 1.2))
    axes[2].legend(loc="upper left")

    for axis in axes:
        axis.grid(axis="y", alpha=0.25)
    fig.tight_layout()
    output = REPORTS / "robustness_charts.png"
    fig.savefig(output, dpi=150, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    return output


def generate_report(payload: dict[str, Any]) -> Path:
    rows = []
    for result in payload["scenarios"]:
        metrics = result["apiMetrics"]
        rows.append(
            f"{result['name']:<18} | {result['created']:>3} | "
            f"{result['successfulThroughputRps']:>8.2f} | {metrics.get('serviceRatePct', 0):>7.2f}% | "
            f"{format_value(metrics.get('p95TtfaMs')):>9} | {format_value(metrics.get('p95TtrMs')):>9} | "
            f"{format_value(metrics.get('p95DegradedReassignmentMs')):>9}"
        )

    load = payload["loadAnalysis"]
    report = "\n".join(
        [
            "PULSARIDE V1 - ROBUSTNESS AND LOAD REPORT",
            "=" * 78,
            f"Run: {payload['runAt']}",
            f"Strategy: {payload['strategy']}",
            f"Scenarios isolated with PostgreSQL/Redis reset: {payload['isolatedScenarios']}",
            "",
            "Scenario           | Req | Closed/s | Service  | P95 TTFA  | P95 TTR   | P95 MTTR",
            "-" * 92,
            *rows,
            "",
            "LOAD ANALYSIS",
            "-" * 78,
            f"Maximum sustainable submitted load: {format_value(load.get('maxSustainableRequests'), ' requests')}",
            f"Maximum sustainable throughput: {format_value(load.get('maxSustainableThroughputRps'), ' closed/s')}",
            f"Maximum observed throughput: {format_value(load.get('maxObservedThroughputRps'), ' closed/s')}",
            f"First degraded load: {format_value(load.get('firstDegradedLoadRequests'), ' requests')}",
            "",
            "A load is sustainable only when all requests become terminal, service rate is at",
            "least 95%, P95 TTFA is below 5 seconds, and P95 TTR is below 30 seconds.",
            "Degraded scenarios are intentional stress cases and are not used as nominal targets.",
            "",
        ]
    )
    output = REPORTS / "robustness_report.txt"
    output.write_text(report, encoding="utf-8")
    return output


def main() -> None:
    input_path = REPORTS / "robustness_results.json"
    with input_path.open(encoding="utf-8") as file:
        payload = json.load(file)
    DOCS.mkdir(parents=True, exist_ok=True)
    chart = generate_chart(payload)
    report = generate_report(payload)
    shutil.copy2(chart, DOCS / chart.name)
    shutil.copy2(report, DOCS / report.name)
    print(f"Chart written to {chart}")
    print(f"Report written to {report}")


if __name__ == "__main__":
    main()
