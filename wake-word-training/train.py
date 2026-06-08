"""Train openWakeWord classifier for Russian wake word "сиреневый".

Pipeline:
  1. Load WAV clips from data/positive/ and data/adversarial/
  2. Augment clips (noise, pitch, speed, volume)
  3. Extract features: audio -> melspectrogram.onnx -> embedding_model.onnx -> (N, 16, 96)
  4. Download ACAV100M background features (pre-computed, from HuggingFace)
  5. Train DNN classifier (matching openWakeWord architecture)
  6. Export as ONNX to models/sireneviy.onnx

Usage:
    python train.py                         # Full pipeline
    python train.py --skip-augment          # Skip augmentation (use raw clips)
    python train.py --skip-background       # Skip ACAV100M download (less negative data)
    python train.py --epochs 100            # Custom epoch count
    python train.py --threshold 0.5         # Detection threshold for evaluation
"""

import argparse
import logging
import os
import random
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
from scipy.io import wavfile
from scipy.signal import resample_poly
from tqdm import tqdm

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
MODELS_DIR = BASE_DIR / "models"
FEATURES_DIR = DATA_DIR / "features"

TARGET_SR = 16000
N_EMBEDDING_FRAMES = 16  # ~1.28 seconds of audio context
EMBEDDING_DIM = 96
MEL_WINDOW = 76  # mel frames per embedding window
MEL_STEP = 8  # mel frame step between windows
MEL_BINS = 32
HOP_SAMPLES = 160  # mel hop size in audio samples

# Background features from HuggingFace (pre-computed by openWakeWord project)
ACAV100M_URL = "https://huggingface.co/datasets/davidscripka/openwakeword_features/resolve/main"


# ---------- Feature Extraction ----------

def get_onnx_models():
    """Locate melspectrogram.onnx and embedding_model.onnx from openwakeword package."""
    try:
        import openwakeword
        models_dir = Path(openwakeword.__file__).parent / "resources" / "models"
        mel_path = models_dir / "melspectrogram.onnx"
        emb_path = models_dir / "embedding_model.onnx"
        if mel_path.exists() and emb_path.exists():
            return str(mel_path), str(emb_path)
    except ImportError:
        pass

    # Fallback: check local models/ dir
    mel_path = MODELS_DIR / "melspectrogram.onnx"
    emb_path = MODELS_DIR / "embedding_model.onnx"
    if mel_path.exists() and emb_path.exists():
        return str(mel_path), str(emb_path)

    raise FileNotFoundError(
        "Cannot find melspectrogram.onnx and embedding_model.onnx. "
        "Install openwakeword (pip install openwakeword) or place them in models/"
    )


def load_wav(path: Path) -> np.ndarray:
    """Load a WAV file as float32 array at 16kHz."""
    sr, audio = wavfile.read(str(path))
    if audio.dtype == np.int16:
        audio = audio.astype(np.float32) / 32768.0
    elif audio.dtype == np.int32:
        audio = audio.astype(np.float32) / 2147483648.0
    elif audio.dtype != np.float32:
        audio = audio.astype(np.float32)

    # Convert stereo to mono
    if audio.ndim > 1:
        audio = audio.mean(axis=1)

    # Resample if needed
    if sr != TARGET_SR:
        from math import gcd
        g = gcd(TARGET_SR, sr)
        audio = resample_poly(audio, TARGET_SR // g, sr // g).astype(np.float32)

    return audio


def extract_features_batch(
    audio_clips: list[np.ndarray],
    mel_session: ort.InferenceSession = None,
    emb_session: ort.InferenceSession = None,
    target_length: int = 32000,  # 2 seconds at 16kHz
) -> np.ndarray:
    """Extract (N, 16, 96) features using openwakeword's own AudioFeatures.

    Uses the same preprocessing as openwakeword.Model.predict() to ensure
    training features exactly match inference features.
    """
    from openwakeword.utils import AudioFeatures

    features = []
    for audio in audio_clips:
        # Pad or trim to target length
        if len(audio) < target_length:
            pad_total = target_length - len(audio)
            pad_left = pad_total // 2
            audio = np.pad(audio, (pad_left, pad_total - pad_left), mode="constant")
        elif len(audio) > target_length:
            start = (len(audio) - target_length) // 2
            audio = audio[start:start + target_length]

        # Convert to int16 (openwakeword expects int16 input)
        audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)

        # Use openwakeword's own feature extractor (fresh instance per clip)
        af = AudioFeatures()

        # Feed audio in 1280-sample chunks (80ms) -- same as streaming inference
        for i in range(0, len(audio_int16) - 1280 + 1, 1280):
            chunk = audio_int16[i:i + 1280]
            af._streaming_features(chunk)

        # Get last N_EMBEDDING_FRAMES from the feature buffer
        clip_features = af.get_features(N_EMBEDDING_FRAMES)
        features.append(clip_features)

    return np.array(features, dtype=np.float32)  # (N, 16, 96)


