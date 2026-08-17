import os
import sys
import webbrowser
import http.server
import socketserver
import threading
import numpy as np
# pyaudio will be imported lazily inside AudioEngine to suppress ALSA/JACK noise on import

PORT = 8000
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
os.chdir(BASE_DIR)

CHUNK = 1024
CHANNELS = 1
RATE = 44100

class AudioEngine:
    def __init__(self):
        # Initialize audio objects lazily, suppressing noisy native backend logs
        self.pyaudio = None
        self.p = None
        self.FORMAT = None
        self.stream = None
        self.is_running = False
        self.volume_level = 0.0

        _stderr = sys.stderr
        try:
            # temporarily silence native audio library stderr
            sys.stderr = open(os.devnull, 'w')
            # prefer a dummy SDL audio driver when available
            os.environ.setdefault('SDL_AUDIODRIVER', 'dummy')
            import pyaudio as _pyaudio
            self.pyaudio = _pyaudio
            self.FORMAT = _pyaudio.paInt16
            try:
                self.p = _pyaudio.PyAudio()
            except Exception as e:
                # PyAudio may still fail to initialize (no device) — handled gracefully
                _stderr.write(f"[!] PyAudio initialization failed: {e}\n")
                self.p = None
        except Exception as e:
            _stderr.write(f"[!] PyAudio import failed: {e}\n")
            self.pyaudio = None
        finally:
            try:
                sys.stderr.close()
            except Exception:
                pass
            sys.stderr = _stderr

    def start_stream(self):
        if not self.p:
            print('[!] Audio backend unavailable; skipping audio stream.')
            return
        self.is_running = True
        try:
            self.stream = self.p.open(
                format=self.FORMAT,
                channels=CHANNELS,
                rate=RATE,
                input=True,
                frames_per_buffer=CHUNK
            )
            threading.Thread(target=self._audio_loop, daemon=True).start()
            print('[+] Audio processing stream initialized.')
        except Exception as e:
            print(f"[!] Audio stream setup failed: {e}")
            self.is_running = False

    def _audio_loop(self):
        while self.is_running and self.stream:
            try:
                data = self.stream.read(CHUNK, exception_on_overflow=False)
                audio_data = np.frombuffer(data, dtype=np.int16)
                self.volume_level = np.sqrt(np.mean(audio_data**2))
            except Exception:
                pass

    def stop(self):
        self.is_running = False
        if self.stream:
            try:
                self.stream.stop_stream()
                self.stream.close()
            except Exception:
                pass
        if self.p:
            try:
                self.p.terminate()
            except Exception:
                pass

class ReusableTCPServer(socketserver.TCPServer):
    allow_reuse_address = True

class CustomHTTPRequestHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=BASE_DIR, **kwargs)

    # Disable browser caching to ensure instant JS/HTML reloads
    def end_headers(self):
        self.send_header('Cache-Control', 'no-store, no-cache, must-revalidate')
        super().end_headers()

def main():
    print("======================================================================")
    print("   NEONDROMEDA // CHROMA Z ENGINE NATIVE LAUNCHER")
    print("======================================================================")
    
    audio = AudioEngine()
    url = f"http://localhost:{PORT}"

    try:
        with ReusableTCPServer(("", PORT), CustomHTTPRequestHandler) as httpd:
            print(f"[+] Root directory bound to: {BASE_DIR}")
            print(f"[+] Serving Chroma Z Engine at {url}")
            print("[+] Opening web environment in browser...")
            
            webbrowser.open(url)

            print("[!] Native Python server running. Press Ctrl+C to stop.\n")
            httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[-] Shutting down server...")
        audio.stop()
        sys.exit(0)
    except OSError:
        print(f"\n[!] Port {PORT} is busy. Run `fuser -k {PORT}/tcp` to free it.")

if __name__ == "__main__":
    main()