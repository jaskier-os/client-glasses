"""Simulate the exact Android phone pipeline to test wake word detection.

Feeds audio in 512-sample chunks (matching AudioRecorder.CHUNK_SIZE),
accumulates to 1280 before running mel, and uses the same continuous-feed
approach as the phone app. This catches issues that clip-based testing misses.

Usage:
    python test_android_pipeline.py                          # Test all recorded samples
    python test_android_pipeline.py --positive-only          # Only positive samples
    python test_android_pipeline.py --negative-only          # Only negative samples
    python test_android_pipeline.py --threshold 0.5          # Custom threshold
"""

import argparse
import logging
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
from scipy.io import wavfile

logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
ASSETS_DIR = Path("/media/user/Lobotomite/Repository/AI/clients/phone/app/src/main/assets")

CHUNK_SIZE = 512        # AudioRecorder chunk size on phone
ACCUM_SIZE = 1280       # OWW accumulation target
MEL_BINS = 32
MEL_WINDOW = 76
EMBEDDING_DIM = 96
N_FRAMES = 16
MAX_MEL_BUFFER = 200
SAMPLE_RATE = 16000


class AndroidPipelineSimulator:
    """Simulates the exact OpenWakeWordEngine + WakeWordDetector flow on Android."""

    def __init__(self, mel_path, emb_path, cls_path):
        self.mel_session = ort.InferenceSession(mel_path)
        self.emb_session = ort.InferenceSession(emb_path)
        self.cls_session = ort.InferenceSession(cls_path)

        self.audio_accum = np.zeros(ACCUM_SIZE, dtype=np.int16)
        self.audio_accum_pos = 0
        self.mel_buffer = []
        self.emb_buffer = []

    def reset(self):
        self.audio_accum_pos = 0
        self.mel_buffer.clear()
        self.emb_buffer.clear()

    def feed_chunk(self, samples_int16):
        """Feed a chunk of int16 samples (any size). Returns latest score or 0."""
        src_pos = 0
        last_score = 0.0
        while src_pos < len(samples_int16):
            to_copy = min(len(samples_int16) - src_pos, ACCUM_SIZE - self.audio_accum_pos)
            self.audio_accum[self.audio_accum_pos:self.audio_accum_pos + to_copy] = \
                samples_int16[src_pos:src_pos + to_copy]
            self.audio_accum_pos += to_copy
            src_pos += to_copy

            if self.audio_accum_pos < ACCUM_SIZE:
                continue
            self.audio_accum_pos = 0

            last_score = self._process_full_chunk()
        return last_score

    def _process_full_chunk(self):
        audio_f32 = self.audio_accum.astype(np.float32) / 32768.0

        # Melspectrogram
        mel_input = audio_f32[np.newaxis, :].astype(np.float32)
        mel_output = self.mel_session.run(None, {"input": mel_input})[0]
        mel_output = np.squeeze(mel_output)
        if mel_output.ndim == 1:
            mel_output = mel_output.reshape(1, -1)
        mel_output = mel_output / 10.0 + 2.0

        for i in range(mel_output.shape[0]):
            self.mel_buffer.append(mel_output[i])
        while len(self.mel_buffer) > MAX_MEL_BUFFER:
            self.mel_buffer.pop(0)

        if len(self.mel_buffer) < MEL_WINDOW:
            return 0.0

        # Embedding
        start_idx = len(self.mel_buffer) - MEL_WINDOW
        window = np.array(self.mel_buffer[start_idx:start_idx + MEL_WINDOW], dtype=np.float32)
        emb_input = window[np.newaxis, :, :, np.newaxis]
        emb_output = self.emb_session.run(None, {"input_1": emb_input})[0]
        embedding = np.squeeze(emb_output)
        self.emb_buffer.append(embedding)
        if len(self.emb_buffer) > N_FRAMES:
            self.emb_buffer.pop(0)

        if len(self.emb_buffer) < N_FRAMES:
            return 0.0

        # Classifier
        features = np.array(self.emb_buffer[-N_FRAMES:], dtype=np.float32)
        cls_input = features[np.newaxis, :]
        score = self.cls_session.run(None, {"input": cls_input})[0]
        return float(np.squeeze(score))


