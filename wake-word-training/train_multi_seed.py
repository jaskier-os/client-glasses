"""Train wake word models with multiple seeds and pick the best one.

Runs train.py logic N times with different random seeds, evaluates each
with sliding window detection, and keeps the model with:
  - FP rate < 5% on negatives
  - Highest recall on positives

Usage:
    python train_multi_seed.py                    # 5 seeds, default params
    python train_multi_seed.py --seeds 10         # 10 seeds
    python train_multi_seed.py --epochs 80        # More epochs per seed
"""

import argparse
import copy
import logging
import os
import random
import shutil
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODELS_DIR = BASE_DIR / "models"

# Import from train.py
import sys
sys.path.insert(0, str(BASE_DIR))
from train import (
    get_onnx_models, load_and_augment_clips, extract_features_batch,
    train_model, export_onnx, WakeWordDNN, evaluate_sliding_window,
    N_EMBEDDING_FRAMES, EMBEDDING_DIM, FEATURES_DIR, TARGET_SR, load_wav
)


def evaluate_model_detailed(cls_path: str, mel_path: str, emb_path: str,
                            threshold: float = 0.8, required_hits: int = 2, window_size: int = 5):
    """Evaluate model and return structured results (not just print)."""
    from train import load_wav
    from test_android_pipeline import AndroidPipelineSimulator, CHUNK_SIZE, SAMPLE_RATE as SIM_SR

    sim = AndroidPipelineSimulator(mel_path, emb_path, cls_path)

    def detect_in_file(wav_path):
        audio = load_wav(wav_path)
        sim.reset()

        warmup = np.zeros(SIM_SR * 3, dtype=np.int16)
        for i in range(0, len(warmup), CHUNK_SIZE):
            chunk = warmup[i:i + CHUNK_SIZE]
            if len(chunk) < CHUNK_SIZE:
                chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
            sim.feed_chunk(chunk)

        audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        silence = np.zeros(SIM_SR * 2, dtype=np.int16)
        audio_int16 = np.concatenate([silence, audio_int16, silence])

        score_history = []
        detections = 0
        cooldown_frames = 0
        max_score = 0.0

        for i in range(0, len(audio_int16), CHUNK_SIZE):
            chunk = audio_int16[i:i + CHUNK_SIZE]
            if len(chunk) < CHUNK_SIZE:
                chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
            score = sim.feed_chunk(chunk)
            max_score = max(max_score, score)

            if cooldown_frames > 0:
                cooldown_frames -= 1
                continue

            score_history.append(score > threshold)
            if len(score_history) > window_size:
                score_history.pop(0)

            hits = sum(score_history)
            if hits >= required_hits:
                detections += 1
                score_history.clear()
                cooldown_frames = 25

        return detections, max_score

    results = {}

    # Evaluate positives
    pos_dirs = [DATA_DIR / "recorded_positive"]
    total_pos = 0
    detected_pos = 0
    for d in pos_dirs:
        if not d.exists():
            continue
        for f in sorted(d.glob("*.wav"))[:200]:
            n_det, _ = detect_in_file(f)
            total_pos += 1
            if n_det > 0:
                detected_pos += 1

    results["pos_total"] = total_pos
    results["pos_detected"] = detected_pos
    results["recall"] = detected_pos / max(total_pos, 1)

    # Evaluate negatives
    neg_dirs = [DATA_DIR / "recorded_negative", DATA_DIR / "adversarial"]
    total_neg = 0
    fp_neg = 0
    fp_files = []
    for d in neg_dirs:
        if not d.exists():
            continue
        for f in sorted(d.glob("*.wav"))[:300]:
            n_det, max_s = detect_in_file(f)
            total_neg += 1
            if n_det > 0:
                fp_neg += 1
                fp_files.append((f.name, max_s))

    results["neg_total"] = total_neg
    results["neg_fp"] = fp_neg
    results["fp_rate"] = fp_neg / max(total_neg, 1)
    results["fp_files"] = fp_files

    # Evaluate specifically on synthetic vowels (the hard case)
    vowel_dir = DATA_DIR / "adversarial"
    vowel_total = 0
    vowel_fp = 0
    for f in sorted(vowel_dir.glob("synth_vowel_*.wav")):
        n_det, _ = detect_in_file(f)
        vowel_total += 1
        if n_det > 0:
            vowel_fp += 1
    for f in sorted(vowel_dir.glob("kokoro_vowel_*.wav"))[:50]:
        n_det, _ = detect_in_file(f)
        vowel_total += 1
        if n_det > 0:
            vowel_fp += 1

    results["vowel_total"] = vowel_total
    results["vowel_fp"] = vowel_fp
    results["vowel_fp_rate"] = vowel_fp / max(vowel_total, 1)

    return results


