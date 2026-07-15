"""
Simulator: Patient Request Generator
Epic 2 — Story 2.2
"""

import json
import random
import uuid
from datetime import datetime, timedelta

# Seed fixe — NE PAS CHANGER
SEED = 42
random.seed(SEED)

# Templates de textes libres patients par spécialité
REQUEST_TEMPLATES = {
    "generaliste": [
        "J'ai de la fièvre depuis {days} jours avec des frissons et de la fatigue.",
        "Je tousse beaucoup depuis {days} jours, j'ai mal à la gorge et le nez bouché.",
        "J'ai des maux de tête intenses depuis {days} jours, je me sens épuisé.",
        "J'ai des douleurs dans le dos depuis {days} jours, j'ai du mal à marcher.",
        "Je me sens très fatigué depuis {days} semaines, je dors mal et je manque d'énergie.",
    ],
    "pediatrie": [
        "Mon enfant de {age} ans a de la fièvre à {temp}°C depuis {days} jours.",
        "Mon bébé de {age} mois pleure beaucoup, il a le nez qui coule et tousse.",
        "Ma fille de {age} ans a des boutons rouges sur tout le corps depuis hier.",
        "Mon fils de {age} ans vomit depuis {days} jours et refuse de manger.",
        "Mon enfant de {age} ans se plaint d'une douleur à l'oreille depuis ce matin.",
    ],
    "cardiologie": [
        "J'ai des palpitations depuis {days} jours, mon coeur bat très vite par moments.",
        "Je ressens une douleur dans la poitrine légère depuis {days} jours.",
        "J'ai une tension artérielle élevée, je prends des médicaments mais je me sens mal.",
        "J'ai des essoufflements à l'effort depuis {days} semaines.",
    ],
    "dermatologie": [
        "J'ai des plaques rouges qui démangent sur les bras depuis {days} jours.",
        "J'ai une éruption cutanée sur le visage qui s'étend depuis hier.",
        "J'ai un grain de beauté qui a changé de forme et de couleur récemment.",
        "J'ai de l'eczéma qui s'aggrave depuis {days} semaines malgré la crème.",
    ],
    "psychiatrie": [
        "Je me sens très anxieux depuis {days} semaines, j'ai du mal à dormir.",
        "Je traverses une période de dépression, je n'arrive plus à travailler.",
        "J'ai des crises d'angoisse depuis {days} jours, je ne sais plus quoi faire.",
        "Je me sens très stressé, j'ai besoin d'aide pour gérer mon anxiété.",
    ],
    "gynecologie": [
        "J'ai des douleurs abdominales depuis {days} jours, je suis inquiète.",
        "J'ai des saignements inhabituels, je voudrais un avis médical.",
        "Je suis enceinte de {months} mois et j'ai une question sur mes symptômes.",
        "J'ai des brûlures et des démangeaisons intimes depuis {days} jours.",
    ],
    "orl": [
        "J'ai très mal à la gorge depuis {days} jours, j'ai du mal à avaler.",
        "J'ai une sinusite douloureuse depuis {days} jours avec beaucoup de mucus.",
        "J'ai une douleur à l'oreille droite depuis hier avec une légère fièvre.",
        "J'ai perdu l'odorat depuis {days} jours suite à un rhume.",
    ],
    "ophtalmologie": [
        "J'ai les yeux rouges et irrités depuis {days} jours avec des sécrétions.",
        "J'ai une douleur à l'oeil gauche depuis ce matin, je vois flou.",
        "J'ai un orgelet douloureux depuis {days} jours qui grossit.",
    ],
}

URGENCY_RULES = {
    "cardiologie": 3,
    "pediatrie": 2,
    "psychiatrie": 2,
    "generaliste": 1,
    "gynecologie": 1,
    "orl": 1,
    "dermatologie": 0,
    "ophtalmologie": 1,
}

GROUND_TRUTH = {
    "generaliste": {
        "specialty_hint": "generaliste",
        "symptoms": ["fièvre", "toux", "fatigue", "maux de tête"],
    },
    "pediatrie": {
        "specialty_hint": "pediatrie",
        "symptoms": ["fièvre enfant", "toux enfant", "éruption cutanée enfant"],
    },
    "cardiologie": {
        "specialty_hint": "cardiologie",
        "symptoms": ["palpitations", "douleur poitrine", "essoufflement"],
    },
    "dermatologie": {
        "specialty_hint": "dermatologie",
        "symptoms": ["plaques rouges", "éruption cutanée", "démangeaisons"],
    },
    "psychiatrie": {
        "specialty_hint": "psychiatrie",
        "symptoms": ["anxiété", "dépression", "insomnie", "stress"],
    },
    "gynecologie": {
        "specialty_hint": "gynecologie",
        "symptoms": ["douleurs abdominales", "saignements"],
    },
    "orl": {
        "specialty_hint": "orl",
        "symptoms": ["mal de gorge", "sinusite", "douleur oreille"],
    },
    "ophtalmologie": {
        "specialty_hint": "ophtalmologie",
        "symptoms": ["yeux rouges", "douleur oeil", "vision floue"],
    },
}


def generate_request_text(specialty: str) -> str:
    template = random.choice(REQUEST_TEMPLATES[specialty])
    return template.format(
        days=random.randint(1, 7),
        age=random.randint(1, 15),
        temp=round(random.uniform(37.5, 40.0), 1),
        months=random.randint(1, 9),
    )


def generate_requests(n: int = 50) -> list:
    specialties = list(REQUEST_TEMPLATES.keys())
    requests = []
    base_time = datetime.now()

    for i in range(n):
        specialty = random.choice(specialties)
        text = generate_request_text(specialty)
        urgency = URGENCY_RULES[specialty]
        gt = GROUND_TRUTH[specialty]

        request = {
            "id": str(uuid.uuid4()),
            "patient_id": f"patient_{i+1:03d}",
            "patient_text": text,
            "specialty_hint": gt["specialty_hint"],
            "expected_symptoms": gt["symptoms"],
            "urgency_score": urgency,
            "status": "PENDING",
            "created_at": (base_time + timedelta(minutes=i * 3)).isoformat(),
        }
        requests.append(request)

    return requests


def save_requests(requests: list, path: str = "data/requests.json"):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(requests, f, ensure_ascii=False, indent=2)
    print(f"✅ {len(requests)} demandes générées → {path}")


if __name__ == "__main__":
    requests = generate_requests(n=50)
    save_requests(requests)

    # Aperçu
    print("\n📋 Aperçu des 3 premières demandes :")
    for r in requests[:3]:
        print(f"\n  Patient : {r['patient_id']}")
        print(f"  Texte   : {r['patient_text'][:60]}...")
        print(f"  Urgence : {r['urgency_score']} | Spécialité : {r['specialty_hint']}")