"""Full training pipeline: augment -> balance ratios -> train with rolling validation -> multi-seed.

Steps:
  1. Re-augment all samples with background noise/pitch/speed variations
  2. Extract features with proper positive:negative ratio (1:4)
  3. Train with rolling validation (train on 80%, validate on 20% held-out)
  4. Pick best epoch checkpoint per seed
  5. Run multiple seeds, pick overall best

Usage:
    python train_pipeline.py                        # Full pipeline
    python train_pipeline.py --skip-augment         # Skip augmentation step
    python train_pipeline.py --seeds 8 --epochs 80  # More seeds/epochs
"""

import argparse
import copy
import logging
import random
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODELS_DIR = BASE_DIR / "models"
FEATURES_DIR = DATA_DIR / "features"

sys.path.insert(0, str(BASE_DIR))
from train import (
    get_onnx_models, load_and_augment_clips, extract_features_batch,
    WakeWordDNN, export_onnx, load_wav,
    N_EMBEDDING_FRAMES, EMBEDDING_DIM, TARGET_SR,
)

TARGET_POS_NEG_RATIO = 4  # 1 positive : 4 negatives


def step1_augment():
    """Re-run augmentation with background noise mixing."""
    log.info("=" * 60)
    log.info("STEP 1: Augmenting samples with background noise")
    log.info("=" * 60)

    # Clear old augmented data
    aug_pos = DATA_DIR / "augmented_positive"
    aug_neg = DATA_DIR / "augmented_negative"
    if aug_pos.exists():
        shutil.rmtree(aug_pos)
        log.info("Cleared old augmented positives")
    if aug_neg.exists():
        shutil.rmtree(aug_neg)
        log.info("Cleared old augmented negatives")

    # Run augment_samples.py
    result = subprocess.run(
        [sys.executable, str(BASE_DIR / "augment_samples.py")],
        capture_output=True, text=True, cwd=str(BASE_DIR)
    )
    for line in result.stdout.strip().split("\n")[-10:]:
        log.info(f"  {line}")
    if result.returncode != 0:
        log.error(f"Augmentation failed: {result.stderr[-500:]}")
        return False
    return True