# ---------- Augmentation ----------

def augment_clip(audio: np.ndarray) -> np.ndarray:
    """Apply random augmentations to a single audio clip."""
    aug = audio.copy()

    # Speed perturbation (0.85x - 1.15x)
    if random.random() < 0.5:
        speed = random.uniform(0.85, 1.15)
        new_len = int(len(aug) / speed)
        aug = np.interp(
            np.linspace(0, len(aug) - 1, new_len),
            np.arange(len(aug)),
            aug,
        ).astype(np.float32)

    # Volume variation (-6dB to +3dB)
    if random.random() < 0.8:
        gain_db = random.uniform(-6, 3)
        aug = aug * (10 ** (gain_db / 20))

    # Add noise (white or pink)
    if random.random() < 0.5:
        snr_db = random.uniform(10, 30)
        signal_power = np.mean(aug ** 2)
        if signal_power > 0:
            noise_power = signal_power / (10 ** (snr_db / 10))
            noise = np.random.randn(len(aug)).astype(np.float32) * np.sqrt(noise_power)
            # Optionally make it pink (1/f)
            if random.random() < 0.5:
                freqs = np.fft.rfftfreq(len(noise))
                freqs[0] = 1  # avoid div by zero
                spectrum = np.fft.rfft(noise)
                spectrum /= np.sqrt(freqs)
                noise = np.fft.irfft(spectrum, n=len(noise)).astype(np.float32)
                noise *= np.sqrt(noise_power) / (np.std(noise) + 1e-8)
            aug = aug + noise

    # Simple reverb (exponential decay convolution)
    if random.random() < 0.3:
        rt60 = random.uniform(0.1, 0.5)  # seconds
        ir_len = int(TARGET_SR * rt60)
        ir = np.random.randn(ir_len).astype(np.float32)
        ir *= np.exp(-np.linspace(0, 6, ir_len))
        ir[0] = 1.0
        ir /= np.sqrt(np.sum(ir ** 2))
        aug_conv = np.convolve(aug, ir, mode="full")[:len(aug)].astype(np.float32)
        mix = random.uniform(0.3, 0.7)
        aug = (1 - mix) * aug + mix * aug_conv

    # Clip to [-1, 1]
    aug = np.clip(aug, -1.0, 1.0)
    return aug


def load_and_augment_clips(
    clip_dir: Path,
    n_augmented_per_clip: int = 5,
) -> list[np.ndarray]:
    """Load all WAV files from a directory and produce augmented versions."""
    wav_files = sorted(clip_dir.glob("*.wav"))
    if not wav_files:
        log.warning(f"No WAV files found in {clip_dir}")
        return []

    log.info(f"Loading {len(wav_files)} clips from {clip_dir}")
    clips = []
    for f in tqdm(wav_files, desc=f"Loading {clip_dir.name}"):
        try:
            audio = load_wav(f)
            # Original (un-augmented)
            clips.append(audio)
            # Augmented versions
            for _ in range(n_augmented_per_clip):
                clips.append(augment_clip(audio))
        except Exception as e:
            log.warning(f"Failed to load {f}: {e}")

    log.info(f"Produced {len(clips)} clips ({len(wav_files)} originals + augmented)")
    return clips


# ---------- DNN Classifier (matches openWakeWord architecture) ----------

