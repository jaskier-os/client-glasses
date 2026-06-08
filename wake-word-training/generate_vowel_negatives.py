"""Generate sustained vowel and non-speech negative samples using Kokoro TTS.

These are hard negatives that the wake word model commonly confuses with the
target word "sireneviy". Covers sustained vowels, filler sounds, humming,
repetitive syllables, and other non-wake-word patterns.

Requires Kokoro TTS running at localhost:8880.

Usage:
    python generate_vowel_negatives.py                    # Generate all
    python generate_vowel_negatives.py --output-dir data/adversarial  # Custom output
    python generate_vowel_negatives.py --voices af_bella,am_adam      # Specific voices
"""

import argparse
import logging
import random
import struct
import wave
from pathlib import Path

import numpy as np
import requests
from scipy.signal import resample_poly

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
DEFAULT_OUTPUT = DATA_DIR / "adversarial"

KOKORO_URL = "http://localhost:8880/v1/audio/speech"
KOKORO_SR = 24000
TARGET_SR = 16000

# Voices to use -- mix of genders and languages for diversity
DEFAULT_VOICES = [
    "af_bella", "af_sky", "af_heart",
    "am_adam", "am_michael", "am_echo",
    "bf_emma", "bm_george",
]

# === NEGATIVE TEXT CATEGORIES ===

# Sustained single vowels (the primary false positive trigger)
SUSTAINED_VOWELS = [
    "аааааааааааа",
    "ааааааааааааааааааа",
    "оооооооооооо",
    "ооооооооооооооооооо",
    "уууууууууууу",
    "ууууууууууууууууууу",
    "эээээээээээээ",
    "ээээээээээээээээээ",
    "иииииииииииии",
    "ыыыыыыыыыыыы",
    "ееееееееееее",
    "ёёёёёёёёёёёёёё",
    "яяяяяяяяяяяяяя",
    "юююююююююю",
]

# Sustained vowels in English (embedding model is language-agnostic)
SUSTAINED_VOWELS_EN = [
    "aaaaaaaaaaaa",
    "aaaaaaaaaaaaaaaaaaa",
    "oooooooooooo",
    "ooooooooooooooooooo",
    "eeeeeeeeeeee",
    "eeeeeeeeeeeeeeeeeee",
    "uuuuuuuuuuuu",
    "iiiiiiiiiiiiii",
]

# Vowel transitions (gliding between vowels)
VOWEL_TRANSITIONS = [
    "ааааоооооааааоооо",
    "уууааааууууааааууу",
    "ээээааааээээ",
    "ооооуууууооооууууу",
    "ииииээээииииэээ",
    "аааааэээээааааа",
    "оооооааааааоооо",
    "ууууииииууууиииии",
]

# Sustained consonants and nasal sounds (humming)
SUSTAINED_CONSONANTS = [
    "мммммммммммммм",
    "ммммммммммммммммммм",
    "нннннннннннн",
    "ннннннннннннннннн",
    "ссссссссссссс",
    "шшшшшшшшшшшшш",
    "ззззззззззззз",
    "жжжжжжжжжжжж",
    "ффффффффффффф",
    "ввввввввввввв",
    "ллллллллллллл",
    "рррррррррррррр",
]

# Filler sounds and non-verbal utterances
FILLER_SOUNDS = [
    "эээммммм",
    "ааааммммм",
    "мммэээ",
    "ууумммм",
    "хмммм",
    "аааах",
    "ооооох",
    "ууууух",
    "ээээх",
    "ааа ммм ааа",
    "эм эм эм",
    "ам ам ам",
    "ммм ааа ммм",
    "хммм ааа",
    "ну эээ ну",
    "аааа нуууу",
]

# Sighing, exhaling patterns
SIGHING_SOUNDS = [
    "ааааааахххх",
    "оооооохххх",
    "ууууухххх",
    "эээээхххх",
    "ааааах ооооох",
    "пфффф",
    "фуууух",
    "фффааааа",
    "хааааааа",
    "хоооооо",
]

# Repetitive syllables (babbling, la-la-la patterns)
REPETITIVE_SYLLABLES = [
    "ла ла ла ла ла ла",
    "на на на на на на",
    "та та та та та та",
    "да да да да да да",
    "ба ба ба ба ба ба",
    "ра ра ра ра ра ра",
    "ма ма ма ма ма ма",
    "па па па па па па",
    "ка ка ка ка ка ка",
    "ва ва ва ва ва ва",
    "са са са са са са",
    "ля ля ля ля ля ля",
    "ню ню ню ню ню ню",
    "ти ти ти ти ти ти",
]

# Whispered/breathy vowels
BREATHY_SOUNDS = [
    "хааааа",
    "хооооо",
    "хууууу",
    "хэээээ",
    "хиииии",
    "шааааа",
    "шооооо",
    "шууууу",
]

