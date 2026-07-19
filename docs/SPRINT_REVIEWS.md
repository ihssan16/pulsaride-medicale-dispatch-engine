# Sprint Reviews — Pulsaride Medical Dispatch Engine
**Équipe :** Ihssan Ben Labsir · Salmane Sossey
**Encadrant :** M. Lyazid Salihi — Pulsaride Solutions

---

## W1–W2 — Cadrage & Architecture ✅

**Période :** Semaine 1–2
**Responsable :** Ihssan Ben Labsir

### Done
- Réunions de cadrage avec M. Salihi (x2)
- Fiche descriptive de stage remplie et signée
- Jira configuré : 7 Epics, 13 User Stories, toutes les tâches réparties
- Dossier de conception complet :
  - Diagramme Use Case (draw.io)
  - FSM cycle de vie d'une demande
  - Diagramme de séquence flux complet
  - Modèle de données PostgreSQL (4 entités)
  - Contrat d'interface Redis (5 clés)
  - ADR de périmètre
- Architecture validée par M. Salihi : V1 Simple MVP

### Décisions clés
- V1 sans couche IA (couche IA = évolution future hors périmètre)
- Séparer profil professionnel et disponibilités (instruction M. Salihi)
- Stack : Spring Boot + Redis + PostgreSQL + Python simulateur

### Risques identifiés
- Stage 1 mois et demi vs offre 4 mois → périmètre borné au MVP
- Coéquipier absent aux 2 premières réunions → communication à améliorer

---

## W3 — Setup & Simulation ✅

**Période :** Semaine 3
**Responsable :** Ihssan Ben Labsir (simulateur) · Salmane Sossey (backend)

### Done — Ihssan
- Structure GitHub créée et pushée (main)
- `docker-compose.yml` : PostgreSQL 15 + Redis 7
- `simulator/generate_professionals.py` → 20 profils pros, seed=42
- `simulator/generate_requests.py` → 50 demandes patients simulées
- `simulator/generate_scenarios.py` → 6 scénarios paramétrables
- `simulator/simulate_behavior.py` → simulation complète avec métriques de base

### Done — Salmane
- Spring Boot initialisé et compilé (Maven)
- FSM complète : PENDING → PROPOSED → ACCEPTED/REFUSED/TIMEOUT → CLOSED
- 4 stratégies de matching : S1, S2, S3, S4
- Redis connecté (file d'attente + registry + lock atomique)
- PostgreSQL avec Flyway migrations (V1 + V2)
- Tests d'intégration
- Docker Compose complet avec backend

### Métriques simulateur (statique)
| Scénario | Service rate | TTFA moy |
|----------|-------------|----------|
| Nominal | 30.0% | 1693 ms |
| Pic de nuit | 4.0% | 1387 ms |
| Refus cascade | 13.33% | 1566 ms |
| Aucune dispo | 0.0% | 1009 ms |

---

## W4 — Intégration & Évaluation ✅

**Période :** Semaine 4
**Responsable :** Ihssan Ben Labsir

### Done
- Clone repo sur VM Ubuntu (VirtualBox + Docker)
- Compilation Spring Boot sur Ubuntu (`mvn clean package`)
- Lancement Docker Compose : PostgreSQL ✅ Redis ✅ API ✅
- Tests API manuels (curl) : flux complet PENDING → CLOSED validé
- `simulator/api_client.py` → simulateur connecté à l'API en temps réel
- Comparaison S1/S2/S3/S4 avec vraies données API
- `evaluator/comparison_report.py` → rapport textuel comparatif
- `evaluator/generate_charts.py` → graphiques bar chart + radar chart
- `docs/evaluation/` → graphiques pushés sur GitHub

### Métriques API réelles
| Stratégie | Service rate | TTFA (ms) | TTR (ms) | Gini |
|-----------|-------------|-----------|----------|------|
| S1 First Available | 34.15% | 13 944 | 9 919 | 0.25 |
| S2 Tag Exact | 41.30% | 11 394 | 8 230 | 0.33 |
| S3 Score Composite | 26.39% | 18 771 | 12 937 | **0.10** ✅ |
| S4 Lexical IA | **46.08%** | **9 842** | **7 350** | 0.25 |

### Conclusion W4
- S4 meilleure performance globale
- S3 meilleure équité (Gini 0.10 < cible 0.15 ✅)
- Flux complet validé de bout en bout

---

## W5 — À venir 🔲

### Planned
- P4 Tests de robustesse : peak_night et load_ramp via l'API
- Mesurer le point de rupture du moteur
- Collection Postman
- Rapport final de stage

