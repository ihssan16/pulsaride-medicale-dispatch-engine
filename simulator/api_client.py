"""
Connecte le simulateur Python à l'API Spring Boot
"""

import json
import time
import random
import requests
from pathlib import Path

BASE_URL = "http://localhost:8080"
SEED = 42
random.seed(SEED)


def load_json(path: str) -> list:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


# ─── PROFESSIONALS ────────────────────────────────────────────────────────────

def push_professionals(professionals: list) -> dict:
    """Envoie tous les professionnels à l'API."""
    results = {"success": 0, "failed": 0, "ids": []}

    print(f"\n📤 Envoi de {len(professionals)} professionnels vers l'API...")

    for pro in professionals:
        payload = {
            "id": pro["id"],
            "name": pro["name"],
            "specialtyTag": pro["specialty_tag"],
            "experienceYears": pro["experience_years"],
            "profileText": pro["profile_text"],
            "quotaMaxPerHour": pro["quota_max_per_hour"],
            "status": "AVAILABLE"
        }

        try:
            response = requests.post(
                f"{BASE_URL}/professionals",
                json=payload,
                timeout=5
            )
            if response.status_code in [200, 201]:
                results["success"] += 1
                results["ids"].append(pro["id"])
            else:
                # Pro déjà existant — on continue
                results["ids"].append(pro["id"])
        except requests.exceptions.RequestException as e:
            results["failed"] += 1
            print(f"  ❌ {pro['id']} — {e}")

    print(f"  ✅ {results['success']} professionnels envoyés")
    return results


# ─── REQUESTS ─────────────────────────────────────────────────────────────────

def push_requests(requests_data: list, delay: float = 0.5) -> list:
    """Envoie les demandes patients à l'API une par une avec délai."""
    created = []

    print(f"\n📤 Envoi de {len(requests_data)} demandes vers l'API...")

    for i, req in enumerate(requests_data):
        payload = {
            "patientId": req["patient_id"],
            "patientText": req["patient_text"],
            "specialtyHint": req["specialty_hint"],
            "urgencyScore": req["urgency_score"]
        }

        try:
            response = requests.post(
                f"{BASE_URL}/requests",
                json=payload,
                timeout=5
            )
            if response.status_code in [200, 201]:
                data = response.json()
                created.append(data)
                if (i + 1) % 10 == 0:
                    print(f"  📋 {i+1}/{len(requests_data)} demandes envoyées...")
            else:
                print(f"  ❌ Erreur {response.status_code} pour {req['patient_id']}")
        except requests.exceptions.RequestException as e:
            print(f"  ❌ {req['patient_id']} — {e}")

        time.sleep(delay)

    print(f"  ✅ {len(created)} demandes créées en base")
    return created


# ─── DISPATCH ────────────────────────────────────────────────────────────────

def dispatch_requests(created_requests: list, strategy: str = "S3",
                      accept_rate: float = 0.8, delay: float = 0.3) -> dict:
    """Dispatche chaque demande et simule accept/refuse."""
    results = {
        "dispatched": 0,
        "accepted": 0,
        "refused": 0,
        "failed": 0,
        "no_pro": 0
    }

    print(f"\n🚀 Dispatch de {len(created_requests)} demandes (stratégie {strategy})...")

    for i, req in enumerate(created_requests):
        req_id = req["id"]

        try:
            # Dispatcher
            dispatch_url = f"{BASE_URL}/dispatch/{req_id}"
            if strategy != "S3":
                dispatch_url += f"?strategy={strategy}"

            resp = requests.post(dispatch_url, timeout=5)

            if resp.status_code != 200:
                results["failed"] += 1
                continue

            data = resp.json()
            results["dispatched"] += 1

            if data.get("status") == "FAILED" or data.get("assignedProfessionalId") is None:
                results["no_pro"] += 1
                continue

            # Simuler accept ou refuse
            time.sleep(delay)

            if random.random() < accept_rate:
                # Accepter
                acc_resp = requests.post(
                    f"{BASE_URL}/dispatch/{req_id}/accept",
                    timeout=5
                )
                if acc_resp.status_code == 200:
                    # Fermer
                    time.sleep(random.uniform(0.1, 0.5))
                    requests.post(f"{BASE_URL}/dispatch/{req_id}/close", timeout=5)
                    results["accepted"] += 1
            else:
                # Refuser
                ref_resp = requests.post(
                    f"{BASE_URL}/dispatch/{req_id}/refuse",
                    timeout=5
                )
                if ref_resp.status_code == 200:
                    results["refused"] += 1

        except requests.exceptions.RequestException as e:
            results["failed"] += 1
            print(f"  ❌ {req_id} — {e}")

        if (i + 1) % 10 == 0:
            print(f"  🔄 {i+1}/{len(created_requests)} traitées...")

    return results


# ─── METRICS ─────────────────────────────────────────────────────────────────

def fetch_metrics() -> dict:
    """Récupère les métriques depuis l'API."""
    try:
        resp = requests.get(f"{BASE_URL}/metrics/summary", timeout=5)
        return resp.json()
    except Exception as e:
        print(f"❌ Impossible de récupérer les métriques : {e}")
        return {}


def print_metrics(metrics: dict, strategy: str):
    print(f"\n{'='*55}")
    print(f"📊 MÉTRIQUES — Stratégie {strategy}")
    print(f"{'='*55}")
    print(f"  Total demandes   : {metrics.get('totalRequests', '—')}")
    print(f"  Closed           : {metrics.get('closedRequests', '—')}")
    print(f"  Failed           : {metrics.get('failedRequests', '—')}")
    print(f"  Service rate     : {metrics.get('serviceRatePct', '—')}%")
    print(f"  TTFA moyen       : {metrics.get('avgTtfaMs', '—')} ms")
    print(f"  TTR moyen        : {metrics.get('avgTtrMs', '—')} ms")
    print(f"  Timeout rate     : {metrics.get('timeoutRatePct', '—')}%")
    print(f"  Gini fairness    : {metrics.get('giniFairness', '—')}")


# ─── MAIN ────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("🏥 Pulsaride — Simulateur connecté à l'API\n")

    # Charger les données
    professionals = load_json("data/professionals.json")
    requests_data = load_json("data/requests.json")

    # Étape 1 — Pousser les professionnels
    push_professionals(professionals)

    # Étape 2 — Pousser 20 demandes (pas toutes pour aller vite)
    created = push_requests(requests_data[:20], delay=0.1)

    # Étape 3 — Dispatcher avec stratégie S3
    results = dispatch_requests(created, strategy="S3",
                                accept_rate=0.85, delay=0.2)

    print(f"\n📋 Résultats dispatch :")
    print(f"  Dispatché  : {results['dispatched']}")
    print(f"  Accepté    : {results['accepted']}")
    print(f"  Refusé     : {results['refused']}")
    print(f"  Sans pro   : {results['no_pro']}")
    print(f"  Erreurs    : {results['failed']}")

    # Étape 4 — Métriques finales
    metrics = fetch_metrics()
    print_metrics(metrics, "S3")

    # Sauvegarder les métriques
    Path("data").mkdir(exist_ok=True)
    with open("data/api_metrics_S3.json", "w") as f:
        json.dump(metrics, f, indent=2)
    print(f"\n✅ Métriques sauvegardées → data/api_metrics_S3.json")