# Yawn-like and stretch sounds
YAWN_SOUNDS = [
    "аааааааааах",
    "ааааааэээээ",
    "ооооааааааах",
    "уааааааааа",
    "ооооаааааааа",
    "ааааааооооооо",
]

# Short bursts of vowels (like surprise/exclamation)
EXCLAMATION_VOWELS = [
    "а а а а а",
    "о о о о о",
    "э э э э э",
    "у у у у у",
    "аа аа аа аа",
    "оо оо оо оо",
    "ай ай ай ай",
    "ой ой ой ой",
    "эй эй эй эй",
    "уй уй уй уй",
]

# Words with similar vowel rhythm to "сиреневый" but different consonants
RHYTHM_SIMILAR = [
    "пирожковый",
    "кирпичовый",
    "виноградный",
    "березовый",
    "деревянный",
    "шоколадный",
    "мимолетный",
    "бирюзовый",
    "говорливый",
    "переменный",
    "неизменный",
    "гиперновый",
    "миллионный",
    "территория",
    "директивный",
    "примитивный",
    "перспективный",
    "литературный",
    "минеральный",
    "сироповый",
]

# Mixed vowel-consonant drones
DRONE_SOUNDS = [
    "нааааааа",
    "маааааааа",
    "лааааааа",
    "вааааааа",
    "рааааааа",
    "нооооооо",
    "мооооооо",
    "лооооооо",
    "ноооуууу",
    "маааоооо",
    "лааааууу",
    "раааэээ",
]

# Speed variations for each text (Kokoro speed parameter)
SPEEDS = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2]


def all_negative_texts():
    """Return all negative text categories with their labels."""
    return {
        "vowel": SUSTAINED_VOWELS,
        "vowel_en": SUSTAINED_VOWELS_EN,
        "vtrans": VOWEL_TRANSITIONS,
        "cons": SUSTAINED_CONSONANTS,
        "filler": FILLER_SOUNDS,
        "sigh": SIGHING_SOUNDS,
        "repeat": REPETITIVE_SYLLABLES,
        "breath": BREATHY_SOUNDS,
        "yawn": YAWN_SOUNDS,
        "exclam": EXCLAMATION_VOWELS,
        "rhythm": RHYTHM_SIMILAR,
        "drone": DRONE_SOUNDS,
    }


def generate_with_kokoro(text: str, voice: str, speed: float) -> bytes | None:
    """Call Kokoro TTS and return WAV bytes."""
    payload = {
        "model": "kokoro",
        "input": text,
        "voice": voice,
        "response_format": "wav",
        "speed": speed,
    }
    try:
        resp = requests.post(KOKORO_URL, json=payload, timeout=30)
        if resp.status_code == 200:
            return resp.content
        else:
            log.warning(f"Kokoro error {resp.status_code} for '{text[:30]}': {resp.text[:100]}")
            return None
    except Exception as e:
        log.warning(f"Kokoro request failed for '{text[:30]}': {e}")
        return None


