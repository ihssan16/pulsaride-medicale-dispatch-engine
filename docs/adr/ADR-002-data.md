# ADR-002 — Choix base de données et contrat Redis

**Date :** Juillet 2026
**Statut :** Accepté
**Auteurs :** Ihssan Ben Labsir, Salmane Sossey

---

## Décision : PostgreSQL + Redis

### PostgreSQL 15+ avec pgvector
Pour la persistance et l'audit complet de toutes les transitions FSM.

#### Schéma

```sql
-- Demandes patients
CREATE TABLE dispatch_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    patient_id VARCHAR(50) NOT NULL,
    patient_text TEXT,
    specialty_hint VARCHAR(50),
    urgency_score INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',
    assigned_professional_id VARCHAR(50),
    assigned_professional_name VARCHAR(100),
    failure_reason TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    proposed_at TIMESTAMP,
    accepted_at TIMESTAMP,
    closed_at TIMESTAMP,
    ttfa_ms BIGINT,
    ttr_ms BIGINT
);

-- Professionnels de santé
CREATE TABLE professionals (
    id VARCHAR(50) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    specialty_tag VARCHAR(50) NOT NULL,
    profile_text TEXT,
    experience_years INTEGER,
    quota_max_per_hour INTEGER DEFAULT 6,
    consultations_today INTEGER DEFAULT 0,
    load FLOAT DEFAULT 0.0,
    status VARCHAR(20) DEFAULT 'AVAILABLE',
    created_at TIMESTAMP DEFAULT NOW()
);

-- Tentatives d'attribution
CREATE TABLE assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID REFERENCES dispatch_requests(id),
    professional_id VARCHAR(50) REFERENCES professionals(id),
    professional_name VARCHAR(100),
    strategy VARCHAR(10),
    outcome VARCHAR(20),
    created_at TIMESTAMP DEFAULT NOW()
);

-- Historique transitions FSM
CREATE TABLE state_transitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID REFERENCES dispatch_requests(id),
    from_status VARCHAR(20),
    to_status VARCHAR(20),
    reason TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);
```

---

### Redis 7+ — Contrat d'interface

| Clé | Type | Format / Valeur | TTL | Rôle |
|-----|------|----------------|-----|------|
| `dispatch:queue` | Sorted Set | member=request_id · score=timestamp-(urgency×3600) | Aucun | File d'attente priorisée |
| `pro:registry:{id}` | Hash | `{status, specialtyTag, load, updatedAt}` | 120s | État temps réel du pro · TTL détecte déconnexion |
| `dispatch:lock:{request_id}` | String | `"1"` · verrou atomique | 5s | Évite la double attribution |
| `dispatch:events` | Pub/Sub | JSON `{type, requestId, proId, status, timestamp}` | — | Canal événements WebSocket |

---

## Justification

- **PostgreSQL** → auditabilité complète, requêtes analytiques pour métriques, compatible pgvector pour V2
- **Redis** → sub-milliseconde pour file d'attente et états pros, TTL automatique pour détecter déconnexions
- **Séparation des rôles** : Redis = temps réel, PostgreSQL = persistance et analyse (instruction M. Salihi)

