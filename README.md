# Pulsaride — Medical Dispatch Engine

Moteur de dispatch médical en temps réel connectant instantanément
patients et professionnels de santé disponibles.

## Stack technique
- **Backend** : Spring Boot (Java)
- **Temps réel** : Redis 7+
- **Persistance** : PostgreSQL 15+ avec pgvector
- **Simulation & Évaluation** : Python
- **IA V2** : microservice FastAPI Darija Health NLP optionnel
- **Infrastructure** : Docker Compose

## Structure du projet
```
pulsaride-medical-dispatch-engine/
├── backend/        # Spring Boot API / Dispatch Engine
├── simulator/      # Python: génération de données et scénarios
├── evaluator/      # Python: métriques et comparaison stratégies
├── embeddings/     # Scripts embeddings et pgvector
├── docs/           # Architecture, ADR, API contract, démo
├── postman/        # Collection API de démo
├── docker-compose.yml
└── README.md
```

## Démarrage rapide

### Prérequis
- Java 21
- Maven 3.9+
- Python 3.10+
- Docker + Docker Compose

Installer les dépendances Python d'évaluation une seule fois :
```bash
python3 -m pip install -r evaluator/requirements.txt
```

### Tout exécuter
```bash
./scripts/run-all.sh
```

Ce script génère les données de simulation, lance les scénarios Python, compile le backend Spring Boot, puis démarre PostgreSQL, Redis et l'API.

## Branches et CI

- `main` contient la version stable V1.
- `develop` sert aux prochains travaux, notamment les évolutions IA.
- GitHub Actions vérifie le backend Spring Boot avec Java 21 et compile les scripts Python du simulateur/evaluateur sur chaque push et pull request vers `main` ou `develop`.

Avant de démarrer une évolution importante, créer un tag de release depuis `main`
permet de garder un point de retour clair pour la V1.

### Exécution manuelle
```bash
# Générer les données et métriques de simulation
cd simulator
python3 generate_professionals.py
python3 generate_requests.py
python3 generate_scenarios.py
python3 simulate_behavior.py
cd ..

# Compiler et tester le backend
cd backend
mvn clean test
cd ..

# Lancer PostgreSQL + Redis + API
# L'image API compile le backend Spring Boot dans Docker.
docker compose up --build
```

```bash
# Vérifier les conteneurs
docker compose ps
```

### Lancer avec le service IA Darija

Le mode par défaut reste `mock`, donc l'API fonctionne sans modèle IA. Pour
tester V2 avec le modèle Darija Health NLP, cloner le projet voisin
`../darija-health-nlp`, placer le modèle dans
`../darija-health-nlp/models/transformer_MARBERT_specialty/`, puis lancer :

```bash
AI_PROVIDER=external docker compose --profile ai up --build
```

Documentation complète : `docs/V2_AI_TRIAGE_DARIJA.md`.
Gestion des clés et fournisseurs IA : `docs/SECRETS_AND_AI_PROVIDERS.md`.

### Lancer avec OpenAI optionnel

OpenAI est disponible comme provider expérimental. Comme pour Darija Health NLP,
il ne remplace pas les règles locales : après la réponse du provider IA,
Pulsaride applique toujours un plancher de sécurité déterministe pour les
signaux critiques. OpenAI n'est pas gratuit : les appels API peuvent être
facturés selon le modèle et le nombre de tokens, donc ce mode doit rester
optionnel avec budget/limites configurés avant une démonstration.

Dans `.env`, renseigner une clé rotatée :

```text
AI_PROVIDER=openai
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o-mini
```

Puis lancer :

```bash
docker compose up --build
```

### Vérifier l'API
```bash
curl http://localhost:8080/health
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v2/health
curl http://localhost:8080/professionals
curl http://localhost:8080/api/dispatch-requests
```

