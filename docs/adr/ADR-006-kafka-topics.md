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

## État runtime Spring Boot

La table `outbox_events` existe côté Spring Boot et enregistre déjà :

- `request.created.v1` lors de la création d'une demande ;
- `dispatch.proposed.v1` lors d'une proposition ;
- `dispatch.accepted.v1` lors d'une acceptation ;
- `dispatch.refused.v1` lors d'un refus ;
- `dispatch.timed-out.v1` lors d'un timeout ;
- `dispatch.closed.v1` lors de la clôture.

Ces lignes restent avec `published=false` tant que le publisher Kafka n'est pas
activé. En environnement Compose, `PULSARIDE_OUTBOX_PUBLISHER_ENABLED=true`
active un scheduler Spring Boot qui :

1. lit un batch d'événements non publiés ;
2. publie chaque enveloppe JSON sur le topic égal à `eventType` ;
3. utilise `aggregateId` comme clé Kafka ;
4. marque la ligne `published=true` uniquement après succès Kafka.

En cas d'indisponibilité Kafka, la ligne reste non publiée et sera retentée.
Les consumers V2 doivent quand même dédupliquer par `eventId`, car un crash
après envoi Kafka mais avant update SQL peut produire un doublon.

Le consumer Dispatch de `request.triaged.v1` est activable via
`PULSARIDE_TRIAGE_CONSUMER_ENABLED=true`. Son rôle en V2 est volontairement
limité :

- appliquer `urgencyScore` et `specialtyHint` sur une demande encore `PENDING` ;
- enregistrer une transition d'audit indiquant le modèle/confiance ;
- lancer le dispatch avec `S4` pour produire ensuite `dispatch.proposed.v1`.

## Déduplication consumer (V2-204)

Dispatch persiste les envelopes consommées dans `processed_events`, avec
`eventId` comme clé primaire. Le consumer réserve d'abord cet `eventId` dans la
même transaction, puis exécute le handler. Si le même message Kafka est livré
une deuxième fois, il est ignoré avant d'appeler le dispatch.

Outcomes enregistrés :

- `PROCESSED` : triage appliqué et dispatch lancé ;
- `SKIPPED_UNKNOWN_REQUEST` : demande absente localement, message non bloquant.

Si le handler échoue de façon inattendue, la transaction est rollbackée :
l'`eventId` n'est pas marqué comme traité et Kafka peut retenter.

## Retry, DLT et replay (V2-205)

Le runtime Spring Boot définit un `DefaultErrorHandler` Kafka explicite :

- erreurs non déterministes : retry avec `FixedBackOff` ;
- erreurs de payload/enveloppe invalides (`IllegalArgumentException`) : envoi
  direct en DLT, car retenter ne corrigera pas le message ;
- topic DLT : `<topic>.dlt` ;
- partition DLT : `0`, car les DLT sont single-partition.

Paramètres d'environnement :

- `PULSARIDE_KAFKA_RETRY_INTERVAL_MS` : délai entre retries, défaut `1000` ms ;
- `PULSARIDE_KAFKA_RETRY_MAX_ATTEMPTS` : retries après la première livraison,
  défaut `3`.

Replay manuel local :

```bash
MAX_MESSAGES=10 scripts/replay_dlt.sh request.triaged.v1
```

Le replay republie les records de `request.triaged.v1.dlt` vers
`request.triaged.v1`. La déduplication par `eventId` reste active pendant le
replay, donc un événement déjà consommé ne relance pas le dispatch.
