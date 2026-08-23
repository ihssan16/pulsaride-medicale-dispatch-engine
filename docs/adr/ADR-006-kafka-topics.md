# ADR-006 — Kafka event envelope, topic naming et delivery semantics

**Date :** Août 2026
**Statut :** Accepté
**Auteurs :** Ihssan Ben Labsir
**Story :** V2-201

## Décision — Mode KRaft (sans ZooKeeper)

Kafka déployé en mode KRaft (`apache/kafka:3.7.0`), broker+controller combinés
sur un seul nœud. Pas de Kafka UI en V2 initial (contrainte disque VM ~1GB
disponible) — visibilité via `kafka-topics.sh` / `kafka-console-consumer.sh`
en CLI, Kafka UI ajouté en P1 si l'espace le permet.

## Topics créés (catalogue officiel §5.2)

| Topic | Producer | Partitions | Clé | Rôle |
|-------|----------|-----------:|-----|------|
| request.created.v1 | Demand | 3 | requestId | Demande validée |
| request.triaged.v1 | AI Triage | 3 | requestId | Urgence, spécialités, confiance |
| triage.failed.v1 | AI Triage | 3 | requestId | Échec → fallback déterministe |
| dispatch.proposed.v1 | Dispatch | 3 | requestId | Proposition + deadline |
| dispatch.accepted.v1 | Dispatch | 3 | requestId | Assignation acceptée |
| dispatch.refused.v1 | Dispatch | 3 | requestId | Refus (catégorie, pas de texte libre) |
| dispatch.timed-out.v1 | Dispatch | 3 | requestId | Timeout + compteur retry |
| dispatch.closed.v1 | Dispatch | 3 | requestId | Fin de cycle de vie |
| availability.changed.v1 | Dispatch | 3 | professionalId | Changement statut/slot |

## Topics DLT (dead-letter)

| Topic | Partitions | Rôle |
|-------|-----------:|------|
| request.created.v1.dlt | 1 | Échecs consumers Demand → replay manuel |
| request.triaged.v1.dlt | 1 | Échecs consumers AI → replay manuel |
| dispatch.proposed.v1.dlt | 1 | Échecs consumers Dispatch → replay manuel |

## Convention de nommage

Noms avec points (`.`) suivant strictement le catalogue du doc technique V2 §5.2,
malgré l'avertissement Kafka sur les collisions de noms de métriques
(`topics with a period or underscore could collide`) — accepté car :
- Cohérence avec le contrat documenté et partagé avec Dev B
- Un seul topic par nom exact, pas de collision réelle dans notre cas
- Risque de collision concerne les métriques JMX agrégées, non bloquant en V2

## Configuration retenue

- Réplication factor = 1 (mode local Compose, pas de HA en V2 — cf doc §5.2)
- 3 partitions sur les topics lifecycle (ordering garanti par requestId comme clé)
- 1 partition sur les DLT (pas besoin d'ordering, juste stockage des échecs)

## Contrats JSON (V2-202)

Les schémas JSON de l'enveloppe d'event et des payloads par topic sont
versionnés dans `docs/events/schemas/`. Des exemples valides sont fournis dans
`docs/events/examples/` et validés par `scripts/validate_event_schemas.py`.

Règle de clé opérationnelle :
- les événements de cycle demande/dispatch utilisent `requestId` comme
  `aggregateId` et clé Kafka ;
- `availability.changed.v1` utilise `professionalId` comme `aggregateId` et clé
  Kafka.

## Prochaines étapes (V2-203, V2-204, V2-205)

- V2-203 : transactional outbox pour publication fiable depuis Demand/Dispatch
- V2-204 : déduplication consumer par eventId (constraint unique)
- V2-205 : retry + DLT + script de replay opérateur
