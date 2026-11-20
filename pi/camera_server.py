"""
PutokUlo helmet camera server (runs on the Raspberry Pi).

Streams the camera as MJPEG for the Android app, shares the latest readings from
proximity.py, and announces itself on the network so the app can find the Pi on a
phone hotspot without typing an IP address.

On the Pi:                python3 camera_server.py
With YOLO detection:      python3 camera_server.py --detect
Test without a Pi:        python3 camera_server.py --video ../dashcam.mp4

Endpoints:
  /             test page you can open in a browser
  /stream.mjpg  the video
  /status       JSON with the proximity readings
"""
import argparse
import json
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import cv2

from common import DISCOVERY_PORT, PROXIMITY_FILE

PROXIMITY_MAX_AGE_S = 2  # older readings mean proximity.py isn't running

TEST_PAGE = b"""<!doctype html>
<html><head><title>PutokUlo helmet</title><meta name="viewport" content="width=device-width"></head>
<body style="margin:0;background:#111;color:#eee;font-family:sans-serif">
<img src="/stream.mjpg" style="width:100%">
<pre id="status"></pre>
<script>
setInterval(async () => {
  const r = await fetch('/status');
  document.getElementById('status').textContent = JSON.stringify(await r.json(), null, 2);
}, 500);
</script>
</body></html>"""


class PiCamera:
    def __init__(self, width, height, flip):
        from libcamera import Transform
        from picamera2 import Picamera2

        self.camera = Picamera2()
        config = self.camera.create_video_configuration(
            main={"size": (width, height), "format": "RGB888"},
            transform=Transform(hflip=flip, vflip=flip),
        )
        self.camera.configure(config)
        self.camera.start()

    def read(self):
        return self.camera.capture_array()  # "RGB888" is BGR in memory, which is what OpenCV expects


class VideoFile:
    """Plays a video file in a loop at its normal speed, for testing without a Pi."""

    def __init__(self, path, width):
        self.capture = cv2.VideoCapture(path)
        if not self.capture.isOpened():
            raise SystemExit(f"Could not open video file {path}")
        self.frame_time = 1 / (self.capture.get(cv2.CAP_PROP_FPS) or 30)
        self.width = width
        self.next_frame_at = time.monotonic()

    def read(self):
        ok, frame = self.capture.read()
        if not ok:
            self.capture.set(cv2.CAP_PROP_POS_FRAMES, 0)
            ok, frame = self.capture.read()

        self.next_frame_at += self.frame_time
        delay = self.next_frame_at - time.monotonic()
        if delay > 0:
            time.sleep(delay)
        else:
            self.next_frame_at = time.monotonic()  # running behind, e.g. while detecting

        height, width = frame.shape[:2]
        if width > self.width:
            frame = cv2.resize(frame, (self.width, int(height * self.width / width)))
        return frame


class Detector:
    CLASSES = [0, 1, 2, 3, 5, 7]  # person, bicycle, car, motorcycle, bus, truck

    def __init__(self, model_path, image_size):
        from ultralytics import YOLO

        self.model = YOLO(model_path)
        self.image_size = image_size

    def annotate(self, frame):
        results = self.model(frame, imgsz=self.image_size, classes=self.CLASSES, verbose=False)
        return results[0].plot()


class LatestFrame:
    """Holds the newest JPEG and wakes up every client waiting for it."""

    def __init__(self):
        self.jpeg = None
        self.fps = 0.0
        self.condition = threading.Condition()

    def publish(self, jpeg):
        with self.condition:
            self.jpeg = jpeg
            self.condition.notify_all()

    def wait(self, timeout=5):
        with self.condition:
            self.condition.wait(timeout)
            return self.jpeg


def capture_loop(source, detector, latest, quality):
    last = time.monotonic()
    while True:
        frame = source.read()
        if detector:
            frame = detector.annotate(frame)
        ok, jpeg = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, quality])
        if ok:
            latest.publish(jpeg.tobytes())

        now = time.monotonic()
        latest.fps = 0.9 * latest.fps + 0.1 / max(now - last, 1e-3)
        last = now


def read_proximity():
    try:
        data = json.loads(PROXIMITY_FILE.read_text())
    except (OSError, ValueError):
        return None
    if time.time() - data.get("time", 0) > PROXIMITY_MAX_AGE_S:
        return None
    return data


def announce(http_port):
    """Broadcast our HTTP port every 2 s so the app can find us on the hotspot."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    message = f"PUTOKULO {http_port}".encode()
    while True:
        try:
            sock.sendto(message, ("255.255.255.255", DISCOVERY_PORT))
        except OSError:
            pass  # not on a network yet
        time.sleep(2)


def make_handler(latest):
    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path == "/":
                self.send_bytes(TEST_PAGE, "text/html")
            elif self.path == "/status":
                status = {"fps": round(latest.fps, 1), "proximity": read_proximity()}
                self.send_bytes(json.dumps(status).encode(), "application/json")
            elif self.path == "/stream.mjpg":
                self.stream()
            else:
                self.send_error(404)

        def send_bytes(self, body, content_type):
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(body)

        def stream(self):
            self.send_response(200)
            self.send_header("Cache-Control", "no-cache, private")
            self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=FRAME")
            self.end_headers()
            try:
                while True:
                    jpeg = latest.wait()
                    if jpeg is None:
                        continue
                    self.wfile.write(b"--FRAME\r\n")
                    self.send_header("Content-Type", "image/jpeg")
                    self.send_header("Content-Length", str(len(jpeg)))
                    self.end_headers()
                    self.wfile.write(jpeg)
                    self.wfile.write(b"\r\n")
            except (BrokenPipeError, ConnectionResetError):
                pass  # the app disconnected

        def log_message(self, *args):
            pass  # keep the console quiet

    return Handler


def local_ip():
    """The address other devices on the Wi-Fi/hotspot can reach us at."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        try:
            sock.connect(("8.8.8.8", 80))  # no packets are sent; this just picks the outgoing interface
            return sock.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=8000)
    parser.add_argument("--width", type=int, default=640)
    parser.add_argument("--height", type=int, default=480)
    parser.add_argument("--flip", action="store_true", help="rotate 180° if the camera is mounted upside down")
    parser.add_argument("--quality", type=int, default=70, help="JPEG quality, lower uses less Wi-Fi")
    parser.add_argument("--video", help="stream a video file instead of the Pi camera (for testing)")
    parser.add_argument("--detect", action="store_true", help="draw YOLO boxes around people and vehicles")
    parser.add_argument("--model", default="yolo11n.pt", help="YOLO model; an NCNN export is much faster on a Pi")
    parser.add_argument("--imgsz", type=int, default=320, help="YOLO input size; smaller is faster")
    args = parser.parse_args()

    source = VideoFile(args.video, args.width) if args.video else PiCamera(args.width, args.height, args.flip)
    detector = Detector(args.model, args.imgsz) if args.detect else None
    latest = LatestFrame()

    threading.Thread(target=capture_loop, args=(source, detector, latest, args.quality), daemon=True).start()
    threading.Thread(target=announce, args=(args.port,), daemon=True).start()

    httpd = ThreadingHTTPServer(("", args.port), make_handler(latest))
    httpd.daemon_threads = True
    print(f"Streaming on http://{local_ip()}:{args.port}/  (Ctrl+C to stop)", flush=True)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
