#!/usr/bin/env bash
# Start proximity.py and camera_server.py automatically every time the Pi boots,
# and restart them if they ever crash. Run it on the Pi, WITHOUT sudo (it asks when needed).
#
#   ./autostart.sh install                          set up autostart and start both now
#   CAMERA_ARGS="--flip" ./autostart.sh install     same, with options for camera_server.py
#   ./autostart.sh status                           are they running?
#   ./autostart.sh logs                             watch their output live (Ctrl+C to quit)
#   ./autostart.sh stop | start | restart           e.g. stop them to run a script by hand
#   ./autostart.sh remove                           stop them and turn autostart off
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVICES=(putokulo-proximity putokulo-camera)

# Use the project's virtual environment if there is one (needed for --detect / ultralytics)
if [[ -x "$DIR/venv/bin/python" ]]; then
    PYTHON="${PYTHON:-$DIR/venv/bin/python}"
else
    PYTHON="${PYTHON:-/usr/bin/python3}"
fi

write_unit() {
    local name=$1 description=$2 command=$3
    sudo tee "/etc/systemd/system/$name.service" >/dev/null <<EOF
[Unit]
Description=$description
After=network.target
StartLimitIntervalSec=0

[Service]
User=$(id -un)
WorkingDirectory=$DIR
ExecStart=$command
Environment=PYTHONUNBUFFERED=1
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
}

install() {
    if [[ $EUID -eq 0 ]]; then
        echo "Run this without sudo (as your normal user), so the scripts run as you." >&2
        exit 1
    fi
    for group in gpio video; do
        if ! id -nG | grep -qw "$group"; then
            echo "Warning: user $(id -un) is not in the '$group' group. Fix: sudo usermod -aG $group $(id -un)" >&2
        fi
    done

    write_unit putokulo-proximity "PutokUlo proximity sensors and buzzer" "$PYTHON $DIR/proximity.py"
    write_unit putokulo-camera "PutokUlo helmet camera stream" "$PYTHON $DIR/camera_server.py ${CAMERA_ARGS:-}"
    sudo systemctl daemon-reload
    sudo systemctl enable "${SERVICES[@]}"
    sudo systemctl restart "${SERVICES[@]}"

    echo "Autostart is on. Both scripts are starting now and will start on every boot."
    sleep 3
    status
}

status() {
    systemctl --no-pager --lines=3 status "${SERVICES[@]}" || true
}

remove() {
    sudo systemctl disable --now "${SERVICES[@]}" 2>/dev/null || true
    for name in "${SERVICES[@]}"; do
        sudo rm -f "/etc/systemd/system/$name.service"
    done
    sudo systemctl daemon-reload
    echo "Autostart removed. The scripts are stopped and won't start on boot."
}

case "${1:-}" in
    install) install ;;
    status) status ;;
    logs) journalctl -f -u putokulo-proximity -u putokulo-camera ;;
    start | stop | restart) sudo systemctl "$1" "${SERVICES[@]}" && echo "Done: $1" ;;
    remove) remove ;;
    *)
        sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'
        exit 1
        ;;
esac