Créer et dispatcher une demande :
```bash
REQUEST_ID=$(curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient_demo",
    "patientText": "J ai des palpitations depuis deux jours.",
    "specialtyHint": "cardiologie",
    "urgencyScore": 3
  }' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}?strategy=S3"
```

## Endpoints principaux
- `GET /health`
- `POST /requests`
- `GET /requests/{id}`
- `POST /professionals`
- `GET /professionals`
- `PUT /professionals/{id}/status`
- `GET /availability` — synthèse temps réel des professionnels par statut et spécialité
- `GET /availability/specialties/{specialtyTag}` — disponibilité filtrée pour une spécialité
- `POST /dispatch/next?strategy=S1` — dispatche la prochaine demande `PENDING` par priorité (`urgencyScore` décroissant, puis ancienneté)
- `POST /dispatch/{requestId}?strategy=S1`
- `POST /dispatch/{requestId}?strategy=S2`
- `POST /dispatch/{requestId}?strategy=S3`
- `POST /dispatch/{requestId}?strategy=S4`
- `POST /dispatch/{requestId}/accept`
- `POST /dispatch/{requestId}/refuse`
- `POST /dispatch/{requestId}/timeout`
- `POST /dispatch/{requestId}/close`
- `GET /requests/{requestId}/assignments`
- `GET /requests/{requestId}/transitions`
- `GET /metrics/summary`
- `GET /api/v2/analytics/summary`
- `POST /ai/triage` — triage texte libre en mode `mock`, Darija Health NLP ou OpenAI avec plancher de sécurité local
- `POST /api/v2/requests/{id}/triage` — triage IA d'une demande stockée et publication `request.triaged.v1`
- `GET /dashboard.html`
- `GET /dashboard-v2.html`

Les chemins historiques `/api/dispatch-requests` et `/api/professionals` restent aussi disponibles.
Les chemins `/api/availability` et `/api/availability/specialties/{specialtyTag}` sont également exposés pour rester cohérents avec les anciens endpoints préfixés.
Les chemins publics V2 sont exposés sous `/api/v2/...` pour préparer le routage API Gateway. Le détail est dans `docs/API_CONTRACT.md`.

## Pipeline V2 event-driven

En mode Docker Compose, le flux V2 complet est activé :

1. `DemandService` crée une demande `PENDING` et écrit `request.created.v1`.
2. L'outbox publie l'event dans Kafka.
3. Le worker IA consomme `request.created.v1` et produit soit `request.triaged.v1`, soit `triage.failed.v1` si le provider IA échoue sans fallback.
4. Le consumer Dispatch consomme `request.triaged.v1`, applique les champs IA, puis lance le dispatch avec la stratégie `S4`.
5. Les events `dispatch.*` et `availability.changed.v1` alimentent le suivi analytics/dashboard.

Flags utiles :

```bash
PULSARIDE_OUTBOX_PUBLISHER_ENABLED=true
PULSARIDE_TRIAGE_WORKER_ENABLED=true
PULSARIDE_TRIAGE_CONSUMER_ENABLED=true
```

## Service de disponibilité

Le service de disponibilité expose l'état des slots séparés du profil professionnel :
- `AVAILABLE` : le slot peut recevoir une proposition.
- `RESERVED` : le slot est réservé pour une demande, juste avant la proposition.
- `BUSY` : la demande est acceptée, consultation en cours.
- `BREAK` : indisponible après refus ou timeout.
- `OFFLINE` : indisponible manuellement.

Exemples rapides :
```bash
curl http://localhost:8080/availability
curl http://localhost:8080/availability/specialties/cardiologie
curl -X PUT http://localhost:8080/professionals/pro_demo/status \
  -H "Content-Type: application/json" \
  -d '{ "status": "OFFLINE" }'
```

Le dispatch ne sélectionne que les slots `AVAILABLE`. Quand un slot est choisi, il est verrouillé dans Redis, sauvegardé sur la demande (`assignedSlotId`), puis la demande passe par une transition `RESERVED` avant `PROPOSED`. Après `accept`, le slot passe `BUSY`; après `close`, il redevient `AVAILABLE`. Après `refuse` ou `timeout`, il passe `BREAK` et la demande revient en file `PENDING`.

