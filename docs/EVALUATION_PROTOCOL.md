# Evaluation Protocol — Pulsaride Dispatch Engine V1

**Version :** 1.0  
**Auteurs :** Ihssan Ben Labsir · Salmane Sossey  
**Encadrant :** M. Lyazid Salihi — Pulsaride Solutions

---

## 1. Objectif

Évaluer et comparer les 4 stratégies de matching (S1/S2/S3/S4) du moteur de dispatch médical sur des scénarios reproductibles, et mesurer la robustesse du système sous charge.

---

## 2. Stratégies évaluées

| ID | Nom | Description |
|----|-----|-------------|
| S1 | First Available | Premier professionnel AVAILABLE sans critère de qualité |
| S2 | Tag Exact | Matching par `specialty_tag` exact uniquement |
| S3 | Score Composite | Disponibilité (0.5) + charge (0.3) + tag (0.2) |
| S4 | Lexical IA | S3 + similarité lexicale profil/texte patient |

---

## 3. Métriques

| Métrique | Définition | Cible V1 |
|----------|-----------|----------|
| TTFA | Time To First Assignment (ms) | < 5 000 ms |
| TTR | Time To Resolution (ms) | < 30 000 ms |
| Service Rate | % demandes CLOSED / total | > 95% nominal |
| Refusal Rate | % REFUSED / propositions | < 10% |
| Failure Rate | % FAILED / total | < 5% |
| Gini Fairness | Équité de charge entre pros (0=équitable) | < 0.15 |
| Débit | Requêtes traitées par seconde | À mesurer |

---

## 4. Scénarios de test

### 4.1 Comparaison des stratégies

| ID | Nom | Seed | Pros | Demandes | Objectif |
|----|-----|------|------|----------|----------|
| nominal | Scénario Nominal | 42 | 20 | 20 | Valider le cas de base |
| peak_night | Pic de Nuit | 43 | 20 | 40 | Dégradation sous-capacité |
| refusal_cascade | Refus en Cascade | 44 | 20 | 30 | Robustesse ré-attribution |
| no_availability | Aucune Disponibilité | 45 | 20 | 10 | Cas limite |
| load_ramp | Montée en Charge | 46 | 20 | 80 | Point de rupture |
| semantic_affinity | Affinité Sémantique | 47 | 20 | 20 | Gain S4 vs S2 |

### 4.2 Tests de robustesse (P4)

| Scénario | Req | Accept rate | Objectif |
|----------|-----|-------------|----------|
| Nominal | 20 | 85% | Référence |
| Pic de nuit | 40 | 50% | Forte charge + refus |
| Refus cascade | 30 | 20% | 80% de refus simulés |
| Montée en charge | 80 | 75% | Point de rupture |

---

## 5. Résultats — Comparaison S1/S2/S3/S4

*(Run nominal · 20 demandes · 20 professionnels · seed=42)*

| Stratégie | Service rate | TTFA (ms) | TTR (ms) | Gini | Verdict |
|-----------|-------------|-----------|----------|------|---------|
| S1 First Available | 34.15% | 13 944 | 9 919 | 0.25 | Baseline |
| S2 Tag Exact | 41.30% | 11 394 | 8 230 | 0.33 | Charge déséquilibrée |
| S3 Score Composite | 26.39% | 18 771 | 12 937 | **0.10** ✅ | Meilleure équité |
| S4 Lexical IA | **46.08%** | **9 842** | **7 350** | 0.25 | **Meilleure perf** |

**Analyse :**
- S4 recommandée pour la production : +12% service rate vs S1, TTFA et TTR les plus rapides
- S3 recommandée si équité prioritaire : Gini 0.10 < cible 0.15 ✅
- S2 déconseillée seule : Gini 0.33 = charge très déséquilibrée

---

## 6. Résultats — Tests de robustesse P4

*(API réelle · Spring Boot + Redis + PostgreSQL)*

| Scénario | Req | Débit | Service rate | Dégradation |
|----------|-----|-------|-------------|-------------|
| Nominal | 20 | 2.49 req/s | 38.52% | Référence |
| Pic de nuit | 40 | 5.35 req/s | 29.01% | -9.51% |
| Refus cascade | 30 | 2.82 req/s | 24.48% | -14.04% |
| Montée en charge | 80 | 13.48 req/s | 17.28% | -21.24% |

**Point de rupture :** 80 requêtes simultanées  
**Débit max mesuré :** 13.48 req/s  
**Dégradation maximale :** -21.24% (montée en charge)

---

## 7. Limites V1 identifiées

- Service rate < 95% dès le scénario nominal — cause : accumulation de demandes PENDING des sessions précédentes
- TTFA moyen > 5 000 ms — cause : délais de traitement HTTP + Redis accumulés
- Gini > 0.15 pour S1, S2, S4 — seul S3 atteint la cible

**Améliorations V2 :**
- Mécanisme de purge automatique des demandes PENDING orphelines
- TTL sur les demandes en attente
- Embeddings pgvector pour matching sémantique réel (S4 améliorée)

---

## 8. Règle d'or

> Un moteur fiable et bien compris prime sur un moteur impressionnant mais opaque.
> L'analyse honnête des limites fait partie du livrable.
> — M. Lyazid Salihi, document technique V1
