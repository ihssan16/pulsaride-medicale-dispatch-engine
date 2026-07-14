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
pulsaride-medical-dispatch-engine/
├── backend/        # Spring Boot API / Dispatch Engine
├── simulator/      # Python: génération de données et scénarios
├── evaluator/      # Python: métriques et comparaison stratégies
├── embeddings/     # Scripts embeddings et pgvector
├── docs/           # Architecture, ADR, API contract, démo
├── postman/        # Collection API de démo
├── docker-compose.yml
└── README.md

## Démarrage rapide
# Lancer PostgreSQL + Redis
docker-compose up -d

# Vérifier les conteneurs
docker-compose ps

## Équipe
- Salmane Sossey 
- Ihssan Ben Labsir 

## Encadrant
M. Lyazid Salihi — Pulsaride Solutions
