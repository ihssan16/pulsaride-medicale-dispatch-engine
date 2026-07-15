"""
Simulator: Professional Profile Generator
Epic 2 — Story 2.1
"""

import json
import random

# Seed fixe pour reproductibilité — NE PAS CHANGER
SEED = 42
random.seed(SEED)

SPECIALTIES = [
    "generaliste",
    "pediatrie",
    "cardiologie",
    "dermatologie",
    "psychiatrie",
    "gynecologie",
    "ophtalmologie",
    "orl",
]

FIRST_NAMES = ["Marie", "Pierre", "Sophie", "Jean", "Isabelle",
               "Thomas", "Claire", "Nicolas", "Julie", "Marc",
               "Fatima", "Karim", "Nadia", "Youssef", "Leila"]

LAST_NAMES = ["Martin", "Bernard", "Dupont", "Moreau", "Laurent",
              "Simon", "Michel", "Lefebvre", "Garcia", "Durand",
              "Benali", "Khalil", "Rousseau", "Petit", "Roux"]

PROFILE_TEMPLATES = {
    "generaliste": "Médecin généraliste avec {exp} ans d'expérience. "
                   "Suivi polyvalent adultes et enfants. "
                   "Spécialisé en médecine préventive et maladies courantes.",
    "pediatrie": "Pédiatre avec {exp} ans d'expérience. "
                 "Suivi nourrissons, enfants et adolescents (0-18 ans). "
                 "Maladies infantiles, vaccinations et urgences pédiatriques.",
    "cardiologie": "Cardiologue avec {exp} ans d'expérience. "
                   "Maladies cardiovasculaires, hypertension, arythmies. "
                   "Téléconseil pour suivi post-opératoire.",
    "dermatologie": "Dermatologue avec {exp} ans d'expérience. "
                    "Maladies de la peau, allergies cutanées, dermatoses. "
                    "Téléconsultation adaptée aux diagnostics visuels.",
    "psychiatrie": "Psychiatre avec {exp} ans d'expérience. "
                   "Troubles anxieux, dépression, stress chronique. "
                   "Suivi thérapeutique et téléconseil de soutien.",
    "gynecologie": "Gynécologue avec {exp} ans d'expérience. "
                   "Santé féminine, contraception, suivi grossesse. "
                   "Téléconseil adapté aux consultations non techniques.",
    "ophtalmologie": "Ophtalmologue avec {exp} ans d'expérience. "
                     "Troubles visuels, irritations oculaires, urgences oeil. "
                     "Téléconseil pour orientation et premiers soins.",
    "orl": "ORL avec {exp} ans d'expérience. "
           "Oreilles, nez, gorge, sinusites, angines, otites. "
           "Téléconseil adapté aux pathologies courantes ORL.",
}


def generate_professionals(n: int = 20) -> list:
    professionals = []

    for i in range(n):
        specialty = random.choice(SPECIALTIES)
        exp = random.randint(3, 25)
        first_name = random.choice(FIRST_NAMES)
        last_name = random.choice(LAST_NAMES)

        pro = {
            "id": f"pro_{i+1:03d}",
            "name": f"Dr. {first_name} {last_name}",
            "specialty_tag": specialty,
            "experience_years": exp,
            "profile_text": PROFILE_TEMPLATES[specialty].format(exp=exp),
            "quota_max_per_hour": random.randint(4, 10),
            "status": "AVAILABLE",
            "consultations_today": 0,
            "load": 0.0,
        }
        professionals.append(pro)

    return professionals


def save_professionals(professionals: list, path: str = "data/professionals.json"):
    with open(path, "w", encoding="utf-8") as f:
        json.dump(professionals, f, ensure_ascii=False, indent=2)
    print(f"✅ {len(professionals)} professionnels générés → {path}")


if __name__ == "__main__":
    pros = generate_professionals(n=20)
    save_professionals(pros)

    # Aperçu
    print("\n📋 Aperçu des 3 premiers professionnels :")
    for p in pros[:3]:
        print(f"  - {p['name']} | {p['specialty_tag']} | {p['experience_years']} ans")