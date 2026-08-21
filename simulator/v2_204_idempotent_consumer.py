"""
Pulsaride V2 — V2-204 : Idempotent Consumer
Garantit qu'un event dupliqué (redélivré par Kafka) ne crée jamais
d'effet de bord métier en double.
"""

import json
import sqlite3
import uuid
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = "data/v2/dispatch_consumer.db"


def get_connection():
    Path("data/v2").mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_db():
    """
    processed_events : table de déduplication — un event_id ne peut
    apparaître qu'une seule fois (contrainte UNIQUE).

    dispatch_projections : effet métier simulé (ce que produirait
    Dispatch Core en recevant un request.triaged.v1). Une seule ligne
    par requestId même si l'event arrive plusieurs fois.
    """
    conn = get_connection()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS processed_events (
            event_id TEXT PRIMARY KEY,
            event_type TEXT NOT NULL,
            aggregate_id TEXT NOT NULL,
            processed_at TEXT NOT NULL
        );

        CREATE TABLE IF NOT EXISTS dispatch_projections (
            request_id TEXT PRIMARY KEY,
            urgency_score INTEGER NOT NULL,
            specialty_hint TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'PENDING',
            created_at TEXT NOT NULL,
            update_count INTEGER NOT NULL DEFAULT 1
        );
    """)
    conn.commit()
    conn.close()
    print(f"✅ Base consumer initialisée → {DB_PATH}")


def consume_event(event: dict) -> dict:
    """
    Traite UN event de façon idempotente.

    Logique :
    1. Tenter d'insérer event_id dans processed_events (contrainte UNIQUE)
    2. Si l'insertion échoue (IntegrityError) → event déjà vu → NO-OP, on s'arrête là
    3. Si l'insertion réussit → c'est la première fois → on applique l'effet métier
    """
    conn = get_connection()

    try:
        # Étape 1 : tentative d'enregistrement — clé primaire fait le travail de dédup
        conn.execute(
            "INSERT INTO processed_events (event_id, event_type, aggregate_id, processed_at) "
            "VALUES (?, ?, ?, ?)",
            (event["eventId"], event["eventType"], event["aggregateId"],
             datetime.now(timezone.utc).isoformat())
        )

        # Si on arrive ici, c'est la PREMIÈRE fois qu'on voit cet event_id
        payload = event["payload"]
        request_id = payload["requestId"]

        existing = conn.execute(
            "SELECT * FROM dispatch_projections WHERE request_id = ?", (request_id,)
        ).fetchone()

        if existing is None:
            conn.execute("""
                INSERT INTO dispatch_projections
                (request_id, urgency_score, specialty_hint, status, created_at, update_count)
                VALUES (?, ?, ?, 'PENDING', ?, 1)
            """, (request_id, payload["urgencyScore"], payload["specialtyHint"],
                  datetime.now(timezone.utc).isoformat()))
            result = {"status": "processed", "action": "created", "request_id": request_id}
        else:
            # Cas rare mais possible : même requestId, event_id différent
            # (ex: deux triages successifs légitimes) — on met à jour sans dupliquer
            conn.execute("""
                UPDATE dispatch_projections
                SET urgency_score = ?, specialty_hint = ?, update_count = update_count + 1
                WHERE request_id = ?
            """, (payload["urgencyScore"], payload["specialtyHint"], request_id))
            result = {"status": "processed", "action": "updated", "request_id": request_id}

        conn.commit()

    except sqlite3.IntegrityError:
        # Étape 2 : event_id déjà présent → DUPLICATE → NO-OP garanti
        conn.rollback()
        result = {"status": "duplicate_skipped", "action": "none",
                  "event_id": event["eventId"]}

    finally:
        conn.close()

    return result


def get_projection_count(request_id: str) -> int:
    """Vérifie combien de lignes existent pour un requestId (doit toujours être 0 ou 1)."""
    conn = get_connection()
    count = conn.execute(
        "SELECT COUNT(*) as c FROM dispatch_projections WHERE request_id = ?", (request_id,)
    ).fetchone()["c"]
    conn.close()
    return count


def get_update_count(request_id: str) -> int:
    conn = get_connection()
    row = conn.execute(
        "SELECT update_count FROM dispatch_projections WHERE request_id = ?", (request_id,)
    ).fetchone()
    conn.close()
    return row["update_count"] if row else 0


def test_duplicate_delivery_scenario():
    """
    Simule EXACTEMENT le critère du doc :
    'Replaying the same event twice produces one triage result,
     assignment and metric record.'
    """
    print("\n" + "="*60)
    print("🧪 TEST V2-204 — Redélivrance dupliquée (at-least-once)")
    print("="*60)

    init_db()

    request_id = f"req_dup_test_{uuid.uuid4().hex[:8]}"
    event = {
        "eventId": str(uuid.uuid4()),
        "eventType": "request.triaged.v1",
        "aggregateId": request_id,
        "correlationId": str(uuid.uuid4()),
        "occurredAt": datetime.now(timezone.utc).isoformat(),
        "producer": "ai-triage-service",
        "schemaVersion": 1,
        "payload": {
            "requestId": request_id,
            "urgencyScore": 2,
            "specialtyHint": "pediatrie",
            "confidence": 0.87,
            "modelVersion": "phi3-mini-v1",
            "ruleVersion": "v2402-r1",
            "requiresReview": False
        }
    }

    print(f"\n[Livraison 1] Event {event['eventId'][:8]}... pour {request_id}")
    r1 = consume_event(event)
    print(f"              → {r1}")

    print(f"\n[Livraison 2] MÊME event redélivré par Kafka (simule un rebalance consumer)")
    r2 = consume_event(event)
    print(f"              → {r2}")

    print(f"\n[Livraison 3] MÊME event redélivré une 3e fois (pire cas)")
    r3 = consume_event(event)
    print(f"              → {r3}")

    projection_count = get_projection_count(request_id)
    update_count = get_update_count(request_id)

    print(f"\n{'='*60}")
    print(f"📊 RÉSULTATS V2-204")
    print(f"{'='*60}")
    print(f"Livraisons envoyées        : 3")
    print(f"Traitements réels appliqués : {sum(1 for r in [r1,r2,r3] if r['status']=='processed')}")
    print(f"Doublons détectés/ignorés  : {sum(1 for r in [r1,r2,r3] if r['status']=='duplicate_skipped')}")
    print(f"Lignes en projection (doit être 1) : {projection_count}")
    print(f"update_count (doit être 1)         : {update_count}")

    success = (projection_count == 1 and update_count == 1
               and r1["status"] == "processed"
               and r2["status"] == "duplicate_skipped"
               and r3["status"] == "duplicate_skipped")

    if success:
        print(f"\n✅ TEST RÉUSSI — 3 livraisons → 1 seul effet métier appliqué")
        print(f"   Critère doc satisfait : 'Replaying the same event twice")
        print(f"   produces one triage result, assignment and metric record.'")
    else:
        print(f"\n❌ ÉCHEC — idempotence non garantie")

    return success


if __name__ == "__main__":
    test_duplicate_delivery_scenario()
