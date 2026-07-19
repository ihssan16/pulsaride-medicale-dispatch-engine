# ADR-001 — Périmètre du stage V1

**Date :** Juillet 2026
**Statut :** Accepté
**Auteurs :** Ihssan Ben Labsir, Salmane Sossey
**Validé par :** M. Lyazid Salihi (réunion de cadrage W1)

---

## Contexte

Stage de 1 mois et demi pour implémenter un moteur de dispatch médical
en temps réel pour Pulsaride Solutions. L'offre de stage prévoyait 4 mois
et une couche IA (NLP, embeddings, matching sémantique). Le périmètre
doit être borné pour livrer un MVP fonctionnel et évaluable.

---

## Décision

### Ce qu'on fait (IN)
- Simulateur Python : profils pros, demandes patients, 6 scénarios seed fixe
- Moteur de dispatch Spring Boot : FSM complète, 4 stratégies S1/S2/S3/S4
- Redis : file d'attente priorisée, registry pros temps réel
- PostgreSQL : persistance, audit FSM, métriques
- Évaluation : TTFA, TTR, service rate, Gini, comparaison stratégies
- Docker Compose : PostgreSQL + Redis + API

### Ce qu'on ne fait pas (OUT — évolutions futures)
- Interface patient ou professionnel (pas d'UI)
- Authentification JWT réelle
- Kafka / bus d'événements
- Embeddings pgvector (V2)
- Dashboard temps réel (si temps le permet — P5 optionnel)
- Gestion des comptes et calendriers professionnels
- Déploiement cloud

---

## Justification

M. Salihi lors de la réunion de cadrage :
> "Dans un premier temps il ne faut pas vraiment se casser la tête pour
> développer toute la partie gestion des clients et tout ça. L'objectif
> c'est de démontrer un flux simple et fonctionnel avant toute complexification."

---

## Conséquences

- Le simulateur joue le rôle des patients et professionnels (pas d'UI)
- Les tests sont reproductibles grâce aux seeds fixes
- La qualité algorithmique du moteur est l'objectif principal
- Le rapport final doit documenter honnêtement les limites du V1

