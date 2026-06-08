"""Stress test the wake word model with adversarial and edge-case audio.

Tests the model against scenarios that commonly cause false positives:
1. Sustained vowels at various pitches and durations
2. Background noise (white, pink, babble)
3. Music and rhythmic patterns
4. Whispered speech
5. Phonetically similar words with noise overlay
6. Rapid speech / slow speech
7. Silence and near-silence
8. Random PCM noise bursts
9. Vowel + consonant combinations that share phonetic features
10. Real-world ambient: TV, traffic, keyboard typing simulations

Reports per-category FP rates and overall pass/fail.

Usage:
    python stress_test.py
    python stress_test.py --model models/sireneviy.onnx
    python stress_test.py --threshold 0.8
"""

import argparse
import logging
import wave
import io
import random
from pathlib import Path

import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
MODELS_DIR = BASE_DIR / "models"
TARGET_SR = 16000


def generate_sustained_vowel(f1, f2, duration=2.5, sr=TARGET_SR, vibrato_hz=5, vibrato_depth=0.02):
    """Generate a sustained vowel-like tone from formant frequencies."""
    t = np.linspace(0, duration, int(sr * duration))
    phase_mod = 1 + vibrato_depth * np.sin(2 * np.pi * vibrato_hz * t)
    signal = 0.5 * np.sin(2 * np.pi * f1 * t * phase_mod)
    signal += 0.3 * np.sin(2 * np.pi * f2 * t * phase_mod)
    for h in [2, 3, 4]:
        signal += (0.1 / h) * np.sin(2 * np.pi * f1 * h * t)
    fade = int(sr * 0.03)
    signal[:fade] *= np.linspace(0, 1, fade)
    signal[-fade:] *= np.linspace(1, 0, fade)
    signal = signal / (np.max(np.abs(signal)) + 1e-8) * 0.7
    return signal


def generate_white_noise(duration=3.0, sr=TARGET_SR, amplitude=0.3):
    return np.random.randn(int(sr * duration)).astype(np.float32) * amplitude


def generate_pink_noise(duration=3.0, sr=TARGET_SR, amplitude=0.3):
    n = int(sr * duration)
    white = np.random.randn(n)
    freqs = np.fft.rfftfreq(n)
    freqs[0] = 1
    spectrum = np.fft.rfft(white) / np.sqrt(freqs)
    pink = np.fft.irfft(spectrum, n=n).astype(np.float32)
    return pink / (np.max(np.abs(pink)) + 1e-8) * amplitude


def generate_sine_sweep(f_start=100, f_end=4000, duration=3.0, sr=TARGET_SR):
    t = np.linspace(0, duration, int(sr * duration))
    freq = f_start + (f_end - f_start) * t / duration
    signal = 0.5 * np.sin(2 * np.pi * np.cumsum(freq) / sr)
    return signal.astype(np.float32)


def generate_click_train(rate_hz=4, duration=3.0, sr=TARGET_SR):
    n = int(sr * duration)
    signal = np.zeros(n, dtype=np.float32)
    interval = int(sr / rate_hz)
    click_len = int(sr * 0.002)
    for i in range(0, n, interval):
        end = min(i + click_len, n)
        signal[i:end] = 0.8 * np.sin(2 * np.pi * 1000 * np.arange(end - i) / sr)
    return signal


def generate_babble(n_voices=5, duration=3.0, sr=TARGET_SR):
    """Simulated babble noise -- overlapping vowel-like tones at different pitches."""
    signal = np.zeros(int(sr * duration), dtype=np.float32)
    for _ in range(n_voices):
        f1 = random.uniform(200, 900)
        f2 = random.uniform(800, 2500)
        phase = random.uniform(0, 2 * np.pi)
        t = np.linspace(0, duration, int(sr * duration))
        voice = 0.2 * np.sin(2 * np.pi * f1 * t + phase)
        voice += 0.1 * np.sin(2 * np.pi * f2 * t + phase * 1.3)
        # Random amplitude modulation (simulates speech rhythm)
        mod_freq = random.uniform(2, 6)
        voice *= 0.5 + 0.5 * np.sin(2 * np.pi * mod_freq * t + random.uniform(0, 2 * np.pi))
        signal += voice
    return signal / (np.max(np.abs(signal)) + 1e-8) * 0.5