def test_file(sim, wav_path, threshold, verbose=False):
    """Simulate the full Android pipeline for a WAV file.

    Returns dict with max_score, frame_scores, detection count, etc.
    """
    sr, audio = wavfile.read(str(wav_path))
    if audio.dtype != np.int16:
        audio = (audio * 32767).astype(np.int16)

    # Pad with 2s silence before and after (simulates continuous listening)
    silence = np.zeros(SAMPLE_RATE * 2, dtype=np.int16)
    full_audio = np.concatenate([silence, audio, silence])

    sim.reset()

    # Feed 2s of silence first to warm up buffers (simulates phone always-on)
    warmup = np.zeros(SAMPLE_RATE * 3, dtype=np.int16)
    for i in range(0, len(warmup), CHUNK_SIZE):
        chunk = warmup[i:i + CHUNK_SIZE]
        if len(chunk) < CHUNK_SIZE:
            chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
        sim.feed_chunk(chunk)

    # Now feed the actual audio in 512-sample chunks
    scores = []
    for i in range(0, len(full_audio), CHUNK_SIZE):
        chunk = full_audio[i:i + CHUNK_SIZE]
        if len(chunk) < CHUNK_SIZE:
            chunk = np.pad(chunk, (0, CHUNK_SIZE - len(chunk)))
        score = sim.feed_chunk(chunk)
        scores.append(score)

    max_score = max(scores) if scores else 0.0
    above_threshold = sum(1 for s in scores if s > threshold)
    high_scores = [s for s in scores if s > 0.1]

    if verbose and high_scores:
        print(f"    High scores: {' '.join(f'{s:.3f}' for s in high_scores)}")

    return {
        "max_score": max_score,
        "above_threshold": above_threshold,
        "total_frames": len(scores),
        "high_scores": high_scores,
    }


def main():
    parser = argparse.ArgumentParser(description="Test Android wake word pipeline")
    parser.add_argument("--threshold", type=float, default=0.8)
    parser.add_argument("--positive-only", action="store_true")
    parser.add_argument("--negative-only", action="store_true")
    parser.add_argument("--verbose", "-v", action="store_true")
    args = parser.parse_args()

    # Use same models as the phone
    mel_path = str(ASSETS_DIR / "melspectrogram.onnx")
    emb_path = str(ASSETS_DIR / "embedding_model.onnx")
    cls_path = str(ASSETS_DIR / "sireneviy.onnx")

    for p in [mel_path, emb_path, cls_path]:
        if not Path(p).exists():
            log.error(f"Model not found: {p}")
            return

    sim = AndroidPipelineSimulator(mel_path, emb_path, cls_path)

    do_pos = not args.negative_only
    do_neg = not args.positive_only

    if do_pos:
        pos_dir = BASE_DIR / "data" / "recorded_positive"
        pos_files = sorted(pos_dir.glob("*.wav")) if pos_dir.exists() else []
        if not pos_files:
            pos_dir = BASE_DIR / "data" / "positive"
            pos_files = sorted(pos_dir.glob("*.wav"))[:20]

        print(f"\n=== POSITIVE ({len(pos_files)} files, threshold={args.threshold}) ===")
        detected = 0
        for f in pos_files:
            result = test_file(sim, f, args.threshold, args.verbose)
            hit = result["above_threshold"] > 0
            if hit:
                detected += 1
            status = "OK" if hit else "MISS"
            print(f"  [{status}] {f.name}: max={result['max_score']:.4f} frames_above={result['above_threshold']}")

        print(f"  DETECTION RATE: {detected}/{len(pos_files)} ({detected/len(pos_files)*100:.0f}%)")

    if do_neg:
        neg_dir = BASE_DIR / "data" / "recorded_negative"
        neg_files = sorted(neg_dir.glob("*.wav")) if neg_dir.exists() else []
        if not neg_files:
            neg_dir = BASE_DIR / "data" / "adversarial"
            neg_files = sorted(neg_dir.glob("*.wav"))[:20]

        print(f"\n=== NEGATIVE ({len(neg_files)} files, threshold={args.threshold}) ===")
        false_positives = 0
        fp_list = []
        for f in neg_files:
            result = test_file(sim, f, args.threshold, args.verbose)
            hit = result["above_threshold"] > 0
            if hit:
                false_positives += 1
                fp_list.append((f.name, result["max_score"], result["above_threshold"]))

        print(f"  FALSE POSITIVE RATE: {false_positives}/{len(neg_files)} ({false_positives/len(neg_files)*100:.1f}%)")
        if fp_list:
            fp_list.sort(key=lambda x: x[1], reverse=True)
            print(f"  Worst FPs:")
            for name, score, frames in fp_list[:15]:
                print(f"    {score:.4f} ({frames} frames) {name}")


if __name__ == "__main__":
    main()