def wav_bytes_to_16k_int16(wav_bytes: bytes) -> np.ndarray | None:
    """Convert WAV bytes (any sample rate) to 16kHz int16 numpy array."""
    import io
    try:
        with wave.open(io.BytesIO(wav_bytes), "rb") as wf:
            sr = wf.getframerate()
            n_frames = wf.getnframes()
            n_channels = wf.getnchannels()
            raw = wf.readframes(n_frames)

        # Parse raw PCM
        samples = np.frombuffer(raw, dtype=np.int16)
        if n_channels > 1:
            samples = samples[::n_channels]  # Take first channel

        audio = samples.astype(np.float64) / 32768.0

        # Resample to 16kHz
        if sr != TARGET_SR:
            from math import gcd
            g = gcd(TARGET_SR, sr)
            audio = resample_poly(audio, TARGET_SR // g, sr // g)

        audio = np.clip(audio, -1.0, 1.0)
        return (audio * 32767).astype(np.int16)
    except Exception as e:
        log.warning(f"WAV conversion failed: {e}")
        return None


def save_wav(audio: np.ndarray, path: Path):
    """Save int16 numpy array as 16kHz mono WAV."""
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(TARGET_SR)
        f.writeframes(audio.tobytes())


def generate_synthetic_vowels(output_dir: Path, count_per_vowel: int = 20):
    """Generate pure synthetic sustained vowel tones (no TTS needed).

    Creates sine-wave approximations of formant frequencies for each vowel.
    These are extremely clean negatives that the model must learn to reject.
    """
    log.info("Generating synthetic sustained vowels...")

    # Approximate F1/F2 formant pairs for Russian vowels (Hz)
    vowel_formants = {
        "a": [(800, 1200)],
        "o": [(500, 900)],
        "u": [(300, 700)],
        "e": [(450, 1800)],
        "i": [(300, 2200)],
    }

    generated = 0
    for vowel, formants in vowel_formants.items():
        for i in range(count_per_vowel):
            duration = random.uniform(1.5, 3.0)
            t = np.linspace(0, duration, int(TARGET_SR * duration))

            # Mix formant frequencies with slight vibrato
            signal = np.zeros_like(t)
            for f1, f2 in formants:
                # Add random variation
                f1_var = f1 * random.uniform(0.9, 1.1)
                f2_var = f2 * random.uniform(0.9, 1.1)
                vibrato = random.uniform(3, 7)  # Hz
                vibrato_depth = random.uniform(0.01, 0.03)

                phase1 = 2 * np.pi * f1_var * t * (1 + vibrato_depth * np.sin(2 * np.pi * vibrato * t))
                phase2 = 2 * np.pi * f2_var * t * (1 + vibrato_depth * np.sin(2 * np.pi * vibrato * t))
                signal += 0.5 * np.sin(phase1) + 0.3 * np.sin(phase2)

            # Add harmonics
            for h in [2, 3, 4]:
                harm_amp = random.uniform(0.05, 0.15) / h
                signal += harm_amp * np.sin(2 * np.pi * formants[0][0] * h * t)

            # Envelope (fade in/out)
            fade_len = int(TARGET_SR * 0.05)
            signal[:fade_len] *= np.linspace(0, 1, fade_len)
            signal[-fade_len:] *= np.linspace(1, 0, fade_len)

            # Normalize and add slight noise
            signal = signal / (np.max(np.abs(signal)) + 1e-8) * 0.7
            noise_level = random.uniform(0.001, 0.02)
            signal += np.random.randn(len(signal)) * noise_level

            # Volume variation
            gain = random.uniform(0.3, 1.0)
            signal *= gain

            audio = np.clip(signal, -1.0, 1.0)
            audio_int16 = (audio * 32767).astype(np.int16)

            filename = f"synth_vowel_{vowel}_{i:04d}.wav"
            save_wav(audio_int16, output_dir / filename)
            generated += 1

    log.info(f"Generated {generated} synthetic vowel clips")
    return generated


def main():
    parser = argparse.ArgumentParser(description="Generate vowel/non-speech negatives via Kokoro TTS")
    parser.add_argument("--output-dir", type=str, default=str(DEFAULT_OUTPUT),
                        help=f"Output directory (default: {DEFAULT_OUTPUT})")
    parser.add_argument("--voices", type=str, default=None,
                        help="Comma-separated voice names (default: mix of 8 voices)")
    parser.add_argument("--samples-per-text", type=int, default=3,
                        help="Samples per text per voice (default: 3)")
    parser.add_argument("--skip-tts", action="store_true",
                        help="Skip Kokoro TTS, only generate synthetic")
    parser.add_argument("--skip-synthetic", action="store_true",
                        help="Skip synthetic vowels")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    voices = args.voices.split(",") if args.voices else DEFAULT_VOICES
    categories = all_negative_texts()

    total_texts = sum(len(v) for v in categories.values())
    log.info(f"Categories: {len(categories)}, total texts: {total_texts}")
    log.info(f"Voices: {voices}")
    log.info(f"Samples per text per voice: {args.samples_per_text}")

    total_generated = 0

    # Generate synthetic vowels (no TTS needed)
    if not args.skip_synthetic:
        total_generated += generate_synthetic_vowels(output_dir)

    if args.skip_tts:
        log.info(f"Skipping TTS generation. Total: {total_generated} clips")
        return

    # Check Kokoro is running
    try:
        r = requests.get("http://localhost:8880/health", timeout=5)
        log.info(f"Kokoro TTS ready (status {r.status_code})")
    except Exception as e:
        log.error(f"Kokoro TTS not available at localhost:8880: {e}")
        log.error("Start it with: cd AI/infrastructure/kokoro-tts && bash start-gpu.sh")
        if total_generated > 0:
            log.info(f"Generated {total_generated} synthetic clips (no TTS)")
        return

    # Generate TTS clips for each category
    for cat_label, texts in categories.items():
        log.info(f"--- Category: {cat_label} ({len(texts)} texts) ---")
        for text in texts:
            for voice in voices:
                for sample_i in range(args.samples_per_text):
                    speed = random.choice(SPEEDS)

                    wav_bytes = generate_with_kokoro(text, voice, speed)
                    if wav_bytes is None:
                        continue

                    audio = wav_bytes_to_16k_int16(wav_bytes)
                    if audio is None:
                        continue

                    # Skip very short or silent clips
                    if len(audio) < TARGET_SR * 0.3:
                        continue
                    if np.max(np.abs(audio)) < 100:
                        continue

                    safe_text = text[:15].replace(" ", "_")
                    voice_short = voice.split("_")[-1]
                    filename = f"kokoro_{cat_label}_{safe_text}_{voice_short}_s{speed:.1f}_{sample_i}.wav"
                    save_wav(audio, output_dir / filename)
                    total_generated += 1

        log.info(f"  Category {cat_label}: done")

    log.info(f"Total generated: {total_generated} clips in {output_dir}")


if __name__ == "__main__":
    main()
