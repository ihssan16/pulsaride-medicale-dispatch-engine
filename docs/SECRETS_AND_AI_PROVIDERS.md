# Secrets and AI Providers

## Immediate rule

Never commit API keys, model credentials, `.env` files, downloaded model weights,
or Drive export links that grant private access.

If an API key was pasted in chat, a screenshot, a ticket, or a public document,
treat it as exposed and rotate it before use.

## Local environment

Use `.env.example` as the template:

```bash
cp .env.example .env
```

Then fill `.env` locally. `.env` is ignored by Git.

Useful variables:

```text
AI_MODE=mock
AI_MODE=external
AI_EXTERNAL_URL=http://darija-ai:8000
AI_FALLBACK_ENABLED=true
DARIJA_HEALTH_NLP_DIR=../darija-health-nlp
DARIJA_MODEL_DIR=../darija-health-nlp/models
OPENAI_API_KEY=replace-me-after-rotation
```

## Current V2 recommendation

For this project, OpenAI should not replace the medical safety layer.

Recommended order:

1. Deterministic red-flag rules remain mandatory.
2. Darija Health NLP remains the preferred local Moroccan-language triage service.
3. OpenAI can be used later as an optional benchmark or fallback provider, using
   a server-side environment variable only.

This keeps the project explainable in the internship defense and avoids making
the demo depend on a paid external API.

## Secret scanning

Run before pushing:

```bash
python3 scripts/check_no_secrets.py
```

CI also runs this script on `main` and `develop`. It catches obvious OpenAI
`sk-...` keys in tracked and untracked non-ignored files. It is a guardrail,
not a replacement for careful secret handling.

## GitHub and VPS deployment

For GitHub Actions or a VPS:

- store keys in GitHub Actions secrets or the server environment;
- never put real values in `docker-compose.yml`;
- never put real values in `.env.example`;
- rotate keys after any accidental exposure;
- use project-level spend limits in the OpenAI dashboard before demos.
