"""Generate positive and adversarial WAV clips for Russian wake word training using Piper TTS.

Usage:
    python generate_clips.py                    # Generate all clips
    python generate_clips.py --positive-only    # Only positive clips
    python generate_clips.py --adversarial-only # Only adversarial clips
    python generate_clips.py --samples 200      # Samples per voice (default: 500)
"""

import argparse
import logging
import os
import random
import wave
from pathlib import Path

import numpy as np
import requests
from scipy.signal import resample_poly
from tqdm import tqdm

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
VOICES_DIR = BASE_DIR / "voices"
DATA_DIR = BASE_DIR / "data"
POSITIVE_DIR = DATA_DIR / "positive"
ADVERSARIAL_DIR = DATA_DIR / "adversarial"

TARGET_SR = 16000  # openWakeWord requires 16kHz
PIPER_MEDIUM_SR = 22050  # Piper medium quality output

# Piper Russian voices (medium quality, 22.05kHz)
VOICE_NAMES = ["denis", "dmitri", "irina", "ruslan"]
HF_VOICE_BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/ru/ru_RU"

# Target wake word variations (all declensions of "сиреневый")
POSITIVE_TEXTS = [
    "сиреневый",
    "сиреневой",
    "сиренево",
    "сиреневая",
    "сиреневым",
    "сиреневые",
]

# Phonetically similar Russian words that should NOT trigger detection
ADVERSARIAL_TEXTS = [
    # Share "сирен" prefix
    "сирена",
    "сирень",
    "сиреневатый",
    # Share "с" + similar vowel patterns
    "серенький",
    "синеватый",
    "северный",
    "серебряный",
    # Share "сир" prefix
    "сиропный",
    "сиротливый",
    # Similar phonetic shape
    "свирепый",
    "сиреневый куст",  # wake word embedded in phrase
    # Common words to reduce general false positives
    "привет",
    "давай",
    "хорошо",
    "понятно",
    "спасибо",
    "пожалуйста",
    "конечно",
    "подожди",
    "слушай",
    "скажи",
]

# Piper TTS parameter ranges for diversity
LENGTH_SCALES = [0.8, 0.9, 1.0, 1.1, 1.2]
NOISE_SCALES = [0.4, 0.6, 0.667, 0.8, 1.0]
NOISE_WS = [0.4, 0.6, 0.8, 1.0]


def download_voice(voice_name: str) -> Path:
    """Download a Piper voice model from HuggingFace if not present."""
    voice_dir = VOICES_DIR / voice_name
    model_name = f"ru_RU-{voice_name}-medium"
    onnx_path = voice_dir / f"{model_name}.onnx"
    json_path = voice_dir / f"{model_name}.onnx.json"

    if onnx_path.exists() and json_path.exists():
        return onnx_path

    voice_dir.mkdir(parents=True, exist_ok=True)

    for filename, dest in [(f"{model_name}.onnx", onnx_path), (f"{model_name}.onnx.json", json_path)]:
        url = f"{HF_VOICE_BASE}/{voice_name}/medium/{filename}?download=true"
        log.info(f"Downloading {filename}...")
        resp = requests.get(url, stream=True)
        resp.raise_for_status()
        total = int(resp.headers.get("content-length", 0))
        with open(dest, "wb") as f:
            with tqdm(total=total, unit="B", unit_scale=True, desc=filename) as pbar:
                for chunk in resp.iter_content(chunk_size=8192):
                    f.write(chunk)
                    pbar.update(len(chunk))

    return onnx_path


def load_voice(model_path: Path):
    """Load a Piper voice model."""
    from piper import PiperVoice
    config_path = model_path.with_suffix(".onnx.json")
    return PiperVoice.load(str(model_path), config_path=str(config_path))


def synthesize_clip(voice, text: str, length_scale: float, noise_scale: float, noise_w: float) -> np.ndarray:
    """Synthesize a single clip and return as 16kHz int16 numpy array."""
    from piper.config import SynthesisConfig

    syn_config = SynthesisConfig(
        length_scale=length_scale,
        noise_scale=noise_scale,
        noise_w_scale=noise_w,
    )

    # Collect audio from all chunks
    audio_parts = []
    sr = None
    for chunk in voice.synthesize(text, syn_config=syn_config):
        audio_parts.append(chunk.audio_float_array)
        if sr is None:
            sr = chunk.sample_rate

    if not audio_parts or sr is None:
        raise RuntimeError("No audio generated")

    audio = np.concatenate(audio_parts).astype(np.float64)

    # Resample from Piper SR to 16kHz
    if sr != TARGET_SR:
        from math import gcd
        g = gcd(TARGET_SR, sr)
        audio = resample_poly(audio, TARGET_SR // g, sr // g)

    # Convert to int16
    audio = np.clip(audio, -1.0, 1.0)
    return (audio * 32767).astype(np.int16)


def save_wav(audio: np.ndarray, path: Path, sr: int = TARGET_SR):
    """Save int16 numpy array as WAV file."""
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sr)
        f.writeframes(audio.tobytes())


def generate_clips(
    texts: list[str],
    output_dir: Path,
    samples_per_voice: int,
    label: str,
):
    """Generate TTS clips with random parameter variations."""
    output_dir.mkdir(parents=True, exist_ok=True)

    # Load all voices
    voices = {}
    for name in VOICE_NAMES:
        log.info(f"Loading voice: {name}")
        model_path = download_voice(name)
        voices[name] = load_voice(model_path)

    total = len(VOICE_NAMES) * samples_per_voice
    log.info(f"Generating {total} {label} clips ({samples_per_voice} per voice, {len(texts)} texts)")

    count = 0
    for voice_name, voice in voices.items():
        for i in tqdm(range(samples_per_voice), desc=f"{label}/{voice_name}"):
            text = random.choice(texts)
            ls = random.choice(LENGTH_SCALES)
            ns = random.choice(NOISE_SCALES)
            nw = random.choice(NOISE_WS)

            try:
                audio = synthesize_clip(voice, text, ls, ns, nw)
            except Exception as e:
                log.warning(f"Failed to synthesize '{text}' with {voice_name}: {e}")
                continue

            # Skip very short clips (< 0.3s) or silent clips
            if len(audio) < TARGET_SR * 0.3:
                continue
            if np.max(np.abs(audio)) < 100:
                continue

            filename = f"{voice_name}_{i:04d}_ls{ls:.1f}_ns{ns:.2f}_nw{nw:.2f}.wav"
            save_wav(audio, output_dir / filename)
            count += 1

    log.info(f"Generated {count} {label} clips in {output_dir}")


def main():
    parser = argparse.ArgumentParser(description="Generate Russian wake word training clips")
    parser.add_argument("--samples", type=int, default=500, help="Samples per voice (default: 500)")
    parser.add_argument("--positive-only", action="store_true", help="Only generate positive clips")
    parser.add_argument("--adversarial-only", action="store_true", help="Only generate adversarial clips")
    args = parser.parse_args()

    do_positive = not args.adversarial_only
    do_adversarial = not args.positive_only

    if do_positive:
        generate_clips(POSITIVE_TEXTS, POSITIVE_DIR, args.samples, "positive")

    if do_adversarial:
        # Fewer adversarial samples per voice (they supplement ACAV100M background)
        adv_samples = max(args.samples // 3, 100)
        generate_clips(ADVERSARIAL_TEXTS, ADVERSARIAL_DIR, adv_samples, "adversarial")

    log.info("Clip generation complete.")


if __name__ == "__main__":
    main()
