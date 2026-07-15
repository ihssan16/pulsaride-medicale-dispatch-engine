"""
Simulator: Scenario Generator
Epic 2 — Story 2.3
"""

import json
import random

SEED = 42

SCENARIOS = [
    {
        "id": "nominal",
        "name": "Scénario Nominal",
        "description": "10 pros disponibles, 20 demandes/h, 95% disponibilité",
        "seed": SEED,
        "nb_professionals": 10,
        "nb_requests": 20,
        "refusal_rate": 0.05,
        "timeout_rate": 0.0,
        "unavailability_rate": 0.05,
        "expected_outcome": "Toutes les demandes servies, TTFA < 5s",
    },
    {
        "id": "peak_night",
        "name": "Pic de Nuit",
        "description": "2 pros disponibles, 80 demandes/h — forte sous-capacité",
        "seed": SEED + 1,
        "nb_professionals": 2,
        "nb_requests": 80,
        "refusal_rate": 0.10,
        "timeout_rate": 0.05,
        "unavailability_rate": 0.30,
        "expected_outcome": "File d'attente longue, dégradation mesurée",
    },
    {
        "id": "refusal_cascade",
        "name": "Refus en Cascade",
        "description": "30% des pros refusent systématiquement",
        "seed": SEED + 2,
        "nb_professionals": 10,
        "nb_requests": 30,
        "refusal_rate": 0.30,
        "timeout_rate": 0.10,
        "unavailability_rate": 0.10,
        "expected_outcome": "Ré-attributions multiples, TTR dégradé",
    },
    {
        "id": "no_availability",
        "name": "Aucune Disponibilité",
        "description": "Tous les pros sont BUSY au départ",
        "seed": SEED + 3,
        "nb_professionals": 5,
        "nb_requests": 10,
        "refusal_rate": 0.0,
        "timeout_rate": 0.0,
        "unavailability_rate": 1.0,
        "expected_outcome": "Toutes les demandes en FAILED ou attente longue",
    },
    {
        "id": "load_ramp",
        "name": "Montée en Charge",
        "description": "Demandes croissantes de 10/h à 200/h en 1h",
        "seed": SEED + 4,
        "nb_professionals": 10,
        "nb_requests": 200,
        "refusal_rate": 0.05,
        "timeout_rate": 0.05,
        "unavailability_rate": 0.10,
        "expected_outcome": "Identifier le point de rupture du moteur",
    },
    {
        "id": "semantic_affinity",
        "name": "Affinité Sémantique",
        "description": "Cas pédiatriques avec pros à spécialités mixtes",
        "seed": SEED + 5,
        "nb_professionals": 10,
        "nb_requests": 20,
        "refusal_rate": 0.05,
        "timeout_rate": 0.0,
        "unavailability_rate": 0.10,
        "expected_outcome": "S4 sélectionne meilleur pro vs S2 tag exact",
    },
]


def generate_scenarios(path: str = "scenarios/scenarios.json"):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(SCENARIOS, f, ensure_ascii=False, indent=2)
    print(f"✅ {len(SCENARIOS)} scénarios générés → {path}")
    print("\n📋 Scénarios créés :")
    for s in SCENARIOS:
        print(f"  [{s['id']}] {s['name']} — {s['description'][:50]}...")


if __name__ == "__main__":
    generate_scenarios()