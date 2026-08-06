"""
Pulsaride V2 — Free Text Request Generator
IA1 input corpus : textes libres patients + ground truth JSON
"""

import json
import random
import uuid
from datetime import datetime, timedelta
from pathlib import Path

SEED = 42
random.seed(SEED)

Path("data/v2").mkdir(parents=True, exist_ok=True)

# ─── TEMPLATES TEXTES LIBRES ─────────────────────────────────────────────────

TEMPLATES = {
    "pediatrie": {
        "texts": [
            "Mon enfant de {age} ans a de la fièvre à {temp}°C depuis {days} jours, il tousse beaucoup.",
            "Ma fille de {age} ans a mal à la gorge depuis {days} jours et refuse de manger.",
            "Mon bébé de {age} mois pleure sans arrêt, il a le nez bouché et de la fièvre.",
            "Mon fils de {age} ans a des boutons rouges partout sur le corps depuis hier.",
            "Mon enfant de {age} ans vomit depuis {days} jours et a de la fièvre à {temp}°C.",
        ],
        "ground_truth": {
            "specialty_hint": "pediatrie",
            "age_group": "enfant",
            "severity": 2
        }
    },
    "cardiologie": {
        "texts": [
            "J'ai des douleurs dans la poitrine depuis {days} jours, surtout quand je monte les escaliers.",
            "Mon cœur bat très vite par moments depuis {days} jours, j'ai des palpitations.",
            "J'ai du mal à respirer à l'effort depuis {days} semaines, je me fatigue vite.",
            "J'ai une douleur dans la poitrine qui irradie dans le bras gauche depuis ce matin.",
            "Ma tension est très élevée depuis {days} jours malgré mes médicaments.",
        ],
        "ground_truth": {
            "specialty_hint": "cardiologie",
            "age_group": "adulte",
            "severity": 3
        }
    },
    "generaliste": {
        "texts": [
            "J'ai de la fièvre depuis {days} jours avec des frissons et je me sens très fatigué.",
            "J'ai mal à la tête depuis {days} jours, je n'arrive pas à travailler.",
            "Je tousse depuis {days} semaines, j'ai le nez qui coule et mal à la gorge.",
            "J'ai des douleurs dans le dos depuis {days} jours, j'ai du mal à marcher.",
            "Je me sens épuisé depuis {days} semaines, je dors mal et je n'ai pas d'appétit.",
        ],
        "ground_truth": {
            "specialty_hint": "generaliste",
            "age_group": "adulte",
            "severity": 1
        }
    },
    "dermatologie": {
        "texts": [
            "J'ai des plaques rouges qui démangent sur les bras depuis {days} jours.",
            "J'ai une éruption cutanée sur le visage qui s'étend depuis hier.",
            "J'ai un grain de beauté qui a changé de forme et de couleur depuis {days} semaines.",
            "J'ai de l'eczéma qui s'aggrave depuis {days} jours malgré ma crème.",
            "J'ai des démangeaisons intenses sur tout le corps depuis {days} jours.",
        ],
        "ground_truth": {
            "specialty_hint": "dermatologie",
            "age_group": "adulte",
            "severity": 1
        }
    },
    "orl": {
        "texts": [
            "J'ai très mal à la gorge depuis {days} jours, j'ai du mal à avaler ma salive.",
            "J'ai une sinusite douloureuse depuis {days} jours avec beaucoup de mucus jaune.",
            "J'ai mal à l'oreille droite depuis {days} jours avec une légère fièvre.",
            "J'ai perdu l'odorat depuis {days} jours suite à un rhume.",
            "J'ai des vertiges et des bourdonnements dans les oreilles depuis {days} jours.",
        ],
        "ground_truth": {
            "specialty_hint": "orl",
            "age_group": "adulte",
            "severity": 1
        }
    },
    "psychiatrie": {
        "texts": [
            "Je me sens très anxieux depuis {days} semaines, j'ai du mal à dormir.",
            "Je traverse une période de dépression, je n'arrive plus à aller travailler.",
            "J'ai des crises d'angoisse depuis {days} jours, mon cœur s'emballe.",
            "Je me sens très stressé, j'ai des pensées négatives que je n'arrive pas à contrôler.",
            "Je dors très mal depuis {days} semaines, je me réveille plusieurs fois par nuit.",
        ],
        "ground_truth": {
            "specialty_hint": "psychiatrie",
            "age_group": "adulte",
            "severity": 2
        }
    },
}

# ─── TEXTES AMBIGUS (20% du corpus) ─────────────────────────────────────────