class WakeWordDNN(nn.Module):
    """DNN classifier for wake word detection.

    Larger than default openWakeWord (128 hidden vs 32) with 3 FCN blocks
    and dropout for regularization. Needed because the embedding model
    produces very similar features for phonetically close Russian words.

    Input: (batch, 16, 96) -> Output: (batch, 1)
    """

    def __init__(self, n_frames: int = 16, embed_dim: int = 96, layer_dim: int = 128, n_blocks: int = 3, dropout: float = 0.2):
        super().__init__()
        input_dim = n_frames * embed_dim  # 1536

        layers = [
            nn.Flatten(),
            nn.Linear(input_dim, layer_dim),
            nn.ReLU(),
            nn.LayerNorm(layer_dim),
            nn.Dropout(dropout),
        ]
        for _ in range(n_blocks):
            layers.extend([
                nn.Linear(layer_dim, layer_dim),
                nn.ReLU(),
                nn.LayerNorm(layer_dim),
                nn.Dropout(dropout),
            ])
        layers.extend([
            nn.Linear(layer_dim, 1),
            nn.Sigmoid(),
        ])
        self.net = nn.Sequential(*layers)

    def forward(self, x):
        return self.net(x)


# ---------- Training ----------

def download_background_features(max_files: int = 5) -> list[Path]:
    """Download pre-computed ACAV100M background features from HuggingFace.

    These are (N, n_frames, 96) numpy arrays of speech/noise embeddings.
    """
    bg_dir = FEATURES_DIR / "background"
    bg_dir.mkdir(parents=True, exist_ok=True)

    # The dataset has multiple shards. Download a subset for practical training.
    downloaded = []
    for i in range(max_files):
        filename = f"negative_features_{i}.npy"
        dest = bg_dir / filename
        if dest.exists():
            downloaded.append(dest)
            continue

        url = f"{ACAV100M_URL}/{filename}"
        log.info(f"Downloading background features: {filename}")
        try:
            resp = requests.get(url, stream=True, timeout=30)
            if resp.status_code != 200:
                log.warning(f"Could not download {filename} (HTTP {resp.status_code}), skipping")
                break
            total = int(resp.headers.get("content-length", 0))
            with open(dest, "wb") as f:
                with tqdm(total=total, unit="B", unit_scale=True, desc=filename) as pbar:
                    for chunk in resp.iter_content(chunk_size=8192):
                        f.write(chunk)
                        pbar.update(len(chunk))
            downloaded.append(dest)
        except Exception as e:
            log.warning(f"Failed to download {filename}: {e}")
            break

    return downloaded


import requests


