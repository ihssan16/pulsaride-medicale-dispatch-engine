# Sprint Reviews — Pulsaride Medical Dispatch Engine

**Équipe :** Ihssan Ben Labsir · Salmane Sossey  
**Encadrant :** M. Lyazid Salihi — Pulsaride Solutions  
**Méthodologie :** Agile Scrum — sprints de 1 semaine

---

## Sprint 1 — Cadrage & Conception

**Goal :** Définir le périmètre, modéliser le système et configurer l'environnement de travail.

### Planned
- Réunions de cadrage avec M. Salihi
- Configuration Jira (Epics, User Stories, tâches)
- Conception architecture globale V1
- Diagrammes : Use Case, FSM, séquence, modèle données
- Contrat d'interface Redis + ADR de périmètre

### Done ✅
- Réunions de cadrage avec M. Salihi (x2) — architecture V1 Simple validée
- Jira configuré : 7 Epics, 13 User Stories, tâches réparties
- Diagramme Use Case complet (draw.io)
- FSM cycle de vie d'une demande : PENDING → PROPOSED → ACCEPTED/REFUSED → CLOSED
- Diagramme de séquence flux complet
- Modèle de données PostgreSQL (4 entités)
- Contrat d'interface Redis (5 clés)
- ADR-001 périmètre + ADR-002 données

### Key Decisions
- V1 sans couche IA — évolution future hors périmètre
- Séparer profil professionnel et disponibilités (instruction M. Salihi)
- Stack : Spring Boot + Redis + PostgreSQL + Python simulateur
- Stage 2 mois → périmètre borné au MVP

### Demo
- Présentation des diagrammes de conception à M. Salihi
- Architecture V1 Simple validée

### Velocity
- Planned : 13 story points
- Completed : 13 story points ✅

---

## Sprint 2 — Simulation & Setup Infrastructure

**Goal :** Mettre en place l'infrastructure et livrer le simulateur Python complet.

### Planned
- Structure GitHub + docker-compose.yml
- Epic 1 : simulateur Python complet
- Epic 6 : infrastructure Docker

### Done ✅

**Ihssan — Simulator (Epic 1)**
- `generate_professionals.py` → 20 profils pros, seed=42
- `generate_requests.py` → 50 demandes patients simulées
- `generate_scenarios.py` → 6 scénarios paramétrables
- `simulate_behavior.py` → simulation complète avec métriques de base

