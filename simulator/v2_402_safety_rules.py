"""
Pulsaride V2 — V2-402 : Deterministic preprocessing and safety rules
Red-flag rules qui priment sur le modèle IA (ne peuvent pas être dégradées)
"""

import json
import re
from pathlib import Path

# Règles red-flag : mots-clés → urgency minimum forcé
# Ces règles priment TOUJOURS sur la sortie du modèle (IA1/IA2)
RED_FLAG_RULES = [
    {
        "id": "RF001",
        "pattern": r"douleur.{0,20}(poitrine|thorax).{0,30}(bras|irradi)",
        "min_urgency": 3,
        "reason": "douleur thoracique irradiante — suspicion cardiaque"
    },
    {
        "id": "RF002",
        "pattern": r"(bébé|bebe|nourrisson|enfant).{0,20}\d{1,2}\s*(mois).{0,40}(40|39\.[5-9])",
        "min_urgency": 3,
        "reason": "fievre elevee nourrisson"
    },
    {
        "id": "RF003",
        "pattern": r"(n'arrive plus|difficulte|difficile).{0,15}respir",
        "min_urgency": 3,
        "reason": "detresse respiratoire"
    },
    {
        "id": "RF004",
        "pattern": r"(idées noires|envie de mourir|plus capable de continuer|suicid)",
        "min_urgency": 3,
        "reason": "risque suicidaire — priorité absolue"
    },
    {
        "id": "RF005",
        "pattern": r"(enfant|bébé|bebe).{0,20}(avalé|avale|ingéré|ingere).{0,20}(médicament|medicament|produit)",
        "min_urgency": 3,
        "reason": "intoxication pédiatrique"
    },
    {
        "id": "RF006",
        "pattern": r"(faiblesse|paralysie).{0,60}(visage|côté).{0,60}parler",
        "min_urgency": 3,
        "reason": "suspicion AVC"
    },
]

MAX_INPUT_LENGTH = 2000
MIN_INPUT_LENGTH = 3


def validate_input(text: str) -> dict:
    """Étape 1 — Validation déterministe avant tout traitement."""
    if not text or not isinstance(text, str):
        return {"valid": False, "reason": "empty_or_invalid_type"}

    text = text.strip()

    if len(text) < MIN_INPUT_LENGTH:
        return {"valid": False, "reason": "too_short"}

    if len(text) > MAX_INPUT_LENGTH:
        return {"valid": False, "reason": "too_long", "truncated_text": text[:MAX_INPUT_LENGTH]}

    return {"valid": True, "text": text}


def normalize_text(text: str) -> str:
    """Normalisation déterministe : minuscules, espaces multiples, accents conservés (FR)."""
    text = text.lower().strip()
    text = re.sub(r"\s+", " ", text)
    return text


def check_red_flags(text: str) -> list:
    """
    Vérifie tous les red-flags. Retourne la liste des règles déclenchées.
    Ces règles ne peuvent JAMAIS être dégradées par le modèle IA en aval.
    """
    normalized = normalize_text(text)
    triggered = []

    for rule in RED_FLAG_RULES:
        if re.search(rule["pattern"], normalized, re.IGNORECASE):
            triggered.append({
                "rule_id": rule["id"],
                "min_urgency": rule["min_urgency"],
                "reason": rule["reason"]
            })

    return triggered


def apply_safety_floor(model_urgency: int, red_flags: list) -> dict:
    """
    Applique le plancher de sécurité : le score final ne peut jamais
    être inférieur au max des red-flags déclenchés.
    """
    if not red_flags:
        return {
            "final_urgency": model_urgency,
            "safety_override": False,
            "triggered_rules": []
        }

    floor = max(rf["min_urgency"] for rf in red_flags)
    final = max(model_urgency, floor)

    return {
        "final_urgency": final,
        "safety_override": final != model_urgency,
        "triggered_rules": [rf["rule_id"] for rf in red_flags],
        "floor_applied": floor
    }


def run_safety_pipeline(text: str, model_urgency: int) -> dict:
    """Pipeline complet : validate → normalize → red-flag check → safety floor."""
    validation = validate_input(text)
    if not validation["valid"]:
        return {
            "status": "rejected",
            "reason": validation["reason"],
            "final_urgency": None
        }

    red_flags = check_red_flags(validation["text"])
    safety_result = apply_safety_floor(model_urgency, red_flags)

    return {
        "status": "processed",
        **safety_result
    }


def test_on_red_flag_suite(dataset_path: str = "data/v2/frozen_eval_dataset.json"):
    """Teste les règles sur la suite red-flag gelée (V2-401)."""
    with open(dataset_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    suite = data["red_flag_suite"]
    print(f"🛡️  V2-402 — Test des règles de sécurité sur {len(suite)} cas red-flag\n")

    passed = 0
    results = []

    for case in suite:
        # On simule un modèle qui SOUS-estime volontairement (pire cas)
        model_underestimate = 0
        result = run_safety_pipeline(case["free_text"], model_underestimate)

        expected = case["expected_min_urgency"]
        actual = result.get("final_urgency")
        ok = actual is not None and actual >= expected

        passed += 1 if ok else 0
        icon = "✅" if ok else "❌"

        print(f"  {icon} {case['id']:<15} attendu>={expected} obtenu={actual} "
              f"| règles={result.get('triggered_rules', [])}")

        results.append({
            "case_id": case["id"],
            "expected_min": expected,
            "actual": actual,
            "passed": ok,
            "triggered_rules": result.get("triggered_rules", [])
        })

    recall = passed / len(suite) * 100 if suite else 0

    print(f"\n{'='*50}")
    print(f"📊 RÉSULTATS V2-402 — Red-flag recall")
    print(f"{'='*50}")
    print(f"Recall (cible >= 90%) : {recall:.1f}%")

    Path("data/v2").mkdir(parents=True, exist_ok=True)
    with open("data/v2/v2_402_redflag_results.json", "w", encoding="utf-8") as f:
        json.dump({"recall_pct": recall, "results": results}, f, ensure_ascii=False, indent=2)

    print(f"✅ Résultats → data/v2/v2_402_redflag_results.json")

    return recall


if __name__ == "__main__":
    test_on_red_flag_suite()