def step2_extract_features(augment_factor_raw=3):
    """Extract features with proper ratios.

    Sources and their weights:
      Positive:
        - recorded_positive: real voice, highest value (aug_factor * 3)
        - augmented_positive: noise-mixed real+TTS (no further augment, already mixed)
        - positive: raw TTS (aug_factor * 1)

      Negative:
        - recorded_negative: real voice negatives including vowels (aug_factor * 3)
        - augmented_negative: noise-mixed negatives + bg slices (no further augment)
        - adversarial: TTS adversarial (aug_factor * 1)
    """
    log.info("=" * 60)
    log.info("STEP 2: Extracting features with balanced ratios")
    log.info("=" * 60)

    # Clear cached features
    for f in FEATURES_DIR.glob("*.npy"):
        if f.name in ("positive_features.npy", "adversarial_features.npy"):
            f.unlink()
            log.info(f"Cleared {f.name}")

    # --- Load positive clips ---
    pos_clips = []

    # Recorded positives (highest value -- real voice)
    rec_pos_dir = DATA_DIR / "recorded_positive"
    if rec_pos_dir.exists() and list(rec_pos_dir.glob("*.wav")):
        clips = load_and_augment_clips(rec_pos_dir, n_augmented_per_clip=augment_factor_raw * 3)
        log.info(f"Recorded positives: {len(clips)} (from {len(list(rec_pos_dir.glob('*.wav')))} files)")
        pos_clips.extend(clips)

    # Augmented positives (already noise-mixed)
    aug_pos_dir = DATA_DIR / "augmented_positive"
    if aug_pos_dir.exists() and list(aug_pos_dir.glob("*.wav")):
        clips = load_and_augment_clips(aug_pos_dir, n_augmented_per_clip=0)
        log.info(f"Augmented positives: {len(clips)}")
        pos_clips.extend(clips)

    # Raw TTS positives
    pos_dir = DATA_DIR / "positive"
    if pos_dir.exists() and list(pos_dir.glob("*.wav")):
        clips = load_and_augment_clips(pos_dir, n_augmented_per_clip=augment_factor_raw)
        log.info(f"TTS positives: {len(clips)}")
        pos_clips.extend(clips)

    log.info(f"Total positive clips: {len(pos_clips)}")

    # --- Load negative clips ---
    neg_clips = []

    # Recorded negatives (real voice -- includes vowel samples!)
    rec_neg_dir = DATA_DIR / "recorded_negative"
    if rec_neg_dir.exists() and list(rec_neg_dir.glob("*.wav")):
        clips = load_and_augment_clips(rec_neg_dir, n_augmented_per_clip=augment_factor_raw * 3)
        log.info(f"Recorded negatives: {len(clips)} (from {len(list(rec_neg_dir.glob('*.wav')))} files)")
        neg_clips.extend(clips)

    # Augmented negatives (already noise-mixed + bg slices)
    aug_neg_dir = DATA_DIR / "augmented_negative"
    if aug_neg_dir.exists() and list(aug_neg_dir.glob("*.wav")):
        clips = load_and_augment_clips(aug_neg_dir, n_augmented_per_clip=0)
        log.info(f"Augmented negatives: {len(clips)}")
        neg_clips.extend(clips)

    # Raw adversarial (TTS)
    adv_dir = DATA_DIR / "adversarial"
    if adv_dir.exists() and list(adv_dir.glob("*.wav")):
        clips = load_and_augment_clips(adv_dir, n_augmented_per_clip=max(augment_factor_raw // 2, 1))
        log.info(f"Adversarial: {len(clips)}")
        neg_clips.extend(clips)

    log.info(f"Total negative clips: {len(neg_clips)}")

    # --- Balance ratio ---
    target_neg = len(pos_clips) * TARGET_POS_NEG_RATIO
    if len(neg_clips) > target_neg:
        # Downsample negatives
        random.shuffle(neg_clips)
        neg_clips = neg_clips[:target_neg]
        log.info(f"Downsampled negatives to {len(neg_clips)} (ratio 1:{TARGET_POS_NEG_RATIO})")
    elif len(neg_clips) < target_neg:
        # Upsample negatives by repeating with augmentation
        deficit = target_neg - len(neg_clips)
        extra = [neg_clips[i % len(neg_clips)] for i in range(deficit)]
        neg_clips.extend(extra)
        log.info(f"Upsampled negatives to {len(neg_clips)} (ratio 1:{TARGET_POS_NEG_RATIO})")

    log.info(f"Final ratio: {len(pos_clips)} positive : {len(neg_clips)} negative "
             f"(1:{len(neg_clips)/max(len(pos_clips),1):.1f})")

    # --- Extract features ---
    log.info("Extracting positive features...")
    pos_features = extract_features_batch(pos_clips)
    if pos_features.ndim == 4:
        pos_features = pos_features.squeeze(1)
    log.info(f"Positive features: {pos_features.shape}")

    log.info("Extracting negative features...")
    neg_features = extract_features_batch(neg_clips)
    if neg_features.ndim == 4:
        neg_features = neg_features.squeeze(1)
    log.info(f"Negative features: {neg_features.shape}")

    # Save
    FEATURES_DIR.mkdir(parents=True, exist_ok=True)
    np.save(str(FEATURES_DIR / "positive_features.npy"), pos_features)
    np.save(str(FEATURES_DIR / "adversarial_features.npy"), neg_features)
    log.info("Features saved")

    return pos_features, neg_features


def step3_train_rolling_validation(pos_features, neg_features, bg_files,
                                    epochs=60, batch_size=128, lr=0.0001, seed=42):
    """Train with proper validation: stratified split, track best val metrics.

    Uses 85/15 stratified split. Monitors both val_loss and a composite score
    (recall - 2*fp_rate) to pick the checkpoint that balances recall and FP.
    """
    np.random.seed(seed)
    torch.manual_seed(seed)
    random.seed(seed)

    # Stratified split
    val_frac = 0.15
    n_pos = len(pos_features)
    n_neg = len(neg_features)

    pos_idx = np.random.permutation(n_pos)
    neg_idx = np.random.permutation(n_neg)

    n_pos_val = max(int(n_pos * val_frac), 10)
    n_neg_val = max(int(n_neg * val_frac), 10)

    val_pos = pos_features[pos_idx[:n_pos_val]]
    train_pos = pos_features[pos_idx[n_pos_val:]]
    val_neg = neg_features[neg_idx[:n_neg_val]]
    train_neg = neg_features[neg_idx[n_neg_val:]]

    # Load background features
    bg_features_list = []
    for f in bg_files:
        try:
            data = np.load(str(f), mmap_mode="r")
            if data.ndim == 3 and data.shape[1] >= N_EMBEDDING_FRAMES:
                n_bg = min(len(data), 10000)
                idx = np.random.choice(len(data), n_bg, replace=False)
                bg_features_list.append(data[idx, :N_EMBEDDING_FRAMES, :].copy())
            elif data.ndim == 2 and data.shape[1] == EMBEDDING_DIM:
                max_start = len(data) - N_EMBEDDING_FRAMES
                n_windows = min((len(data) - N_EMBEDDING_FRAMES) // N_EMBEDDING_FRAMES, 20000)
                starts = np.random.choice(max_start, n_windows, replace=False)
                windows = np.array([data[s:s + N_EMBEDDING_FRAMES] for s in starts], dtype=np.float32)
                bg_features_list.append(windows)
        except Exception:
            pass

    if bg_features_list:
        bg_features = np.concatenate(bg_features_list, axis=0).astype(np.float32)
        train_neg = np.concatenate([train_neg, bg_features], axis=0)
        n_bg_val = min(500, len(bg_features))
        val_neg = np.concatenate([val_neg, bg_features[:n_bg_val]], axis=0)
        log.info(f"Added {len(bg_features)} background features")

    log.info(f"Train: {len(train_pos)} pos, {len(train_neg)} neg")
    log.info(f"Val: {len(val_pos)} pos, {len(val_neg)} neg")

    # Build tensors
    device = "cpu"
    X_train = np.concatenate([train_pos, train_neg], axis=0)
    y_train = np.concatenate([np.ones(len(train_pos)), np.zeros(len(train_neg))]).astype(np.float32)
    X_val = np.concatenate([val_pos, val_neg], axis=0)
    y_val = np.concatenate([np.ones(len(val_pos)), np.zeros(len(val_neg))]).astype(np.float32)

    X_train_t = torch.from_numpy(X_train).to(device)
    y_train_t = torch.from_numpy(y_train).unsqueeze(1).to(device)
    X_val_t = torch.from_numpy(X_val).to(device)
    y_val_t = torch.from_numpy(y_val).unsqueeze(1).to(device)

    # === Round 1: Initial training ===
    model = WakeWordDNN(n_frames=N_EMBEDDING_FRAMES, embed_dim=EMBEDDING_DIM).to(device)
    loss_fn = nn.BCELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=lr / 100)

    best_score = -float("inf")
    best_state = None
    best_epoch = 0
    best_metrics = {}

    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(len(X_train_t))
        epoch_loss = 0.0
        n_batches = 0

        for i in range(0, len(X_train_t), batch_size):
            X_b = X_train_t[perm[i:i + batch_size]]
            y_b = y_train_t[perm[i:i + batch_size]]
            pred = model(X_b)
            loss = loss_fn(pred, y_b)
            optimizer.zero_grad()
            loss.backward()
            optimizer.step()
            epoch_loss += loss.item()
            n_batches += 1

        scheduler.step()

        # Validate
        model.eval()
        with torch.no_grad():
            val_pred = model(X_val_t)
            val_loss = loss_fn(val_pred, y_val_t).item()
            val_binary = (val_pred > 0.5).float()

            pos_mask = y_val_t == 1
            neg_mask = y_val_t == 0
            recall = (val_binary[pos_mask] == 1).float().mean().item() if pos_mask.any() else 0
            fp_rate = (val_binary[neg_mask] == 1).float().mean().item() if neg_mask.any() else 0

        # Composite score: heavily penalize FP while maintaining recall
        score = recall - 3.0 * fp_rate

        if score > best_score:
            best_score = score
            best_state = {k: v.clone() for k, v in model.state_dict().items()}
            best_epoch = epoch + 1
            best_metrics = {"recall": recall, "fp_rate": fp_rate, "val_loss": val_loss, "score": score}

        if epoch % 10 == 0 or epoch == epochs - 1:
            log.info(f"  E{epoch+1:3d}/{epochs} loss={epoch_loss/n_batches:.4f} "
                     f"val_loss={val_loss:.4f} recall={recall:.3f} fp={fp_rate:.4f} "
                     f"score={score:.3f} {'*' if epoch+1 == best_epoch else ''}")

    log.info(f"  R1 best: epoch {best_epoch} recall={best_metrics['recall']:.3f} "
             f"fp={best_metrics['fp_rate']:.4f} score={best_metrics['score']:.3f}")

    # === Hard negative mining ===
    model.load_state_dict(best_state)
    model.eval()
    neg_start = len(train_pos)
    with torch.no_grad():
        all_pred = model(X_train_t)
        neg_preds = all_pred[neg_start:].squeeze().cpu().numpy()
    hard_mask = neg_preds > 0.3
    n_hard = hard_mask.sum()

    if n_hard > 0:
        hard_neg = train_neg[hard_mask]
        hard_repeated = np.repeat(hard_neg, 10, axis=0)
        log.info(f"  Hard negative mining: {n_hard} FPs (score>0.3), adding {len(hard_repeated)} copies")

        train_neg_boosted = np.concatenate([train_neg, hard_repeated], axis=0)
        X_train = np.concatenate([train_pos, train_neg_boosted], axis=0)
        y_train = np.concatenate([np.ones(len(train_pos)), np.zeros(len(train_neg_boosted))]).astype(np.float32)

        X_train_t = torch.from_numpy(X_train).to(device)
        y_train_t = torch.from_numpy(y_train).unsqueeze(1).to(device)

        # Round 2 with lower LR
        optimizer = torch.optim.Adam(model.parameters(), lr=lr / 5)
        scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=lr / 500)

        for epoch in range(epochs):
            model.train()
            perm = torch.randperm(len(X_train_t))
            epoch_loss = 0.0
            n_batches = 0

            for i in range(0, len(X_train_t), batch_size):
                X_b = X_train_t[perm[i:i + batch_size]]
                y_b = y_train_t[perm[i:i + batch_size]]
                pred = model(X_b)
                loss = loss_fn(pred, y_b)
                optimizer.zero_grad()
                loss.backward()
                optimizer.step()
                epoch_loss += loss.item()
                n_batches += 1

            scheduler.step()

            model.eval()
            with torch.no_grad():
                val_pred = model(X_val_t)
                val_loss = loss_fn(val_pred, y_val_t).item()
                val_binary = (val_pred > 0.5).float()
                recall = (val_binary[pos_mask] == 1).float().mean().item() if pos_mask.any() else 0
                fp_rate = (val_binary[neg_mask] == 1).float().mean().item() if neg_mask.any() else 0

            score = recall - 3.0 * fp_rate
            if score > best_score:
                best_score = score
                best_state = {k: v.clone() for k, v in model.state_dict().items()}
                best_epoch = epoch + 1
                best_metrics = {"recall": recall, "fp_rate": fp_rate, "val_loss": val_loss, "score": score}

            if epoch % 10 == 0 or epoch == epochs - 1:
                log.info(f"  R2 E{epoch+1:3d}/{epochs} loss={epoch_loss/n_batches:.4f} "
                         f"val_loss={val_loss:.4f} recall={recall:.3f} fp={fp_rate:.4f} "
                         f"score={score:.3f} {'*' if epoch+1 == best_epoch else ''}")

        log.info(f"  R2 best: epoch {best_epoch} recall={best_metrics['recall']:.3f} "
                 f"fp={best_metrics['fp_rate']:.4f} score={best_metrics['score']:.3f}")

    model.load_state_dict(best_state)
    return model, best_metrics


def step4_evaluate_real_world(model_path, mel_path, emb_path, threshold=0.8):
    """Evaluate on real recorded files with sliding window (simulates actual inference)."""
    from train_multi_seed import evaluate_model_detailed
    return evaluate_model_detailed(str(model_path), mel_path, emb_path, threshold=threshold)


def main():
    parser = argparse.ArgumentParser(description="Full training pipeline")
    parser.add_argument("--skip-augment", action="store_true", help="Skip re-augmentation")
    parser.add_argument("--seeds", type=int, default=8, help="Number of seeds (default: 8)")
    parser.add_argument("--epochs", type=int, default=60, help="Epochs per round (default: 60)")
    parser.add_argument("--lr", type=float, default=0.0001, help="Learning rate")
    parser.add_argument("--threshold", type=float, default=0.8, help="Detection threshold for eval")
    parser.add_argument("--max-fp", type=float, default=0.05, help="Max FP rate (default: 5%%)")
    args = parser.parse_args()

    # Step 1: Augment
    if not args.skip_augment:
        if not step1_augment():
            return
    else:
        log.info("Skipping augmentation (--skip-augment)")

    # Step 2: Extract features
    pos_features, neg_features = step2_extract_features()

    # Background features
    bg_dir = FEATURES_DIR / "background"
    bg_files = sorted(bg_dir.glob("*.npy")) if bg_dir.exists() else []
    log.info(f"Background feature files: {len(bg_files)}")

    mel_path, emb_path = get_onnx_models()

    # Step 3+4: Train multiple seeds
    seed_list = [42, 123, 256, 512, 1024, 2048, 3141, 7777][:args.seeds]
    results = []

    for i, seed in enumerate(seed_list):
        log.info("")
        log.info("=" * 60)
        log.info(f"SEED {seed} ({i+1}/{len(seed_list)})")
        log.info("=" * 60)

        model, val_metrics = step3_train_rolling_validation(
            pos_features, neg_features, bg_files,
            epochs=args.epochs, seed=seed, lr=args.lr
        )

        # Export
        model_path = MODELS_DIR / f"sireneviy_seed{seed}.onnx"
        export_onnx(model, model_path)

        # Real-world evaluation
        log.info(f"Real-world evaluation (seed {seed})...")
        eval_r = step4_evaluate_real_world(model_path, mel_path, emb_path, args.threshold)

        log.info(f"  Val: recall={val_metrics['recall']:.3f} fp={val_metrics['fp_rate']:.4f}")
        log.info(f"  Real: recall={eval_r['pos_detected']}/{eval_r['pos_total']} ({eval_r['recall']*100:.1f}%) "
                 f"fp={eval_r['neg_fp']}/{eval_r['neg_total']} ({eval_r['fp_rate']*100:.1f}%) "
                 f"vowel_fp={eval_r['vowel_fp']}/{eval_r['vowel_total']} ({eval_r['vowel_fp_rate']*100:.1f}%)")

        results.append({
            "seed": seed,
            "model_path": model_path,
            "val_metrics": val_metrics,
            "recall": eval_r["recall"],
            "fp_rate": eval_r["fp_rate"],
            "vowel_fp_rate": eval_r["vowel_fp_rate"],
            "eval": eval_r,
        })

    # Step 5: Pick the best
    log.info("")
    log.info("=" * 60)
    log.info("RESULTS SUMMARY")
    log.info("=" * 60)

    for r in results:
        status = "PASS" if r["fp_rate"] <= args.max_fp else "FAIL"
        log.info(f"  Seed {r['seed']:5d}: recall={r['recall']*100:.1f}% "
                 f"fp={r['fp_rate']*100:.1f}% vowel_fp={r['vowel_fp_rate']*100:.1f}% [{status}]")

    passing = [r for r in results if r["fp_rate"] <= args.max_fp]
    if passing:
        best = sorted(passing, key=lambda r: (-r["recall"], r["vowel_fp_rate"], r["fp_rate"]))[0]
    else:
        log.warning(f"No model passed {args.max_fp*100:.0f}% FP threshold")
        best = sorted(results, key=lambda r: (r["fp_rate"], -r["recall"]))[0]

    log.info(f"\nBEST: seed={best['seed']} recall={best['recall']*100:.1f}% "
             f"fp={best['fp_rate']*100:.1f}% vowel_fp={best['vowel_fp_rate']*100:.1f}%")

    # Save best
    final_path = MODELS_DIR / "sireneviy.onnx"
    shutil.copy2(str(best["model_path"]), str(final_path))
    log.info(f"Saved to {final_path}")

    phone_assets = Path("/media/user/Lobotomite/Repository/AI/clients/phone/app/src/main/assets")
    if phone_assets.exists():
        shutil.copy2(str(final_path), str(phone_assets / "sireneviy.onnx"))
        log.info(f"Copied to phone assets")

    # Cleanup
    for r in results:
        if r["model_path"].exists() and r["model_path"] != final_path:
            r["model_path"].unlink()

    log.info("Pipeline complete!")


if __name__ == "__main__":
    main()
