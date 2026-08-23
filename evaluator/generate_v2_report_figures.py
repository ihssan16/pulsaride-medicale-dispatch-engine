"""Generate V2 evaluation figures used by the LaTeX report.

The script is intentionally dependency-light: it reads the committed JSON
evaluation artifacts and writes PNG figures under docs/evaluation.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "simulator/data/v2"
DOCS = ROOT / "docs/evaluation"

BLUE = "#1d4ed8"
TEAL = "#0f766e"
GREEN = "#15803d"
AMBER = "#b45309"
RED = "#b91c1c"
GRAY = "#64748b"


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as file:
        return json.load(file)


def save(fig: plt.Figure, filename: str) -> Path:
    DOCS.mkdir(parents=True, exist_ok=True)
    output = DOCS / filename
    fig.savefig(output, dpi=160, bbox_inches="tight", facecolor="white")
    plt.close(fig)
    return output


def add_bar_labels(axis: plt.Axes, suffix: str = "", precision: int = 1) -> None:
    for patch in axis.patches:
        height = patch.get_height()
        if height == 0:
            label = "0"
        elif precision == 0:
            label = f"{height:.0f}{suffix}"
        else:
            label = f"{height:.{precision}f}{suffix}"
        axis.text(
            patch.get_x() + patch.get_width() / 2,
            height + max(axis.get_ylim()[1] * 0.015, 0.2),
            label,
            ha="center",
            va="bottom",
            fontsize=9,
        )


def dataset_split_chart() -> Path:
    payload = load_json(DATA / "frozen_eval_dataset.json")
    sizes = payload["sizes"]
    labels = ["Train", "Validation", "Test"]
    values = [sizes["train"], sizes["val"], sizes["test"]]

    fig, axis = plt.subplots(figsize=(8, 4.6))
    axis.bar(labels, values, color=[BLUE, TEAL, AMBER])
    axis.set_title("V2 Frozen Evaluation Dataset Split (seed=42)", fontweight="bold")
    axis.set_ylabel("Number of cases")
    axis.set_ylim(0, max(values) * 1.25)
    axis.grid(axis="y", alpha=0.25)
    add_bar_labels(axis, precision=0)
    axis.text(
        0.5,
        -0.18,
        "Grouped by specialty and ambiguity to reduce template leakage.",
        transform=axis.transAxes,
        ha="center",
        fontsize=9,
        color=GRAY,
    )
    fig.tight_layout()
    return save(fig, "v2_dataset_split.png")


def large_demo_dataset_chart() -> Path:
    payload = load_json(DATA / "v2_large_demo_summary.json")
    labels = ["Clear", "Ambiguous", "Red-flag"]
    counts = payload["case_type_counts"]
    values = [counts["clear"], counts["ambiguous"], counts["red_flag"]]

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    axes[0].bar(labels, values, color=[BLUE, AMBER, RED])
    axes[0].set_title("Large Synthetic Demonstration Corpus")
    axes[0].set_ylabel("Number of cases")
    axes[0].set_ylim(0, max(values) * 1.2)
    axes[0].grid(axis="y", alpha=0.25)
    add_bar_labels(axes[0], precision=0)

    metrics = [
        payload["clear_specialty_baseline_accuracy_pct"],
        payload["red_flag_recall_pct"],
    ]
    metric_labels = ["Specialty\nbaseline accuracy", "Red-flag\nrecall"]
    axes[1].bar(metric_labels, metrics, color=[TEAL, GREEN])
    axes[1].axhline(90, color=RED, linestyle="--", label="90% safety target")
    axes[1].set_title("Coverage Checks")
    axes[1].set_ylabel("Percent")
    axes[1].set_ylim(0, 110)
    axes[1].grid(axis="y", alpha=0.25)
    axes[1].legend(loc="lower right")
    add_bar_labels(axes[1], suffix="%", precision=1)

    fig.suptitle(
        f"V2 Large Demo Dataset: {payload['total_cases']} Synthetic Cases (seed={payload['seed']})",
        fontweight="bold",
    )
    fig.tight_layout()
    return save(fig, "v2_large_demo_dataset.png")


def benchmark_chart() -> Path:
    rows = load_json(DATA / "v2_403_benchmark_report.json")
    labels = [row["approach"].replace("_", "\n") for row in rows]
    accuracy = [row["specialty_top1_accuracy_pct"] or 0 for row in rows]
    urgency = [row["urgency_exact_match_pct"] or 0 for row in rows]
    p95_latency = [row["p95_latency_ms"] or 0 for row in rows]

    fig, axes = plt.subplots(1, 2, figsize=(12, 4.8))
    x = range(len(labels))
    width = 0.36
    axes[0].bar([i - width / 2 for i in x], accuracy, width, label="Specialty top-1", color=BLUE)
    axes[0].bar([i + width / 2 for i in x], urgency, width, label="Urgency exact", color=TEAL)
    axes[0].set_title("Frozen Test Accuracy")
    axes[0].set_ylabel("Percent")
    axes[0].set_xticks(list(x), labels, fontsize=8)
    axes[0].set_ylim(0, 100)
    axes[0].grid(axis="y", alpha=0.25)
    axes[0].legend()

    colors = [GREEN, AMBER, RED]
    axes[1].bar(labels, p95_latency, color=colors)
    axes[1].set_title("P95 Latency")
    axes[1].set_ylabel("Milliseconds")
    axes[1].set_yscale("symlog", linthresh=1)
    axes[1].grid(axis="y", alpha=0.25)
    axes[1].tick_params(axis="x", labelsize=8)
    for patch, value in zip(axes[1].patches, p95_latency):
        axes[1].text(
            patch.get_x() + patch.get_width() / 2,
            max(value, 0.1) * 1.25,
            f"{value:.1f} ms",
            ha="center",
            fontsize=8,
        )

    fig.suptitle("V2-403 AI Benchmark Comparison", fontweight="bold")
    fig.tight_layout()
    return save(fig, "v2_benchmark_comparison.png")


def redflag_chart() -> Path:
    payload = load_json(DATA / "v2_402_redflag_results.json")
    results = payload["results"]
    labels = [result["case_id"].replace("redflag_", "RF") for result in results]
    actual = [result["actual"] for result in results]
    expected = [result["expected_min"] for result in results]
    colors = [GREEN if result["passed"] else RED for result in results]

    fig, axis = plt.subplots(figsize=(9, 4.5))
    axis.bar(labels, actual, color=colors, label="Actual urgency")
    axis.plot(labels, expected, color=RED, marker="o", linewidth=2, label="Required minimum")
    axis.set_title("V2-402 Red-Flag Safety Rules", fontweight="bold")
    axis.set_ylabel("Urgency score")
    axis.set_ylim(0, 3.6)
    axis.grid(axis="y", alpha=0.25)
    axis.legend(loc="lower right")
    axis.text(
        0.5,
        -0.18,
        f"Critical recall: {payload['recall_pct']}% ({sum(1 for r in results if r['passed'])}/{len(results)} cases passed).",
        transform=axis.transAxes,
        ha="center",
        fontsize=9,
        color=GRAY,
    )
    fig.tight_layout()
    return save(fig, "v2_redflag_safety.png")


def resilience_chart() -> Path:
    payload = load_json(DATA / "v2_1002_failure_scenarios_report.json")
    ai = payload["ai_outage"]
    kafka = payload["kafka_restart"]
    dup = payload["duplicate_events"]

    labels = ["AI outage\nreview flagged", "Kafka restart\nevents lost", "Duplicate events\neffects applied"]
    values = [
        ai["requires_review_flagged"] / ai["total_cases"] * 100,
        kafka["events_lost"],
        dup["effects_applied"],
    ]
    colors = [GREEN, GREEN if values[1] == 0 else RED, GREEN if values[2] == 1 else RED]

    fig, axis = plt.subplots(figsize=(9, 4.6))
    axis.bar(labels, values, color=colors)
    axis.set_title("V2-1002 Robustness Scenarios", fontweight="bold")
    axis.set_ylabel("Scenario metric")
    axis.set_ylim(0, max(values) * 1.2 if max(values) > 5 else 6)
    axis.grid(axis="y", alpha=0.25)
    for patch, label in zip(axis.patches, ["100%", "0 lost", "1 effect"]):
        axis.text(
            patch.get_x() + patch.get_width() / 2,
            patch.get_height() + 0.15,
            label,
            ha="center",
            fontsize=9,
        )
    axis.text(
        0.5,
        -0.2,
        "Fallback keeps the service available; outbox prevents event loss; idempotency skips duplicates.",
        transform=axis.transAxes,
        ha="center",
        fontsize=9,
        color=GRAY,
    )
    fig.tight_layout()
    return save(fig, "v2_resilience_scenarios.png")


def pipeline_chart() -> Path:
    rows = load_json(DATA / "v2_404_pipeline_results.json")
    labels = ["Fallback review", "Safety override", "Extraction OK"]
    values = [
        sum(1 for row in rows if row["requires_review"]),
        sum(1 for row in rows if row["safety_override"]),
        sum(1 for row in rows if row["extraction_ok"]),
    ]
    total = len(rows)

    fig, axis = plt.subplots(figsize=(8, 4.5))
    axis.bar(labels, values, color=[AMBER, GREEN, BLUE])
    axis.set_title("V2-404 Triage Event Pipeline Sample", fontweight="bold")
    axis.set_ylabel(f"Cases out of {total}")
    axis.set_ylim(0, total + 1)
    axis.grid(axis="y", alpha=0.25)
    add_bar_labels(axis, precision=0)
    axis.text(
        0.5,
        -0.18,
        "This historical run documents an AI extraction outage and the fallback behavior.",
        transform=axis.transAxes,
        ha="center",
        fontsize=9,
        color=GRAY,
    )
    fig.tight_layout()
    return save(fig, "v2_pipeline_sample.png")


def main() -> None:
    outputs = [
        dataset_split_chart(),
        large_demo_dataset_chart(),
        benchmark_chart(),
        redflag_chart(),
        resilience_chart(),
        pipeline_chart(),
    ]
    for output in outputs:
        print(output.relative_to(ROOT))


if __name__ == "__main__":
    main()
