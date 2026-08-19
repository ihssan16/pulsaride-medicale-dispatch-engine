git add docs/adr/ADR-003-v2-scope.md
git commit -m "docs(v2): add ADR-003 V2 scope and evaluation protocol"
git push origin develop

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