def main():
    parser = argparse.ArgumentParser(description="Multi-seed wake word training")
    parser.add_argument("--seeds", type=int, default=5, help="Number of seeds to try (default: 5)")
    parser.add_argument("--epochs", type=int, default=50, help="Epochs per seed (default: 50)")
    parser.add_argument("--batch-size", type=int, default=128, help="Batch size (default: 128)")
    parser.add_argument("--lr", type=float, default=0.0001, help="Learning rate (default: 0.0001)")
    parser.add_argument("--augment-factor", type=int, default=5, help="Augmented copies per clip")
    parser.add_argument("--threshold", type=float, default=0.8, help="Detection threshold for eval")
    parser.add_argument("--max-fp-rate", type=float, default=0.05, help="Max acceptable FP rate (default: 0.05)")
    args = parser.parse_args()

    FEATURES_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    mel_path, emb_path = get_onnx_models()
    log.info(f"Mel model: {mel_path}")
    log.info(f"Emb model: {emb_path}")

    # Step 1: Extract features (once, reuse across seeds)
    pos_cache = FEATURES_DIR / "positive_features.npy"
    adv_cache = FEATURES_DIR / "adversarial_features.npy"

    if pos_cache.exists() and adv_cache.exists():
        log.info("Loading cached features...")
        pos_features = np.load(str(pos_cache))
        adv_features = np.load(str(adv_cache))
    else:
        # Load clips
        pos_dir = DATA_DIR / "positive"
        adv_dir = DATA_DIR / "adversarial"
        rec_pos_dir = DATA_DIR / "recorded_positive"
        rec_neg_dir = DATA_DIR / "recorded_negative"

        positive_clips = load_and_augment_clips(pos_dir, n_augmented_per_clip=args.augment_factor)
        if rec_pos_dir.exists() and list(rec_pos_dir.glob("*.wav")):
            rec_aug = max(args.augment_factor * 3, 10)
            recorded_pos = load_and_augment_clips(rec_pos_dir, n_augmented_per_clip=rec_aug)
            positive_clips.extend(recorded_pos)

        aug_pos_dir = DATA_DIR / "augmented_positive"
        if aug_pos_dir.exists() and list(aug_pos_dir.glob("*.wav")):
            aug_pos = load_and_augment_clips(aug_pos_dir, n_augmented_per_clip=0)
            positive_clips.extend(aug_pos)

        adversarial_clips = []
        if adv_dir.exists() and list(adv_dir.glob("*.wav")):
            adversarial_clips = load_and_augment_clips(adv_dir, n_augmented_per_clip=max(args.augment_factor // 2, 1))

        if rec_neg_dir.exists() and list(rec_neg_dir.glob("*.wav")):
            rec_neg_aug = max(args.augment_factor * 2, 5)
            recorded_neg = load_and_augment_clips(rec_neg_dir, n_augmented_per_clip=rec_neg_aug)
            adversarial_clips.extend(recorded_neg)

        aug_neg_dir = DATA_DIR / "augmented_negative"
        if aug_neg_dir.exists() and list(aug_neg_dir.glob("*.wav")):
            aug_neg = load_and_augment_clips(aug_neg_dir, n_augmented_per_clip=0)
            adversarial_clips.extend(aug_neg)

        log.info("Extracting positive features...")
        mel_session = ort.InferenceSession(mel_path)
        emb_session = ort.InferenceSession(emb_path)
        pos_features = extract_features_batch(positive_clips, mel_session, emb_session)
        if pos_features.ndim == 4:
            pos_features = pos_features.squeeze(1)

        log.info("Extracting adversarial features...")
        adv_features = extract_features_batch(adversarial_clips, mel_session, emb_session)
        if adv_features.ndim == 4:
            adv_features = adv_features.squeeze(1)

        np.save(str(pos_cache), pos_features)
        np.save(str(adv_cache), adv_features)

    log.info(f"Features: {pos_features.shape[0]} positive, {adv_features.shape[0]} adversarial")

    # Load background features
    bg_files = []
    bg_dir = FEATURES_DIR / "background"
    if bg_dir.exists():
        bg_files = sorted(bg_dir.glob("*.npy"))
    log.info(f"Background feature files: {len(bg_files)}")

    # Step 2: Train with multiple seeds
    seed_list = [42, 123, 256, 512, 1024, 2048, 3141, 4096, 7777, 9999][:args.seeds]
    results = []

    for i, seed in enumerate(seed_list):
        log.info(f"\n{'='*60}")
        log.info(f"SEED {seed} ({i+1}/{len(seed_list)})")
        log.info(f"{'='*60}")

        model_path = MODELS_DIR / f"sireneviy_seed{seed}.onnx"

        model = train_model(
            pos_features, adv_features, bg_files,
            epochs=args.epochs, batch_size=args.batch_size,
            lr=args.lr, seed=seed,
        )
        export_onnx(model, model_path)

        # Evaluate
        log.info(f"Evaluating seed {seed}...")
        eval_results = evaluate_model_detailed(
            str(model_path), mel_path, emb_path,
            threshold=args.threshold
        )

        recall = eval_results["recall"]
        fp_rate = eval_results["fp_rate"]
        vowel_fp_rate = eval_results["vowel_fp_rate"]

        log.info(f"  Recall: {eval_results['pos_detected']}/{eval_results['pos_total']} ({recall*100:.1f}%)")
        log.info(f"  FP rate: {eval_results['neg_fp']}/{eval_results['neg_total']} ({fp_rate*100:.1f}%)")
        log.info(f"  Vowel FP: {eval_results['vowel_fp']}/{eval_results['vowel_total']} ({vowel_fp_rate*100:.1f}%)")
        if eval_results["fp_files"]:
            log.info(f"  FP files: {[f[0][:40] for f in eval_results['fp_files'][:5]]}")

        results.append({
            "seed": seed,
            "model_path": model_path,
            "recall": recall,
            "fp_rate": fp_rate,
            "vowel_fp_rate": vowel_fp_rate,
            "eval": eval_results,
        })

    # Step 3: Pick the best model
    log.info(f"\n{'='*60}")
    log.info("RESULTS SUMMARY")
    log.info(f"{'='*60}")

    for r in results:
        status = "PASS" if r["fp_rate"] <= args.max_fp_rate else "FAIL"
        log.info(f"  Seed {r['seed']:5d}: recall={r['recall']*100:.1f}%  fp={r['fp_rate']*100:.1f}%  "
                 f"vowel_fp={r['vowel_fp_rate']*100:.1f}%  [{status}]")

    # Filter by FP threshold, then sort by recall
    passing = [r for r in results if r["fp_rate"] <= args.max_fp_rate]

    if passing:
        # Among passing models, prefer: lowest vowel FP, then highest recall
        best = sorted(passing, key=lambda r: (-r["recall"], r["vowel_fp_rate"], r["fp_rate"]))[0]
        log.info(f"\nBEST MODEL: seed={best['seed']} "
                 f"recall={best['recall']*100:.1f}% fp={best['fp_rate']*100:.1f}% "
                 f"vowel_fp={best['vowel_fp_rate']*100:.1f}%")

        # Copy best to the canonical location
        final_path = MODELS_DIR / "sireneviy.onnx"
        shutil.copy2(str(best["model_path"]), str(final_path))
        log.info(f"Saved best model to {final_path}")

        # Also copy to phone assets
        phone_assets = Path("/media/user/Lobotomite/Repository/AI/clients/phone/app/src/main/assets")
        if phone_assets.exists():
            shutil.copy2(str(final_path), str(phone_assets / "sireneviy.onnx"))
            log.info(f"Copied to phone assets: {phone_assets / 'sireneviy.onnx'}")
    else:
        log.warning(f"NO model passed the {args.max_fp_rate*100:.0f}% FP threshold!")
        log.warning("Consider: more negative data, more epochs, or relaxing the threshold.")
        # Still pick the best of the bunch
        best = sorted(results, key=lambda r: (r["fp_rate"], -r["recall"]))[0]
        log.info(f"Least-bad model: seed={best['seed']} "
                 f"recall={best['recall']*100:.1f}% fp={best['fp_rate']*100:.1f}%")
        final_path = MODELS_DIR / "sireneviy.onnx"
        shutil.copy2(str(best["model_path"]), str(final_path))
        log.info(f"Saved least-bad model to {final_path}")

    # Cleanup intermediate models (keep best)
    for r in results:
        if r["model_path"] != best["model_path"] and r["model_path"].exists():
            r["model_path"].unlink()
    # Rename best seed model too
    if best["model_path"].exists() and best["model_path"] != MODELS_DIR / "sireneviy.onnx":
        best["model_path"].unlink()

    log.info("Done!")


if __name__ == "__main__":
    main()
