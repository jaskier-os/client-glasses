"""Live microphone test for the trained wake word model.

Captures audio from the default microphone and runs openWakeWord inference
in real-time, printing detection scores.

Usage:
    python test_live.py                              # Use default model
    python test_live.py --model models/sireneviy.onnx  # Custom model path
    python test_live.py --threshold 0.5              # Custom threshold
    python test_live.py --list-devices               # List audio devices
    python test_live.py --device 3                   # Use specific device
"""

import argparse
import logging
import sys
import time
from pathlib import Path

import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
DEFAULT_MODEL = BASE_DIR / "models" / "sireneviy.onnx"


def test_with_openwakeword(model_path: str, threshold: float, device_id: int | None):
    """Test using openwakeword's Model class (recommended, handles all buffering)."""
    import sounddevice as sd
    from openwakeword.model import Model

    oww = Model(wakeword_model_paths=[model_path])
    model_name = Path(model_path).stem

    # Consecutive-frame detection: require N out of M recent frames above threshold
    # 2/5 with t=0.8 gives 93% recall, 7/152 FP (all phonetically similar words)
    REQUIRED_HITS = 2   # need this many frames above threshold
    WINDOW_SIZE = 5     # within this many recent frames
    score_history = []

    log.info(f"Loaded model: {model_name}")
    log.info(f"Threshold: {threshold}")
    log.info(f"Smoothing: {REQUIRED_HITS}/{WINDOW_SIZE} frames required")
    log.info(f"Listening... (Ctrl+C to stop)")
    log.info("-" * 60)

    cooldown_until = 0

    def audio_callback(indata, frames, time_info, status):
        nonlocal cooldown_until
        if status:
            log.warning(f"Audio status: {status}")

        audio = (indata[:, 0] * 32768).astype(np.int16)
        prediction = oww.predict(audio)

        score = prediction.get(model_name, 0.0)
        now = time.time()

        # Track score history for smoothing
        score_history.append(score > threshold)
        if len(score_history) > WINDOW_SIZE:
            score_history.pop(0)

        hits = sum(score_history)

        if hits >= REQUIRED_HITS and now > cooldown_until:
            log.info(f"** DETECTED ** score={score:.4f} ({hits}/{WINDOW_SIZE} frames)")
            cooldown_until = now + 2.0  # 2 second cooldown
            score_history.clear()
        elif score > threshold * 0.3:
            sys.stdout.write(f"\r  score={score:.4f} [{hits}/{WINDOW_SIZE}]  ")
            sys.stdout.flush()

    kwargs = dict(
        callback=audio_callback,
        channels=1,
        samplerate=16000,
        blocksize=1280,  # 80ms chunks (openWakeWord frame size)
        dtype="float32",
    )
    if device_id is not None:
        kwargs["device"] = device_id

    try:
        with sd.InputStream(**kwargs):
            input("Press Enter to stop...\n")
    except KeyboardInterrupt:
        pass

    log.info("Stopped.")


def test_standalone(model_path: str, threshold: float, device_id: int | None):
    """Test using raw ONNX Runtime inference (fallback if openwakeword import fails)."""
    import onnxruntime as ort
    import sounddevice as sd

    # Locate feature extraction models
    try:
        import openwakeword
        models_dir = Path(openwakeword.__file__).parent / "resources" / "models"
        mel_path = str(models_dir / "melspectrogram.onnx")
        emb_path = str(models_dir / "embedding_model.onnx")
    except ImportError:
        mel_path = str(BASE_DIR / "models" / "melspectrogram.onnx")
        emb_path = str(BASE_DIR / "models" / "embedding_model.onnx")

    mel_session = ort.InferenceSession(mel_path)
    emb_session = ort.InferenceSession(emb_path)
    cls_session = ort.InferenceSession(model_path)

    mel_input_name = mel_session.get_inputs()[0].name
    emb_input_name = emb_session.get_inputs()[0].name
    cls_input_name = cls_session.get_inputs()[0].name

    # Streaming buffers
    mel_buffer = np.zeros((76, 32), dtype=np.float32)  # Initialize with 76 frames
    embedding_buffer = []

    log.info(f"Loaded model: {model_path}")
    log.info(f"Threshold: {threshold}")
    log.info(f"Listening (standalone mode)... (Ctrl+C to stop)")
    log.info("-" * 60)

    cooldown_until = 0

    def audio_callback(indata, frames, time_info, status):
        nonlocal mel_buffer, cooldown_until
        if status:
            log.warning(f"Audio status: {status}")

        audio = indata[:, 0].astype(np.float32)

        # Melspectrogram
        mel_input = audio[np.newaxis, :]
        mel_output = mel_session.run(None, {mel_input_name: mel_input})[0]
        mel_output = mel_output / 10.0 + 2.0
        new_mel_frames = mel_output[0]  # (n_frames, 32)

        # Append to mel buffer
        mel_buffer = np.concatenate([mel_buffer, new_mel_frames], axis=0)

        # Extract embedding from last 76 frames
        if mel_buffer.shape[0] >= 76:
            window = mel_buffer[-76:]
            emb_input = window[np.newaxis, :, :, np.newaxis]
            emb_output = emb_session.run(None, {emb_input_name: emb_input})[0]
            embedding_buffer.append(emb_output[0])

            # Keep buffer manageable
            if mel_buffer.shape[0] > 200:
                mel_buffer = mel_buffer[-76:]

        # Run classifier when we have enough embeddings
        if len(embedding_buffer) >= 16:
            features = np.array(embedding_buffer[-16:], dtype=np.float32)
            cls_input = features[np.newaxis, :]  # (1, 16, 96)
            score = cls_session.run(None, {cls_input_name: cls_input})[0][0][0]

            now = time.time()
            if score > threshold and now > cooldown_until:
                log.info(f"** DETECTED ** score={score:.4f}")
                cooldown_until = now + 2.0
            elif score > threshold * 0.5:
                sys.stdout.write(f"\r  score={score:.4f}  ")
                sys.stdout.flush()

            # Keep embedding buffer manageable
            if len(embedding_buffer) > 120:
                del embedding_buffer[:len(embedding_buffer) - 20]

    kwargs = dict(
        callback=audio_callback,
        channels=1,
        samplerate=16000,
        blocksize=1280,
        dtype="float32",
    )
    if device_id is not None:
        kwargs["device"] = device_id

    try:
        with sd.InputStream(**kwargs):
            input("Press Enter to stop...\n")
    except KeyboardInterrupt:
        pass

    log.info("Stopped.")


