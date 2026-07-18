"""
Comparison Report
Génère les graphiques comparatifs S1/S2/S3/S4
"""

import json
import os
from pathlib import Path

# Données des métriques collectées
metrics = {
    "S1 - First Available": {
        "service_rate": 34.15,
        "ttfa_ms": 13944.75,
        "ttr_ms": 9919.46,
        "gini": 0.25,
        "timeout_rate": 4.55
    },
    "S2 - Tag Exact": {
        "service_rate": 41.30,
        "ttfa_ms": 11394.88,
        "ttr_ms": 8230.61,
        "gini": 0.33,
        "timeout_rate": 4.55
    },
    "S3 - Score Composite": {
        "service_rate": 26.39,
        "ttfa_ms": 18771.64,
        "ttr_ms": 12937.68,
        "gini": 0.10,
        "timeout_rate": 4.55
    },
    "S4 - Lexical IA": {
        "service_rate": 46.08,
        "ttfa_ms": 9842.65,
        "ttr_ms": 7350.28,
        "gini": 0.25,
        "timeout_rate": 4.55
    }
}

strategies = list(metrics.keys())

# Rapport texte
Path("reports").mkdir(exist_ok=True)

report = """
=======================================================
PULSARIDE — RAPPORT COMPARATIF DES STRATÉGIES
=======================================================

Stratégie          | Service% | TTFA(ms)  | TTR(ms)   | Gini
-------------------|----------|-----------|-----------|------
S1 First Available | 34.15%   | 13944 ms  | 9919 ms   | 0.25
S2 Tag Exact       | 41.30%   | 11394 ms  | 8230 ms   | 0.33
S3 Score Composite | 26.39%   | 18771 ms  | 12937 ms  | 0.10
S4 Lexical IA      | 46.08%   |  9842 ms  | 7350 ms   | 0.25

=======================================================
ANALYSE
=======================================================

1. MEILLEURE PERFORMANCE GLOBALE : S4 - Lexical IA
   - Service rate le plus élevé : 46.08%
   - TTFA le plus rapide : 9842 ms
   - TTR le plus rapide : 7350 ms
   - Le matching lexical améliore significativement la qualité

2. MEILLEURE ÉQUITÉ : S3 - Score Composite
   - Gini le plus bas : 0.10 (excellent < 0.15)
   - Meilleure répartition de charge entre professionnels
   - Mais service rate plus faible car plus sélectif

3. S2 vs S1 :
   - S2 améliore S1 de +7% en service rate
   - Le tag exact apporte une valeur réelle
   - Mais Gini dégradé (0.33) = charge déséquilibrée

4. RECOMMANDATION :
   - Production : S4 pour la performance
   - Équité prioritaire : S3
   - MVP simple : S1 ou S2

=======================================================
CONCLUSION
=======================================================
S4 est la meilleure stratégie globale.
S3 est la meilleure pour l'équité de charge.
La couche lexicale (S4) apporte +12% vs S1 simple.
"""

with open("reports/comparison_report.txt", "w", encoding="utf-8") as f:
    f.write(report)

print(report)
print("✅ Rapport sauvegardé → reports/comparison_report.txt")

# Sauvegarder aussi en JSON
with open("reports/comparison_data.json", "w") as f:
    json.dump(metrics, f, indent=2, ensure_ascii=False)
print("✅ Données → reports/comparison_data.json")