def train_model(
    positive_features: np.ndarray,
    adversarial_features: np.ndarray,
    background_feature_files: list[Path],
    epochs: int = 50,
    batch_size: int = 128,
    lr: float = 0.0001,
    val_split: float = 0.15,
    device: str = "cpu",
    seed: int = 42,
) -> WakeWordDNN:
    """Train the DNN classifier."""
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed(seed)
    random.seed(seed)

    # Split positive into train/val
    n_pos = len(positive_features)
    n_val = max(int(n_pos * val_split), 10)
    indices = np.random.permutation(n_pos)
    val_pos = positive_features[indices[:n_val]]
    train_pos = positive_features[indices[n_val:]]

    # Split adversarial into train/val
    n_adv = len(adversarial_features)
    n_adv_val = max(int(n_adv * val_split), 5)
    adv_indices = np.random.permutation(n_adv)
    val_adv = adversarial_features[adv_indices[:n_adv_val]]
    train_adv = adversarial_features[adv_indices[n_adv_val:]]

    # Load background features (sample a manageable subset)
    bg_features_list = []
    for f in background_feature_files:
        try:
            data = np.load(str(f), mmap_mode="r")
            if data.ndim == 3 and data.shape[1] >= N_EMBEDDING_FRAMES:
                # Already windowed: (N, frames, 96)
                n_bg = min(len(data), 10000)
                idx = np.random.choice(len(data), n_bg, replace=False)
                bg_features_list.append(data[idx, :N_EMBEDDING_FRAMES, :].copy())
            elif data.ndim == 2 and data.shape[1] == EMBEDDING_DIM:
                # Flat embeddings: (N, 96) -- create sliding windows of 16 frames
                n_windows = min((len(data) - N_EMBEDDING_FRAMES) // N_EMBEDDING_FRAMES, 20000)
                # Sample random starting positions
                max_start = len(data) - N_EMBEDDING_FRAMES
                starts = np.random.choice(max_start, n_windows, replace=False)
                windows = np.array([data[s:s + N_EMBEDDING_FRAMES] for s in starts], dtype=np.float32)
                bg_features_list.append(windows)
                log.info(f"Created {n_windows} windows from flat features {f} shape {data.shape}")
            else:
                log.warning(f"Background features in {f} have unexpected shape {data.shape}")
        except Exception as e:
            log.warning(f"Failed to load background features {f}: {e}")

    if bg_features_list:
        bg_features = np.concatenate(bg_features_list, axis=0).astype(np.float32)
        log.info(f"Loaded {len(bg_features)} background feature vectors")
    else:
        bg_features = np.zeros((0, N_EMBEDDING_FRAMES, EMBEDDING_DIM), dtype=np.float32)
        log.warning("No background features loaded. Training with adversarial negatives only.")

    # Combine all negatives
    neg_parts = [train_adv]
    if len(bg_features) > 0:
        neg_parts.append(bg_features)
    train_neg = np.concatenate(neg_parts, axis=0)

    val_neg = val_adv
    if len(bg_features) > 0:
        # Add some background to validation
        n_bg_val = min(500, len(bg_features))
        val_neg = np.concatenate([val_adv, bg_features[:n_bg_val]], axis=0)

    log.info(f"Training data: {len(train_pos)} positive, {len(train_neg)} negative")
    log.info(f"Validation data: {len(val_pos)} positive, {len(val_neg)} negative")

    # Create tensors
    X_train = np.concatenate([train_pos, train_neg], axis=0)
    y_train = np.concatenate([np.ones(len(train_pos)), np.zeros(len(train_neg))]).astype(np.float32)

    X_val = np.concatenate([val_pos, val_neg], axis=0)
    y_val = np.concatenate([np.ones(len(val_pos)), np.zeros(len(val_neg))]).astype(np.float32)

    # Initialize model
    model = WakeWordDNN(n_frames=N_EMBEDDING_FRAMES, embed_dim=EMBEDDING_DIM).to(device)
    loss_fn = nn.BCELoss()

    # --- Round 1: Initial training ---
    log.info("=== Round 1: Initial training ===")
    model, best_state = _train_loop(model, X_train, y_train, X_val, y_val,
                                     epochs, batch_size, lr, loss_fn, device)

    # --- Hard negative mining: find FP negatives, duplicate them ---
    model.eval()
    neg_start = len(train_pos)
    with torch.no_grad():
        all_pred = model(torch.from_numpy(X_train).to(device))
        neg_preds = all_pred[neg_start:].squeeze().cpu().numpy()
    hard_neg_mask = neg_preds > 0.3
    n_hard = hard_neg_mask.sum()

    if n_hard > 0:
        hard_negatives = train_neg[hard_neg_mask]
        # Duplicate hard negatives 10x to emphasize them
        hard_neg_repeated = np.repeat(hard_negatives, 10, axis=0)
        log.info(f"=== Hard negative mining: found {n_hard} FP negatives (score>0.3), "
                 f"adding {len(hard_neg_repeated)} copies ===")

        # Rebuild training data with oversampled hard negatives
        train_neg_boosted = np.concatenate([train_neg, hard_neg_repeated], axis=0)
        X_train = np.concatenate([train_pos, train_neg_boosted], axis=0)
        y_train = np.concatenate([np.ones(len(train_pos)), np.zeros(len(train_neg_boosted))]).astype(np.float32)

        # --- Round 2: Retrain with hard negatives, lower LR ---
        log.info(f"=== Round 2: Retraining with {len(train_neg_boosted)} negatives (was {len(train_neg)}) ===")
        model, best_state = _train_loop(model, X_train, y_train, X_val, y_val,
                                         epochs, batch_size, lr / 5, loss_fn, device)
    else:
        log.info("No hard negatives found (all negatives score < 0.3). Skipping round 2.")

    if best_state:
        model.load_state_dict(best_state)

    return model


def _train_loop(model, X_train, y_train, X_val, y_val,
                epochs, batch_size, lr, loss_fn, device):
    """Single training loop. Returns (model, best_state_dict)."""
    X_train_t = torch.from_numpy(X_train).to(device)
    y_train_t = torch.from_numpy(y_train).unsqueeze(1).to(device)
    X_val_t = torch.from_numpy(X_val).to(device)
    y_val_t = torch.from_numpy(y_val).unsqueeze(1).to(device)

    optimizer = torch.optim.Adam(model.parameters(), lr=lr)
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=epochs, eta_min=lr / 100)

    best_val_acc = 0.0
    best_state = None

    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(len(X_train_t))
        X_shuffled = X_train_t[perm]
        y_shuffled = y_train_t[perm]

        epoch_loss = 0.0
        n_batches = 0

        for i in range(0, len(X_shuffled), batch_size):
            X_batch = X_shuffled[i:i + batch_size]
            y_batch = y_shuffled[i:i + batch_size]

            pred = model(X_batch)
            loss = loss_fn(pred, y_batch)

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
            val_acc = (val_binary == y_val_t).float().mean().item()

            pos_mask = y_val_t == 1
            neg_mask = y_val_t == 0
            recall = (val_binary[pos_mask] == 1).float().mean().item() if pos_mask.any() else 0
            fp_rate = (val_binary[neg_mask] == 1).float().mean().item() if neg_mask.any() else 0

        if epoch % 5 == 0 or epoch == epochs - 1:
            log.info(
                f"Epoch {epoch + 1}/{epochs} | "
                f"loss={epoch_loss / n_batches:.4f} | "
                f"val_loss={val_loss:.4f} | "
                f"val_acc={val_acc:.4f} | "
                f"recall={recall:.4f} | "
                f"fp_rate={fp_rate:.4f} | "
                f"lr={scheduler.get_last_lr()[0]:.6f}"
            )

        if val_acc > best_val_acc:
            best_val_acc = val_acc
            best_state = {k: v.clone() for k, v in model.state_dict().items()}

    if best_state:
        model.load_state_dict(best_state)
        log.info(f"Best val_acc={best_val_acc:.4f}")

    return model, best_state


