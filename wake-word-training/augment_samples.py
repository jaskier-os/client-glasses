"""Augment ALL wake word training data with noise, pitch, speed, and volume variations.

Augments positives, negatives, and adversarial samples. Also slices background
noise files into standalone negative samples.

Usage:
    python augment_samples.py              # Full augmentation
    python augment_samples.py --preview 5  # Preview mode
"""

import argparse
import logging
import random
import wave
from pathlib import Path

import numpy as np
from scipy.io import wavfile
from scipy.signal import resample

try:
    from pydub import AudioSegment
    HAS_PYDUB = True
except ImportError:
    HAS_PYDUB = False

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
BACKGROUND_DIR = DATA_DIR / "background_additives"

SAMPLE_RATE = 16000
CLIP_DURATION = 2.0
CLIP_SAMPLES = int(SAMPLE_RATE * CLIP_DURATION)

SNR_LEVELS = [15, 10, 5, 3, 0, -3]


def load_wav(path):
    sr, audio = wavfile.read(str(path))
    if audio.dtype == np.int16:
        audio = audio.astype(np.float32) / 32768.0
    if audio.ndim > 1:
        audio = audio[:, 0]
    if sr != SAMPLE_RATE:
        audio = resample(audio, int(len(audio) * SAMPLE_RATE / sr)).astype(np.float32)
    return audio


def load_background(path):
    suffix = path.suffix.lower()
    if suffix == ".wav":
        return load_wav(path)
    if suffix == ".mp3":
        if not HAS_PYDUB:
            raise ImportError("pydub required for MP3")
        seg = AudioSegment.from_mp3(str(path))
        seg = seg.set_channels(1).set_frame_rate(SAMPLE_RATE).set_sample_width(2)
        return np.array(seg.get_array_of_samples(), dtype=np.float32) / 32768.0
    raise ValueError(f"Unsupported: {suffix}")


def save_wav(audio, path):
    audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(audio_int16.tobytes())


def rms(audio):
    return np.sqrt(np.mean(audio ** 2)) + 1e-10