AMBIGUOUS_TEMPLATES = [
    "Je ne me sens pas bien depuis quelques jours.",
    "J'ai mal un peu partout, je suis fatigué.",
    "Je ne sais pas ce que j'ai mais ça ne va pas.",
    "J'ai des douleurs mais je ne sais pas exactement où.",
    "Je me sens bizarre depuis hier, j'ai du mal à décrire.",
    "Quelque chose ne va pas, j'ai besoin d'un avis médical.",
    "Je suis pas bien depuis quelque temps.",
    "J'ai des symptômes que je n'arrive pas à expliquer.",
    "J'ai mal depuis plusieurs jours mais c'est difficile à localiser.",
    "Je veux consulter un médecin mais je ne sais pas lequel.",
]


def generate_text(specialty: str) -> str:
    template = random.choice(TEMPLATES[specialty]["texts"])
    return template.format(
        age=random.randint(1, 12),
        temp=round(random.uniform(37.5, 40.0), 1),
        days=random.randint(1, 7),
        months=random.randint(1, 11),
    )


def generate_urgency_score(specialty: str, severity: int) -> int:
    """IA2 rule-based urgency score."""
    base = {
        "cardiologie": 3,
        "pediatrie": 2,
        "psychiatrie": 2,
        "generaliste": 1,
        "orl": 1,
        "dermatologie": 0,
        "gynecologie": 1,
    }
    return min(3, base.get(specialty, 1) + (1 if severity == 3 else 0))


def generate_corpus(n_normal: int = 40,
                    n_ambiguous: int = 10) -> list:
    """
    Génère le corpus V2 :
    - n_normal textes clairs avec ground truth
    - n_ambiguous textes ambigus (test robustesse IA1)
    """
    specialties = list(TEMPLATES.keys())
    corpus = []
    base_time = datetime.now()

    # Textes normaux
    for i in range(n_normal):
        specialty = random.choice(specialties)
        gt = TEMPLATES[specialty]["ground_truth"]
        text = generate_text(specialty)
        urgency = generate_urgency_score(specialty, gt["severity"])

        entry = {
            "id": str(uuid.uuid4()),
            "patient_id": f"v2_patient_{i+1:03d}",
            "free_text": text,
            "ground_truth": {
                "specialty_hint": gt["specialty_hint"],
                "age_group": gt["age_group"],
                "severity": gt["severity"],
                "urgency_score": urgency,
                "symptoms": [],
                "is_ambiguous": False
            },
            "created_at": (base_time + timedelta(minutes=i * 2)).isoformat(),
        }
        corpus.append(entry)

    # Textes ambigus
    for i in range(n_ambiguous):
        text = random.choice(AMBIGUOUS_TEMPLATES)
        entry = {
            "id": str(uuid.uuid4()),
            "patient_id": f"v2_ambiguous_{i+1:03d}",
            "free_text": text,
            "ground_truth": {
                "specialty_hint": "generaliste",
                "age_group": "inconnu",
                "severity": 1,
                "urgency_score": 0,
                "symptoms": [],
                "is_ambiguous": True
            },
            "created_at": (base_time + timedelta(
                minutes=(n_normal + i) * 2)).isoformat(),
        }
        corpus.append(entry)

    random.shuffle(corpus)
    return corpus


if __name__ == "__main__":
    print("🏥 Pulsaride V2 — Générateur de textes libres\n")

    corpus = generate_corpus(n_normal=40, n_ambiguous=10)

    # Sauvegarder
    path = "data/v2/free_text_corpus.json"
    with open(path, "w", encoding="utf-8") as f:
        json.dump(corpus, f, ensure_ascii=False, indent=2)

    # Stats
    total = len(corpus)
    ambiguous = sum(1 for e in corpus if e["ground_truth"]["is_ambiguous"])
    by_specialty = {}
    for e in corpus:
        sp = e["ground_truth"]["specialty_hint"]
        by_specialty[sp] = by_specialty.get(sp, 0) + 1

    print(f"✅ {total} entrées générées → {path}")
    print(f"   Textes clairs   : {total - ambiguous}")
    print(f"   Textes ambigus  : {ambiguous} ({ambiguous/total*100:.0f}%)")
    print(f"\n📋 Répartition par spécialité :")
    for sp, count in sorted(by_specialty.items()):
        print(f"   {sp:<15} : {count}")

    print(f"\n📝 Exemples :")
    for e in corpus[:3]:
        print(f"\n   [{e['patient_id']}]")
        print(f"   Texte   : {e['free_text'][:70]}...")
        print(f"   GT      : {e['ground_truth']['specialty_hint']} | "
              f"urgence {e['ground_truth']['urgency_score']} | "
              f"ambigु {e['ground_truth']['is_ambiguous']}")
