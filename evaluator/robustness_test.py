"""
Pulsaride — P4 Robustness & Load Tests
Tests de montée en charge et cas dégradés via l'API réelle
Owner: Ihssan Ben Labsir
"""

import json
import time
import random
import requests
import threading
from datetime import datetime
from pathlib import Path

BASE_URL = "http://localhost:8080"
SEED = 42
random.seed(SEED)

Path("reports").mkdir(exist_ok=True)

SPECIALTIES = ["pediatrie", "cardiologie", "generaliste", "orl", "dermatologie"]

def create_request(patient_id: str, urgency: int = 1) -> dict | None:
    try:
        r = requests.post(f"{BASE_URL}/requests", json={
            "patientId": patient_id,
            "patientText": f"Patient {patient_id} symptômes urgence {urgency}",
            "specialtyHint": random.choice(SPECIALTIES),
            "urgencyScore": urgency
        }, timeout=10)
        return r.json() if r.status_code in [200, 201] else None
    except:
        return None

def dispatch_and_resolve(req_id: str, accept_rate: float = 0.8) -> str:
    try:
        r = requests.post(f"{BASE_URL}/dispatch/next?strategy=S3", timeout=10)
        if r.status_code != 200:
            return "no_dispatch"
        data = r.json()
        if not data.get("assignedProfessionalId"):
            return "no_pro"
        time.sleep(0.1)
        if random.random() < accept_rate:
            requests.post(f"{BASE_URL}/dispatch/{req_id}/accept", timeout=5)
            time.sleep(0.05)
            requests.post(f"{BASE_URL}/dispatch/{req_id}/close", timeout=5)
            return "closed"
        else:
            requests.post(f"{BASE_URL}/dispatch/{req_id}/refuse", timeout=5)
            return "refused"
    except:
        return "error"

def get_metrics() -> dict:
    try:
        r = requests.get(f"{BASE_URL}/metrics/summary", timeout=5)
        return r.json()
    except:
        return {}

def run_scenario(name: str, nb_requests: int, nb_pros: int,
                 accept_rate: float, delay: float, urgency_range: tuple) -> dict:
    print(f"\n{'='*55}")
    print(f"🧪 Scénario : {name}")
    print(f"   {nb_requests} demandes · {nb_pros} pros · accept={accept_rate*100:.0f}%")
    print(f"{'='*55}")

    created = []
    errors = 0
    start_time = time.time()

    for i in range(nb_requests):
        urgency = random.randint(*urgency_range)
        req = create_request(f"{name}_p{i:03d}", urgency)
        if req:
            created.append(req)
        else:
            errors += 1
        time.sleep(delay)
        if (i + 1) % 10 == 0:
            print(f"   📋 {i+1}/{nb_requests} demandes créées...")

    creation_time = time.time() - start_time
    print(f"   ✅ {len(created)} créées en {creation_time:.1f}s ({errors} erreurs)")

    # Dispatcher toutes
    dispatch_start = time.time()
    results = {"closed": 0, "refused": 0, "no_pro": 0, "error": 0}

    for req in created:
        outcome = dispatch_and_resolve(req["id"], accept_rate)
        results[outcome] = results.get(outcome, 0) + 1
        time.sleep(delay * 0.5)

    dispatch_time = time.time() - dispatch_start
    total_time = time.time() - start_time

    metrics = get_metrics()

    result = {
        "scenario": name,
        "nb_requests": nb_requests,
        "nb_pros": nb_pros,
        "accept_rate": accept_rate,
        "created": len(created),
        "creation_errors": errors,
        "dispatch_results": results,
        "total_time_s": round(total_time, 2),
        "throughput_req_per_s": round(len(created) / total_time, 2),
        "api_metrics": metrics,
        "run_at": datetime.now().isoformat()
    }

    print(f"\n   📊 Résultats :")
    print(f"   Fermées    : {results.get('closed', 0)}")
    print(f"   Refusées   : {results.get('refused', 0)}")
    print(f"   Sans pro   : {results.get('no_pro', 0)}")
    print(f"   Débit      : {result['throughput_req_per_s']} req/s")
    print(f"   Service    : {metrics.get('serviceRatePct', '—')}%")
    print(f"   TTFA moy   : {metrics.get('avgTtfaMs', '—')} ms")
    print(f"   Gini       : {metrics.get('giniFairness', '—')}")

    return result


if __name__ == "__main__":
    print("🏥 Pulsaride — P4 Tests de Robustesse & Charge\n")

    # Vérifier que l'API tourne
    try:
        requests.get(f"{BASE_URL}/health", timeout=5)
        print("✅ API disponible\n")
    except:
        print("❌ API non disponible — lance docker compose up -d")
        exit(1)

    all_results = []

    # ─── SCÉNARIO 1 : Nominal (référence) ────────────────────────────
    r1 = run_scenario(
        name="nominal",
        nb_requests=20,
        nb_pros=20,
        accept_rate=0.85,
        delay=0.1,
        urgency_range=(1, 2)
    )
    all_results.append(r1)

    # ─── SCÉNARIO 2 : Pic de nuit ────────────────────────────────────
    r2 = run_scenario(
        name="peak_night",
        nb_requests=40,
        nb_pros=20,
        accept_rate=0.5,
        delay=0.05,
        urgency_range=(1, 3)
    )
    all_results.append(r2)

    # ─── SCÉNARIO 3 : Refus en cascade ───────────────────────────────
    r3 = run_scenario(
        name="refusal_cascade",
        nb_requests=30,
        nb_pros=20,
        accept_rate=0.2,
        delay=0.1,
        urgency_range=(1, 2)
    )
    all_results.append(r3)

    # ─── SCÉNARIO 4 : Montée en charge ───────────────────────────────
    r4 = run_scenario(
        name="load_ramp",
        nb_requests=80,
        nb_pros=20,
        accept_rate=0.75,
        delay=0.02,
        urgency_range=(0, 3)
    )
    all_results.append(r4)

    # ─── RAPPORT FINAL ───────────────────────────────────────────────
    print(f"\n{'='*55}")
    print("📊 RAPPORT P4 — Tests de Robustesse")
    print(f"{'='*55}")
    print(f"\n{'Scénario':<20} {'Req':>5} {'Débit':>8} {'Service%':>10} {'TTFA(ms)':>10} {'Gini':>6}")
    print("-" * 62)

    for r in all_results:
        m = r.get("api_metrics", {})
        print(f"{r['scenario']:<20} {r['nb_requests']:>5} "
              f"{r['throughput_req_per_s']:>7.2f}/s "
              f"{str(m.get('serviceRatePct', '—')):>10} "
              f"{str(m.get('avgTtfaMs', '—')):>10} "
              f"{str(m.get('giniFairness', '—')):>6}")

    # Sauvegarder
    with open("reports/robustness_results.json", "w") as f:
        json.dump(all_results, f, indent=2, ensure_ascii=False)
    print("\n✅ Résultats → reports/robustness_results.json")

    # Point de rupture
    print(f"\n🔍 Analyse du point de rupture :")
    load = next((r for r in all_results if r["scenario"] == "load_ramp"), None)
    if load:
        m = load.get("api_metrics", {})
        sr = m.get("serviceRatePct", 0)
        if sr and float(sr) < 95:
            print(f"   ⚠️  Dégradation détectée à {load['nb_requests']} req")
            print(f"   Service rate : {sr}% (cible > 95%)")
            print(f"   Débit max mesuré : {load['throughput_req_per_s']} req/s")
        else:
            print(f"   ✅ Pas de dégradation à {load['nb_requests']} req")
            print(f"   Débit : {load['throughput_req_per_s']} req/s")