def test_from_phone(model_path: str, threshold: float, phone_ip: str):
    """Test using glasses mic audio streamed from phone via TCP."""
    import socket
    import struct
    from openwakeword.model import Model

    oww = Model(wakeword_model_paths=[model_path])
    model_name = Path(model_path).stem

    REQUIRED_HITS = 2
    WINDOW_SIZE = 5
    score_history = []
    cooldown_until = 0

    log.info(f"Loaded model: {model_name}")
    log.info(f"Threshold: {threshold}")
    log.info(f"Connecting to phone at {phone_ip}:5050...")

    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.connect((phone_ip, 5050))
    log.info(f"Connected. Listening for glasses mic audio...")
    log.info("-" * 60)

    CHUNK_SAMPLES = 1280  # 80ms, matches OWW frame size
    pcm_accum = np.zeros(0, dtype=np.int16)
    remainder = b""
    try:
        while True:
            data = sock.recv(16384)
            if not data:
                break
            data = remainder + data
            n_complete = (len(data) // 2) * 2
            remainder = data[n_complete:]
            if n_complete == 0:
                continue

            pcm_accum = np.concatenate([pcm_accum, np.frombuffer(data[:n_complete], dtype=np.int16)])
            if len(pcm_accum) < CHUNK_SAMPLES:
                continue

            # Process in 1280-sample chunks
            while len(pcm_accum) >= CHUNK_SAMPLES:
                chunk = pcm_accum[:CHUNK_SAMPLES]
                pcm_accum = pcm_accum[CHUNK_SAMPLES:]
                prediction = oww.predict(chunk)
                score = prediction.get(model_name, 0.0)
                now = time.time()

                score_history.append(score > threshold)
                if len(score_history) > WINDOW_SIZE:
                    score_history.pop(0)

                hits = sum(score_history)

                if hits >= REQUIRED_HITS and now > cooldown_until:
                    log.info(f"** DETECTED ** score={score:.4f} ({hits}/{WINDOW_SIZE} frames)")
                    cooldown_until = now + 2.0
                    score_history.clear()
                elif score > threshold * 0.3:
                    sys.stdout.write(f"\r  score={score:.4f} [{hits}/{WINDOW_SIZE}]  ")
                    sys.stdout.flush()
    except KeyboardInterrupt:
        pass
    finally:
        sock.close()
    log.info("Stopped.")


def list_devices():
    import sounddevice as sd
    print(sd.query_devices())


def main():
    parser = argparse.ArgumentParser(description="Test wake word model with live microphone")
    parser.add_argument("--model", type=str, default=str(DEFAULT_MODEL), help="Path to ONNX classifier model")
    parser.add_argument("--threshold", type=float, default=0.8, help="Detection threshold (default: 0.8)")
    parser.add_argument("--device", type=int, default=None, help="Audio input device ID")
    parser.add_argument("--list-devices", action="store_true", help="List available audio devices")
    parser.add_argument("--standalone", action="store_true", help="Use standalone ONNX inference (no openwakeword)")
    parser.add_argument("--phone", type=str, default=None,
                        help="Phone IP to stream glasses mic audio (e.g. 192.168.0.103)")
    args = parser.parse_args()

    if args.list_devices:
        list_devices()
        return

    if not Path(args.model).exists():
        log.error(f"Model not found: {args.model}")
        log.error("Run train.py first to generate the model.")
        return

    if args.phone:
        test_from_phone(args.model, args.threshold, args.phone)
    elif args.standalone:
        test_standalone(args.model, args.threshold, args.device)
    else:
        try:
            test_with_openwakeword(args.model, args.threshold, args.device)
        except ImportError:
            log.warning("openwakeword not available, using standalone mode")
            test_standalone(args.model, args.threshold, args.device)


if __name__ == "__main__":
    main()
