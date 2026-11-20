"""
PutokUlo helmet proximity warning (runs on the Raspberry Pi).

Reads 4 HC-SR04 ultrasonic sensors one at a time and beeps an active buzzer
faster as the nearest object gets closer, like a car's parking sensor.

Run:               python3 proximity.py
Test buzzer only:  python3 proximity.py --test-buzzer
"""
import argparse
import json
import os
import signal
import statistics
import sys
import threading
import time
from collections import deque

import lgpio

from common import PROXIMITY_FILE

# BCM pin numbers: name -> (trig, echo). Every echo pin MUST go through a voltage divider.
SENSORS = {
    "front": (23, 24),
    "back": (17, 27),
    "left": (5, 6),
    "right": (20, 21),
}
BUZZER_PIN = 18

IGNORE_BELOW_CM = 25   # closer readings are probably the rider's own shoulders
SOLID_BELOW_CM = 50    # continuous tone
WARN_BELOW_CM = 200    # start beeping
MAX_RANGE_CM = 400     # HC-SR04 limit

PING_PERIOD_S = 0.06   # time per sensor, lets echoes die out before the next one pings
ECHO_TIMEOUT_S = 0.05
SPEED_OF_SOUND_CM_S = 34300

CHIP = 0  # gpiochip0 on a Pi 4


class Ultrasonic:
    """One HC-SR04. Echo edges are timestamped by lgpio, so Python jitter doesn't skew distances."""

    def __init__(self, handle, name, trig, echo):
        self.handle = handle
        self.name = name
        self.trig = trig
        self._rise = None
        self._echo_s = None
        self._done = threading.Event()
        # Last 3 readings; an object must show up in 2 of them to count, which filters stray echoes.
        self._history = deque([None] * 3, maxlen=3)

        lgpio.gpio_claim_output(handle, trig, 0)
        lgpio.gpio_claim_alert(handle, echo, lgpio.BOTH_EDGES)
        self._callback = lgpio.callback(handle, echo, lgpio.BOTH_EDGES, self._on_edge)

    def _on_edge(self, chip, gpio, level, tick_ns):
        if level == 1:
            self._rise = tick_ns
        elif level == 0 and self._rise is not None:
            self._echo_s = (tick_ns - self._rise) / 1e9
            self._done.set()

    def ping(self):
        self._rise = None
        self._echo_s = None
        self._done.clear()
        lgpio.gpio_trigger(self.handle, self.trig, 10, 1)  # 10 µs trigger pulse

        cm = None
        if self._done.wait(ECHO_TIMEOUT_S):
            measured = self._echo_s * SPEED_OF_SOUND_CM_S / 2
            if IGNORE_BELOW_CM <= measured <= MAX_RANGE_CM:
                cm = measured
        self._history.append(cm)

    @property
    def distance(self):
        """Smoothed distance in cm, or None if nothing is in range."""
        cm = statistics.median(d if d is not None else float("inf") for d in self._history)
        return None if cm == float("inf") else cm

    def close(self):
        self._callback.cancel()


class Beeper:
    """Drives an active buzzer from a background thread. Set .interval to change the pattern."""

    BEEP_S = 0.05

    def __init__(self, handle, pin):
        self.handle = handle
        self.pin = pin
        self.interval = None  # None = silent, 0 = continuous, else seconds of silence between beeps
        lgpio.gpio_claim_output(handle, pin, 0)
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self):
        while not self._stop.is_set():
            interval = self.interval
            if interval is None:
                lgpio.gpio_write(self.handle, self.pin, 0)
                self._stop.wait(0.05)
            elif interval == 0:
                lgpio.gpio_write(self.handle, self.pin, 1)
                self._stop.wait(0.05)
            else:
                lgpio.gpio_write(self.handle, self.pin, 1)
                self._stop.wait(self.BEEP_S)
                lgpio.gpio_write(self.handle, self.pin, 0)
                self._stop.wait(interval)
        lgpio.gpio_write(self.handle, self.pin, 0)

    def close(self):
        self._stop.set()
        self._thread.join()


def beep_interval(cm):
    """Map the nearest distance to a beep pattern: farther = slower, very close = continuous."""
    if cm is None or cm >= WARN_BELOW_CM:
        return None
    if cm < SOLID_BELOW_CM:
        return 0
    fraction = (cm - SOLID_BELOW_CM) / (WARN_BELOW_CM - SOLID_BELOW_CM)
    return 0.08 + fraction * (0.6 - 0.08)


def publish(readings):
    """Share the readings with camera_server.py, which passes them to the app."""
    data = {
        "time": time.time(),
        "readings": {name: None if cm is None else round(cm, 1) for name, cm in readings.items()},
        "warn_below_cm": WARN_BELOW_CM,
        "solid_below_cm": SOLID_BELOW_CM,
    }
    tmp = PROXIMITY_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(data))
    os.replace(tmp, PROXIMITY_FILE)  # atomic, so the server never reads a half-written file


def format_readings(readings):
    return " | ".join(
        f"{name:>5} {cm:4.0f}cm" if cm is not None else f"{name:>5}   ---  "
        for name, cm in readings.items()
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--test-buzzer", action="store_true", help="play slow, fast, then continuous beeps and exit")
    args = parser.parse_args()

    # systemctl stop sends SIGTERM; turn it into a normal exit so the cleanup below
    # runs and the buzzer is switched off instead of being left on
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))

    handle = lgpio.gpiochip_open(CHIP)
    beeper = Beeper(handle, BUZZER_PIN)
    sensors = []
    try:
        if args.test_buzzer:
            for interval in (0.6, 0.3, 0.1, 0):
                print(f"interval {interval}s")
                beeper.interval = interval
                time.sleep(1.5)
            return

        sensors = [Ultrasonic(handle, name, trig, echo) for name, (trig, echo) in SENSORS.items()]
        # Live readings only in a terminal; as a background service they would flood the system log
        live = sys.stdout.isatty()
        print("Reading sensors..." + (" Ctrl+C to stop." if live else ""), flush=True)
        while True:
            for sensor in sensors:
                started = time.monotonic()
                sensor.ping()
                time.sleep(max(0, PING_PERIOD_S - (time.monotonic() - started)))

            readings = {s.name: s.distance for s in sensors}
            nearest = min((cm for cm in readings.values() if cm is not None), default=None)
            beeper.interval = beep_interval(nearest)
            publish(readings)
            if live:
                print("\r" + format_readings(readings), end="", flush=True)
    except KeyboardInterrupt:
        print()
    finally:
        beeper.close()
        for sensor in sensors:
            sensor.close()
        lgpio.gpiochip_close(handle)


if __name__ == "__main__":
    main()
