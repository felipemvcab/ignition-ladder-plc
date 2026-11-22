#!/usr/bin/env zsh
set -e
setopt NULL_GLOB

# 1. Stop running Gateway cleanly so it does not overwrite modules.json on shutdown
if [ -f /usr/local/ignition/Ignition-Gateway.pid ]; then
  PID=$(cat /usr/local/ignition/Ignition-Gateway.pid)
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    for i in {1..30}; do
      if ! kill -0 "$PID" 2>/dev/null; then
        break
      fi
      sleep 0.5
    done
  fi
fi

# 2. Copy the signed .modl and remove any stale quarantined copy
rm -f /usr/local/ignition/user-lib/modules/quarantine/LadderLogic* 2>/dev/null || true
cp $(cd "$(dirname "$0")" && pwd)/build/dist/LadderLogic-SoftPLC-1.0.0.modl /usr/local/ignition/user-lib/modules/LadderLogic-SoftPLC-1.0.0.modl

# 3. Register permanent Felipe Integrations certFingerprint in modules.json while Gateway is stopped
python3 -c '
import json
with open("/usr/local/ignition/data/modules.json") as f:
    data = json.load(f)
data["com.felipe.ladderplc"] = {
    "filename": "/usr/local/ignition/user-lib/modules/LadderLogic-SoftPLC-1.0.0.modl",
    "onStartup": "enabled",
    "certFingerprint": "1a19037d66fbf82bbc2403d960d3e039a89bfb5e"
}
with open("/usr/local/ignition/data/modules.json", "w") as f:
    json.dump(data, f, indent=2)
'

# 4. Start Gateway with ACCEPT_MODULE_CERTS set so certificate is always trusted
export ACCEPT_MODULE_CERTS="com.felipe.ladderplc"
cd /usr/local/ignition
./ignition-gateway ./data/ignition.conf \
  wrapper.syslog.ident=Ignition-Gateway \
  wrapper.pidfile=/usr/local/ignition/Ignition-Gateway.pid \
  wrapper.daemonize=TRUE
