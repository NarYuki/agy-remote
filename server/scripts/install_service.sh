#!/bin/sh
# Runs agy-connector in the background for the current user: it starts at login and restarts if it
# exits. macOS: a LaunchAgent. Linux: a systemd --user unit. No sudo needed.
#   --uninstall   stop it and remove the service
set -eu

LABEL="moe.n4tsu.agyremote.connector"
UNIT="agy-connector.service"
here="$(cd "$(dirname "$0")" && pwd)"
BIN="${AGY_CONNECTOR_BIN:-$(command -v agy-connector || echo "$HOME/.local/bin/agy-connector")}"
[ -x "$BIN" ] || BIN="$here/agy-connector"
action="${1:-install}"

# Must match the connector's own config_dir().
if [ -n "${AGY_CONNECTOR_HOME:-}" ]; then
  CONF="$AGY_CONNECTOR_HOME"
elif [ "$(uname -s)" = Linux ] && [ -n "${XDG_CONFIG_HOME:-}" ] && [ "${XDG_CONFIG_HOME#/}" != "$XDG_CONFIG_HOME" ]; then
  CONF="$XDG_CONFIG_HOME/agy-connector"
else
  CONF="$HOME/.config/agy-connector"
fi
SVC_PATH="$HOME/.gemini/bin:$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"

if [ "$action" != --uninstall ] && [ ! -x "$BIN" ]; then
  echo "agy-connector not found (looked for $BIN); set AGY_CONNECTOR_BIN" >&2
  exit 1
fi

mac() {
  plist="$HOME/Library/LaunchAgents/$LABEL.plist"
  launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
  if [ "$action" = --uninstall ]; then
    rm -f "$plist"; echo "Removed LaunchAgent $LABEL"; return
  fi
  mkdir -p "$HOME/Library/LaunchAgents" "$CONF/logs"
  chmod 700 "$CONF"
  cat > "$plist" << PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$BIN</string>
        <string>run</string>
    </array>
    <key>EnvironmentVariables</key>
    <dict>
        <key>PATH</key>
        <string>$SVC_PATH</string>
        <key>HOME</key>
        <string>$HOME</string>
    </dict>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>$CONF/logs/connector.out.log</string>
    <key>StandardErrorPath</key>
    <string>$CONF/logs/connector.err.log</string>
</dict>
</plist>
PLIST
  launchctl bootstrap "gui/$(id -u)" "$plist"
  echo "Installed and started LaunchAgent: $LABEL"
}

linux() {
  command -v systemctl >/dev/null 2>&1 || { echo "systemd not found; run 'agy-connector up' after you log in instead" >&2; exit 1; }
  units="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
  if [ "$action" = --uninstall ]; then
    systemctl --user disable --now "$UNIT" 2>/dev/null || true
    rm -f "$units/$UNIT"; systemctl --user daemon-reload || true
    echo "Removed $UNIT"; return
  fi
  mkdir -p "$units" "$CONF"
  chmod 700 "$CONF"
  cat > "$units/$UNIT" << EOF
[Unit]
Description=AGY Remote connector

[Service]
ExecStart="$BIN" run
Environment="PATH=$SVC_PATH"
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
EOF
  systemctl --user daemon-reload
  systemctl --user enable --now "$UNIT"
  echo "Installed and started $UNIT (logs: journalctl --user -u agy-connector -f)"
  # Without lingering, user services stop at logout and don't start until you log in.
  if command -v loginctl >/dev/null 2>&1 && [ "$(loginctl show-user "$(id -un)" -p Linger --value 2>/dev/null)" != yes ]; then
    echo "To keep it running while you're logged out (e.g. a headless server): loginctl enable-linger $(id -un)"
  fi
}

case "$(uname -s)" in
  Darwin) mac ;;
  Linux) linux ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
[ "$action" = --uninstall ] || echo "Pair a phone any time with: agy-connector pair"
