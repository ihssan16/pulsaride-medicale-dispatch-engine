# VPS Deployment

This guide is for the supervisor demo/staging VPS deployment.

## Server Requirements

- Ubuntu 22.04 or 24.04
- Docker and Docker Compose plugin
- At least 4 GB RAM for the core stack
- At least 20 GB disk

The current VPS has enough capacity for the core stack: Spring Boot API,
PostgreSQL, Redis, and Kafka. The Darija AI sidecar is optional and depends on
model files being available on the VPS.

## Security Notes

- Do not commit `.env`.
- Rotate any password shared in chat after the first deployment.
- Only the API dashboard port should be public for a demo.
- PostgreSQL, Redis, Kafka, and Darija AI are bound to `127.0.0.1` in Compose.
- For a public production deployment, add Nginx/HTTPS and stronger secret
  management.

## Deploy

```bash
git clone https://github.com/ihssan16/pulsaride-medicale-dispatch-engine.git
cd pulsaride-medicale-dispatch-engine
git checkout develop
cp .env.example .env
scripts/deploy.sh
```

By default, `.env.example` runs the local deterministic AI mode:

```bash
AI_MODE=mock
AI_FALLBACK_ENABLED=true
```

## Verify

```bash
docker compose ps
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/v2/analytics/summary
```

Open from a browser:

```text
http://<server-ip>:8080/dashboard-v2.html
```

## Optional Darija AI Sidecar

Only enable this if the Darija Health NLP repository and model files are
present on the VPS:

```bash
AI_MODE=external
AI_EXTERNAL_URL=http://darija-ai:8000
DARIJA_HEALTH_NLP_DIR=/opt/darija-health-nlp
DARIJA_MODEL_DIR=/opt/darija-health-nlp/models
docker compose --profile ai up --build -d
```

The model directory must contain the runtime files used by the FastAPI service:

```text
config.json
model.safetensors
tokenizer.json
tokenizer_config.json
training_args.bin
```

If the sidecar is healthy but Pulsaride still returns `"mode": "mock"` from
`/ai/triage`, the API container was started with mock settings. Update `.env`
to `AI_MODE=external` and recreate the API container.

## Optional OpenAI Provider

OpenAI is supported only as an experimental provider. It is not free: API calls
can be billed according to model and token usage. Do not enable it on the VPS
unless billing, budget limits, and key rotation are handled explicitly.

## Useful Operations

```bash
docker compose logs -f api
docker compose logs -f kafka
docker compose down
docker compose up --build -d
```

After a successful first deployment, ask the VPS owner to rotate the root
password and prefer SSH keys for future access.
