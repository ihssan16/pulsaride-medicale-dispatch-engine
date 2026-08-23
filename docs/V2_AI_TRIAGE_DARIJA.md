# V2 — Darija Health NLP Integration

## Objectif

V2 peut utiliser le projet `darija-health-nlp` comme microservice IA externe pour
analyser le texte libre patient en darija, arabe, français ou mélange des trois.
Pulsaride ne charge pas le modèle directement dans Spring Boot : l'API Java
appelle le endpoint FastAPI `/predict`, puis transforme la réponse en format
interne de dispatch.

## Projet externe

Repository :

```text
https://github.com/SalmaneSossey/darija-health-nlp
```

Chemin local conseillé :

```text
/home/salmane/projects/darija-health-nlp
```

Le modèle n'est pas versionné dans Git. Il doit rester dans Google Drive ou dans
un stockage d'artefacts, puis être copié localement avant le démarrage.

## Modèle attendu

Le dossier Google Drive trouvé est :

```text
transformer_MARBERT_specialty
```

Il doit être placé ici :

```text
/home/salmane/projects/darija-health-nlp/models/transformer_MARBERT_specialty/
```

Fichiers attendus dans ce dossier :

```text
config.json
model.safetensors
tokenizer.json
tokenizer_config.json
training_args.bin
```

Les checkpoints peuvent rester dans le dossier, mais le runtime utilise surtout
le modèle final et les fichiers tokenizer/config.

## Démarrage local du service IA seul

Depuis le projet `darija-health-nlp` :

```bash
docker compose up --build backend
```

Vérification :

```bash
curl http://localhost:8000/health

curl -X POST http://localhost:8000/predict \
  -H "Content-Type: application/json" \
  -d '{ "message": "kanhess b douleur f sdri w ma9aderch ntnefess" }'
```

## Démarrage avec Pulsaride

Depuis `pulsaride-medicale-dispatch-engine` :

```bash
AI_PROVIDER=external docker compose --profile ai up --build
```

Le profil `ai` ajoute le service `darija-ai`. Sans ce profil, Pulsaride démarre
comme avant en mode `mock`.

Si le conteneur `darija-ai` est healthy mais que `/ai/triage` répond encore avec
`"mode": "mock"`, le modèle Darija n'est pas utilisé par l'API Spring Boot. Dans
ce cas, vérifier `.env` et redémarrer l'API :

```bash
AI_MODE=external
AI_EXTERNAL_URL=http://darija-ai:8000
AI_FALLBACK_ENABLED=true

docker compose --profile ai up -d api
```

Le service Darija seul peut donc fonctionner correctement pendant que Pulsaride
reste en mock si `AI_MODE`/`AI_PROVIDER` n'a pas été changé au moment du
démarrage du conteneur `api`.

Variables utiles :

```text
AI_PROVIDER=mock|external|darija|openai
AI_MODE=mock|external|openai
AI_EXTERNAL_URL=http://darija-ai:8000
AI_FALLBACK_ENABLED=true|false
DARIJA_HEALTH_NLP_DIR=../darija-health-nlp
DARIJA_MODEL_DIR=../darija-health-nlp/models
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o-mini
```

Ports locaux optionnels :

```text
API_HOST_PORT=8080
POSTGRES_HOST_PORT=5432
REDIS_HOST_PORT=6379
KAFKA_HOST_PORT=9092
DARIJA_AI_HOST_PORT=8000
```

Ces variables sont utiles si Docker Desktop refuse d'exposer un port déjà occupé
ou bloqué côté machine hôte. Par exemple, on peut mettre
`POSTGRES_HOST_PORT=15432` sans changer la connexion interne de l'API, qui reste
`postgres:5432` dans le réseau Docker.

## Contrat entre les deux services

Pulsaride envoie :

```json
{
  "message": "kanhess b douleur f sdri w ma9aderch ntnefess"
}
```

Darija Health NLP retourne notamment :

```json
{
  "predicted_specialty": "Cardiology",
  "specialty_confidence": 0.91,
  "urgency": "high",
  "urgency_reason": "Chest pain red flag detected.",
  "symptoms": ["chest_pain"]
}
```

Pulsaride convertit ensuite :

```text
Cardiology -> cardiologie
high -> urgencyScore 3
medium -> urgencyScore 2
low -> urgencyScore 1
unknown -> urgencyScore 0
```

Le endpoint Pulsaride reste :

```bash
curl -X POST http://localhost:8080/ai/triage \
  -H "Content-Type: application/json" \
  -d '{ "text": "kanhess b douleur f sdri w ma9aderch ntnefess" }'
```

La réponse indique la source :

```json
{
  "mode": "external",
  "sourceModel": "darija-health-nlp",
  "specialtyHint": "cardiologie",
  "urgencyScore": 3,
  "confidence": 0.91
}
```

## Fallback

Si `AI_MODE=external` mais que le service FastAPI est indisponible :

- avec `AI_FALLBACK_ENABLED=true`, Pulsaride revient aux règles locales et met
  `mode=external-fallback` ;
- avec `AI_FALLBACK_ENABLED=false`, Pulsaride retourne une erreur, utile pour
  détecter les problèmes en environnement de test strict.

Ce fallback permet de garder les démonstrations V1/V2 exécutables même sans GPU,
sans RAM suffisante ou sans modèle téléchargé.

## Provider OpenAI optionnel

OpenAI est maintenant disponible comme provider expérimental :

```bash
AI_PROVIDER=openai docker compose up --build
```

Le backend appelle l'API OpenAI Responses et demande une sortie JSON structurée.
Ensuite, il applique les règles locales comme plancher de sécurité. Exemple :
si le provider OpenAI retourne `urgencyScore=0` pour une douleur thoracique, les
règles locales peuvent remonter la réponse à `urgencyScore=3` avec
`mode=openai+safety-floor`.

Important : OpenAI n'est pas gratuit. Chaque appel API peut être facturé selon
le modèle choisi et la consommation de tokens. Pour ce projet, OpenAI doit rester
un provider optionnel de benchmark/fallback, avec clé côté serveur, budget limité
et aucune dépendance obligatoire pour la soutenance.

La trajectoire recommandée reste :

1. règles red-flag déterministes obligatoires ;
2. Darija Health NLP pour la valeur locale/multilingue ;
3. OpenAI pour benchmark, fallback ou comparaison contrôlée.

Les clés doivent être stockées dans `.env`, GitHub Secrets ou les variables
d'environnement du VPS, jamais dans Git.

Voir `docs/SECRETS_AND_AI_PROVIDERS.md`.
