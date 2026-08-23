"""Generate a larger synthetic V2 evaluation/demo dataset.

This does not replace the small frozen V2-401 regression split. It creates a
larger, clearly labelled synthetic corpus for presentations and evaluation
reports where 50 cases would look too small.
"""

from __future__ import annotations

import json
import random
import sys
import uuid
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "simulator/data/v2"
sys.path.append(str(ROOT / "simulator"))

from v2_402_safety_rules import run_safety_pipeline  # noqa: E402


SEED = 4242
RNG = random.Random(SEED)

SPECIALTY_TEMPLATES: dict[str, dict[str, Any]] = {
    "generaliste": {
        "urgency": 1,
        "age_group": "adulte",
        "texts": [
            "J'ai de la fievre depuis {days} jours avec des frissons et beaucoup de fatigue.",
            "Je tousse depuis {days} jours, avec nez qui coule et mal a la gorge.",
            "J'ai mal au dos depuis {days} jours, je peux marcher mais difficilement.",
            "Je me sens epuise depuis {days} semaines et je dors mal.",
        ],
    },
    "cardiologie": {
        "urgency": 3,
        "age_group": "adulte",
        "texts": [
            "J'ai des palpitations depuis {days} jours, surtout quand je monte les escaliers.",
            "Ma tension reste tres elevee depuis {days} jours malgre le traitement.",
            "Je suis essouffle rapidement a l'effort depuis {days} semaines.",
            "J'ai une gene dans la poitrine qui revient plusieurs fois par jour.",
        ],
    },
    "pediatrie": {
        "urgency": 2,
        "age_group": "enfant",
        "texts": [
            "Mon enfant de {age} ans a de la fievre et tousse beaucoup.",
            "Ma fille de {age} ans a mal a la gorge et refuse de manger.",
            "Mon bebe de {months} mois pleure sans arret avec le nez bouche.",
            "Mon fils de {age} ans vomit depuis {days} jours.",
        ],
    },
    "dermatologie": {
        "urgency": 1,
        "age_group": "adulte",
        "texts": [
            "J'ai des plaques rouges qui demangent sur les bras depuis {days} jours.",
            "Une eruption cutanee sur mon visage s'etend depuis hier.",
            "Un grain de beaute a change de forme et de couleur.",
            "Mon eczema s'aggrave malgre la creme.",
        ],
    },
    "orl": {
        "urgency": 1,
        "age_group": "adulte",
        "texts": [
            "J'ai tres mal a la gorge depuis {days} jours et du mal a avaler.",
            "J'ai une sinusite douloureuse avec mucus jaune.",
            "J'ai mal a l'oreille droite avec une legere fievre.",
            "J'ai des vertiges et des bourdonnements dans les oreilles.",
        ],
    },
    "psychiatrie": {
        "urgency": 2,
        "age_group": "adulte",
        "texts": [
            "Je me sens tres anxieux depuis {days} semaines et je dors mal.",
            "Je traverse une periode de depression et je n'arrive plus a travailler.",
            "J'ai des crises d'angoisse, mon coeur s'emballe.",
            "Je suis tres stresse et mes pensees negatives reviennent souvent.",
        ],
    },
    "gynecologie": {
        "urgency": 1,
        "age_group": "adulte",
        "texts": [
            "J'ai des douleurs pelviennes depuis {days} jours.",
            "J'ai un retard de regles et des douleurs au bas ventre.",
            "J'ai des saignements inhabituels depuis hier.",
            "Je souhaite une consultation pour suivi de grossesse.",
        ],
    },
    "ophtalmologie": {
        "urgency": 1,
        "age_group": "adulte",
        "texts": [
            "Ma vision est floue depuis {days} jours.",
            "J'ai l'oeil rouge et douloureux depuis ce matin.",
            "Je vois des taches noires dans mon champ visuel.",
            "J'ai besoin de verifier ma vue car je lis difficilement.",
        ],
    },
}

AMBIGUOUS_TEXTS = [
    "Je ne me sens pas bien depuis quelques jours.",
    "J'ai mal un peu partout et je suis fatigue.",
    "Je ne sais pas ce que j'ai mais ca ne va pas.",
    "J'ai des douleurs difficiles a localiser.",
    "Je veux consulter mais je ne sais pas quelle specialite choisir.",
]

RED_FLAG_TEMPLATES = [
    ("RF001", "J'ai une douleur intense dans la poitrine qui irradie vers le bras gauche.", "cardiologie"),
    ("RF002", "Mon bebe de {months} mois a 40 degres de fievre et ne reagit pas bien.", "pediatrie"),
    ("RF003", "Je n'arrive plus a respirer correctement depuis ce matin.", "urgence"),
    ("RF004", "J'ai des idees noires et je ne me sens plus capable de continuer.", "psychiatrie"),
    ("RF005", "Mon enfant a avale des medicaments il y a quelques minutes.", "urgence"),
    ("RF006", "J'ai une faiblesse soudaine du cote du visage et je n'arrive plus a parler.", "urgence"),
]