def export_onnx(model: WakeWordDNN, output_path: Path):
    """Export trained model to ONNX format compatible with openWakeWord inference."""
    model.eval()
    dummy_input = torch.randn(1, N_EMBEDDING_FRAMES, EMBEDDING_DIM)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    torch.onnx.export(
        model,
        dummy_input,
        str(output_path),
        opset_version=13,
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
    )

    log.info(f"Exported ONNX model to {output_path}")

    # Verify
    session = ort.InferenceSession(str(output_path))
    test_input = np.random.randn(1, N_EMBEDDING_FRAMES, EMBEDDING_DIM).astype(np.float32)
    result = session.run(None, {"input": test_input})[0]
    log.info(f"ONNX verification: input shape {test_input.shape} -> output shape {result.shape}, value={result[0][0]:.4f}")


def evaluate_sliding_window(cls_path: str, mel_path: str, emb_path: str,
                            threshold: float = 0.8, required_hits: int = 2, window_size: int = 5):
    """Evaluate model with sliding window detection on raw WAV files.

    Simulates actual real-time detection: feeds audio continuously through
    mel -> embedding -> classifier, applies N-of-M sliding window, counts detections.
    """
    from test_android_pipeline import AndroidPipelineSimulator, CHUNK_SIZE, SAMPLE_RATE as SIM_SR

    sim = AndroidPipelineSimulator(mel_path, emb_path, cls_path)

    def detect_in_file(wav_path):
        """Run sliding window detection on a WAV file. Returns number of detections."""
        audio = load_wav(wav_path)
        sim.reset()

        # Warmup: 3s silence to fill mel buffer, then 2s padding before/after audio
        warmup = np.zeros(SIM_SR * 3, dtype=np.int16)
        for i in range(0, len(warmup), CHUNK_SIZE):
            chunk = warmup[i:i + CHUNK_SIZE]
            if len(chunk) < CHUNK_SIZE:
                chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
            sim.feed_chunk(chunk)

        # Convert float32 to int16, pad with 2s silence before and after
        audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        silence = np.zeros(SIM_SR * 2, dtype=np.int16)
        audio_int16 = np.concatenate([silence, audio_int16, silence])

        score_history = []
        detections = 0
        cooldown_frames = 0

        for i in range(0, len(audio_int16), CHUNK_SIZE):
            chunk = audio_int16[i:i + CHUNK_SIZE]
            if len(chunk) < CHUNK_SIZE:
                chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
            score = sim.feed_chunk(chunk)

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
                cooldown_frames = 25  # ~2s cooldown at 80ms/frame

        return detections

    # Evaluate recorded positives
    pos_dirs = [DATA_DIR / "recorded_positive"]
    neg_dirs = [DATA_DIR / "recorded_negative", DATA_DIR / "adversarial"]

    for label, dirs, expect_detect in [("POSITIVE", pos_dirs, True), ("NEGATIVE", neg_dirs, False)]:
        total_files = 0
        correct = 0
        total_detections = 0

        for d in dirs:
            if not d.exists():
                continue
            files = sorted(d.glob("*.wav"))[:200]  # Cap to keep eval fast
            for f in files:
                n_det = detect_in_file(f)
                total_files += 1
                total_detections += n_det
                if expect_detect and n_det > 0:
                    correct += 1
                elif not expect_detect and n_det == 0:
                    correct += 1

        if total_files > 0:
            rate = correct / total_files * 100
            avg_det = total_detections / total_files
            if expect_detect:
                log.info(f"  {label}: {correct}/{total_files} detected ({rate:.1f}%) "
                         f"avg_detections={avg_det:.2f}")
            else:
                fp = total_files - correct
                log.info(f"  {label}: {fp}/{total_files} false alarms ({100-rate:.1f}%) "
                         f"avg_false_detections={avg_det:.2f}")


