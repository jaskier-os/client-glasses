"""Record wake word samples from glasses mic via phone TCP stream.

GUI with real-time spectrogram. Connect to phone, see audio flowing,
select regions containing the wake word, and save as training samples.

Phone streams glasses audio (PCM 16-bit LE, mono, 16kHz) on TCP port 5050.

Usage:
    python record_samples.py                        # Auto-detect phone IP
    python record_samples.py --phone 192.168.0.103  # Specific phone IP
    python record_samples.py --negative             # Record negative samples
"""

import argparse
import logging
import socket
import struct
import threading
import time
import wave
from pathlib import Path

import numpy as np

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger(__name__)

BASE_DIR = Path(__file__).parent
POSITIVE_DIR = BASE_DIR / "data" / "recorded_positive"
NEGATIVE_DIR = BASE_DIR / "data" / "recorded_negative"

SAMPLE_RATE = 16000
BUFFER_SECONDS = 10
BUFFER_SIZE = SAMPLE_RATE * BUFFER_SECONDS
STREAM_PORT = 5050


class AudioStream:
    """Receives PCM audio from phone TCP stream."""

    def __init__(self):
        self.buffer = np.zeros(BUFFER_SIZE, dtype=np.float32)
        self.write_pos = 0
        self.total_samples = 0
        self.connected = False
        self.sock = None
        self._stop = False
        self._lock = threading.Lock()

    def connect(self, host, port=STREAM_PORT):
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.settimeout(5)
        self.sock.connect((host, port))
        self.connected = True
        self._stop = False
        log.info(f"Connected to {host}:{port}")

    def disconnect(self):
        self._stop = True
        self.connected = False
        if self.sock:
            try:
                self.sock.close()
            except Exception:
                pass
            self.sock = None

    def read_loop(self):
        """Read PCM data from socket. Run in a thread."""
        remainder = b""
        while not self._stop and self.sock:
            try:
                data = self.sock.recv(16384)
                if not data:
                    break
                data = remainder + data
                n_complete = (len(data) // 2) * 2
                remainder = data[n_complete:]
                if n_complete == 0:
                    continue
                samples = np.frombuffer(data[:n_complete], dtype=np.int16).astype(np.float32) / 32768.0
                n = len(samples)
                with self._lock:
                    pos = self.write_pos % BUFFER_SIZE
                    if pos + n <= BUFFER_SIZE:
                        self.buffer[pos:pos + n] = samples
                    else:
                        first = BUFFER_SIZE - pos
                        self.buffer[pos:] = samples[:first]
                        self.buffer[:n - first] = samples[first:]
                    self.write_pos += n
                    self.total_samples += n
            except socket.timeout:
                continue
            except Exception as e:
                if not self._stop:
                    log.error(f"Stream error: {e}")
                break
        self.connected = False
        log.info("Stream disconnected")

    def get_buffer(self):
        """Get ordered audio buffer (oldest to newest)."""
        with self._lock:
            if self.write_pos < BUFFER_SIZE:
                return self.buffer[:self.write_pos].copy()
            pos = self.write_pos % BUFFER_SIZE
            return np.concatenate([self.buffer[pos:], self.buffer[:pos]])

    def get_time_range(self, t_start, t_end):
        """Extract audio between t_start and t_end seconds (relative to buffer start)."""
        buf = self.get_buffer()
        s_start = max(0, int(t_start * SAMPLE_RATE))
        s_end = min(len(buf), int(t_end * SAMPLE_RATE))
        return buf[s_start:s_end]


def save_wav(audio, path):
    """Save float32 audio as 16-bit WAV."""
    audio_int16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SAMPLE_RATE)
        f.writeframes(audio_int16.tobytes())


def get_next_index(output_dir):
    existing = sorted(output_dir.glob("*.wav"))
    if not existing:
        return 0
    last = existing[-1].stem
    try:
        return int(last.split("_")[-1]) + 1
    except ValueError:
        return len(existing)