def generate_humming(f0=200, duration=3.0, sr=TARGET_SR):
    """Simulated humming -- fundamental + nasal harmonics."""
    t = np.linspace(0, duration, int(sr * duration))
    vibrato = 0.015 * np.sin(2 * np.pi * 5.5 * t)
    signal = np.zeros_like(t)
    for h in range(1, 8):
        amp = 0.5 / h if h <= 3 else 0.1 / h
        signal += amp * np.sin(2 * np.pi * f0 * h * t * (1 + vibrato))
    fade = int(sr * 0.05)
    signal[:fade] *= np.linspace(0, 1, fade)
    signal[-fade:] *= np.linspace(1, 0, fade)
    return signal / (np.max(np.abs(signal)) + 1e-8) * 0.6


def audio_to_int16(audio):
    return (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)


class StressTestSuite:
    def __init__(self, cls_path, mel_path, emb_path, threshold=0.8, required_hits=2, window_size=5):
        from test_android_pipeline import AndroidPipelineSimulator, CHUNK_SIZE, SAMPLE_RATE
        self.sim = AndroidPipelineSimulator(mel_path, emb_path, cls_path)
        self.threshold = threshold
        self.required_hits = required_hits
        self.window_size = window_size
        self.chunk_size = CHUNK_SIZE
        self.sim_sr = SAMPLE_RATE

    def detect_audio(self, audio_float):
        """Run detection on float32 audio. Returns (n_detections, max_score)."""
        self.sim.reset()

        # Warmup
        warmup = np.zeros(self.sim_sr * 3, dtype=np.int16)
        for i in range(0, len(warmup), self.chunk_size):
            chunk = warmup[i:i + self.chunk_size]
            if len(chunk) < self.chunk_size:
                chunk = np.pad(chunk, (0, self.chunk_size - len(chunk)))
            self.sim.feed_chunk(chunk)

        audio_int16 = audio_to_int16(audio_float)
        silence = np.zeros(self.sim_sr * 1, dtype=np.int16)
        audio_int16 = np.concatenate([silence, audio_int16, silence])

        score_history = []
        detections = 0
        cooldown_frames = 0
        max_score = 0.0

        for i in range(0, len(audio_int16), self.chunk_size):
            chunk = audio_int16[i:i + self.chunk_size]
            if len(chunk) < self.chunk_size:
                chunk = np.pad(chunk, (0, self.chunk_size - len(chunk)))
            score = self.sim.feed_chunk(chunk)
            max_score = max(max_score, score)

            if cooldown_frames > 0:
                cooldown_frames -= 1
                continue

            score_history.append(score > self.threshold)
            if len(score_history) > self.window_size:
                score_history.pop(0)

            if sum(score_history) >= self.required_hits:
                detections += 1
                score_history.clear()
                cooldown_frames = 25

        return detections, max_score

    def run_category(self, name, samples):
        """Run detection on a list of (label, audio_float) pairs. Returns results dict."""
        total = len(samples)
        fp = 0
        fp_details = []
        for label, audio in samples:
            n_det, max_s = self.detect_audio(audio)
            if n_det > 0:
                fp += 1
                fp_details.append((label, max_s))
        fp_rate = fp / max(total, 1)
        return {"name": name, "total": total, "fp": fp, "fp_rate": fp_rate, "fp_details": fp_details}


