# PutokUlo – Pi side

Two scripts run side by side on the helmet's Pi:

- `proximity.py` – ultrasonic sensors + buzzer (works on its own, even with no phone)
- `camera_server.py` – camera stream for the app; also passes the latest proximity readings to the app

## Getting the files onto the Pi

The code is on GitHub, so the Pi can download it from anywhere with internet (SSH or Raspberry Pi Connect's
remote shell both work). Once, on the Pi:

```bash
git clone <the repo's URL> ~/PutokUlo
```

To get your latest changes later (after you've pushed them from the laptop):

```bash
cd ~/PutokUlo && git pull && pi/autostart.sh restart
```

The scripts live in `~/PutokUlo/pi`. If you set up autostart earlier from a different folder (like `~/pi`), run
`./autostart.sh install` again from `~/PutokUlo/pi` so the services point to the new place.

Without internet, from the laptop on the same Wi-Fi/hotspot:
`rsync -av --exclude venv ~/PutokUlo/pi/ putokulo@putokulo.local:~/PutokUlo/pi/`

## Start automatically on boot

On the Pi, once both scripts work when you run them by hand:

```bash
cd ~/PutokUlo/pi
./autostart.sh install                          # or: CAMERA_ARGS="--flip" ./autostart.sh install
```

From then on both scripts start by themselves a few seconds after the Pi powers on (no login needed) and restart
if they crash. If the camera isn't connected, the camera script keeps retrying every 3 s and the sensors still work.

| Command | What it does |
|---------|--------------|
| `./autostart.sh status` | Are they running? |
| `./autostart.sh logs` | Watch their output live (Ctrl+C to quit) |
| `./autostart.sh stop` | Stop both, e.g. to run `python3 proximity.py` by hand (otherwise you get "GPIO busy" / camera busy) |
| `./autostart.sh start` / `restart` | Start them again / after copying new files |
| `./autostart.sh remove` | Turn autostart off completely |

To change the camera options later, run `install` again with the new `CAMERA_ARGS`. If there's a `venv` folder next to
the scripts (needed for `--detect`), the services use its Python automatically.

For the Pi to join the phone's hotspot by itself at boot, connect to it once with `sudo nmtui`; it's remembered.

## Camera stream (`camera_server.py`)

Streams the camera as MJPEG on port 8000 and broadcasts "I'm here" on UDP port 50000 every 2 s,
so the app finds the helmet by itself when the Pi joins the phone's hotspot.

### Setup

```bash
sudo apt install python3-opencv     # picamera2 is already on Raspberry Pi OS Bookworm
```

### Run

```bash
python3 camera_server.py            # plain video, fastest
python3 camera_server.py --flip     # if the camera is mounted upside down
python3 camera_server.py --detect   # draw YOLO boxes (needs: pip install ultralytics)
```

It prints the address, e.g. `Streaming on http://192.168.43.20:8000/`. Open that in any browser on the
same network to check the video and readings without the app.

**YOLO on a Pi 4 is slow.** Export the model to NCNN once for a big speed-up, then use it:

```bash
yolo export model=yolo11n.pt format=ncnn
python3 camera_server.py --detect --model yolo11n_ncnn_model
```

### Test without a Pi

On any computer with OpenCV, stream a video file in place of the camera:

```bash
python3 camera_server.py --video ../dashcam.mp4 --detect --model ../yolo11n.pt
```

### Connecting the app

1. Turn on the phone's hotspot and connect the Pi to it (once: `sudo nmtui` → Activate a connection).
2. Start `camera_server.py` (and `proximity.py`).
3. Open the app's Camera tab. It connects automatically. If it doesn't, type the address the server
   printed (e.g. `192.168.43.20:8000`) into Settings → Helmet address.

## Proximity warning (`proximity.py`)

4 × HC-SR04 ultrasonic sensors + 1 active buzzer. Beeps faster as the nearest object gets closer.

**New to wiring? Follow [wiring/WIRING_GUIDE.txt](wiring/WIRING_GUIDE.txt)**, a step-by-step beginner guide with pictures (`wiring/1-pi-pins.png`, `wiring/2-breadboard.png`).

### Wiring (BCM pin numbers)

| Part   | Trig    | Echo (through divider) |
|--------|---------|------------------------|
| Front  | GPIO 23 | GPIO 24                |
| Back   | GPIO 17 | GPIO 27                |
| Left   | GPIO 5  | GPIO 6                 |
| Right  | GPIO 20 | GPIO 21                |
| Buzzer | GPIO 18 | –                      |

GPIO 2/3 are left free for an MPU6050 later.

**HC-SR04:** VCC → 5V, GND → GND, Trig → GPIO directly.

**Echo outputs 5V. Never connect it straight to the Pi.** Use a voltage divider on each one:

```
Echo ──[1kΩ]──┬── GPIO
              │
            [2kΩ]
              │
             GND
```

**Buzzer** (through a transistor so the GPIO pin isn't overloaded):

```
GPIO 18 ──[1kΩ]── B (S8050 / 2N2222)
                  E ── GND
                  C ── buzzer black (–)
5V ─────────────────── buzzer red (+)
```

### Check the buzzer type first

Connect red to 3.3V and black to GND for a second:
- **Steady beep** → active buzzer. This script works as-is.
- **Only a click** → passive buzzer. It needs a tone signal instead (tell Claude and the script will be switched to PWM).

### Setup and run

`lgpio` comes with Raspberry Pi OS Bookworm. If it's missing: `sudo apt install python3-lgpio`.
If you use a virtual environment, create it with `python3 -m venv --system-site-packages venv` so it can see `lgpio`.

```bash
python3 proximity.py --test-buzzer   # slow, fast, then continuous beeps
python3 proximity.py                 # live readings + beeping, Ctrl+C to stop
```

While it runs, it also writes its latest readings to `/dev/shm/putokulo_proximity.json` (memory, not the
SD card). `camera_server.py` reads that file and sends the readings to the app.

### Tuning (top of `proximity.py`)

- `IGNORE_BELOW_CM` – raise it if the side/back sensors keep detecting your shoulders.
- `WARN_BELOW_CM` / `SOLID_BELOW_CM` – when beeping starts and when it becomes a continuous tone.
- `SENSORS` / `BUZZER_PIN` – change if you wire to different pins.
