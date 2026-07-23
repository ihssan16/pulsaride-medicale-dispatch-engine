# Evaluation Protocol - Pulsaride Dispatch Engine V1

**Version:** 1.1

**Auteurs:** Ihssan Ben Labsir et Salmane Sossey

**Encadrant:** M. Lyazid Salihi - Pulsaride Solutions

## 1. Objectif

Comparer les stratégies S1/S2/S3/S4 et mesurer la robustesse du moteur Spring
Boot avec des données reproductibles. Les résultats publiés doivent provenir de
l'API réelle et chaque scénario de robustesse doit commencer avec PostgreSQL et
Redis vides.

## 2. Stratégies

| ID | Nom | Description |
|---|---|---|
| S1 | Round Robin | Rotation stable entre slots disponibles |
| S2 | Tag Exact | Spécialité exacte uniquement |
| S3 | Score Composite | Disponibilité (0,5), charge (0,3), spécialité (0,2) |
| S4 | Lexical | S3 avec affinité lexicale profil/texte patient |

## 3. Métriques V1

| Métrique | Définition | Cible | Source |
|---|---|---:|---|
| TTFA P95 | Soumission vers première proposition | < 5 000 ms | PostgreSQL `ttfa_ms` |
| TTR P95 | Soumission vers acceptation ou résolution terminale | < 30 000 ms | PostgreSQL `ttr_ms` |
| Service rate | Demandes acceptées/closes sur demandes soumises | >= 95% nominal | `/metrics/summary` |
| Failure rate | Demandes `FAILED` sur demandes soumises | < 5% nominal | `/metrics/summary` |
| Refusal rate | Assignments refusés sur toutes les propositions | < 10% nominal | `/metrics/summary` |
| Gini | Inégalité de charge, 0 = répartition égale | < 0,15 indicatif | `/metrics/summary` |
| MTTR dégradé P95 | Refus/timeout vers proposition suivante | < 10 000 ms | Historique `assignments` |
| Débit | Demandes closes par seconde de traitement | À mesurer | Evaluateur Python |

L'API fournit également les moyennes TTFA, TTR et MTTR. Le P95 utilise la
méthode nearest-rank sur les valeurs persistées.

## 4. Isolation et reproductibilité

`evaluator/robustness_test.py` applique les règles suivantes :

1. Vérifier `/actuator/health`.
2. Tronquer les tables métier et vider Redis avant chaque scénario.
3. Recharger exactement 20 professionnels depuis le dataset versionné.
4. Soumettre les demandes avec plusieurs workers.
5. Dispatcher avec `/dispatch/next?strategy=S3` et utiliser l'ID retourné.
6. Vérifier les réponses de création, dispatch, acceptation, refus et clôture.
7. Attendre que toutes les demandes soient `CLOSED` ou `FAILED`.
8. Sauvegarder les métriques live et générer rapport et graphiques depuis le JSON.

Commandes :

```bash
python3 -m pip install -r evaluator/requirements.txt
python3 evaluator/robustness_test.py --strategy S3
python3 evaluator/generate_robustness_charts.py
```

## 5. Comparaison S1-S4

Run live prioritaire : 20 demandes, 20 professionnels, seed 42, taux
d'acceptation simulé 85%.

| Stratégie | Service | TTFA moyen | TTR moyen | Gini |
|---|---:|---:|---:|---:|
| S1 | 100% | 4 924 ms | 5 037 ms | 0,54 |
| S2 | 90% | 3 184 ms | 3 265 ms | 0,43 |
| S3 | 100% | 3 188 ms | 3 282 ms | 0,27 |
| S4 | 100% | 3 044 ms | 3 129 ms | 0,26 |

S2 expose la limite attendue du matching strict lorsqu'une spécialité n'a plus
de slot disponible. S1, S3 et S4 terminent les 20 demandes. S1 prouve la
rotation round-robin, mais S3/S4 restent meilleurs pour la latence et la
répartition sur ce dataset. Ce run compare les stratégies; le run P4 ci-dessous
mesure séparément les P95 et la charge.

## 6. Robustesse et charge P4

Run du 23 juillet 2026, API Spring Boot + PostgreSQL + Redis, stratégie S3.

| Scénario | Demandes | Service | Closes/s | TTFA P95 | TTR P95 | MTTR P95 |
|---|---:|---:|---:|---:|---:|---:|
| Nominal | 20 | 100% | 28,45 | 656 ms | 668 ms | n/a |
| Pic de nuit | 40 | 100% | 25,14 | 1 379 ms | 1 391 ms | 101 ms |
| Refus en cascade | 30 | 36,67% | 10,82 | 704 ms | 1 072 ms | 37 ms |
| Charge 20 | 20 | 100% | 25,43 | 650 ms | 665 ms | n/a |
| Charge 40 | 40 | 100% | 28,96 | 1 213 ms | 1 241 ms | n/a |
| Charge 80 | 80 | 100% | 28,30 | 2 633 ms | 2 661 ms | 32 ms |
| Charge 160 | 160 | 100% | 28,77 | 5 131 ms | 5 194 ms | 135 ms |

Résultat de capacité :

- 80 demandes est la plus grande charge testée respectant service, TTFA et TTR.
- 28,96 demandes closes/s est le débit durable maximal observé.
- 160 demandes est le premier niveau dégradé : service 100%, mais TTFA P95
  atteint 5 131 ms, soit 131 ms au-dessus de la cible de 5 secondes.
- Aucun appel HTTP n'a échoué pendant le run publié.
- Le scénario de refus en cascade est volontairement dégradé : 80% de refus
  simulés placent les professionnels en `BREAK`, ce qui réduit le service.

## 7. Limites honnêtes de la V1

- Le Gini reste entre 0,18 et 0,26 sur les charges nominales, au-dessus de la
  cible indicative de 0,15. La spécialisation des professionnels limite une
  répartition parfaitement uniforme.
- La contention augmente avec le nombre de workers car plusieurs dispatchers
  peuvent lire la même tête de file avant que le verrou Redis soit acquis.
  Aucun doublon d'affectation n'a été observé, mais ces retries coûtent du débit.
- Le refus met le slot en `BREAK`; la V1 ne contient pas encore de minuterie de
  retour automatique à `AVAILABLE`.
- S4 est lexical et déterministe. Les embeddings restent une évolution V2.

Les fichiers de preuve sont versionnés sous `evaluator/reports/` et les livrables
visuels sous `docs/evaluation/`.
