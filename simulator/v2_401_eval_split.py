"""
Pulsaride V2 — V2-401 : Freeze evaluation dataset
Split train/val/test sans fuite de template + suite red-flag dédiée
"""

import json
import random
from pathlib import Path

SEED = 42
random.seed(SEED)

# Suite red-flag : cas qui DOIVENT déclencher urgency>=3 quel que soit le modèle
RED_FLAG_SUITE = [
    {
        "id": "redflag_001",
        "free_text": "J'ai une douleur intense dans la poitrine qui irradie dans le bras gauche depuis 10 minutes.",
        "expected_min_urgency": 3,
        "trigger_reason": "douleur thoracique irradiante"
    },
    {
        "id": "redflag_002",
        "free_text": "Mon bébé de 3 mois a 40°C de fièvre et ne se réveille plus normalement.",
        "expected_min_urgency": 3,
        "trigger_reason": "fievre nourrisson + trouble conscience"
    },
    {
        "id": "redflag_003",
        "free_text": "Je n'arrive plus à respirer correctement depuis ce matin, ça empire.",
        "expected_min_urgency": 3,
        "trigger_reason": "detresse respiratoire"
    },
    {
        "id": "redflag_004",
        "free_text": "J'ai des idées noires et je ne me sens plus capable de continuer.",
        "expected_min_urgency": 3,
        "trigger_reason": "risque suicidaire"
    },
    {
        "id": "redflag_005",
        "free_text": "Mon enfant a avalé des médicaments il y a 20 minutes, je ne sais pas combien.",
        "expected_min_urgency": 3,
        "trigger_reason": "intoxication enfant"
    },
    {
        "id": "redflag_006",
        "free_text": "J'ai une faiblesse soudaine d'un côté du visage et je n'arrive plus à parler correctement.",
        "expected_min_urgency": 3,
        "trigger_reason": "suspicion AVC"
    },
]


def load_corpus(path: str = "data/v2/free_text_corpus.json") -> list:
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def split_by_template_group(corpus: list) -> dict:
    """
    Split train/val/test SANS fuite : on groupe par (specialty_hint, is_ambiguous)
    car les textes du même groupe partagent le même template source.
    """
    groups = {}
    for entry in corpus:
        key = (entry["ground_truth"]["specialty_hint"], entry["ground_truth"]["is_ambiguous"])
        groups.setdefault(key, []).append(entry)

    train, val, test = [], [], []

    for key, items in groups.items():
        random.shuffle(items)
        n = len(items)
        n_train = max(1, int(n * 0.6))
        n_val = max(1, int(n * 0.2)) if n > 2 else 0

        train.extend(items[:n_train])
        val.extend(items[n_train:n_train + n_val])
        test.extend(items[n_train + n_val:])

    random.shuffle(train)
    random.shuffle(val)
    random.shuffle(test)

    return {"train": train, "val": val, "test": test}


def build_frozen_dataset():
    corpus = load_corpus()
    splits = split_by_template_group(corpus)

    output = {
        "seed": SEED,
        "split_strategy": "grouped_by_specialty_and_ambiguity_no_leakage",
        "sizes": {k: len(v) for k, v in splits.items()},
        "train": splits["train"],
        "val": splits["val"],
        "test": splits["test"],
        "red_flag_suite": RED_FLAG_SUITE,
    }

    Path("data/v2").mkdir(parents=True, exist_ok=True)
    with open("data/v2/frozen_eval_dataset.json", "w", encoding="utf-8") as f:
        json.dump(output, f, ensure_ascii=False, indent=2)

    print("📊 Dataset gelé — V2-401")
    print(f"   Train : {len(splits['train'])} entrées")
    print(f"   Val   : {len(splits['val'])} entrées")
    print(f"   Test  : {len(splits['test'])} entrées")
    print(f"   Red-flag suite : {len(RED_FLAG_SUITE)} cas")
    print(f"\n✅ Sauvegardé → data/v2/frozen_eval_dataset.json")
    print(f"\n⚠️  Ce fichier est GELÉ à partir de maintenant (seed={SEED}).")
    print(f"   Ne pas régénérer le corpus source sans créer une nouvelle version datée.")


if __name__ == "__main__":
    build_frozen_dataset()
