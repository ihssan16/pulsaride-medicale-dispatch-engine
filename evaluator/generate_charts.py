"""
Charts Generator
Génère les graphiques comparatifs S1/S2/S3/S4
"""

import matplotlib
matplotlib.use('Agg')  # Sans interface graphique
import matplotlib.pyplot as plt
import numpy as np
from pathlib import Path

Path("reports").mkdir(exist_ok=True)

strategies = ["S1\nFirst\nAvailable", "S2\nTag\nExact", "S3\nScore\nComposite", "S4\nLexical\nIA"]
colors = ["#4A8FE7", "#4CAF50", "#F59E0B", "#A855F7"]

service_rates = [34.15, 41.30, 26.39, 46.08]
ttfa_ms       = [13944, 11394, 18771, 9842]
ttr_ms        = [9919,  8230,  12937, 7350]
gini          = [0.25,  0.33,  0.10,  0.25]

fig, axes = plt.subplots(2, 2, figsize=(14, 10))
fig.suptitle("Pulsaride — Comparaison des stratégies de dispatch\nS1 vs S2 vs S3 vs S4",
             fontsize=16, fontweight='bold', y=1.02)

# --- Graphique 1 : Service Rate ---
ax1 = axes[0, 0]
bars = ax1.bar(strategies, service_rates, color=colors, edgecolor='white', linewidth=1.5)
ax1.axhline(y=95, color='red', linestyle='--', linewidth=1.5, label='Cible 95%')
ax1.set_title("Service Rate (%)", fontweight='bold', fontsize=13)
ax1.set_ylabel("Pourcentage (%)")
ax1.set_ylim(0, 100)
ax1.legend(fontsize=10)
for bar, val in zip(bars, service_rates):
    ax1.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 0.5,
             f'{val}%', ha='center', va='bottom', fontweight='bold', fontsize=11)
ax1.grid(axis='y', alpha=0.3)

# --- Graphique 2 : TTFA ---
ax2 = axes[0, 1]
bars = ax2.bar(strategies, ttfa_ms, color=colors, edgecolor='white', linewidth=1.5)
ax2.axhline(y=5000, color='red', linestyle='--', linewidth=1.5, label='Cible 5000ms')
ax2.set_title("TTFA — Time To First Assignment (ms)", fontweight='bold', fontsize=13)
ax2.set_ylabel("Millisecondes (ms)")
ax2.legend(fontsize=10)
for bar, val in zip(bars, ttfa_ms):
    ax2.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 100,
             f'{val:,}ms', ha='center', va='bottom', fontweight='bold', fontsize=10)
ax2.grid(axis='y', alpha=0.3)

# --- Graphique 3 : TTR ---
ax3 = axes[1, 0]
bars = ax3.bar(strategies, ttr_ms, color=colors, edgecolor='white', linewidth=1.5)
ax3.axhline(y=30000, color='red', linestyle='--', linewidth=1.5, label='Cible 30000ms')
ax3.set_title("TTR — Time To Resolution (ms)", fontweight='bold', fontsize=13)
ax3.set_ylabel("Millisecondes (ms)")
ax3.legend(fontsize=10)
for bar, val in zip(bars, ttr_ms):
    ax3.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 100,
             f'{val:,}ms', ha='center', va='bottom', fontweight='bold', fontsize=10)
ax3.grid(axis='y', alpha=0.3)

# --- Graphique 4 : Gini ---
ax4 = axes[1, 1]
bars = ax4.bar(strategies, gini, color=colors, edgecolor='white', linewidth=1.5)
ax4.axhline(y=0.15, color='red', linestyle='--', linewidth=1.5, label='Cible < 0.15')
ax4.set_title("Gini Fairness (équité de charge)", fontweight='bold', fontsize=13)
ax4.set_ylabel("Coefficient de Gini")
ax4.set_ylim(0, 0.5)
ax4.legend(fontsize=10)
for bar, val in zip(bars, gini):
    ax4.text(bar.get_x() + bar.get_width()/2., bar.get_height() + 0.005,
             f'{val}', ha='center', va='bottom', fontweight='bold', fontsize=11)
ax4.grid(axis='y', alpha=0.3)

plt.tight_layout()
plt.savefig("reports/comparison_charts.png", dpi=150, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("✅ Graphiques sauvegardés → reports/comparison_charts.png")

# --- Radar Chart ---
fig2, ax = plt.subplots(figsize=(8, 8), subplot_kw=dict(polar=True))

categories = ['Service\nRate', 'Vitesse\nTTFA', 'Vitesse\nTTR', 'Équité\nGini']
N = len(categories)
angles = [n / float(N) * 2 * np.pi for n in range(N)]
angles += angles[:1]

# Normaliser les scores (0-1, plus grand = meilleur)
scores = {
    "S1": [34.15/46.08, 1-13944/18771, 1-9919/12937, 1-0.25/0.33],
    "S2": [41.30/46.08, 1-11394/18771, 1-8230/12937, 1-0.33/0.33],
    "S3": [26.39/46.08, 1-18771/18771, 1-12937/12937, 1-0.10/0.33],
    "S4": [46.08/46.08, 1-9842/18771,  1-7350/12937,  1-0.25/0.33],
}

radar_colors = ["#4A8FE7", "#4CAF50", "#F59E0B", "#A855F7"]
labels = ["S1 First Available", "S2 Tag Exact", "S3 Score Composite", "S4 Lexical IA"]

for (strategy, vals), color, label in zip(scores.items(), radar_colors, labels):
    vals_plot = vals + vals[:1]
    ax.plot(angles, vals_plot, 'o-', linewidth=2, color=color, label=label)
    ax.fill(angles, vals_plot, alpha=0.1, color=color)

ax.set_xticks(angles[:-1])
ax.set_xticklabels(categories, fontsize=12)
ax.set_ylim(0, 1)
ax.set_title("Radar — Performance globale des stratégies",
             fontweight='bold', fontsize=14, pad=20)
ax.legend(loc='upper right', bbox_to_anchor=(1.3, 1.1), fontsize=11)
ax.grid(True, alpha=0.3)

plt.tight_layout()
plt.savefig("reports/radar_chart.png", dpi=150, bbox_inches='tight',
            facecolor='white', edgecolor='none')
print("✅ Radar chart → reports/radar_chart.png")
print("\n🎉 Tous les graphiques générés dans evaluator/reports/")
