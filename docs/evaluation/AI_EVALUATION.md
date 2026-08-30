# Pulsaride V2 AI Evaluation

## Purpose

This evaluation measures the integrated Pulsaride endpoint `POST /ai/triage`, including the Darija Health NLP provider and the Pulsaride deterministic safety-floor rules. It is software/AI engineering validation, not clinical validation.

## Dataset Provenance

- **darija_holdout**: 0 usable cases.
  Source file: `/home/salmane/projects/darija-health-nlp/data/sample/sample_medqa_ma.csv`.
  Warning: Exact Darija Health NLP data/processed/test.csv was not present locally. Using sample_medqa_ma.csv only as a labelled sample/proxy, not as a claimed training-independent holdout.
- **external_hf_arabic_medical_consultations**: 240 usable cases.
  Repository: https://huggingface.co/datasets/Youssefx64/Medical-Consultation-Questions-in-Arabic.
- **external_hf_darija_medqa_test**: 300 usable cases.
  Repository: https://huggingface.co/datasets/BrainHealthAI/MedQADataDarijaSaad.
- **pulsaride_red_flags**: 6 usable cases.
- **pulsaride_synthetic**: 840 usable cases.

## Summary Metrics

- API success rate: **100.0%** (1386/1386).
- Specialty accuracy: **52.53%** on 1188 labelled cases.
- Specialty macro F1: **44.81%**.
- Specialty weighted F1: **55.42%**.
- Urgency exact match: **39.7%** on 1146 labelled cases.
- Red-flag recall: **100.0%** (86/86, target >= 90%).
- Latency avg/P50/P95/P99: **241.4 / 199.28 / 584.43 / 988.09 ms**.

## Top Specialty Confusions

- Expected `generaliste`, predicted `urgence`: 107 cases.
- Expected `dermatologie`, predicted `pediatrie`: 48 cases.
- Expected `orl`, predicted `generaliste`: 41 cases.
- Expected `ophtalmologie`, predicted `generaliste`: 34 cases.
- Expected `gynecologie`, predicted `generaliste`: 34 cases.
- Expected `ophtalmologie`, predicted `pediatrie`: 28 cases.
- Expected `generaliste`, predicted `gastroenterologie`: 26 cases.
- Expected `pediatrie`, predicted `generaliste`: 25 cases.
- Expected `orl`, predicted `neurologie`: 21 cases.
- Expected `gynecologie`, predicted `urgence`: 21 cases.

## Interpretation

- The Darija Health NLP repository is reused as an external FastAPI model service: https://github.com/SalmaneSossey/darija-health-nlp.
- OpenAI is not required for this evaluation and remains optional because it is paid.
- The external Arabic dataset is used only for evaluation and only for categories with a defensible mapping to Pulsaride specialties.
- The synthetic Pulsaride corpus is useful for regression and system coverage, but should not be presented as a clinically validated benchmark.
- A stronger future step would be restoring the full original MedQA-MA training artifact bundle and running the same evaluator against the exact held-out test split.

## Generated Figures

- `docs/evaluation/ai_confusion_matrix.png`
- `docs/evaluation/ai_per_specialty_f1.png`
- `docs/evaluation/ai_urgency_confusion.png`
- `docs/evaluation/ai_latency_distribution.png`
- `docs/evaluation/ai_redflag_recall.png`
