# ADR-003 — Périmètre V2 IA et protocole d'évaluation

**Date :** Août 2026
**Statut :** Accepté
**Stories :** V2-101, V2-401, V2-402, V2-403

## Décision

V2 ajoute une couche d'aide au dispatch basée sur du texte libre patient, des
règles de sécurité déterministes et un matching sémantique léger. Le moteur ne
fait pas de diagnostic médical : il assiste la priorisation et le classement des
professionnels, avec des garde-fous explicables.

Le périmètre V2 validé est :

- IA1 : extraction NLP depuis le texte patient.
- IA2 : calcul d'un score d'urgence.
- IA3 : matching sémantique entre demande et profils professionnels.
- V2-402 : règles red-flag déterministes, prioritaires sur le modèle.
- V2-403 : benchmark comparatif contre les stratégies V1.

Les décisions sont volontairement mesurables : chaque choix IA doit produire un
résultat, une limite observable et une explication exploitable en soutenance.

## Mise à jour — Intégration Darija Health NLP

Après revue du projet `darija-health-nlp`, V2 retient une trajectoire plus
réaliste pour IA1 : exposer le modèle Darija/MARBERT dans un microservice FastAPI
séparé, puis appeler ce service depuis Spring Boot. Cette option évite de charger
un modèle lourd dans l'API Java et permet de garder un fallback local lorsque le
service IA ou le modèle est indisponible.

Le dossier modèle attendu est `models/transformer_MARBERT_specialty/` dans le
projet Darija. Il contient notamment `model.safetensors`, `config.json` et les
fichiers tokenizer. Les poids ne sont pas versionnés dans Git.

Le endpoint Pulsaride `/ai/triage` accepte désormais trois familles de modes :

- `AI_PROVIDER=mock` ou `AI_MODE=mock` : règles locales déterministes, utilisables sans modèle.
- `AI_PROVIDER=external|darija` ou `AI_MODE=external` : appel HTTP vers Darija Health NLP `/predict`, avec
  conversion des spécialités et urgences vers les tags internes Pulsaride, puis
  application du plancher de sécurité local.
- `AI_PROVIDER=openai` ou `AI_MODE=openai` : appel OpenAI Responses API avec
  sortie JSON structurée, puis application du plancher de sécurité local pour
  empêcher une sous-estimation des red flags.

## Résultats obtenus — Run complet (50 entrées, seed=42)

### IA1 — NLP triage (Ollama phi3:mini, local)
| Run | Taille échantillon | Extraction OK | Latence moy. | Latence P95 |
|-----|--------------------|--------------:|---------------|---------------|
| Test initial | 5 | 40.0% | 26 805 ms | 81 857 ms |
| Run complet | 50 | 10.0% | 5 154 ms | 37 510 ms |

**Cause de la chute (5→50) :** le service Ollama (`llama-server`) a crashé en cours de run par manque de RAM sur la VM (3.3GB total, contre ~2.7GB déjà consommés par le modèle chargé). Les 10 premières requêtes ont réussi, les 40 suivantes sont tombées en `Connection refused` → fallback automatique déclenché (comportement attendu, cf. §3.2 mitigation R1).

**Risque R1 du doc technique confirmé en pratique**, pas seulement anticipé : *"Latence LLM > 2s → pipeline bloquant"* — ici aggravé en crash mémoire sur infrastructure contrainte (VM 20GB disque / 3.3GB RAM).

### IA2 — Score d'urgence (règles)
| Run | Accuracy vs ground truth |
|-----|---------------------------|
| Test initial (5) | 100.0% |
| Run complet (50) | 30.0% |

**Cause de la chute :** effet de cascade — IA2 consomme la sortie d'IA1. Sur les 45 entrées tombées en fallback IA1 (`severity=1, specialty_hint=generaliste`), IA2 calcule mécaniquement `urgency_score=0`, ce qui casse l'accuracy dès que le ground truth attend un score différent.

### IA3 — Matching sémantique (TF-IDF, fallback léger)
| Run | Pertinence matching (specialty exact) |
|-----|----------------------------------------|
| Test initial (5) | 20.0% |
| Run complet (50) | 26.0% |

**Choix technique documenté :** l'architecture initiale prévoyait sentence-transformers + PyTorch (embeddings 768 dims). Installation impossible sur la VM (20GB disque, ~700MB+ requis pour torch+dépendances, plusieurs échecs `[Errno 28] No space left on device`). Bascule sur **TF-IDF + cosine similarity (scikit-learn)** — alternative légère, aucune dépendance lourde, cohérente avec le risque R7 du doc technique (*"pgvector non disponible en test → alternative calcul cosinus sans SQL"*), étendue ici au choix du modèle d'embedding lui-même.

**Limite connue de TF-IDF vs sentence-transformers :** correspondance purement lexicale (mots partagés), pas de compréhension sémantique réelle — un texte "douleur thoracique" ne matchera un profil "cardiologue" que s'ils partagent des mots, pas des concepts proches. Explique en partie la pertinence limitée (26%), à comparer avec le gain attendu de S4 dans le doc (`sim 0.78-0.91` avec embeddings réels vs `sim 0.06-0.12` observé ici en TF-IDF).

## Conclusion honnête (règle d'or du doc technique)

Le pipeline IA1→IA2→IA3 est **fonctionnel de bout en bout**, mais les performances mesurées sur 50 entrées (10% / 30% / 26%) sont **loin des cibles du doc** (>95% / — / à mesurer mais visiblement supérieur). Deux causes distinctes, toutes deux documentées :

1. **Infrastructure sous-dimensionnée** (RAM insuffisante pour LLM local en continu) → cascade sur IA2
2. **Choix d'architecture par contrainte** (TF-IDF au lieu d'embeddings sémantiques) → limite intrinsèque d'IA3

Ces résultats constituent une base d'analyse honnête pour le rapport final, conformément à la règle d'or : *"Un moteur fiable et bien compris prime sur un moteur impressionnant mais opaque."*
