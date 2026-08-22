"""
Pulsaride V2 — V2-203 : Transactional Outbox Pattern
Garantit qu'un event Kafka n'est jamais perdu même en cas de crash
entre l'écriture métier et la publication.
"""

import json
import sqlite3
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from contextlib import contextmanager

DB_PATH = "data/v2/outbox.db"


def get_connection():
    Path("data/v2").mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """Crée les tables métier + outbox (même DB = même transaction possible)."""
    conn = get_connection()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS triage_results (
            request_id TEXT PRIMARY KEY,
            free_text TEXT NOT NULL,
            urgency_score INTEGER NOT NULL,
            specialty_hint TEXT NOT NULL,
            confidence REAL,
            model_version TEXT NOT NULL,
            rule_version TEXT NOT NULL,
            requires_review INTEGER NOT NULL DEFAULT 0,
            created_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS outbox (
            event_id TEXT PRIMARY KEY,
            aggregate_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            payload TEXT NOT NULL,
            correlation_id TEXT NOT NULL,
            occurred_at TEXT NOT NULL,
            published INTEGER NOT NULL DEFAULT 0,
            published_at TEXT
        );

        CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
            ON outbox(published) WHERE published = 0;
    """)
    conn.commit()
    conn.close()
    print(f"✅ Base outbox initialisée → {DB_PATH}")


@contextmanager
def transaction():
    """Context manager garantissant l'atomicité écriture métier + outbox."""
    conn = get_connection()
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def build_event_envelope(event_type: str, aggregate_id: str,
                         correlation_id: str, payload: dict,
                         producer: str = "ai-triage-service",
                         schema_version: int = 1) -> dict:
    """Enveloppe conforme au format du doc technique V2 §5.1."""
    return {
        "eventId": str(uuid.uuid4()),
        "eventType": event_type,
        "aggregateId": aggregate_id,
        "correlationId": correlation_id,
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "producer": producer,
        "schemaVersion": schema_version,
        "payload": payload,
    }


def save_triage_result_with_event(request_id: str, free_text: str,
                                   urgency_score: int, specialty_hint: str,
                                   confidence: float, model_version: str,
                                   rule_version: str, requires_review: bool,
                                   correlation_id: str = None) -> dict:
    """
    ÉCRITURE ATOMIQUE : résultat triage + event outbox dans LA MÊME transaction.
    Si le process crash après cette fonction mais avant publish_pending_events(),
    l'event reste en base et sera publié au prochain passage du publisher.
    """
    correlation_id = correlation_id or str(uuid.uuid4())

    payload = {
        "requestId": request_id,
        "urgencyScore": urgency_score,
        "specialtyHint": specialty_hint,
        "confidence": confidence,
        "modelVersion": model_version,
        "ruleVersion": rule_version,
        "requiresReview": requires_review,
    }

    event = build_event_envelope(
        event_type="request.triaged.v1",
        aggregate_id=request_id,
        correlation_id=correlation_id,
        payload=payload,
    )

    with transaction() as conn:
        # 1. Écriture métier
        conn.execute("""
            INSERT OR REPLACE INTO triage_results
            (request_id, free_text, urgency_score, specialty_hint,
             confidence, model_version, rule_version, requires_review, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, (request_id, free_text, urgency_score, specialty_hint,
              confidence, model_version, rule_version, int(requires_review),
              datetime.now(timezone.utc).isoformat()))

        # 2. Écriture outbox — MÊME TRANSACTION
        conn.execute("""
            INSERT INTO outbox
            (event_id, aggregate_id, event_type, payload, correlation_id,
             occurred_at, published)
            VALUES (?, ?, ?, ?, ?, ?, 0)
        """, (event["eventId"], event["aggregateId"], event["eventType"],
              json.dumps(event), event["correlationId"], event["occurredAt"]))

    return event


def get_pending_events() -> list:
    conn = get_connection()
    rows = conn.execute(
        "SELECT * FROM outbox WHERE published = 0 ORDER BY occurred_at ASC"
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def mark_published(event_id: str):
    conn = get_connection()
    conn.execute(
        "UPDATE outbox SET published = 1, published_at = ? WHERE event_id = ?",
        (datetime.now(timezone.utc).isoformat(), event_id)
    )
    conn.commit()
    conn.close()


def publish_pending_events(kafka_bootstrap: str = "localhost:9092",
                            dry_run: bool = False) -> dict:
    """
    Publisher séparé de l'écriture métier — peut être relancé indépendamment.
    C'est CE découplage qui protège contre la perte d'event en cas de crash.
    """
    pending = get_pending_events()
    if not pending:
        return {"published": 0, "failed": 0, "total_pending": 0}

    print(f"📤 {len(pending)} event(s) en attente de publication")

    if dry_run:
        print("   (mode dry_run — pas d'envoi Kafka réel)")
        for e in pending:
            print(f"   [DRY] {e['event_type']} — {e['aggregate_id']}")
        return {"published": 0, "failed": 0, "total_pending": len(pending), "dry_run": True}

    from kafka import KafkaProducer
    from kafka.errors import KafkaError

    try:
        producer = KafkaProducer(
            bootstrap_servers=kafka_bootstrap,
            value_serializer=lambda v: json.dumps(v).encode("utf-8"),
            key_serializer=lambda k: k.encode("utf-8") if k else None,
            request_timeout_ms=10000,
        )
    except Exception as e:
        print(f"  ❌ Impossible de se connecter à Kafka : {e}")
        return {"published": 0, "failed": len(pending), "total_pending": len(pending), "error": str(e)}

    published, failed = 0, 0

    for e in pending:
        event_data = json.loads(e["payload"])
        try:
            future = producer.send(
                e["event_type"],
                key=e["aggregate_id"],
                value=event_data
            )
            future.get(timeout=10)
            mark_published(e["event_id"])
            published += 1
            print(f"  ✅ {e['event_type']} — {e['aggregate_id']} → Kafka")
        except KafkaError as ke:
            failed += 1
            print(f"  ❌ {e['event_type']} — {e['aggregate_id']} : {ke}")

    producer.flush()
    producer.close()

    return {"published": published, "failed": failed, "total_pending": len(pending)}


def test_crash_recovery_scenario(publish_after_restart: bool = False):
    """
    Simule le scénario critique du doc : crash entre le commit DB
    et l'envoi Kafka. Prouve qu'aucun event n'est perdu.

    Scénario :
    1. Écrire un résultat de triage (commit DB réussi)
    2. NE PAS appeler publish_pending_events() — simule un crash du process
       juste après le commit, avant que le publisher tourne
    3. Vérifier que l'event existe toujours en base avec published=0
    4. En mode CI, vérifier que l'event reste en attente et publiable.
    5. En mode Kafka réel, appeler publish_pending_events() après redémarrage.
    """
    print("\n" + "="*60)
    print("🧪 TEST DE CRASH — V2-203 critère d'acceptation")
    print("="*60)

    request_id = f"req_crash_test_{uuid.uuid4().hex[:8]}"

    print(f"\n[Étape 1] Écriture métier + outbox pour {request_id}")
    print("          (simule le service AI Triage juste avant un crash)")
    event = save_triage_result_with_event(
        request_id=request_id,
        free_text="Cas de test crash recovery",
        urgency_score=2,
        specialty_hint="generaliste",
        confidence=0.75,
        model_version="phi3-mini-v1",
        rule_version="v2402-r1",
        requires_review=False,
    )
    print(f"          ✅ Commit DB réussi, event {event['eventId'][:8]}... écrit en outbox")

    print(f"\n[Étape 2] 💥 CRASH SIMULÉ — le publisher ne tourne PAS")
    print("          (aucun appel à publish_pending_events() ici)")

    # Vérification post-crash : l'event doit être en base, non publié
    conn = get_connection()
    row = conn.execute(
        "SELECT * FROM outbox WHERE aggregate_id = ?", (request_id,)
    ).fetchone()
    conn.close()

    assert row is not None, "❌ ÉCHEC CRITIQUE : event perdu après crash simulé"
    assert row["published"] == 0, "❌ ÉCHEC : event marqué publié alors qu'il ne l'est pas"

    print(f"\n[Étape 3] Vérification après crash")
    print(f"          ✅ Event RETROUVÉ en base : published={row['published']}")
    print(f"          → Aucune perte de donnée malgré le crash simulé")

    print(f"\n[Étape 4] 🔄 Redémarrage — publisher relancé")
    if publish_after_restart:
        result = publish_pending_events()
    else:
        result = publish_pending_events(dry_run=True)

    conn = get_connection()
    row_after = conn.execute(
        "SELECT * FROM outbox WHERE aggregate_id = ?", (request_id,)
    ).fetchone()
    conn.close()

    print(f"\n[Étape 5] Vérification finale")
    if publish_after_restart and row_after and row_after["published"] == 1:
        print(f"          ✅ Event maintenant publié (published=1)")
        print(f"\n{'='*60}")
        print(f"✅ TEST DE CRASH RÉUSSI — critère d'acceptation V2-203 validé")
        print(f"   'A crash between database commit and Kafka send' est couvert")
        print(f"{'='*60}")
        return True
    if not publish_after_restart and row_after and row_after["published"] == 0:
        print(f"          ✅ Event toujours en attente (published=0)")
        print(f"          → Preuve CI : l'outbox conserve l'event après crash simulé")
        print(f"\n{'='*60}")
        print(f"✅ TEST DE CRASH RÉUSSI — aucun event perdu")
        print(f"   L'envoi Kafka réel reste couvert par --with-kafka")
        print(f"{'='*60}")
        return True

    print(f"          ❌ Event dans un état inattendu — vérifier outbox/Kafka")
    return False


def run_demo(publish_to_kafka: bool = True):
    print("🧪 V2-203 — Test du pattern Transactional Outbox\n")

    init_db()

    # Simuler 3 résultats de triage (comme le ferait ia1/ia2 en prod)
    test_cases = [
        {"request_id": "req_test_001", "free_text": "Fièvre enfant 3 jours",
         "urgency_score": 2, "specialty_hint": "pediatrie", "confidence": 0.87,
         "model_version": "phi3-mini-v1", "rule_version": "v2402-r1", "requires_review": False},
        {"request_id": "req_test_002", "free_text": "Douleur thoracique irradiante",
         "urgency_score": 3, "specialty_hint": "cardiologie", "confidence": 0.95,
         "model_version": "phi3-mini-v1", "rule_version": "v2402-r1", "requires_review": False},
        {"request_id": "req_test_003", "free_text": "Je ne sais pas ce que j'ai",
         "urgency_score": 0, "specialty_hint": "generaliste", "confidence": 0.31,
         "model_version": "phi3-mini-v1", "rule_version": "v2402-r1", "requires_review": True},
    ]

    print("📝 Écriture atomique (métier + outbox) pour 3 cas :\n")
    for case in test_cases:
        event = save_triage_result_with_event(**case)
        print(f"  ✅ {case['request_id']} → event {event['eventId'][:8]}... enregistré (non publié)")

    print(f"\n📋 Vérification : events en attente = {len(get_pending_events())}")

    if publish_to_kafka:
        print(f"\n📤 Publication vers Kafka (localhost:9092)...\n")
        result = publish_pending_events()
    else:
        print(f"\n📤 Vérification publication en dry-run...\n")
        result = publish_pending_events(dry_run=True)

    print(f"\n{'='*50}")
    print(f"📊 RÉSULTATS V2-203")
    print(f"{'='*50}")
    print(f"Publiés  : {result['published']}")
    print(f"Échecs   : {result['failed']}")
    print(f"Restants en attente : {len(get_pending_events())}")

    if publish_to_kafka and result['published'] == 3 and result['failed'] == 0:
        print(f"\n✅ Pattern outbox validé — 0 event perdu")
    elif not publish_to_kafka and result.get("dry_run"):
        print(f"\n✅ Pattern outbox vérifié en dry-run — events conservés pour publication")
    else:
        print(f"\n⚠️  Vérifier la connexion Kafka")


def main():
    publish_to_kafka = "--with-kafka" in sys.argv

    if "--crash-test" in sys.argv:
        init_db()
        ok = test_crash_recovery_scenario(publish_after_restart=publish_to_kafka)
        raise SystemExit(0 if ok else 1)

    run_demo(publish_to_kafka=publish_to_kafka)


if __name__ == "__main__":
    main()
