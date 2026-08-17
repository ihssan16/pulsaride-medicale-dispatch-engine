"""
Pulsaride V2 — IA3 : Embeddings TF-IDF + Matching sémantique
Approche légère (sans PyTorch) : TF-IDF + similarité cosinus
Owner: Ihssan Ben Labsir
"""

import json
import numpy as np
from pathlib import Path
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.metrics.pairwise import cosine_similarity as sk_cosine


def build_professional_profiles() -> list:
    """Profils textuels des pros simulés (doc V2 §5.3)."""
    return [
        {"id": "pro_001", "specialty_tag": "pediatrie",
         "profile_text": "Pédiatre 10 ans experience suivi nourrissons enfants maladies infectieuses infantiles fievre toux"},
        {"id": "pro_002", "specialty_tag": "generaliste",
         "profile_text": "Medecin generaliste 12 ans experience suivi pediatrique frequent enfants maladies infectieuses urgences mineures fatigue douleur"},
        {"id": "pro_003", "specialty_tag": "cardiologie",
         "profile_text": "Cardiologue 15 ans experience troubles rythme hypertension suivi infarctus douleur poitrine palpitations essoufflement"},
        {"id": "pro_004", "specialty_tag": "orl",
         "profile_text": "ORL 8 ans experience sinusites angines otites troubles gorge oreille nez"},
        {"id": "pro_005", "specialty_tag": "psychiatrie",
         "profile_text": "Psychiatre 20 ans experience troubles anxieux depression accompagnement therapeutique stress angoisse sommeil"},
        {"id": "pro_006", "specialty_tag": "dermatologie",
         "profile_text": "Dermatologue 9 ans experience eczema allergies cutanees lesions peau demangeaisons plaques"},
    ]


def score_composite(sim: float, availability: float = 1.0, load: float = 0.3) -> float:
    """Doc V2 §5.3 : sim*0.5 + dispo*0.3 + (1-charge)*0.2"""
    return sim * 0.5 + availability * 0.3 + (1 - load) * 0.2


def run_ia3_matching(input_path: str = "data/v2/ia2_results.json",
                      output_path: str = "data/v2/ia3_results.json"):
    with open(input_path, "r", encoding="utf-8") as f:
        results = json.load(f)

    professionals = build_professional_profiles()

    # Corpus complet : profils pros + textes patients (nécessaire pour un TF-IDF partagé)
    pro_texts = [p["profile_text"] for p in professionals]
    patient_texts = [e["free_text"] for e in results]
    full_corpus = pro_texts + patient_texts

    print(f"📦 Vectorisation TF-IDF sur {len(full_corpus)} documents...")
    vectorizer = TfidfVectorizer()
    tfidf_matrix = vectorizer.fit_transform(full_corpus)
    print("✅ Vectorisation terminée\n")

    n_pros = len(professionals)
    pro_vectors = tfidf_matrix[:n_pros]
    patient_vectors = tfidf_matrix[n_pros:]

    print(f"🔍 IA3 — Matching sémantique sur {len(results)} demandes\n")

    for i, entry in enumerate(results):
        embedding_cas = patient_vectors[i]
        sims = sk_cosine(embedding_cas, pro_vectors).flatten()

        scores = []
        for j, pro in enumerate(professionals):
            sim = float(sims[j])
            composite = score_composite(sim)
            scores.append({
                "pro_id": pro["id"],
                "specialty_tag": pro["specialty_tag"],
                "similarity": round(sim, 3),
                "score_composite": round(composite, 3)
            })

        scores.sort(key=lambda s: s["score_composite"], reverse=True)
        best_match = scores[0]

        entry["ia3_matching"] = {
            "best_pro_id": best_match["pro_id"],
            "best_specialty": best_match["specialty_tag"],
            "similarity": best_match["similarity"],
            "score_composite": best_match["score_composite"],
            "all_scores": scores
        }

        gt_specialty = entry["ground_truth"]["specialty_hint"]
        match_icon = "✅" if best_match["specialty_tag"] == gt_specialty else "⚠️"
        print(f"  {match_icon} {entry['patient_id']:<20} → {best_match['pro_id']} "
              f"({best_match['specialty_tag']}) sim={best_match['similarity']} "
              f"| GT specialty={gt_specialty}")

    matches = sum(1 for e in results
                  if e["ia3_matching"]["best_specialty"] == e["ground_truth"]["specialty_hint"])
    relevance_rate = matches / len(results) * 100 if results else 0

    Path("data/v2").mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(results, f, ensure_ascii=False, indent=2)

    print(f"\n{'='*50}")
    print(f"📊 RÉSULTATS IA3 (TF-IDF)")
    print(f"{'='*50}")
    print(f"Pertinence matching (specialty match) : {relevance_rate:.1f}%")
    print(f"✅ Résultats → {output_path}")

    return results


if __name__ == "__main__":
    run_ia3_matching()
