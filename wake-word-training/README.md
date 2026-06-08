# wake-word-training

Training pipeline for the "sireneviy" wake word used by the glasses listener. Produces the
ONNX models the app ships (`sireneviy.onnx`, `embedding_model.onnx`, `melspectrogram.onnx`).

## Run

```bash
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
python train_pipeline.py          # full pipeline; see train.py / train_multi_seed.py for variants
```

Helper scripts: `record_samples.py` (record positives), `generate_clips.py` /
`generate_vowel_negatives.py` / `augment_samples.py` (synthesize and augment data),
`test_live.py` / `test_on_phone.py` / `stress_test.py` (evaluate).

## What's here vs what you generate

Committed:
- the training/eval scripts
- `models/` — the trained model outputs
- `data/background_additives/` — ambient/noise clips (street, restaurant, etc.) used as the
  source for augmentation

NOT committed, you must generate/provide these before training:
- `data/{positive,recorded_positive,recorded_negative,adversarial,augmented_*,features}` —
  recorded and generated datasets. Recreate them with the record/generate/augment scripts.
- `voices/` — the Piper TTS speaker voices used to synthesize speech samples. These are
  standard public Piper voices (e.g. ru_RU-ruslan/irina/dmitri/denis from
  rhasspy/piper-voices on HuggingFace). Download the ones you want into `voices/<name>/`
  before running the clip generators.
- `venv/`