def run_gui(phone_ip, output_dir, label):
    import matplotlib
    matplotlib.use("TkAgg")
    import matplotlib.pyplot as plt
    from matplotlib.widgets import SpanSelector, Button

    output_dir.mkdir(parents=True, exist_ok=True)
    sample_idx = get_next_index(output_dir)

    stream = AudioStream()
    paused = [False]
    paused_buf = [None]
    selection = [None, None]  # (t_start, t_end)

    # --- Figure setup ---
    fig = plt.figure(figsize=(14, 6))
    fig.canvas.manager.set_window_title(f"Wake Word Recorder - {label}")

    ax_spec = fig.add_axes([0.06, 0.30, 0.88, 0.62])
    ax_wave = fig.add_axes([0.06, 0.18, 0.88, 0.10])

    # Buttons
    ax_connect = fig.add_axes([0.06, 0.03, 0.10, 0.06])
    ax_pause = fig.add_axes([0.18, 0.03, 0.10, 0.06])
    ax_save = fig.add_axes([0.30, 0.03, 0.15, 0.06])
    ax_play = fig.add_axes([0.47, 0.03, 0.10, 0.06])
    ax_status = fig.add_axes([0.60, 0.03, 0.34, 0.06])
    ax_status.axis("off")

    btn_connect = Button(ax_connect, "Connect")
    btn_pause = Button(ax_pause, "Pause")
    btn_save = Button(ax_save, f"Save {label}")
    btn_play = Button(ax_play, "Play")

    status_text = ax_status.text(0.0, 0.5, "Disconnected", fontsize=10,
                                  va="center", fontfamily="monospace")

    def update_status(msg):
        status_text.set_text(msg)
        fig.canvas.draw_idle()

    def on_connect(event):
        if stream.connected:
            stream.disconnect()
            btn_connect.label.set_text("Connect")
            update_status("Disconnected")
            return
        try:
            stream.connect(phone_ip)
            t = threading.Thread(target=stream.read_loop, daemon=True)
            t.start()
            btn_connect.label.set_text("Disconnect")
            update_status(f"Connected to {phone_ip}")
        except Exception as e:
            update_status(f"Failed: {e}")

    def on_pause(event):
        if paused[0]:
            paused[0] = False
            paused_buf[0] = None
            selection[0] = selection[1] = None
            btn_pause.label.set_text("Pause")
        else:
            paused[0] = True
            paused_buf[0] = stream.get_buffer()
            btn_pause.label.set_text("Resume")

    def on_select(t_min, t_max):
        selection[0] = t_min
        selection[1] = t_max
        dur = t_max - t_min
        update_status(f"Selected: {t_min:.2f}s - {t_max:.2f}s ({dur:.2f}s)")
        # Auto-play selection
        on_play(None)

    def on_save(event):
        nonlocal sample_idx
        if selection[0] is None or selection[1] is None:
            update_status("No selection! Click-drag on spectrogram first.")
            return
        buf = paused_buf[0] if paused[0] and paused_buf[0] is not None else stream.get_buffer()
        s_start = max(0, int(selection[0] * SAMPLE_RATE))
        s_end = min(len(buf), int(selection[1] * SAMPLE_RATE))
        audio = buf[s_start:s_end]
        if len(audio) < SAMPLE_RATE * 0.3:
            update_status("Selection too short (< 0.3s)")
            return
        filename = f"sample_{sample_idx:04d}.wav"
        save_wav(audio, output_dir / filename)
        sample_idx += 1
        count = len(list(output_dir.glob("*.wav")))
        update_status(f"Saved {filename} ({count} total)")

    def on_play(event):
        if selection[0] is None:
            update_status("No selection!")
            return
        buf = paused_buf[0] if paused[0] and paused_buf[0] is not None else stream.get_buffer()
        s_start = max(0, int(selection[0] * SAMPLE_RATE))
        s_end = min(len(buf), int(selection[1] * SAMPLE_RATE))
        audio = buf[s_start:s_end]
        try:
            import sounddevice as sd
            sd.play(audio, SAMPLE_RATE)
        except Exception as e:
            update_status(f"Playback error: {e}")

    btn_connect.on_clicked(on_connect)
    btn_save.on_clicked(on_save)
    btn_play.on_clicked(on_play)
    # btn_pause bound below after on_pause_with_render is defined

    # Spectrogram settings
    NFFT = 512
    noverlap = 480
    last_total = [0]

    def render_spectrogram(buf):
        """Full redraw of spectrogram and waveform from buffer."""
        t_max = len(buf) / SAMPLE_RATE

        ax_spec.clear()
        ax_spec.specgram(buf, NFFT=NFFT, Fs=SAMPLE_RATE, noverlap=noverlap,
                         cmap="magma", vmin=-80, vmax=-20)
        ax_spec.set_ylabel("Hz")
        ax_spec.set_xlim(0, t_max)
        ax_spec.set_ylim(0, 4000)

        ax_wave.clear()
        # Downsample waveform for performance (show every 4th sample)
        step = max(1, len(buf) // 4000)
        t_axis = np.arange(0, len(buf), step) / SAMPLE_RATE
        ax_wave.plot(t_axis, buf[::step], color="#00cc66", linewidth=0.3)
        ax_wave.set_xlim(0, t_max)
        ax_wave.set_ylim(-1, 1)
        ax_wave.set_xlabel("Time (s)")

        # Draw selection overlay
        if selection[0] is not None and selection[1] is not None:
            for ax in [ax_spec, ax_wave]:
                ax.axvspan(selection[0], selection[1], alpha=0.25, color="cyan")

    def update_display():
        # When paused, never redraw — selection stays stable
        if paused[0]:
            return

        if not stream.connected and stream.total_samples == 0:
            return

        # Only redraw if new data arrived (at least 0.5s worth)
        if stream.total_samples - last_total[0] < SAMPLE_RATE // 2:
            return
        last_total[0] = stream.total_samples

        buf = stream.get_buffer()
        if len(buf) < NFFT:
            return

        render_spectrogram(buf)

        dur = stream.total_samples / SAMPLE_RATE
        conn = "CONNECTED" if stream.connected else "DISCONNECTED"
        update_status(f"{conn} | {dur:.1f}s received | buf={len(buf)/SAMPLE_RATE:.1f}s")
        fig.canvas.draw_idle()

    span = [None]

    def attach_span_selector():
        """Attach SpanSelector to the spectrogram axis."""
        span[0] = SpanSelector(ax_spec, on_select, "horizontal",
                                useblit=True, props=dict(alpha=0.3, facecolor="cyan"))

    def on_pause_with_render(event):
        on_pause(event)
        if paused[0]:
            buf = paused_buf[0]
            if buf is not None and len(buf) >= NFFT:
                render_spectrogram(buf)
                attach_span_selector()
                fig.canvas.draw_idle()

    btn_pause.on_clicked(on_pause_with_render)

    # Timer for updates (only runs when not paused)
    timer = fig.canvas.new_timer(interval=500)
    timer.add_callback(update_display)
    timer.start()

    plt.show()
    stream.disconnect()


def main():
    parser = argparse.ArgumentParser(description="Record wake word samples from glasses mic")
    parser.add_argument("--phone", type=str, default="192.168.0.103",
                        help="Phone IP address (default: 192.168.0.103)")
    parser.add_argument("--negative", action="store_true",
                        help="Record negative (non-wake-word) samples")
    args = parser.parse_args()

    if args.negative:
        run_gui(args.phone, NEGATIVE_DIR, "NEGATIVE")
    else:
        run_gui(args.phone, POSITIVE_DIR, "POSITIVE")


if __name__ == "__main__":
    main()
