"""Evaluate the integrated Pulsaride V2 AI triage endpoint.

The goal is to measure the deployed behavior of ``POST /ai/triage``:
Darija Health NLP provider response plus Pulsaride deterministic safety floor.

This script intentionally separates datasets by provenance:
- Darija Health NLP held-out test split when available.
- Darija sample fallback when the exact split is absent.
- External Arabic medical consultation data downloaded from Hugging Face.
- Pulsaride red-flag safety suite.
- Pulsaride synthetic system regression corpus.
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import os
import statistics
import sys
import time
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any

import matplotlib.pyplot as plt
import requests


ROOT = Path(__file__).resolve().parents[1]
DATA_DIR = ROOT / "simulator" / "data" / "v2"
DOCS_EVAL_DIR = ROOT / "docs" / "evaluation"
DARIJA_REPO = ROOT.parent / "darija-health-nlp"

DEFAULT_BASE_URL = os.environ.get("PULSARIDE_API_URL", "http://localhost:8080").rstrip("/")
EXTERNAL_SOURCE_URL = (
    "https://huggingface.co/datasets/Youssefx64/"
    "Medical-Consultation-Questions-in-Arabic/resolve/main/questions.csv"
)
EXTERNAL_DARIJA_ROWS_URL = (
    "https://datasets-server.huggingface.co/rows?"
    "dataset=BrainHealthAI/MedQADataDarijaSaad&config=default&split=test"
)

PULSARIDE_SPECIALTIES = {
    "generaliste",
    "cardiologie",
    "pediatrie",
    "dermatologie",
    "orl",
    "psychiatrie",
    "gynecologie",
    "ophtalmologie",
    "urgence",
    "gastroenterologie",
    "neurologie",
    "pneumologie",
}

SPECIALTY_MAP = {
    "General Practice": "generaliste",
    "General Medicine": "generaliste",
    "Internal Medicine": "generaliste",
    "Family Medicine": "generaliste",
    "Cardiology": "cardiologie",
    "Pediatric Medicine": "pediatrie",
    "pediatric_medicine": "pediatrie",
    "Pediatrics": "pediatrie",
    "Dermatology": "dermatologie",
    "Cosmetic Dermatology": "dermatologie",
    "Otolaryngology": "orl",
    "ENT": "orl",
    "Psychiatry": "psychiatrie",
    "Mental Health": "psychiatrie",
    "Obstetrics and Gynecology": "gynecologie",
    "Gynecology": "gynecologie",
    "Ophthalmology": "ophtalmologie",
    "ophthalmology": "ophtalmologie",
    "ophthalmologist": "ophtalmologie",
    "Emergency Medicine": "urgence",
    "Pulmonology": "generaliste",
    "Gastroenterology": "generaliste",
    "gastroenterologie": "gastroenterologie",
    "Neurology": "neurologie",
    "neurologie": "neurologie",
    "Pulmonology": "pneumologie",
    "pneumologie": "pneumologie",
}

EXTERNAL_CATEGORY_MAP = {
    "الأمراض الجلدية": "dermatologie",
    "البشرة والجمال": "dermatologie",
    "الطب العام": "generaliste",
    "صحة عامة": "generaliste",
    "أمراض باطنية": "generaliste",
    "أمراض نسائية": "gynecologie",
    "الحمل والولادة": "gynecologie",
    "صحة المرأة": "gynecologie",
    "أمراض الأطفال": "pediatrie",
    "صحة الطفل": "pediatrie",
    "أمراض القلب و الشرايين": "cardiologie",
    "جراحة القلب والشرايين": "cardiologie",
    "ارتفاع ضغط الدم": "cardiologie",
    "أنف، أذن وحنجرة": "orl",
    "امراض العيون": "ophtalmologie",
    "أمراض نفسية": "psychiatrie",
    "الصحة النفسية": "psychiatrie",
    "إدمان": "psychiatrie",
    "علم السموم": "urgence",
    "إسعاف أولي": "urgence",
}

EXTERNAL_DARIJA_SPECIALTY_MAP = {
    "طب الأمراض الجلدية": "dermatologie",
    "طب القلب والشرايين": "cardiologie",
    "طب الأطفال": "pediatrie",
    "طب الأعصاب": "neurologie",
    "طب العيون": "ophtalmologie",
    "الطب النفسي": "psychiatrie",
    "الطب الباطني": "generaliste",
    "الطب العام": "generaliste",
    "طب الأذن والأنف والحنجرة": "orl",
    "طب النساء والتوليد": "gynecologie",
    "طب الأمراض الصدرية": "pneumologie",
    "طب الجهاز الهضمي": "gastroenterologie",
}

URGENCY_MAP = {
    "unknown": None,
    "low": 1,
    "medium": 2,
    "high": 3,
    "critical": 3,
    "faible": 1,
    "moyen": 2,
    "haute": 3,
    "elevee": 3,
}


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        return list(csv.DictReader(handle))


def text_hash(text: str) -> str:
    return hashlib.sha256(text.strip().lower().encode("utf-8")).hexdigest()


def normalize_specialty(label: object) -> str | None:
    raw = str(label or "").strip()
    if not raw:
        return None
    if raw in PULSARIDE_SPECIALTIES:
        return raw
    return SPECIALTY_MAP.get(raw)


def normalize_urgency(value: object) -> int | None:
    if value is None:
        return None
    if isinstance(value, int):
        return value if 0 <= value <= 3 else None
    raw = str(value).strip().lower()
    if raw == "":
        return None
    if raw.isdigit():
        parsed = int(raw)
        return parsed if 0 <= parsed <= 3 else None
    return URGENCY_MAP.get(raw)


def make_case(
    *,
    dataset: str,
    source_id: str,
    text: str,
    expected_specialty: str | None,
    expected_urgency: int | None = None,
    language: str | None = None,
    case_type: str = "clear",
    source_label: str | None = None,
) -> dict[str, Any]:
    return {
        "id": f"{dataset}:{source_id}",
        "dataset": dataset,
        "source": source_label or dataset,
        "source_id": source_id,
        "text": text.strip(),
        "language": language or "unknown",
        "case_type": case_type,
        "expected": {
            "specialtyHint": expected_specialty,
            "urgencyScore": expected_urgency,
        },
    }


def load_darija_holdout(max_cases: int | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    processed = DARIJA_REPO / "data" / "processed"
    test_path = processed / "test.csv"
    train_path = processed / "train.csv"
    valid_path = processed / "valid.csv"
    sample_path = DARIJA_REPO / "data" / "sample" / "sample_medqa_ma.csv"

    provenance: dict[str, Any] = {
        "dataset": "darija_holdout",
        "repo_path": str(DARIJA_REPO),
        "exact_processed_split_found": test_path.exists(),
        "leakage_check": "not_available",
    }

    if test_path.exists():
        rows = read_csv(test_path)
        source_path = test_path
        dataset_name = "darija_holdout"
        provenance["source_file"] = str(source_path)
        if train_path.exists() and valid_path.exists():
            train_valid_hashes = {text_hash(row.get("text", "")) for row in read_csv(train_path)}
            train_valid_hashes.update(text_hash(row.get("text", "")) for row in read_csv(valid_path))
            leaked = [
                row.get("id", "")
                for row in rows
                if text_hash(row.get("text", "")) in train_valid_hashes
            ]
            provenance["leakage_check"] = {
                "train_valid_files_present": True,
                "overlapping_test_rows": len(leaked),
                "overlap_ids_sample": leaked[:10],
            }
    elif sample_path.exists():
        rows = read_csv(sample_path)
        source_path = sample_path
        dataset_name = "darija_sample_holdout_proxy"
        provenance["source_file"] = str(source_path)
        provenance["warning"] = (
            "Exact Darija Health NLP data/processed/test.csv was not present locally. "
            "Using sample_medqa_ma.csv only as a labelled sample/proxy, not as a claimed "
            "training-independent holdout."
        )
    else:
        provenance["warning"] = "No Darija Health NLP processed test split or sample file found."
        return [], provenance

    cases: list[dict[str, Any]] = []
    for index, row in enumerate(rows):
        expected_specialty = normalize_specialty(row.get("specialty") or row.get("Category"))
        text = row.get("text") or row.get("Question") or row.get("question") or ""
        if not text.strip() or expected_specialty is None:
            continue
        cases.append(
            make_case(
                dataset=dataset_name,
                source_id=row.get("id") or str(index),
                text=text,
                expected_specialty=expected_specialty,
                expected_urgency=normalize_urgency(row.get("urgency")),
                language=row.get("language"),
                source_label="darija-health-nlp",
            )
        )
        if max_cases and len(cases) >= max_cases:
            break

    provenance["usable_cases"] = len(cases)
    return cases, provenance


def load_external_hf(max_cases: int = 240, per_category: int = 20) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    provenance = {
        "dataset": "external_hf_arabic_medical_consultations",
        "source_url": EXTERNAL_SOURCE_URL,
        "repository": "https://huggingface.co/datasets/Youssefx64/Medical-Consultation-Questions-in-Arabic",
        "selection": "Mapped clear medical categories only; capped per category for balance.",
    }
    local_path = DATA_DIR / "external_arabic_medical_eval_source.csv"
    DATA_DIR.mkdir(parents=True, exist_ok=True)

    try:
        urllib.request.urlretrieve(EXTERNAL_SOURCE_URL, local_path)
        provenance["downloaded_to"] = str(local_path.relative_to(ROOT))
    except Exception as exc:  # pragma: no cover - network failure path
        provenance["download_error"] = str(exc)
        return [], provenance

    cases: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    scanned = 0
    with local_path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            scanned += 1
            category = (row.get("Category") or "").strip()
            expected_specialty = EXTERNAL_CATEGORY_MAP.get(category)
            question = (row.get("Question") or "").strip()
            if expected_specialty is None or not question:
                continue
            if counts[category] >= per_category:
                continue
            counts[category] += 1
            cases.append(
                make_case(
                    dataset="external_hf_arabic",
                    source_id=f"row_{scanned}",
                    text=question,
                    expected_specialty=expected_specialty,
                    language="arabic",
                    case_type="external_clear",
                    source_label="Youssefx64/Medical-Consultation-Questions-in-Arabic",
                )
            )
            if len(cases) >= max_cases:
                break

    external_json = DATA_DIR / "external_arabic_medical_eval.json"
    external_json.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    provenance["scanned_rows"] = scanned
    provenance["usable_cases"] = len(cases)
    provenance["category_counts"] = dict(counts)
    provenance["normalized_output"] = str(external_json.relative_to(ROOT))
    return cases, provenance


def load_external_darija_hf(max_cases: int = 300, per_specialty: int = 35) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    provenance = {
        "dataset": "external_hf_darija_medqa_test",
        "repository": "https://huggingface.co/datasets/BrainHealthAI/MedQADataDarijaSaad",
        "split": "test",
        "selection": "Mapped supported Pulsaride specialties only; capped per specialty for balance.",
    }

    cases: list[dict[str, Any]] = []
    counts: Counter[str] = Counter()
    scanned = 0
    total_rows = None
    offset = 0
    page_size = 100

    try:
        while True:
            url = f"{EXTERNAL_DARIJA_ROWS_URL}&offset={offset}&length={page_size}"
            with urllib.request.urlopen(url, timeout=30) as response:
                payload = json.load(response)
            total_rows = payload.get("num_rows_total", total_rows)
            rows = payload.get("rows", [])
            if not rows:
                break
            for item in rows:
                scanned += 1
                row = item.get("row", {})
                specialty = (row.get("speciality") or "").strip()
                expected_specialty = EXTERNAL_DARIJA_SPECIALTY_MAP.get(specialty)
                if expected_specialty is None or counts[expected_specialty] >= per_specialty:
                    continue
                text = (row.get("question") or row.get("context_question") or "").strip()
                if not text:
                    continue
                counts[expected_specialty] += 1
                cases.append(
                    make_case(
                        dataset="external_hf_darija",
                        source_id=f"test_row_{item.get('row_idx', scanned)}",
                        text=text,
                        expected_specialty=expected_specialty,
                        expected_urgency=normalize_urgency(row.get("urgency")),
                        language=row.get("language") or "Darija",
                        case_type="external_darija_test",
                        source_label="BrainHealthAI/MedQADataDarijaSaad",
                    )
                )
                if len(cases) >= max_cases:
                    break
            if len(cases) >= max_cases or (total_rows is not None and offset + page_size >= total_rows):
                break
            offset += page_size
    except Exception as exc:  # pragma: no cover - network failure path
        provenance["download_error"] = str(exc)
        return cases, provenance

    external_json = DATA_DIR / "external_darija_medqa_eval.json"
    external_json.write_text(json.dumps(cases, ensure_ascii=False, indent=2), encoding="utf-8")
    provenance["scanned_rows"] = scanned
    provenance["usable_cases"] = len(cases)
    provenance["specialty_counts"] = dict(counts)
    provenance["normalized_output"] = str(external_json.relative_to(ROOT))
    provenance["source_rows_total"] = total_rows
    return cases, provenance


def load_red_flags() -> tuple[list[dict[str, Any]], dict[str, Any]]:
    dataset_path = DATA_DIR / "frozen_eval_dataset.json"
    if not dataset_path.exists():
        return [], {"dataset": "pulsaride_red_flags", "warning": "frozen_eval_dataset.json not found"}
    data = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases = [
        make_case(
            dataset="pulsaride_red_flags",
            source_id=item["id"],
            text=item["free_text"],
            expected_specialty=None,
            expected_urgency=item.get("expected_min_urgency"),
            language="mixed",
            case_type="red_flag",
            source_label="pulsaride-v2-401",
        )
        for item in data.get("red_flag_suite", [])
    ]
    return cases, {"dataset": "pulsaride_red_flags", "usable_cases": len(cases)}


def load_synthetic(max_cases: int | None = None) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    dataset_path = DATA_DIR / "v2_large_demo_dataset.json"
    if not dataset_path.exists():
        return [], {"dataset": "pulsaride_synthetic", "warning": "v2_large_demo_dataset.json not found"}
    rows = json.loads(dataset_path.read_text(encoding="utf-8"))
    cases: list[dict[str, Any]] = []
    for row in rows:
        gt = row.get("ground_truth", {})
        cases.append(
            make_case(
                dataset="pulsaride_synthetic",
                source_id=row.get("patient_id") or row.get("id"),
                text=row["free_text"],
                expected_specialty=gt.get("specialty_hint"),
                expected_urgency=gt.get("urgency_score"),
                language="synthetic",
                case_type=row.get("case_type", "synthetic"),
                source_label="pulsaride-v2-large-demo",
            )
        )
        if max_cases and len(cases) >= max_cases:
            break
    return cases, {"dataset": "pulsaride_synthetic", "usable_cases": len(cases)}


def call_triage(base_url: str, text: str, timeout: float) -> tuple[dict[str, Any] | None, float, str | None]:
    start = time.perf_counter()
    try:
        response = requests.post(
            f"{base_url}/ai/triage",
            json={"text": text},
            timeout=timeout,
        )
        elapsed_ms = (time.perf_counter() - start) * 1000
        response.raise_for_status()
        return response.json(), elapsed_ms, None
    except Exception as exc:
        elapsed_ms = (time.perf_counter() - start) * 1000
        return None, elapsed_ms, str(exc)


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    sorted_values = sorted(values)
    rank = math.ceil((pct / 100) * len(sorted_values)) - 1
    rank = max(0, min(rank, len(sorted_values) - 1))
    return round(sorted_values[rank], 2)


def classification_metrics(pairs: list[tuple[str, str]]) -> dict[str, Any]:
    labels = sorted({label for pair in pairs for label in pair})
    if not pairs:
        return {
            "n": 0,
            "accuracy_pct": None,
            "macro_f1_pct": None,
            "weighted_f1_pct": None,
            "per_label": {},
        }
    total = len(pairs)
    correct = sum(1 for expected, predicted in pairs if expected == predicted)
    per_label: dict[str, dict[str, float]] = {}
    f1s = []
    weighted_f1_sum = 0.0
    for label in labels:
        tp = sum(1 for expected, predicted in pairs if expected == label and predicted == label)
        fp = sum(1 for expected, predicted in pairs if expected != label and predicted == label)
        fn = sum(1 for expected, predicted in pairs if expected == label and predicted != label)
        support = sum(1 for expected, _ in pairs if expected == label)
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        per_label[label] = {
            "precision_pct": round(precision * 100, 2),
            "recall_pct": round(recall * 100, 2),
            "f1_pct": round(f1 * 100, 2),
            "support": support,
        }
        f1s.append(f1)
        weighted_f1_sum += f1 * support
    return {
        "n": total,
        "accuracy_pct": round(correct / total * 100, 2),
        "macro_f1_pct": round(statistics.mean(f1s) * 100, 2) if f1s else None,
        "weighted_f1_pct": round(weighted_f1_sum / total * 100, 2),
        "per_label": per_label,
    }


def summarize(results: list[dict[str, Any]], provenance: list[dict[str, Any]], base_url: str) -> dict[str, Any]:
    ok_results = [row for row in results if row["api"]["success"]]
    failures = [row for row in results if not row["api"]["success"]]
    latencies = [row["api"]["latencyMs"] for row in ok_results]

    specialty_pairs_by_dataset: dict[str, list[tuple[str, str]]] = defaultdict(list)
    urgency_pairs_by_dataset: dict[str, list[tuple[str, str]]] = defaultdict(list)
    all_specialty_pairs: list[tuple[str, str]] = []
    all_urgency_pairs: list[tuple[str, str]] = []

    red_flags = []
    for row in ok_results:
        expected = row["expected"]
        prediction = row["prediction"]
        predicted_specialty = normalize_specialty(prediction.get("specialtyHint"))
        if expected.get("specialtyHint") and predicted_specialty:
            pair = (expected["specialtyHint"], predicted_specialty)
            specialty_pairs_by_dataset[row["dataset"]].append(pair)
            all_specialty_pairs.append(pair)
        if expected.get("urgencyScore") is not None and prediction.get("urgencyScore") is not None:
            pair = (str(expected["urgencyScore"]), str(prediction["urgencyScore"]))
            urgency_pairs_by_dataset[row["dataset"]].append(pair)
            all_urgency_pairs.append(pair)
        if row["caseType"] == "red_flag":
            red_flags.append(row)

    redflag_passed = sum(
        1
        for row in red_flags
        if row["prediction"].get("urgencyScore") is not None
        and row["prediction"]["urgencyScore"] >= row["expected"]["urgencyScore"]
    )

    modes = Counter(row["prediction"].get("mode", "unknown") for row in ok_results)
    source_models = Counter(row["prediction"].get("sourceModel", "unknown") for row in ok_results)
    safety_overrides = sum(1 for row in ok_results if "safety-floor" in str(row["prediction"].get("mode", "")))
    top_confusions = Counter(
        (expected, predicted)
        for expected, predicted in all_specialty_pairs
        if expected != predicted
    ).most_common(15)

    return {
        "baseUrl": base_url,
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "provenance": provenance,
        "totals": {
            "cases": len(results),
            "success": len(ok_results),
            "failed": len(failures),
            "apiSuccessRatePct": round(len(ok_results) / len(results) * 100, 2) if results else None,
        },
        "latencyMs": {
            "avg": round(statistics.mean(latencies), 2) if latencies else None,
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "p99": percentile(latencies, 99),
            "max": round(max(latencies), 2) if latencies else None,
        },
        "specialty": {
            "overall": classification_metrics(all_specialty_pairs),
            "byDataset": {
                dataset: classification_metrics(pairs)
                for dataset, pairs in sorted(specialty_pairs_by_dataset.items())
            },
            "topConfusions": [
                {"expected": expected, "predicted": predicted, "count": count}
                for (expected, predicted), count in top_confusions
            ],
        },
        "urgency": {
            "overall": classification_metrics(all_urgency_pairs),
            "byDataset": {
                dataset: classification_metrics(pairs)
                for dataset, pairs in sorted(urgency_pairs_by_dataset.items())
            },
        },
        "safety": {
            "redFlagCases": len(red_flags),
            "redFlagPassed": redflag_passed,
            "redFlagRecallPct": round(redflag_passed / len(red_flags) * 100, 2) if red_flags else None,
            "safetyFloorModeCount": safety_overrides,
        },
        "integration": {
            "modeCounts": dict(modes),
            "sourceModelCounts": dict(source_models),
            "failureSamples": failures[:10],
        },
        "notes": [
            "This is software/AI engineering validation, not clinical validation.",
            "Synthetic Pulsaride cases are regression/demo coverage, not independent medical evidence.",
            "External Arabic data is mapped to Pulsaride specialties only for categories with defensible alignment.",
        ],
    }


def plot_confusion(results: list[dict[str, Any]], output: Path) -> None:
    pairs = []
    for row in results:
        if not row["api"]["success"] or not row["expected"].get("specialtyHint"):
            continue
        predicted = normalize_specialty(row["prediction"].get("specialtyHint"))
        if predicted:
            pairs.append((row["expected"]["specialtyHint"], predicted))
    labels = sorted({label for pair in pairs for label in pair})
    matrix = [[0 for _ in labels] for _ in labels]
    indexes = {label: index for index, label in enumerate(labels)}
    for expected, predicted in pairs:
        matrix[indexes[expected]][indexes[predicted]] += 1

    fig, ax = plt.subplots(figsize=(10, 8))
    image = ax.imshow(matrix, cmap="Blues")
    ax.set_xticks(range(len(labels)), labels=labels, rotation=45, ha="right")
    ax.set_yticks(range(len(labels)), labels=labels)
    ax.set_xlabel("Predicted specialty")
    ax.set_ylabel("Expected specialty")
    ax.set_title("AI Triage Specialty Confusion Matrix")
    for i, row in enumerate(matrix):
        for j, value in enumerate(row):
            if value:
                ax.text(j, i, str(value), ha="center", va="center", fontsize=8)
    fig.colorbar(image, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_per_specialty(summary: dict[str, Any], output: Path) -> None:
    per_label = summary["specialty"]["overall"]["per_label"]
    labels = list(per_label)
    f1s = [per_label[label]["f1_pct"] for label in labels]
    supports = [per_label[label]["support"] for label in labels]

    fig, ax = plt.subplots(figsize=(10, 5.5))
    bars = ax.bar(labels, f1s, color="#0f766e")
    ax.set_ylim(0, 100)
    ax.set_ylabel("F1 (%)")
    ax.set_title("AI Triage F1 by Specialty")
    ax.tick_params(axis="x", rotation=35)
    for bar, support in zip(bars, supports):
        ax.text(
            bar.get_x() + bar.get_width() / 2,
            min(98, bar.get_height() + 2),
            f"n={support}",
            ha="center",
            fontsize=8,
        )
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_latency(results: list[dict[str, Any]], output: Path) -> None:
    latencies = [row["api"]["latencyMs"] for row in results if row["api"]["success"]]
    fig, ax = plt.subplots(figsize=(9, 5))
    ax.hist(latencies, bins=30, color="#1d4ed8", alpha=0.85)
    p95 = percentile(latencies, 95)
    if p95 is not None:
        ax.axvline(p95, color="#b91c1c", linestyle="--", label=f"P95 = {p95} ms")
        ax.legend()
    ax.set_title("AI Triage Endpoint Latency Distribution")
    ax.set_xlabel("Latency (ms)")
    ax.set_ylabel("Requests")
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_redflag(summary: dict[str, Any], output: Path) -> None:
    recall = summary["safety"]["redFlagRecallPct"] or 0
    target = 90
    fig, ax = plt.subplots(figsize=(7, 4.5))
    ax.bar(["Observed recall", "Target"], [recall, target], color=["#15803d", "#d97706"])
    ax.set_ylim(0, 105)
    ax.set_ylabel("Recall (%)")
    ax.set_title("Red-Flag Safety Recall")
    for index, value in enumerate([recall, target]):
        ax.text(index, value + 2, f"{value:.1f}%", ha="center", fontsize=10)
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def plot_urgency(results: list[dict[str, Any]], output: Path) -> None:
    pairs = [
        (str(row["expected"]["urgencyScore"]), str(row["prediction"].get("urgencyScore")))
        for row in results
        if row["api"]["success"]
        and row["expected"].get("urgencyScore") is not None
        and row["prediction"].get("urgencyScore") is not None
    ]
    labels = ["0", "1", "2", "3"]
    matrix = [[0 for _ in labels] for _ in labels]
    indexes = {label: index for index, label in enumerate(labels)}
    for expected, predicted in pairs:
        if expected in indexes and predicted in indexes:
            matrix[indexes[expected]][indexes[predicted]] += 1

    fig, ax = plt.subplots(figsize=(6.5, 5.5))
    image = ax.imshow(matrix, cmap="Purples")
    ax.set_xticks(range(len(labels)), labels=labels)
    ax.set_yticks(range(len(labels)), labels=labels)
    ax.set_xlabel("Predicted urgencyScore")
    ax.set_ylabel("Expected urgencyScore")
    ax.set_title("AI Triage Urgency Confusion Matrix")
    for i, row in enumerate(matrix):
        for j, value in enumerate(row):
            ax.text(j, i, str(value), ha="center", va="center", fontsize=9)
    fig.colorbar(image, ax=ax, fraction=0.046, pad=0.04)
    fig.tight_layout()
    fig.savefig(output, dpi=180)
    plt.close(fig)


def write_markdown(summary: dict[str, Any], output: Path) -> None:
    totals = summary["totals"]
    specialty = summary["specialty"]["overall"]
    urgency = summary["urgency"]["overall"]
    latency = summary["latencyMs"]
    safety = summary["safety"]
    lines = [
        "# Pulsaride V2 AI Evaluation",
        "",
        "## Purpose",
        "",
        "This evaluation measures the integrated Pulsaride endpoint `POST /ai/triage`, "
        "including the Darija Health NLP provider and the Pulsaride deterministic "
        "safety-floor rules. It is software/AI engineering validation, not clinical validation.",
        "",
        "## Dataset Provenance",
        "",
    ]
    for item in summary["provenance"]:
        lines.append(f"- **{item.get('dataset')}**: {item.get('usable_cases', 0)} usable cases.")
        if item.get("source_file"):
            lines.append(f"  Source file: `{item['source_file']}`.")
        if item.get("repository"):
            lines.append(f"  Repository: {item['repository']}.")
        if item.get("warning"):
            lines.append(f"  Warning: {item['warning']}")
        if item.get("leakage_check", "not_available") != "not_available":
            lines.append(f"  Leakage check: `{json.dumps(item['leakage_check'], ensure_ascii=False)}`.")
    lines.extend(
        [
            "",
            "## Summary Metrics",
            "",
            f"- API success rate: **{totals['apiSuccessRatePct']}%** ({totals['success']}/{totals['cases']}).",
            f"- Specialty accuracy: **{specialty['accuracy_pct']}%** on {specialty['n']} labelled cases.",
            f"- Specialty macro F1: **{specialty['macro_f1_pct']}%**.",
            f"- Specialty weighted F1: **{specialty['weighted_f1_pct']}%**.",
            f"- Urgency exact match: **{urgency['accuracy_pct']}%** on {urgency['n']} labelled cases.",
            f"- Red-flag recall: **{safety['redFlagRecallPct']}%** "
            f"({safety['redFlagPassed']}/{safety['redFlagCases']}, target >= 90%).",
            f"- Latency avg/P50/P95/P99: **{latency['avg']} / {latency['p50']} / "
            f"{latency['p95']} / {latency['p99']} ms**.",
            "",
            "## Top Specialty Confusions",
            "",
        ]
    )
    for item in summary["specialty"]["topConfusions"][:10]:
        lines.append(f"- Expected `{item['expected']}`, predicted `{item['predicted']}`: {item['count']} cases.")
    if not summary["specialty"]["topConfusions"]:
        lines.append("- No specialty confusions observed on the labelled evaluation subset.")
    lines.extend(
        [
            "",
            "## Interpretation",
            "",
            "- The Darija Health NLP repository is reused as an external FastAPI model service: "
            "https://github.com/SalmaneSossey/darija-health-nlp.",
            "- OpenAI is not required for this evaluation and remains optional because it is paid.",
            "- The external Arabic dataset is used only for evaluation and only for categories "
            "with a defensible mapping to Pulsaride specialties.",
            "- The synthetic Pulsaride corpus is useful for regression and system coverage, "
            "but should not be presented as a clinically validated benchmark.",
            "- A stronger future step would be restoring the full original MedQA-MA training "
            "artifact bundle and running the same evaluator against the exact held-out test split.",
            "",
            "## Generated Figures",
            "",
            "- `docs/evaluation/ai_confusion_matrix.png`",
            "- `docs/evaluation/ai_per_specialty_f1.png`",
            "- `docs/evaluation/ai_urgency_confusion.png`",
            "- `docs/evaluation/ai_latency_distribution.png`",
            "- `docs/evaluation/ai_redflag_recall.png`",
            "",
        ]
    )
    output.write_text("\n".join(lines), encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate Pulsaride V2 /ai/triage endpoint.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--max-holdout", type=int, default=0, help="0 means all available holdout cases.")
    parser.add_argument("--max-external", type=int, default=240)
    parser.add_argument("--external-per-category", type=int, default=20)
    parser.add_argument("--max-external-darija", type=int, default=300)
    parser.add_argument("--external-darija-per-specialty", type=int, default=35)
    parser.add_argument("--max-synthetic", type=int, default=840)
    args = parser.parse_args()

    DATA_DIR.mkdir(parents=True, exist_ok=True)
    DOCS_EVAL_DIR.mkdir(parents=True, exist_ok=True)

    holdout, holdout_provenance = load_darija_holdout(args.max_holdout or None)
    external, external_provenance = load_external_hf(args.max_external, args.external_per_category)
    external_darija, external_darija_provenance = load_external_darija_hf(
        args.max_external_darija,
        args.external_darija_per_specialty,
    )
    red_flags, redflag_provenance = load_red_flags()
    synthetic, synthetic_provenance = load_synthetic(args.max_synthetic or None)

    cases = holdout + external + external_darija + red_flags + synthetic
    provenance = [
        holdout_provenance,
        external_provenance,
        external_darija_provenance,
        redflag_provenance,
        synthetic_provenance,
    ]
    if not cases:
        print("No evaluation cases found.", file=sys.stderr)
        return 1

    results: list[dict[str, Any]] = []
    for index, case in enumerate(cases, start=1):
        prediction, latency_ms, error = call_triage(args.base_url.rstrip("/"), case["text"], args.timeout)
        results.append(
            {
                "id": case["id"],
                "dataset": case["dataset"],
                "source": case["source"],
                "sourceId": case["source_id"],
                "language": case["language"],
                "caseType": case["case_type"],
                "text": case["text"],
                "expected": case["expected"],
                "prediction": prediction or {},
                "api": {
                    "success": prediction is not None,
                    "latencyMs": round(latency_ms, 2),
                    "error": error,
                },
            }
        )
        if index % 100 == 0 or index == len(cases):
            print(f"Evaluated {index}/{len(cases)} cases")

    summary = summarize(results, provenance, args.base_url.rstrip("/"))

    holdout_results = [row for row in results if row["dataset"].startswith("darija_")]
    external_results = [row for row in results if row["dataset"].startswith("external_hf_")]
    (DATA_DIR / "ai_eval_holdout_results.json").write_text(
        json.dumps(holdout_results, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (DATA_DIR / "ai_eval_external_results.json").write_text(
        json.dumps(external_results, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (DATA_DIR / "ai_eval_all_results.json").write_text(
        json.dumps(results, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (DATA_DIR / "ai_eval_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )

    plot_confusion(results, DOCS_EVAL_DIR / "ai_confusion_matrix.png")
    plot_per_specialty(summary, DOCS_EVAL_DIR / "ai_per_specialty_f1.png")
    plot_urgency(results, DOCS_EVAL_DIR / "ai_urgency_confusion.png")
    plot_latency(results, DOCS_EVAL_DIR / "ai_latency_distribution.png")
    plot_redflag(summary, DOCS_EVAL_DIR / "ai_redflag_recall.png")
    write_markdown(summary, DOCS_EVAL_DIR / "AI_EVALUATION.md")

    print(json.dumps(summary["totals"], ensure_ascii=False, indent=2))
    print(json.dumps(summary["specialty"]["overall"], ensure_ascii=False, indent=2))
    print(json.dumps(summary["safety"], ensure_ascii=False, indent=2))
    print(f"Wrote {DATA_DIR / 'ai_eval_summary.json'}")
    print(f"Wrote {DOCS_EVAL_DIR / 'AI_EVALUATION.md'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