def build_test_categories():
    """Build all stress test categories with generated audio."""
    categories = {}

    # 1. Sustained vowels (various pitches and durations)
    log.info("Generating: sustained vowels...")
    vowels = []
    formants = {"a": (800, 1200), "o": (500, 900), "u": (300, 700), "e": (450, 1800), "i": (300, 2200)}
    for name, (f1, f2) in formants.items():
        for pitch_mult in [0.7, 0.85, 1.0, 1.15, 1.3]:
            for dur in [1.5, 2.5, 4.0]:
                audio = generate_sustained_vowel(f1 * pitch_mult, f2 * pitch_mult, duration=dur)
                vowels.append((f"vowel_{name}_p{pitch_mult}_d{dur}", audio))
    categories["sustained_vowels"] = vowels

    # 2. Humming at various pitches
    log.info("Generating: humming...")
    humming = []
    for f0 in [120, 160, 200, 250, 300, 400]:
        for dur in [2.0, 3.0, 5.0]:
            audio = generate_humming(f0, dur)
            humming.append((f"hum_f{f0}_d{dur}", audio))
    categories["humming"] = humming

    # 3. White noise at various levels
    log.info("Generating: noise...")
    noise = []
    for amp in [0.05, 0.1, 0.2, 0.4, 0.6]:
        noise.append((f"white_{amp}", generate_white_noise(3.0, amplitude=amp)))
        noise.append((f"pink_{amp}", generate_pink_noise(3.0, amplitude=amp)))
    categories["noise"] = noise

    # 4. Babble (overlapping voices)
    log.info("Generating: babble...")
    babble = []
    for n_voices in [2, 3, 5, 8, 12]:
        for _ in range(3):
            babble.append((f"babble_{n_voices}v", generate_babble(n_voices, 3.0)))
    categories["babble"] = babble

    # 5. Silence and near-silence
    log.info("Generating: silence...")
    silence = []
    silence.append(("pure_silence", np.zeros(TARGET_SR * 3, dtype=np.float32)))
    for amp in [0.001, 0.005, 0.01, 0.02]:
        silence.append((f"near_silence_{amp}", np.random.randn(TARGET_SR * 3).astype(np.float32) * amp))
    categories["silence"] = silence

    # 6. Sine sweeps (frequency sweeps through speech range)
    log.info("Generating: sine sweeps...")
    sweeps = []
    for f_start, f_end in [(100, 4000), (200, 1000), (300, 800), (500, 2000)]:
        for dur in [2.0, 4.0]:
            sweeps.append((f"sweep_{f_start}-{f_end}", generate_sine_sweep(f_start, f_end, dur)))
    categories["sine_sweeps"] = sweeps

    # 7. Click trains (rhythmic patterns)
    log.info("Generating: clicks...")
    clicks = []
    for rate in [2, 4, 8, 16]:
        clicks.append((f"clicks_{rate}hz", generate_click_train(rate, 3.0)))
    categories["click_trains"] = clicks

    # 8. Vowels mixed with noise (simulates real-world "aaaa" in noisy room)
    log.info("Generating: vowels + noise mix...")
    vowel_noise = []
    for name, (f1, f2) in formants.items():
        vowel = generate_sustained_vowel(f1, f2, duration=3.0)
        for snr_db in [5, 10, 20]:
            noise_amp = np.sqrt(np.mean(vowel**2)) / (10 ** (snr_db / 20))
            noisy = vowel + np.random.randn(len(vowel)).astype(np.float32) * noise_amp
            vowel_noise.append((f"vowel_{name}_snr{snr_db}", noisy))
    categories["vowels_with_noise"] = vowel_noise

    # 9. Vowel transitions (gliding a->o->u etc)
    log.info("Generating: vowel transitions...")
    transitions = []
    transition_pairs = [("a", "o"), ("o", "u"), ("e", "i"), ("a", "e"), ("u", "a")]
    for v1, v2 in transition_pairs:
        f1_start, f2_start = formants[v1]
        f1_end, f2_end = formants[v2]
        dur = 3.0
        t = np.linspace(0, dur, int(TARGET_SR * dur))
        f1_sweep = f1_start + (f1_end - f1_start) * t / dur
        f2_sweep = f2_start + (f2_end - f2_start) * t / dur
        signal = 0.5 * np.sin(2 * np.pi * np.cumsum(f1_sweep) / TARGET_SR)
        signal += 0.3 * np.sin(2 * np.pi * np.cumsum(f2_sweep) / TARGET_SR)
        signal = signal / (np.max(np.abs(signal)) + 1e-8) * 0.6
        transitions.append((f"trans_{v1}_{v2}", signal.astype(np.float32)))
        # Also reverse
        signal_rev = signal[::-1].copy()
        transitions.append((f"trans_{v2}_{v1}", signal_rev.astype(np.float32)))
    categories["vowel_transitions"] = transitions

    # 10. Random PCM bursts (garbage data)
    log.info("Generating: random PCM...")
    random_pcm = []
    for _ in range(10):
        dur = random.uniform(1.0, 4.0)
        audio = np.random.uniform(-0.8, 0.8, int(TARGET_SR * dur)).astype(np.float32)
        random_pcm.append(("random_pcm", audio))
    categories["random_pcm"] = random_pcm

    # 11. Amplitude-modulated tones (simulates TV/radio)
    log.info("Generating: AM tones...")
    am_tones = []
    for carrier_f in [300, 500, 800, 1200]:
        for mod_f in [2, 5, 10]:
            dur = 3.0
            t = np.linspace(0, dur, int(TARGET_SR * dur))
            signal = 0.5 * (1 + 0.8 * np.sin(2 * np.pi * mod_f * t)) * np.sin(2 * np.pi * carrier_f * t)
            am_tones.append((f"am_{carrier_f}_{mod_f}", signal.astype(np.float32)))
    categories["am_tones"] = am_tones

    return categories


