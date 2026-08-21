"""
Pulsaride V2 — V2-205 : Retry / DLT / Replay
Gestion des échecs de traitement : retry borné, bascule DLT, replay opérateur.
"""

import json
import sqlite3
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path

DB_PATH = "data/v2/dispatch_consumer.db"
MAX_RETRIES = 3
BACKOFF_BASE_SECONDS = 0.5  # court pour la démo, réel serait en secondes/minutes


def get_connection():
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    return conn


def init_retry_tables():
    conn = get_connection()
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS retry_attempts (
            event_id TEXT NOT NULL,
            attempt_number INTEGER NOT NULL,
            attempted_at TEXT NOT NULL,
            error_message TEXT,
            PRIMARY KEY (event_id, attempt_number)
        );

        CREATE TABLE IF NOT EXISTS dead_letter_events (
            event_id TEXT PRIMARY KEY,
            original_topic TEXT NOT NULL,
            dlt_topic TEXT NOT NULL,
            event_payload TEXT NOT NULL,
            total_attempts INTEGER NOT NULL,
            last_error TEXT NOT NULL,
            moved_to_dlt_at TEXT NOT NULL,
            replayed INTEGER NOT NULL DEFAULT 0,
            replayed_at TEXT
        );
    """)
    conn.commit()
    conn.close()
    print(f"✅ Tables retry/DLT initialisées")


def process_with_retry(event: dict, handler_fn, original_topic: str,
                       dlt_topic: str, max_retries: int = MAX_RETRIES) -> dict:
    """
    Traite un event avec retry borné + backoff exponentiel.
    Si tous les essais échouent, bascule l'event vers la DLT.
    """
    conn = get_connection()
    event_id = event["eventId"]
    last_error = None

    for attempt in range(1, max_retries + 1):
        try:
            handler_fn(event)  # peut lever une exception

            # Succès — pas besoin de logger les tentatives réussies en détail
            print(f"    ✅ Traité avec succès (tentative {attempt}/{max_retries})")
            conn.close()
            return {"status": "success", "attempts": attempt}

        except Exception as e:
            last_error = str(e)
            conn.execute(
                "INSERT OR REPLACE INTO retry_attempts (event_id, attempt_number, attempted_at, error_message) "
                "VALUES (?, ?, ?, ?)",
                (event_id, attempt, datetime.now(timezone.utc).isoformat(), last_error)
            )
            conn.commit()

            backoff = BACKOFF_BASE_SECONDS * (2 ** (attempt - 1))
            print(f"    ⚠️  Échec tentative {attempt}/{max_retries} : {last_error} "
                  f"(retry dans {backoff}s)")

            if attempt < max_retries:
                time.sleep(backoff)

    # Toutes les tentatives ont échoué → bascule DLT
    conn.execute("""
        INSERT OR REPLACE INTO dead_letter_events
        (event_id, original_topic, dlt_topic, event_payload, total_attempts,
         last_error, moved_to_dlt_at, replayed)
        VALUES (?, ?, ?, ?, ?, ?, ?, 0)
    """, (event_id, original_topic, dlt_topic, json.dumps(event),
          max_retries, last_error, datetime.now(timezone.utc).isoformat()))
    conn.commit()
    conn.close()

    print(f"    ❌ Épuisement des {max_retries} tentatives → basculé vers {dlt_topic}")
    return {"status": "moved_to_dlt", "attempts": max_retries, "dlt_topic": dlt_topic}


def get_dlt_events(replayed: bool = False) -> list:
    conn = get_connection()
    rows = conn.execute(
        "SELECT * FROM dead_letter_events WHERE replayed = ? ORDER BY moved_to_dlt_at ASC",
        (int(replayed),)
    ).fetchall()
    conn.close()
    return [dict(r) for r in rows]


def replay_dlt_event(event_id: str, handler_fn) -> dict:
    """
    Script opérateur : rejoue manuellement un event de la DLT
    (typiquement après avoir corrigé le bug qui causait l'échec).
    """
    conn = get_connection()
    row = conn.execute(
        "SELECT * FROM dead_letter_events WHERE event_id = ?", (event_id,)
    ).fetchone()

    if row is None:
        conn.close()
        return {"status": "not_found", "event_id": event_id}

    event = json.loads(row["event_payload"])

    try:
        handler_fn(event)
        conn.execute(
            "UPDATE dead_letter_events SET replayed = 1, replayed_at = ? WHERE event_id = ?",
            (datetime.now(timezone.utc).isoformat(), event_id)
        )
        conn.commit()
        conn.close()
        print(f"  ✅ Replay réussi pour {event_id[:8]}...")
        return {"status": "replayed_success", "event_id": event_id}

    except Exception as e:
        conn.close()
        print(f"  ❌ Replay échoué à nouveau pour {event_id[:8]}... : {e}")
        return {"status": "replayed_failed", "event_id": event_id, "error": str(e)}


def test_retry_dlt_replay_scenario():
    print("\n" + "="*60)
    print("🧪 TEST V2-205 — Retry / DLT / Replay")
    print("="*60)

    init_retry_tables()

    # Handler qui échoue toujours (simule un bug métier persistant)
    fail_count = {"n": 0}
    def always_failing_handler(event):
        fail_count["n"] += 1
        raise ValueError(f"Erreur métier simulée (appel #{fail_count['n']})")

    event_bad = {
        "eventId": str(uuid.uuid4()),
        "eventType": "request.triaged.v1",
        "aggregateId": f"req_dlt_test_{uuid.uuid4().hex[:8]}",
        "payload": {"requestId": "req_dlt_test", "urgencyScore": 1, "specialtyHint": "generaliste"}
    }

    print(f"\n[Scénario A] Event qui échoue TOUJOURS (bug métier persistant)")
    print(f"             eventId={event_bad['eventId'][:8]}...")
    result_a = process_with_retry(
        event_bad, always_failing_handler,
        original_topic="request.triaged.v1",
        dlt_topic="request.triaged.v1.dlt"
    )
    print(f"             Résultat : {result_a}")

    # Handler qui réussit après 2 échecs (simule un problème transitoire)
    transient_state = {"attempts": 0}
    def transient_failure_handler(event):
        transient_state["attempts"] += 1
        if transient_state["attempts"] < 2:
            raise ConnectionError("Timeout réseau transitoire")
        # 2e tentative : succès

    event_transient = {
        "eventId": str(uuid.uuid4()),
        "eventType": "request.triaged.v1",
        "aggregateId": f"req_transient_test_{uuid.uuid4().hex[:8]}",
        "payload": {"requestId": "req_transient_test", "urgencyScore": 2, "specialtyHint": "cardiologie"}
    }

    print(f"\n[Scénario B] Event avec échec TRANSITOIRE (réussit à la 2e tentative)")
    print(f"             eventId={event_transient['eventId'][:8]}...")
    result_b = process_with_retry(
        event_transient, transient_failure_handler,
        original_topic="request.triaged.v1",
        dlt_topic="request.triaged.v1.dlt"
    )
    print(f"             Résultat : {result_b}")

    # Vérifier la DLT
    dlt_pending = get_dlt_events(replayed=False)
    print(f"\n[Vérification DLT] {len(dlt_pending)} event(s) en attente de replay")
    for e in dlt_pending:
        print(f"    - {e['event_id'][:8]}... (topic origine: {e['original_topic']}, "
              f"tentatives: {e['total_attempts']})")

    # Simuler la correction du bug puis le replay opérateur
    print(f"\n[Replay opérateur] Le bug métier a été corrigé, on rejoue la DLT")
    def fixed_handler(event):
        pass  # le bug est corrigé, ça passe maintenant

    replay_results = []
    for e in dlt_pending:
        r = replay_dlt_event(e["event_id"], fixed_handler)
        replay_results.append(r)

    dlt_after_replay = get_dlt_events(replayed=False)

    print(f"\n{'='*60}")
    print(f"📊 RÉSULTATS V2-205")
    print(f"{'='*60}")
    print(f"Scénario A (échec permanent) : {result_a['status']} après {result_a['attempts']} tentatives")
    print(f"Scénario B (échec transitoire) : {result_b['status']} après {result_b['attempts']} tentatives")
    print(f"Events en DLT avant replay : {len(dlt_pending)}")
    print(f"Events en DLT après replay : {len(dlt_after_replay)}")

    success = (
        result_a["status"] == "moved_to_dlt"
        and result_b["status"] == "success"
        and result_b["attempts"] == 2
        and len(dlt_pending) == 1
        and len(dlt_after_replay) == 0
    )

    if success:
        print(f"\n✅ TEST RÉUSSI :")
        print(f"   - Échec permanent → DLT après {MAX_RETRIES} tentatives ✓")
        print(f"   - Échec transitoire → récupéré au retry #2, PAS mis en DLT ✓")
        print(f"   - Replay opérateur → DLT vidée après correction du bug ✓")
    else:
        print(f"\n❌ ÉCHEC — vérifier la logique retry/DLT")

    return success


if __name__ == "__main__":
    test_retry_dlt_replay_scenario()