def main():
    parser = argparse.ArgumentParser(description="Train Russian wake word model")
    parser.add_argument("--skip-augment", action="store_true", help="Skip augmentation, use raw clips")
    parser.add_argument("--skip-background", action="store_true", help="Skip ACAV100M background download")
    parser.add_argument("--augment-factor", type=int, default=5, help="Augmented copies per clip (default: 5)")
    parser.add_argument("--epochs", type=int, default=50, help="Training epochs (default: 50)")
    parser.add_argument("--batch-size", type=int, default=128, help="Batch size (default: 128)")
    parser.add_argument("--lr", type=float, default=0.0001, help="Learning rate (default: 0.0001)")
    parser.add_argument("--bg-files", type=int, default=3, help="Background feature files to download (default: 3)")
    parser.add_argument("--output", type=str, default="models/sireneviy.onnx", help="Output ONNX path")
    parser.add_argument("--use-cached", action="store_true", help="Skip extraction, use cached features from data/features/")
    args = parser.parse_args()

    FEATURES_DIR.mkdir(parents=True, exist_ok=True)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

    # Locate feature extraction models
    mel_path, emb_path = get_onnx_models()
    log.info(f"Melspectrogram model: {mel_path}")
    log.info(f"Embedding model: {emb_path}")
    mel_session = ort.InferenceSession(mel_path)
    emb_session = ort.InferenceSession(emb_path)

    # Step 1: Load and augment clips
    pos_dir = DATA_DIR / "positive"
    adv_dir = DATA_DIR / "adversarial"
    rec_pos_dir = DATA_DIR / "recorded_positive"
    rec_neg_dir = DATA_DIR / "recorded_negative"

    if not pos_dir.exists() or not list(pos_dir.glob("*.wav")):
        log.error(f"No positive clips found in {pos_dir}. Run generate_clips.py first.")
        return

    aug_factor = 0 if args.skip_augment else args.augment_factor
    positive_clips = load_and_augment_clips(pos_dir, n_augmented_per_clip=aug_factor)

    # Include recorded positive samples with heavier augmentation (real voice = high value)
    if rec_pos_dir.exists() and list(rec_pos_dir.glob("*.wav")):
        rec_aug = max(aug_factor * 3, 10)  # 3x more augmentation for real recordings
        recorded_pos = load_and_augment_clips(rec_pos_dir, n_augmented_per_clip=rec_aug)
        log.info(f"Adding {len(recorded_pos)} recorded positive clips (from {len(list(rec_pos_dir.glob('*.wav')))} recordings)")
        positive_clips.extend(recorded_pos)

    # Include noise-augmented positive samples (already mixed with background noise)
    aug_pos_dir = DATA_DIR / "augmented_positive"
    if aug_pos_dir.exists() and list(aug_pos_dir.glob("*.wav")):
        aug_pos = load_and_augment_clips(aug_pos_dir, n_augmented_per_clip=0)
        log.info(f"Adding {len(aug_pos)} noise-augmented positive clips")
        positive_clips.extend(aug_pos)

    adversarial_clips = []
    if adv_dir.exists() and list(adv_dir.glob("*.wav")):
        adversarial_clips = load_and_augment_clips(adv_dir, n_augmented_per_clip=max(aug_factor // 2, 1))
    else:
        log.warning(f"No adversarial clips in {adv_dir}. Training without adversarial negatives.")

    # Include recorded negative samples
    if rec_neg_dir.exists() and list(rec_neg_dir.glob("*.wav")):
        rec_neg_aug = max(aug_factor * 2, 5)
        recorded_neg = load_and_augment_clips(rec_neg_dir, n_augmented_per_clip=rec_neg_aug)
        log.info(f"Adding {len(recorded_neg)} recorded negative clips")
        adversarial_clips.extend(recorded_neg)

    # Include noise-augmented negatives (already augmented with background noise)
    aug_neg_dir = DATA_DIR / "augmented_negative"
    if aug_neg_dir.exists() and list(aug_neg_dir.glob("*.wav")):
        aug_neg = load_and_augment_clips(aug_neg_dir, n_augmented_per_clip=0)
        log.info(f"Adding {len(aug_neg)} noise-augmented negative clips")
        adversarial_clips.extend(aug_neg)

    # Step 2: Extract features (or load cached)
    pos_cache = FEATURES_DIR / "positive_features.npy"
    adv_cache = FEATURES_DIR / "adversarial_features.npy"

    if args.use_cached and pos_cache.exists() and adv_cache.exists():
        log.info("Loading cached features...")
        pos_features = np.load(str(pos_cache))
        adv_features = np.load(str(adv_cache))
        log.info(f"Positive features shape: {pos_features.shape}")
        log.info(f"Adversarial features shape: {adv_features.shape}")
    else:
        log.info("Extracting positive features...")
        pos_features = extract_features_batch(positive_clips, mel_session, emb_session)
        if pos_features.ndim == 4:
            pos_features = pos_features.squeeze(1)
        log.info(f"Positive features shape: {pos_features.shape}")

        if adversarial_clips:
            log.info("Extracting adversarial features...")
            adv_features = extract_features_batch(adversarial_clips, mel_session, emb_session)
            if adv_features.ndim == 4:
                adv_features = adv_features.squeeze(1)
            log.info(f"Adversarial features shape: {adv_features.shape}")
        else:
            adv_features = np.zeros((0, N_EMBEDDING_FRAMES, EMBEDDING_DIM), dtype=np.float32)

        np.save(str(pos_cache), pos_features)
        np.save(str(adv_cache), adv_features)
        log.info(f"Features saved to {FEATURES_DIR}")

    # Step 3: Locate background features
    bg_files = []
    if not args.skip_background:
        # Check for locally available background feature files
        bg_dir = FEATURES_DIR / "background"
        if bg_dir.exists():
            bg_files = sorted(bg_dir.glob("*.npy"))
        if bg_files:
            log.info(f"Found {len(bg_files)} background feature files in {bg_dir}")
        else:
            log.warning(f"No background features found in {bg_dir}. Download validation_set_features.npy from "
                        f"https://huggingface.co/datasets/davidscripka/openwakeword_features")

    # Step 4: Train
    model = train_model(
        pos_features,
        adv_features,
        bg_files,
        epochs=args.epochs,
        batch_size=args.batch_size,
        lr=args.lr,
    )

    # Step 5: Export
    output_path = BASE_DIR / args.output
    export_onnx(model, output_path)

    # Step 6: Sliding window evaluation on raw WAV files
    log.info("=== Sliding window evaluation (simulates real-time detection) ===")
    evaluate_sliding_window(str(output_path), mel_path, emb_path, threshold=0.8, required_hits=2, window_size=5)

    log.info("Training complete!")
    log.info(f"Model saved to: {output_path}")
    log.info(f"Test with: python test_live.py --model {args.output}")


if __name__ == "__main__":
    main()
