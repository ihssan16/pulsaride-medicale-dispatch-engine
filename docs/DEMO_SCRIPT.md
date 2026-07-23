# Script de Démo — Pulsaride Dispatch Engine V1

**Durée estimée :** 10-15 minutes
**Audience :** M. Lyazid Salihi (encadrant) · M. Hatim Guermah (encadrant ENSIAS)

---

## Prérequis

```bash
# Démarrer l'environnement
docker compose up -d
docker compose ps  # Vérifier PostgreSQL ✅ Redis ✅ API ✅

# Vérifier l'API
curl http://localhost:8080/health
```

---

## Étape 1 — Générer les données simulées (2 min)

```bash
cd simulator
python3 generate_professionals.py   # 20 pros avec seed fixe
python3 generate_requests.py        # 50 demandes patients
python3 generate_scenarios.py       # 6 scénarios de test
python3 simulate_behavior.py        # Simulation statique + métriques de base
```

**Message clé :** *"Le simulateur joue le rôle des patients et professionnels.
Seed fixe = résultats reproductibles à l'identique."*

---

## Étape 2 — Démontrer le flux complet (3 min)

```bash
# Créer un professionnel
curl -X POST http://localhost:8080/professionals \
  -H "Content-Type: application/json" \
  -d '{
    "id": "pro_demo",
    "name": "Dr. Demo Pédiatre",
    "specialtyTag": "pediatrie",
    "experienceYears": 10,
    "profileText": "Pédiatre avec 10 ans experience.",
    "quotaMaxPerHour": 6,
    "status": "AVAILABLE"
  }'

# Créer une demande patient
REQUEST_ID=$(curl -s -X POST http://localhost:8080/requests \
  -H "Content-Type: application/json" \
  -d '{
    "patientId": "patient_demo",
    "patientText": "Mon enfant a de la fièvre depuis 3 jours, 38.5°C.",
    "specialtyHint": "pediatrie",
    "urgencyScore": 2
  }' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')

echo "Request ID: $REQUEST_ID"

# Dispatcher → statut PROPOSED
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}?strategy=S4"

# Accepter → statut ACCEPTED (dans les 30s !)
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/accept"

# Clôturer → statut CLOSED
curl -X POST "http://localhost:8080/dispatch/${REQUEST_ID}/close"
```

**Message clé :** *"FSM déterministe : PENDING → PROPOSED → ACCEPTED → CLOSED.
Chaque transition est auditée en PostgreSQL."*

---

## Étape 3 — Connecter le simulateur à l'API (2 min)

```bash
cd simulator
python3 api_client.py
```

**Message clé :** *"Le simulateur envoie 20 demandes via POST /requests,
dispatche avec stratégie S3, accepte/refuse automatiquement,
puis collecte les métriques via GET /metrics/summary."*

---

## Étape 4 — Comparaison des 4 stratégies (3 min)

Montrer les graphiques générés :

```bash
python3 evaluator/run_priority_api_evaluation.py
```

Ouvrir `docs/evaluation/comparison_charts.png` et `radar_chart.png`.

| Stratégie | Service rate | TTFA | Gini |
|-----------|-------------|------|------|
| S1 First Available | 100% | 2 502 ms | **0.18** |
| S2 Tag Exact | 90% | 2 442 ms | 0.43 |
| S3 Score Composite | 100% | **2 395 ms** | 0.27 |
| S4 Lexical IA | 100% | 2 539 ms | 0.26 |

**Message clé :** *"S1, S3 et S4 ferment les 20 demandes du run live.
S2 montre la limite volontaire du matching strict et échoue deux demandes
lorsque leur spécialité n'a plus de slot disponible."*

---

## Étape 5 — Métriques live (1 min)

```bash
curl http://localhost:8080/metrics/summary
```

**Message clé :** *"Les métriques sont calculées en temps réel depuis
PostgreSQL : moyennes et P95 TTFA/TTR, service/failure/refusal rates,
MTTR après refus ou timeout, et Gini fairness."*

---

## Questions fréquentes

**"Pourquoi Redis et pas PostgreSQL pour tout ?"**
> Redis est sub-milliseconde pour la file d'attente temps réel.
> PostgreSQL pour l'audit et l'analyse. Les deux sont complémentaires.

**"Comment gérez-vous les race conditions ?"**
> Transaction atomique Redis (MULTI/EXEC) + lock sur dispatch:lock:{id}.
> Impossible d'attribuer la même demande deux fois.

**"Quelle est la limite du V1 ?"**
> Service rate < 95% car la base de données se remplit de demandes PENDING
> des sessions précédentes. En production, un reset entre runs serait ajouté.
