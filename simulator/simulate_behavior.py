"""
Simulator: Behavior Simulation (refusals, timeouts, disconnections)
Epic 2 — Story 2.2
"""

import json
import random
import uuid
from datetime import datetime, timedelta
from enum import Enum

SEED = 42
random.seed(SEED)


class RequestStatus(Enum):
    PENDING = "PENDING"
    PROPOSED = "PROPOSED"
    ACCEPTED = "ACCEPTED"
    REFUSED = "REFUSED"
    FAILED = "FAILED"
    CLOSED = "CLOSED"


class ProfessionalStatus(Enum):
    AVAILABLE = "AVAILABLE"
    PROPOSED = "PROPOSED"
    BUSY = "BUSY"
    BREAK = "BREAK"
    OFFLINE = "OFFLINE"


def load_json(path: str) -> list:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def simulate_scenario(scenario: dict, professionals: list, requests: list) -> dict:
    """
    Simule un scénario complet et retourne les résultats.
    """
    random.seed(scenario["seed"])

    # Copie locale pour ne pas modifier les originaux
    pros = [p.copy() for p in professionals[:scenario["nb_professionals"]]]
    reqs = [r.copy() for r in requests[:scenario["nb_requests"]]]

    results = []
    base_time = datetime.now()

    for i, req in enumerate(reqs):
        req_time = base_time + timedelta(seconds=i * 3)
        req["status"] = RequestStatus.PENDING.value
        req["created_at"] = req_time.isoformat()

        # Chercher un pro disponible
        available_pros = [
            p for p in pros
            if p["status"] == ProfessionalStatus.AVAILABLE.value
        ]

        # Cas : aucun pro disponible
        if not available_pros:
            req["status"] = RequestStatus.FAILED.value
            req["failure_reason"] = "No professional available"
            req["closed_at"] = (req_time + timedelta(seconds=30)).isoformat()
            req["ttfa_ms"] = None
            req["ttr_ms"] = 30000
            results.append(req)
            continue

        # Sélectionner un pro (S1 : premier disponible)
        selected_pro = available_pros[0]
        proposed_at = req_time + timedelta(seconds=random.uniform(0.5, 3.0))
        ttfa_ms = int((proposed_at - req_time).total_seconds() * 1000)

        req["status"] = RequestStatus.PROPOSED.value
        req["proposed_at"] = proposed_at.isoformat()
        req["ttfa_ms"] = ttfa_ms
        req["assigned_professional_id"] = selected_pro["id"]
        selected_pro["status"] = ProfessionalStatus.PROPOSED.value

        # Simuler refus ou timeout
        rand = random.random()

        if rand < scenario["timeout_rate"]:
            # Timeout — pro passe BREAK
            req["status"] = RequestStatus.REFUSED.value
            req["failure_reason"] = "Timeout (30s)"
            selected_pro["status"] = ProfessionalStatus.BREAK.value
            req["closed_at"] = (proposed_at + timedelta(seconds=30)).isoformat()
            req["ttr_ms"] = int((proposed_at + timedelta(seconds=30) - req_time).total_seconds() * 1000)

        elif rand < scenario["timeout_rate"] + scenario["refusal_rate"]:
            # Refus explicite — pro passe BREAK 2 min
            req["status"] = RequestStatus.REFUSED.value
            req["failure_reason"] = "Professional refused"
            selected_pro["status"] = ProfessionalStatus.BREAK.value
            refused_at = proposed_at + timedelta(seconds=random.uniform(2, 10))
            req["closed_at"] = refused_at.isoformat()
            req["ttr_ms"] = int((refused_at - req_time).total_seconds() * 1000)

        elif rand < scenario["timeout_rate"] + scenario["refusal_rate"] + scenario["unavailability_rate"]:
            # Pro se déconnecte
            req["status"] = RequestStatus.FAILED.value
            req["failure_reason"] = "Professional disconnected"
            selected_pro["status"] = ProfessionalStatus.OFFLINE.value
            req["closed_at"] = (proposed_at + timedelta(seconds=5)).isoformat()
            req["ttr_ms"] = int((proposed_at + timedelta(seconds=5) - req_time).total_seconds() * 1000)

        else:
            # Acceptation normale
            accepted_at = proposed_at + timedelta(seconds=random.uniform(2, 15))
            closed_at = accepted_at + timedelta(minutes=random.uniform(5, 20))
            req["status"] = RequestStatus.CLOSED.value
            req["accepted_at"] = accepted_at.isoformat()
            req["closed_at"] = closed_at.isoformat()
            req["ttr_ms"] = int((accepted_at - req_time).total_seconds() * 1000)

            selected_pro["status"] = ProfessionalStatus.BUSY.value
            selected_pro["consultations_today"] += 1
            selected_pro["load"] = round(
                selected_pro["consultations_today"] / selected_pro["quota_max_per_hour"], 2
            )

        results.append(req)

    return {
        "scenario_id": scenario["id"],
        "scenario_name": scenario["name"],
        "seed": scenario["seed"],
        "run_at": datetime.now().isoformat(),
        "nb_requests": len(results),
        "results": results,
    }


