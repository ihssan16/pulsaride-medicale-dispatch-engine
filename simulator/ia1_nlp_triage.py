"""
Pulsaride V2 — IA1 : NLP Triage
Extraction structurée depuis texte libre patient via LLM local (Ollama/Phi3)
"""

import json
import re
import time
import requests
from pathlib import Path

OLLAMA_URL = "http://localhost:11434/api/generate"
MODEL = "phi3:mini"

SYSTEM_PROMPT = """Tu es un assistant de triage médical. Extrais les informations suivantes du texte patient et retourne UNIQUEMENT un JSON valide, sans aucun texte avant ou après, sans commentaire.

Schéma JSON exact attendu :
{"symptoms": ["symptome1", "symptome2"], "duration_days": 3, "severity": 2, "age_group": "adulte", "specialty_hint": "generaliste"}

Règles :
- severity : 1=léger, 2=modéré, 3=sévère (entier uniquement)
- age_group : "enfant", "adulte", "senior", ou "inconnu"
- specialty_hint : une spécialité parmi generaliste, pediatrie, cardiologie, dermatologie, psychiatrie, orl, gynecologie, ophtalmologie
- duration_days : nombre entier de jours, ou null si inconnu
- Réponds UNIQUEMENT avec le JSON, rien d'autre.

Texte patient : """


def call_ollama(text: str, timeout: int = 120) -> dict:
    """Appelle Ollama avec le texte patient et retourne le JSON parsé."""
    prompt = SYSTEM_PROMPT + text

    try:
        start = time.time()
        response = requests.post(OLLAMA_URL, json={
            "model": MODEL,
            "prompt": prompt,
            "stream": False,
            "options": {"temperature": 0.1}
        }, timeout=timeout)
        latency_ms = int((time.time() - start) * 1000)

        raw_output = response.json().get("response", "")
        parsed = extract_json(raw_output)

        if parsed:
            parsed["_latency_ms"] = latency_ms
            parsed["_extraction_ok"] = True
            return parsed
        else:
            return fallback_response(latency_ms)

    except Exception as e:
        print(f"  ⚠️ Erreur Ollama : {e}")
        return fallback_response(0)


def extract_json(text: str) -> dict | None:
    """Extrait le premier objet JSON valide trouvé dans le texte."""
    # Chercher le bloc JSON avec regex
    match = re.search(r'\{[^{}]*\}', text, re.DOTALL)
    if not match:
        return None
    try:
        data = json.loads(match.group())
        # Validation minimale
        if "specialty_hint" in data and "severity" in data:
            # Nettoyer severity si c'est une liste ou un float
            sev = data.get("severity", 1)
            if isinstance(sev, list):
                sev = sev[0] if sev else 1
            data["severity"] = int(sev) if isinstance(sev, (int, float)) else 1
            data["severity"] = max(1, min(3, data["severity"]))
            return data
        return None
    except (json.JSONDecodeError, ValueError, TypeError):
        return None


def fallback_response(latency_ms: int) -> dict:
    """Fallback si le JSON est invalide — règle documentée dans ADR-003."""
    return {
        "symptoms": [],
        "duration_days": None,
        "severity": 1,
        "age_group": "inconnu",
        "specialty_hint": "generaliste",
        "_latency_ms": latency_ms,
        "_extraction_ok": False
    }


def run_ia1_on_corpus(corpus_path: str = "data/v2/free_text_corpus.json",
                       output_path: str = "data/v2/ia1_results.json",
                       limit: int | None = None):
    """Fait tourner IA1 sur tout le corpus et sauvegarde les résultats."""

    with open(corpus_path, "r", encoding="utf-8") as f:
        corpus = json.load(f)

    if limit:
        corpus = corpus[:limit]

    print(f"🤖 IA1 — Traitement de {len(corpus)} textes via {MODEL}\n")

    results = []
    ok_count = 0
    latencies = []

    for i, entry in enumerate(corpus):
        text = entry["free_text"]
        extracted = call_ollama(text)

        is_ok = extracted.get("_extraction_ok", False)
        latency = extracted.get("_latency_ms", 0)
        ok_count += 1 if is_ok else 0
        latencies.append(latency)

        result = {
            "id": entry["id"],
            "patient_id": entry["patient_id"],
            "free_text": text,
            "ground_truth": entry["ground_truth"],
            "ia1_extraction": extracted,
        }
        results.append(result)

        status = "✅" if is_ok else "⚠️ fallback"
        print(f"  [{i+1}/{len(corpus)}] {status} | {latency}ms | "
              f"{extracted.get('specialty_hint','?')} | sev={extracted.get('severity','?')}")

    # Sauvegarder
    Path("data/v2").mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    # Stats
    extraction_rate = ok_count / len(corpus) * 100
    avg_latency = sum(latencies) / len(latencies) if latencies else 0
    p95_latency = sorted(latencies)[int(len(latencies) * 0.95)] if latencies else 0

    print(f"\n{'='*50}")
    print(f"📊 RÉSULTATS IA1")
    print(f"{'='*50}")
    print(f"Taux d'extraction OK : {extraction_rate:.1f}% (cible > 95%)")
    print(f"Latence moyenne      : {avg_latency:.0f} ms")
    print(f"Latence P95          : {p95_latency} ms (cible < 2000ms)")
    print(f"✅ Résultats → {output_path}")

    return results


if __name__ == "__main__":
    # Test rapide sur 10 textes d'abord
    run_ia1_on_corpus(limit=5)
