"""
Pulsaride V2 — IA2 : Score d'urgence
Calcule urgency_score (0-3) à partir de la sortie IA1
Owner: Ihssan Ben Labsir
"""

import json
from pathlib import Path

# Règles de scoring basées sur symptômes/durée/âge (doc technique V2 §5.2)
SEVERITY_TO_BASE_SCORE = {1: 0, 2: 1, 3: 2}

HIGH_RISK_SPECIALTIES = {"cardiologie", "psychiatrie"}
CHILD_AGE_GROUPS = {"enfant"}


def compute_urgency_score(extraction: dict) -> int:
    """
    Calcule un score 0-3 à partir du JSON extrait par IA1.
    Règle hybride : base sur severity + bonus âge/spécialité/durée.
    """
    severity = extraction.get("severity", 1)
    age_group = extraction.get("age_group", "inconnu")
    specialty = extraction.get("specialty_hint", "generaliste")
    duration = extraction.get("duration_days")

    score = SEVERITY_TO_BASE_SCORE.get(severity, 0)

    # Bonus : spécialité à risque (cardio, psy)
    if specialty in HIGH_RISK_SPECIALTIES:
        score += 1

    # Bonus : patient enfant
    if age_group in CHILD_AGE_GROUPS:
        score += 1

    # Malus : symptôme chronique (>7 jours) => moins urgent
    if duration is not None and duration > 7:
        score -= 1

    return max(0, min(3, score))


def redis_priority_score(created_at_unix: float, urgency_score: int) -> float:
    """Formule doc V2 §5.2 : score plus bas = plus prioritaire."""
    return created_at_unix - (urgency_score * 3600)


def run_ia2_on_results(input_path: str = "data/v2/ia1_results.json",
                        output_path: str = "data/v2/ia2_results.json"):
    with open(input_path, "r", encoding="utf-8") as f:
        results = json.load(f)

    print(f"⚡ IA2 — Calcul urgency_score sur {len(results)} entrées\n")

    for entry in results:
        extraction = entry["ia1_extraction"]
        score = compute_urgency_score(extraction)
        entry["ia2_urgency_score"] = score

        gt_score = entry["ground_truth"].get("urgency_score")
        match = "✅" if gt_score == score else "❌"
        print(f"  {match} {entry['patient_id']:<20} predicted={score} | ground_truth={gt_score}")

    # Accuracy
    matches = sum(1 for e in results
                  if e["ia2_urgency_score"] == e["ground_truth"].get("urgency_score"))
    accuracy = matches / len(results) * 100 if results else 0

    Path("data/v2").mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print(f"\n{'='*50}")
    print(f"📊 RÉSULTATS IA2")
    print(f"{'='*50}")
    print(f"Accuracy vs ground truth : {accuracy:.1f}%")
    print(f"✅ Résultats → {output_path}")

    return results


if __name__ == "__main__":
    run_ia2_on_results()