def compute_basic_metrics(run_result: dict) -> dict:
    """
    Calcule les métriques de base sur un run.
    """
    results = run_result["results"]
    total = len(results)

    accepted = [r for r in results if r["status"] == RequestStatus.CLOSED.value]
    refused = [r for r in results if r["status"] == RequestStatus.REFUSED.value]
    failed = [r for r in results if r["status"] == RequestStatus.FAILED.value]

    ttfa_list = [r["ttfa_ms"] for r in results if r.get("ttfa_ms") is not None]
    ttr_list = [r["ttr_ms"] for r in results if r.get("ttr_ms") is not None]

    avg_ttfa = round(sum(ttfa_list) / len(ttfa_list), 2) if ttfa_list else None
    avg_ttr = round(sum(ttr_list) / len(ttr_list), 2) if ttr_list else None
    service_rate = round(len(accepted) / total * 100, 2) if total > 0 else 0
    refusal_rate = round(len(refused) / total * 100, 2) if total > 0 else 0
    failure_rate = round(len(failed) / total * 100, 2) if total > 0 else 0

    return {
        "scenario_id": run_result["scenario_id"],
        "scenario_name": run_result["scenario_name"],
        "total_requests": total,
        "accepted": len(accepted),
        "refused": len(refused),
        "failed": len(failed),
        "service_rate_pct": service_rate,
        "refusal_rate_pct": refusal_rate,
        "failure_rate_pct": failure_rate,
        "avg_ttfa_ms": avg_ttfa,
        "avg_ttr_ms": avg_ttr,
    }


def save_results(run_result: dict, metrics: dict, scenario_id: str):
    run_path = f"data/run_{scenario_id}.json"
    metrics_path = f"data/metrics_{scenario_id}.json"

    with open(run_path, "w", encoding="utf-8") as f:
        json.dump(run_result, f, ensure_ascii=False, indent=2)

    with open(metrics_path, "w", encoding="utf-8") as f:
        json.dump(metrics, f, ensure_ascii=False, indent=2)

    print(f"  ✅ Résultats → {run_path}")
    print(f"  ✅ Métriques → {metrics_path}")


if __name__ == "__main__":
    # Charger les données
    professionals = load_json("data/professionals.json")
    requests = load_json("data/requests.json")
    scenarios = load_json("scenarios/scenarios.json")

    print("🚀 Simulation Pulsaride — Dispatch Engine V1\n")
    print("=" * 55)

    all_metrics = []

    for scenario in scenarios:
        print(f"\n📋 Scénario : {scenario['name']}")
        print(f"   {scenario['description']}")

        run_result = simulate_scenario(scenario, professionals, requests)
        metrics = compute_basic_metrics(run_result)
        save_results(run_result, metrics, scenario["id"])
        all_metrics.append(metrics)

        print(f"   Service rate : {metrics['service_rate_pct']}%")
        print(f"   TTFA moyen   : {metrics['avg_ttfa_ms']} ms")
        print(f"   TTR moyen    : {metrics['avg_ttr_ms']} ms")
        print(f"   Refus        : {metrics['refusal_rate_pct']}%")

    # Rapport global
    print("\n" + "=" * 55)
    print("📊 RAPPORT GLOBAL — Comparaison des scénarios\n")
    print(f"{'Scénario':<25} {'Service%':>9} {'TTFA(ms)':>10} {'TTR(ms)':>10} {'Refus%':>8}")
    print("-" * 65)
    for m in all_metrics:
        print(f"{m['scenario_name']:<25} {m['service_rate_pct']:>8}% "
              f"{str(m['avg_ttfa_ms']):>10} {str(m['avg_ttr_ms']):>10} "
              f"{m['refusal_rate_pct']:>7}%")

    # Sauvegarder rapport global
    with open("data/global_metrics.json", "w", encoding="utf-8") as f:
        json.dump(all_metrics, f, ensure_ascii=False, indent=2)
    print("\n✅ Rapport global → data/global_metrics.json")