**Salmane — Backend (Epic 2, 3, 4)**
- Spring Boot initialisé et compilé (Maven)
- FSM complète implémentée
- 4 stratégies de matching : S1, S2, S3, S4
- Redis connecté (file d'attente + registry + lock atomique)
- PostgreSQL avec Flyway migrations V1, V2, V3
- 13 tests unitaires et d'intégration passants

**Partagé — Infrastructure (Epic 6)**
- Structure GitHub créée (main + feature branch)
- `docker-compose.yml` : PostgreSQL 15 + Redis 7 + API

### Metrics (simulateur statique)
| Scénario | Service rate | TTFA moy |
|----------|-------------|----------|
| Nominal | 30.0% | 1 693 ms |
| Pic de nuit | 4.0% | 1 387 ms |
| Refus cascade | 13.33% | 1 566 ms |
| Aucune dispo | 0.0% | 1 009 ms |

### Impediments
- Environnement de développement hétérogène → résolu via Docker Compose

### Velocity
- Planned : 21 story points
- Completed : 21 story points ✅

---

## Sprint 3 — Intégration & Évaluation des Stratégies

**Goal :** Connecter le simulateur à l'API et comparer les 4 stratégies de dispatch.

### Planned
- Connexion simulateur Python → API Spring Boot
- Comparaison S1/S2/S3/S4 via l'API réelle
- Graphiques et rapport comparatif (Epic 5, 7)
- Documentation complète

### Done ✅

**Ihssan — Intégration & Évaluation (Epic 5, 7)**
- `api_client.py` → simulateur connecté à l'API (POST /requests, dispatch, accept, close)
- Comparaison S1/S2/S3/S4 avec vraies données API
- `run_priority_api_evaluation.py` → métriques live, rapport JSON, bar charts et radar chart
- Graphiques pushés dans `docs/evaluation/`

**Salmane — Backend (Epic 3, 4)**
- `GET /availability` + `GET /availability/specialties/{tag}`
- `POST /dispatch/next?strategy=` → dispatch prioritaire par urgencyScore
- `AvailabilityService` séparé du profil professionnel
- Mise à jour `api_client.py` pour dispatch prioritaire

**Partagé — Documentation**
- SPRINT_REVIEWS, EVALUATION_PROTOCOL, DEMO_SCRIPT
- ADR-001, ADR-002, AVAILABILITY_SERVICE
- Collection Postman complète
- README enrichi

### Metrics (API réelle — 20 demandes, seed=42)
| Stratégie | Service rate | TTFA (ms) | TTR (ms) | Gini |
|-----------|-------------|-----------|----------|------|
| S1 Round Robin | 100% | 4 924 | 5 037 | 0.54 |
| S2 Tag Exact | 90% | 3 184 | 3 265 | 0.43 |
| S3 Score Composite | 100% | 3 188 | 3 282 | 0.27 |
| S4 Lexical IA | 100% | 3 044 | 3 129 | 0.26 |

**Conclusion :** S1, S3 et S4 servent les 20 demandes; S2 expose la limite du matching exact. Le nouveau S1 round-robin valide la rotation, mais S4 obtient la meilleure latence moyenne et le meilleur Gini sur ce run.

### Demo
- Flux complet PENDING → PROPOSED → ACCEPTED → CLOSED démontré via curl
- Graphiques comparatifs S1/S2/S3/S4 présentés
- Radar chart prioritaire généré

### Velocity
- Planned : 18 story points
- Completed : 18 story points ✅

---

## Sprint 4 — Robustesse & Charge (P4) ✅

**Goal :** Mesurer la robustesse du moteur sous charge et documenter le point de rupture.

### Planned
- Tests de montée en charge via l'API réelle
- Scénarios dégradés : peak_night, refusal_cascade, load_ramp
- Mesurer le débit max et documenter la dégradation

### Done ✅

**Ihssan — Tests de robustesse (P4)**
- Première version des scénarios P4 et des graphiques de robustesse

**Salmane — Durcissement et métriques P4**
- Isolation PostgreSQL/Redis entre scénarios et vérification de toutes les réponses HTTP
- Charge concurrente 20/40/80/160 et résolution par l'ID réellement dispatché
- P95 TTFA/TTR, failure rate et MTTR dégradé ajoutés à l'API
- Rapport et graphiques générés directement depuis les résultats JSON

### Metrics P4 (API réelle)
| Scénario | Req | Closes/s | Service | TTFA P95 | TTR P95 |
|----------|-----|----------|---------|----------|---------|
| Nominal | 20 | 9,79 | 100% | 1 850 ms | 1 874 ms |
| Pic de nuit | 40 | 8,49 | 82,50% | 3 718 ms | 3 817 ms |
| Refus cascade | 30 | 3,84 | 36,67% | 2 026 ms | 2 900 ms |
| Charge 20 | 20 | 18,45 | 100% | 996 ms | 1 025 ms |
| Charge 40 | 40 | 12,74 | 90% | 2 801 ms | 2 904 ms |
| Charge 80 | 80 | 13,81 | 73,75% | 4 275 ms | 4 304 ms |
| Charge 160 | 160 | 9,80 | 72,50% | 11 287 ms | 11 339 ms |

**Charge durable validée :** 20 demandes · **Débit max durable :** 18,45 closes/s

**Premier niveau dégradé :** 40 demandes, car le service rate passe à 90% et des demandes restent `PENDING` dans la fenêtre de timeout du test.

### Demo
- Graphiques de dégradation sous charge présentés
- Point de rupture identifié et documenté

### Velocity
- Planned : 8 story points
- Completed : 8 story points ✅

---

## Backlog — À venir

| Item | Priorité | Sprint cible |
|------|----------|-------------|
| Rapport final de stage | 🔴 High | Sprint 5 |
| Préparation soutenance | 🔴 High | Sprint 5 |

## Sprint 5 — Dashboard temps réel (P5) ✅

**Goal :** Donner une lecture non-technique des KPIs V1 pendant la démo.

### Done ✅

- Dashboard Spring Boot statique à `/dashboard.html`
- Rafraîchissement automatique toutes les 5 secondes
- KPIs temps réel : service rate, TTFA/TTR P95, Gini, volumes de demandes
- Flux des demandes par statut
- Disponibilité par spécialité
- Charge et statut par professionnel
