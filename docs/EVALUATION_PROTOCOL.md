# Protocole d'Évaluation — Pulsaride Dispatch Engine V1

## Objectif
Comparer les 4 stratégies de matching (S1/S2/S3/S4) sur des scénarios
reproductibles (seed fixe) et mesurer la performance du moteur de dispatch.

---

## Stratégies comparées

| ID | Nom | Description |
|----|-----|-------------|
| S1 | First Available | Premier pro AVAILABLE sans critère de qualité |
| S2 | Tag Exact | Matching par specialty_tag exact uniquement |
| S3 | Score Composite Classique | Disponibilité (0.5) + charge (0.3) + tag (0.2) |
| S4 | Score Composite Lexical | S3 + similarité lexicale profil/texte patient |

---

## Métriques mesurées

| Métrique | Définition | Cible V1 | Comment mesurer |
|----------|-----------|----------|----------------|
| TTFA | Time To First Assignment — délai soumission → première proposition | < 5 000 ms (P95) | `proposed_at - created_at` |
| TTR | Time To Resolution — délai soumission → acceptation finale | < 30 000 ms (P95) | `accepted_at - created_at` |
| Service Rate | % demandes CLOSED / total soumises | > 95% nominal | SQL COUNT sur statut |
| Refusal Rate | % REFUSED / total propositions | < 10% nominal | SQL COUNT assignments |
| Failure Rate | % FAILED / total soumises | < 5% nominal | SQL COUNT sur statut |
| Gini Fairness | Coefficient d'inégalité de charge entre pros (0=équitable, 1=mono) | < 0.15 | Calcul Python post-run |

---

## Scénarios de test

| ID | Nom | Seed | Pros | Demandes/h | Refus% | Objectif |
|----|-----|------|------|-----------|--------|----------|
| nominal | Scénario Nominal | 42 | 10 | 20 | 5% | Valider le cas de base |
| peak_night | Pic de Nuit | 43 | 2 | 80 | 10% | Mesurer dégradation sous-capacité |
| refusal_cascade | Refus en Cascade | 44 | 10 | 30 | 30% | Robustesse ré-attribution |
| no_availability | Aucune Disponibilité | 45 | 5 | 10 | 0% | Cas limite — tous BUSY |
| load_ramp | Montée en Charge | 46 | 10 | 200 | 5% | Point de rupture |
| semantic_affinity | Affinité Sémantique | 47 | 10 | 20 | 5% | Gain S4 vs S2 tag exact |

---

## Résultats obtenus — Comparaison S1/S2/S3/S4

*(Run sur scénario nominal, 20 demandes, 20 professionnels)*

| Stratégie | Service% | TTFA (ms) | TTR (ms) | Gini | Verdict |
|-----------|---------|-----------|----------|------|---------|
| S1 First Available | 34.15% | 13 944 | 9 919 | 0.25 | Baseline simple |
| S2 Tag Exact | 41.30% | 11 394 | 8 230 | 0.33 | Charge déséquilibrée |
| S3 Score Composite | 26.39% | 18 771 | 12 937 | **0.10** ✅ | Meilleure équité |
| S4 Lexical IA | **46.08%** | **9 842** | **7 350** | 0.25 | **Meilleure perf globale** |

---

## Conclusions

**S4 recommandée pour la production** : meilleur service rate (+12% vs S1),
TTFA et TTR les plus rapides. Le matching lexical apporte une valeur réelle.

**S3 recommandée si équité prioritaire** : Gini 0.10 < cible 0.15 ✅,
répartition de charge la plus équitable entre professionnels.

**S2 déconseillée seule** : Gini 0.33 = charge très déséquilibrée,
certains pros surchargés pendant que d'autres sont sous-utilisés.

---

## Règle d'or
> Un moteur fiable et bien compris prime sur un moteur impressionnant
> mais opaque. L'analyse honnête des limites fait partie du livrable.
> — M. Lyazid Salihi, doc technique V1