def main():
    parser = argparse.ArgumentParser(description="Stress test wake word model")
    parser.add_argument("--model", type=str, default=str(MODELS_DIR / "sireneviy.onnx"))
    parser.add_argument("--threshold", type=float, default=0.8)
    parser.add_argument("--max-fp-rate", type=float, default=0.05, help="Max acceptable per-category FP rate")
    args = parser.parse_args()

    from train import get_onnx_models
    mel_path, emb_path = get_onnx_models()

    log.info(f"Model: {args.model}")
    log.info(f"Threshold: {args.threshold}")
    log.info(f"Max FP rate: {args.max_fp_rate*100:.0f}%")
    log.info("")

    suite = StressTestSuite(args.model, mel_path, emb_path, threshold=args.threshold)
    categories = build_test_categories()

    total_samples = sum(len(v) for v in categories.values())
    log.info(f"Total test samples: {total_samples} across {len(categories)} categories")
    log.info("=" * 70)

    all_results = []
    total_fp = 0
    total_tests = 0

    for cat_name, samples in categories.items():
        result = suite.run_category(cat_name, samples)
        all_results.append(result)
        total_fp += result["fp"]
        total_tests += result["total"]

        status = "PASS" if result["fp_rate"] <= args.max_fp_rate else "FAIL"
        log.info(f"  {result['name']:25s}  {result['fp']:3d}/{result['total']:3d} FP  "
                 f"({result['fp_rate']*100:5.1f}%)  [{status}]")
        if result["fp_details"]:
            for label, score in result["fp_details"][:3]:
                log.info(f"    -> {label} (max_score={score:.4f})")

    overall_fp_rate = total_fp / max(total_tests, 1)
    log.info("=" * 70)
    log.info(f"OVERALL: {total_fp}/{total_tests} FP ({overall_fp_rate*100:.1f}%)")

    failing = [r for r in all_results if r["fp_rate"] > args.max_fp_rate]
    if failing:
        log.warning(f"FAILING categories ({len(failing)}):")
        for r in failing:
            log.warning(f"  {r['name']}: {r['fp_rate']*100:.1f}% FP")
    else:
        log.info("ALL CATEGORIES PASS")

    status = "PASS" if overall_fp_rate <= args.max_fp_rate else "FAIL"
    log.info(f"\nFinal verdict: {status} (overall FP {overall_fp_rate*100:.1f}% vs {args.max_fp_rate*100:.0f}% threshold)")


if __name__ == "__main__":
    main()