def random_noise_segment(backgrounds, length):
    bg = random.choice(backgrounds)
    if len(bg) <= length:
        bg = np.tile(bg, (length // len(bg)) + 1)
    start = random.randint(0, len(bg) - length)
    return bg[start:start + length]


def mix_at_snr(voice, noise_segment, snr_db):
    voice_rms = rms(voice)
    target_noise_rms = voice_rms / (10 ** (snr_db / 20))
    noise_gain = target_noise_rms / rms(noise_segment)
    mixed = voice + noise_segment * noise_gain
    peak = np.max(np.abs(mixed))
    if peak > 0.95:
        mixed *= 0.95 / peak
    return mixed


def pitch_shift(audio, semitones):
    """Shift pitch by resampling (changes duration slightly, then resample back)."""
    factor = 2 ** (semitones / 12.0)
    stretched = resample(audio, int(len(audio) / factor)).astype(np.float32)
    # Resample back to original length to preserve duration
    return resample(stretched, len(audio)).astype(np.float32)


def speed_change(audio, factor):
    """Change speed by resampling."""
    new_len = int(len(audio) / factor)
    return resample(audio, new_len).astype(np.float32)


def augment_single(audio, backgrounds):
    """Apply random augmentations to a single clip: pitch, speed, volume, noise."""
    out = audio.copy()

    # Pitch shift: +/- 2 semitones
    if random.random() < 0.7:
        semitones = random.uniform(-2.0, 2.0)
        out = pitch_shift(out, semitones)

    # Speed change: 0.85x - 1.15x
    if random.random() < 0.7:
        factor = random.uniform(0.85, 1.15)
        out = speed_change(out, factor)

    # Pad or trim to target length
    if len(out) > CLIP_SAMPLES:
        start = random.randint(0, len(out) - CLIP_SAMPLES)
        out = out[start:start + CLIP_SAMPLES]
    elif len(out) < CLIP_SAMPLES:
        out = np.pad(out, (0, CLIP_SAMPLES - len(out)))

    # Volume: 0.5x - 1.5x
    out *= random.uniform(0.5, 1.5)

    # Mix with background noise at random SNR
    if backgrounds:
        snr = random.choice(SNR_LEVELS)
        noise = random_noise_segment(backgrounds, len(out))
        out = mix_at_snr(out, noise, snr)

    # Final clip
    peak = np.max(np.abs(out))
    if peak > 0.95:
        out *= 0.95 / peak

    return out


def augment_directory(src_dir, out_dir, backgrounds, per_sample, label):
    """Augment all WAV files in a directory."""
    files = sorted(src_dir.glob("*.wav"))
    if not files:
        log.info(f"  No files in {src_dir}, skipping")
        return 0

    out_dir.mkdir(parents=True, exist_ok=True)
    idx = 0
    for f in files:
        audio = load_wav(f)
        for _ in range(per_sample):
            aug = augment_single(audio, backgrounds)
            save_wav(aug, out_dir / f"aug_{idx:05d}.wav")
            idx += 1
        if idx % 500 == 0 and idx > 0:
            log.info(f"  {label}: {idx} generated...")

    log.info(f"  {label}: {idx} total from {len(files)} source files")
    return idx


def slice_backgrounds(backgrounds, out_dir, count_per_bg=350):
    """Slice background noise into 2s clips as standalone negatives."""
    out_dir.mkdir(parents=True, exist_ok=True)
    idx = 0
    for bg in backgrounds:
        for _ in range(count_per_bg):
            if len(bg) <= CLIP_SAMPLES:
                segment = np.pad(bg, (0, CLIP_SAMPLES - len(bg)))
            else:
                start = random.randint(0, len(bg) - CLIP_SAMPLES)
                segment = bg[start:start + CLIP_SAMPLES]

            # Apply pitch/speed/volume variation
            if random.random() < 0.5:
                segment = pitch_shift(segment, random.uniform(-2.0, 2.0))
            if random.random() < 0.5:
                segment = speed_change(segment, random.uniform(0.85, 1.15))
            segment *= random.uniform(0.5, 1.5)

            if len(segment) > CLIP_SAMPLES:
                segment = segment[:CLIP_SAMPLES]
            elif len(segment) < CLIP_SAMPLES:
                segment = np.pad(segment, (0, CLIP_SAMPLES - len(segment)))

            peak = np.max(np.abs(segment))
            if peak > 0.95:
                segment *= 0.95 / peak

            save_wav(segment, out_dir / f"bg_{idx:05d}.wav")
            idx += 1

    log.info(f"  Background slices: {idx} total")
    return idx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", type=int, default=0)
    args = parser.parse_args()

    # Load backgrounds
    bg_files = list(BACKGROUND_DIR.glob("*.mp3")) + list(BACKGROUND_DIR.glob("*.wav"))
    log.info(f"Loading {len(bg_files)} background files...")
    backgrounds = []
    for f in bg_files:
        try:
            bg = load_background(f)
            backgrounds.append(bg)
            log.info(f"  {f.name}: {len(bg)/SAMPLE_RATE:.1f}s")
        except Exception as e:
            log.error(f"  Failed: {f.name}: {e}")
    if not backgrounds:
        log.error("No backgrounds loaded")
        return

    if args.preview > 0:
        preview_dir = DATA_DIR / "augmented_preview"
        preview_dir.mkdir(parents=True, exist_ok=True)
        src = sorted((DATA_DIR / "recorded_positive").glob("*.wav"))[:2]
        idx = 0
        for f in src:
            audio = load_wav(f)
            for snr in [10, 3, 0, -3]:
                aug = augment_single(audio, backgrounds)
                save_wav(aug, preview_dir / f"preview_{idx:03d}_snr{snr:+d}_{f.stem}.wav")
                idx += 1
                if idx >= args.preview:
                    break
            if idx >= args.preview:
                break
        log.info(f"Preview: {idx} samples in {preview_dir}")
        return

    # Full augmentation
    log.info("=== Augmenting positives ===")

    n = 0
    n += augment_directory(
        DATA_DIR / "recorded_positive",
        DATA_DIR / "augmented_positive",
        backgrounds, per_sample=15, label="Recorded positives"
    )
    n += augment_directory(
        DATA_DIR / "positive",
        DATA_DIR / "augmented_positive",
        backgrounds, per_sample=5, label="Synthetic positives"
    )
    log.info(f"Total augmented positives: {n}")

    log.info("=== Augmenting negatives ===")
    m = 0
    m += augment_directory(
        DATA_DIR / "adversarial",
        DATA_DIR / "augmented_negative",
        backgrounds, per_sample=5, label="Adversarial"
    )
    m += augment_directory(
        DATA_DIR / "recorded_negative",
        DATA_DIR / "augmented_negative",
        backgrounds, per_sample=10, label="Recorded negatives"
    )

    log.info("=== Slicing background noise as negatives ===")
    m += slice_backgrounds(backgrounds, DATA_DIR / "augmented_negative", count_per_bg=350)

    log.info(f"Total augmented negatives: {m}")
    log.info(f"=== Done: {n} positives, {m} negatives ===")


if __name__ == "__main__":
    main()