## Démo cycle de vie V1
```bash
REQUEST_ID=$(curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient_lifecycle",
    "patientText": "Mon enfant a de la fièvre depuis 2 jours.",
    "specialtyHint": "pediatrie",
    "urgencyScore": 2
  }' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}?strategy=S2"
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/accept"
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/close"
curl "http://localhost:8080/requests/${REQUEST_ID}/assignments"
curl "http://localhost:8080/requests/${REQUEST_ID}/transitions"
curl "http://localhost:8080/metrics/summary"
```

Refus ou timeout remettent la demande en `PENDING` et la replacent dans Redis :
```bash
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/refuse"
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/timeout"
```

Pour traiter la file par priorité au lieu de choisir manuellement un ID :
```bash
curl -X POST "http://localhost:8080/dispatch/next?strategy=S3"
```

## Évaluation — Comparaison des stratégies de dispatch

Le simulateur Python connecté à l'API compare les 4 stratégies sur 20 demandes
avec 20 professionnels simulés (seed fixe = 42). Les chiffres ci-dessous viennent
du cycle V1 actuel : file prioritaire, refus remis en file, et métriques lues
depuis l'API Spring Boot.

| Stratégie | Service rate | TTFA (ms) | TTR (ms) | Gini |
|-----------|-------------|-----------|----------|------|
| S1 — Round Robin | **100.0%** | 4 924 | 5 037 | 0.54 |
| S2 — Tag Exact | 90.0% | 3 184 | 3 265 | 0.43 |
| S3 — Score Composite | **100.0%** | 3 188 | 3 282 | 0.27 |
| S4 — Lexical IA | **100.0%** | **3 044** | **3 129** | **0.26** |

- **S1, S3 et S4** atteignent 100% de service rate sur le scénario nominal V1.
- **S2** expose volontairement la limite du matching exact : si la spécialité demandée n'a plus de professionnel `AVAILABLE`, certaines demandes échouent même si le pool global a encore de la capacité.
- **S1 Round Robin** valide la rotation entre professionnels, mais il est moins performant sur ce dataset que S3/S4 car il ne tient pas compte de l'affinité métier dans son choix.

Graphiques et rapport complet disponibles dans `docs/evaluation/`.

Le dashboard V1 est disponible dans le navigateur :
```bash
xdg-open http://localhost:8080/dashboard.html
```

Le dashboard V2 ajoute la lecture analytics combinee avec l'etat de publication
des events Kafka/outbox :
```bash
xdg-open http://localhost:8080/dashboard-v2.html
```
Il rafraîchit automatiquement les KPIs, le flux des demandes, la disponibilité
par spécialité et la charge par professionnel.

Scripts d'évaluation :
```bash
python3 evaluator/run_priority_api_evaluation.py
python3 evaluator/robustness_test.py --strategy S3
python3 evaluator/generate_robustness_charts.py
```

Le test de robustesse remet PostgreSQL et Redis à zéro avant chaque scénario,
charge 20 professionnels, vérifie toutes les réponses HTTP et utilise toujours
l'identifiant réellement retourné par `/dispatch/next`. Le dernier run isolé a
mesuré 100% de service jusqu'à 20 demandes soumises. La charge de 40 demandes
est le premier niveau dégradé : service rate à 90% avec des demandes restées
`PENDING` dans la fenêtre de timeout du test. À 160 demandes, le P95 TTFA atteint
11 287 ms et dépasse la cible de 5 secondes. Le débit durable maximal observé
est de 18,45 demandes closes par seconde.

## Équipe
- Salmane Sossey
- Ihssan Ben Labsir

## Encadrant
M. Lyazid Salihi — Pulsaride Solutions
