"""
Pulsaride V2 — V2-404 : Pipeline IA complet branché sur l'outbox
IA1 (Ollama NLP) -> IA2 (règles urgence) -> V2-402 (safety floor) -> Outbox -> Kafka
"""

import json
import uuid
from datetime import datetime, timezone

from ia1_nlp_triage import call_ollama
from ia2_urgency_score import compute_urgency_score
from v2_402_safety_rules import run_safety_pipeline
from v2_203_outbox import init_db, save_triage_result_with_event, publish_pending_events

MODEL_VERSION = "phi3-mini-q4-v1"
RULE_VERSION = "v2402-r1"

CONFIDENCE_IF_EXTRACTION_OK = 0.80
CONFIDENCE_IF_FALLBACK = 0.30
CONFIDENCE_REVIEW_THRESHOLD = 0.50  # sous ce seuil -> requiresReview=true


def triage_request(request_id: str, free_text: str, correlation_id: str = None) -> dict:
    """
    Pipeline complet pour UNE demande patient :
    1. IA1 : extraction NLP via LLM local (avec fallback intégré)
    2. IA2 : score d'urgence par règles à partir de la sortie IA1
    3. V2-402 : plancher de sécurité red-flag (ne peut jamais être contourné)
    4. Écriture atomique résultat + event dans l'outbox (V2-203)

    Retourne le résultat complet + statut de publication.
    """
    print(f"\n🔄 Triage de {request_id}")
    print(f"   Texte : {free_text[:60]}...")

    # --- IA1 ---
    extraction = call_ollama(free_text)
    extraction_ok = extraction.get("_extraction_ok", False)
    print(f"   [IA1] extraction_ok={extraction_ok} | "
          f"specialty={extraction.get('specialty_hint')} | severity={extraction.get('severity')}")

    # --- IA2 ---
    model_urgency = compute_urgency_score(extraction)
    print(f"   [IA2] urgency_score (avant safety floor) = {model_urgency}")

    # --- V2-402 : safety floor (peut surclasser IA1/IA2, jamais l'inverse) ---
    safety_result = run_safety_pipeline(free_text, model_urgency)
    final_urgency = safety_result.get("final_urgency", model_urgency)
    safety_override = safety_result.get("safety_override", False)

    if safety_override:
        print(f"   [V2-402] ⚠️  SAFETY OVERRIDE : {model_urgency} -> {final_urgency} "
              f"(règles: {safety_result.get('triggered_rules')})")
    else:
        print(f"   [V2-402] Pas d'override, urgency final = {final_urgency}")

    # --- Confidence : honnête, dérivée de l'état réel du pipeline ---
    confidence = CONFIDENCE_IF_EXTRACTION_OK if extraction_ok else CONFIDENCE_IF_FALLBACK
    requires_review = confidence < CONFIDENCE_REVIEW_THRESHOLD or safety_override

    # --- Écriture atomique (métier + outbox, même transaction SQLite) ---
    correlation_id = correlation_id or str(uuid.uuid4())
    event = save_triage_result_with_event(
        request_id=request_id,
        free_text=free_text,
        urgency_score=final_urgency,
        specialty_hint=extraction.get("specialty_hint", "generaliste"),
        confidence=confidence,
        model_version=MODEL_VERSION,
        rule_version=RULE_VERSION,
        requires_review=requires_review,
        correlation_id=correlation_id,
    )

    print(f"   [Outbox] Event {event['eventId'][:8]}... écrit (non publié)")

    return {
        "request_id": request_id,
        "final_urgency": final_urgency,
        "specialty_hint": extraction.get("specialty_hint", "generaliste"),
        "confidence": confidence,
        "requires_review": requires_review,
        "safety_override": safety_override,
        "extraction_ok": extraction_ok,
        "event_id": event["eventId"],
    }


def run_pipeline_on_test_set(limit: int = 5):
    """Lance le pipeline complet sur quelques cas du set de test gelé (V2-401)."""
    with open("data/v2/frozen_eval_dataset.json", "r", encoding="utf-8") as f:
        frozen = json.load(f)

    test_cases = frozen["test"][:limit]

    print(f"🏥 V2-404 — Pipeline IA complet sur {len(test_cases)} cas du set de test gelé\n")
    print(f"   ModelVersion : {MODEL_VERSION}")
    print(f"   RuleVersion  : {RULE_VERSION}")

    init_db()
    results = []

    for entry in test_cases:
        result = triage_request(
            request_id=entry["patient_id"],
            free_text=entry["free_text"],
        )
        results.append(result)

    # Publication groupée vers Kafka
    print(f"\n📤 Publication de {len(results)} events vers Kafka...\n")
    publish_result = publish_pending_events()

    print(f"\n{'='*60}")
    print(f"📊 RÉSULTATS V2-404")
    print(f"{'='*60}")
    reviews = sum(1 for r in results if r["requires_review"])
    overrides = sum(1 for r in results if r["safety_override"])
    print(f"Cas traités          : {len(results)}")
    print(f"requiresReview=true  : {reviews}")
    print(f"safety_override      : {overrides}")
    print(f"Events publiés Kafka : {publish_result['published']}/{publish_result['total_pending']}")

    with open("data/v2/v2_404_pipeline_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"✅ Résultats → data/v2/v2_404_pipeline_results.json")

    return results


if __name__ == "__main__":
    run_pipeline_on_test_set(limit=5)