SPECIALTY_KEYWORDS = {
    "generaliste": ["fievre", "tousse", "dos", "epuise", "fatigue"],
    "cardiologie": ["palpitations", "tension", "essouffle", "poitrine"],
    "pediatrie": ["enfant", "fille", "bebe", "fils"],
    "dermatologie": ["plaques", "eruption", "grain de beaute", "eczema"],
    "orl": ["gorge", "sinusite", "oreille", "vertiges"],
    "psychiatrie": ["anxieux", "depression", "angoisse", "stresse"],
    "gynecologie": ["pelviennes", "regles", "saignements", "grossesse"],
    "ophtalmologie": ["vision", "oeil", "champ visuel", "vue"],
}


def render(text: str) -> str:
    return text.format(
        age=RNG.randint(1, 12),
        months=RNG.randint(2, 18),
        days=RNG.randint(1, 10),
    )


def entry(text: str, specialty: str, urgency: int, age_group: str, case_type: str, index: int) -> dict[str, Any]:
    return {
        "id": str(uuid.uuid4()),
        "patient_id": f"v2_large_{index:04d}",
        "free_text": text,
        "case_type": case_type,
        "ground_truth": {
            "specialty_hint": specialty,
            "age_group": age_group,
            "urgency_score": urgency,
            "is_ambiguous": case_type == "ambiguous",
            "expected_min_urgency": 3 if case_type == "red_flag" else urgency,
        },
    }


def specialty_prediction(text: str) -> str:
    lowered = text.lower()
    scores = {
        specialty: sum(1 for keyword in keywords if keyword in lowered)
        for specialty, keywords in SPECIALTY_KEYWORDS.items()
    }
    best = max(scores, key=scores.get)
    return best if scores[best] > 0 else "generaliste"


def generate_dataset(clear_per_specialty: int = 80, ambiguous: int = 120, red_flags: int = 80) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    index = 1

    for specialty, config in SPECIALTY_TEMPLATES.items():
        for _ in range(clear_per_specialty):
            rows.append(
                entry(
                    render(RNG.choice(config["texts"])),
                    specialty,
                    config["urgency"],
                    config["age_group"],
                    "clear",
                    index,
                )
            )
            index += 1

    for _ in range(ambiguous):
        rows.append(entry(RNG.choice(AMBIGUOUS_TEXTS), "generaliste", 0, "inconnu", "ambiguous", index))
        index += 1

    for _ in range(red_flags):
        rule_id, template, specialty = RNG.choice(RED_FLAG_TEMPLATES)
        item = entry(render(template), specialty, 3, "inconnu", "red_flag", index)
        item["red_flag_rule"] = rule_id
        rows.append(item)
        index += 1

    RNG.shuffle(rows)
    base_time = datetime(2026, 8, 23, 12, 0, tzinfo=timezone.utc)
    for offset, item in enumerate(rows):
        item["created_at"] = (base_time + timedelta(seconds=offset * 15)).isoformat()
    return rows


def evaluate_dataset(rows: list[dict[str, Any]]) -> dict[str, Any]:
    clear_rows = [row for row in rows if row["case_type"] == "clear"]
    red_rows = [row for row in rows if row["case_type"] == "red_flag"]
    specialty_correct = sum(
        1 for row in clear_rows if specialty_prediction(row["free_text"]) == row["ground_truth"]["specialty_hint"]
    )
    redflag_caught = sum(
        1
        for row in red_rows
        if run_safety_pipeline(row["free_text"], model_urgency=0)["final_urgency"]
        >= row["ground_truth"]["expected_min_urgency"]
    )

    return {
        "seed": SEED,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "total_cases": len(rows),
        "case_type_counts": dict(Counter(row["case_type"] for row in rows)),
        "specialty_counts": dict(Counter(row["ground_truth"]["specialty_hint"] for row in rows)),
        "clear_specialty_baseline_accuracy_pct": round(specialty_correct / len(clear_rows) * 100, 1),
        "red_flag_recall_pct": round(redflag_caught / len(red_rows) * 100, 1),
        "red_flag_cases": len(red_rows),
        "ambiguous_cases": sum(1 for row in rows if row["case_type"] == "ambiguous"),
        "note": "Synthetic demonstration corpus. It complements, but does not replace, the smaller frozen regression split.",
    }


def main() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    rows = generate_dataset()
    summary = evaluate_dataset(rows)

    dataset_path = DATA / "v2_large_demo_dataset.json"
    summary_path = DATA / "v2_large_demo_summary.json"
    dataset_path.write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    summary_path.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"Wrote {dataset_path.relative_to(ROOT)}")
    print(f"Wrote {summary_path.relative_to(ROOT)}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
