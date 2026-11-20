"""Settings shared by the PutokUlo Pi scripts."""
import tempfile
from pathlib import Path

# proximity.py writes its latest readings here and camera_server.py serves them to the app.
# /dev/shm is in memory on the Pi, so this doesn't wear out the SD card.
_SHARED_DIR = Path("/dev/shm") if Path("/dev/shm").is_dir() else Path(tempfile.gettempdir())
PROXIMITY_FILE = _SHARED_DIR / "putokulo_proximity.json"

# The camera server broadcasts "PUTOKULO <http port>" on this UDP port so the app can find the Pi
DISCOVERY_PORT = 50000
