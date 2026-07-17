# Pulsaride — Medical Dispatch Engine

Moteur de dispatch médical en temps réel connectant instantanément
patients et professionnels de santé disponibles.

## Stack technique
- **Backend** : Spring Boot (Java)
- **Temps réel** : Redis 7+
- **Persistance** : PostgreSQL 15+ avec pgvector
- **Simulation & Évaluation** : Python
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

### Tout exécuter
```bash
./scripts/run-all.sh
```

Ce script génère les données de simulation, lance les scénarios Python, compile le backend Spring Boot, puis démarre PostgreSQL, Redis et l'API.

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
mvn package
cd ..

# Lancer PostgreSQL + Redis + API
docker compose up --build
```

# Vérifier les conteneurs
```bash
docker compose ps
```

### Vérifier l'API
```bash
curl http://localhost:8080/health
curl http://localhost:8080/actuator/health
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
- `POST /ai/triage`

Les chemins historiques `/api/dispatch-requests` et `/api/professionals` restent aussi disponibles.

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

## Équipe
- Salmane Sossey 
- Ihssan Ben Labsir 

## Encadrant
M. Lyazid Salihi — Pulsaride Solutions
