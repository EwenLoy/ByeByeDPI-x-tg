#!/usr/bin/env bash
# Удаление пользовательской установки. Настройки (~/.config/byebyedpi-tg)
# и списки (~/.local/share/byebyedpi-tg/*.json) сохраняются; флаг --purge удалит и их.
set -euo pipefail

APP_ID="com.github.ewenloy.ByeByeDPI"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
CONFIG_HOME="${XDG_CONFIG_HOME:-$HOME/.config}"

pkill -f "$DATA_HOME/byebyedpi-tg/app/app.py" 2>/dev/null || true

rm -rf "$DATA_HOME/byebyedpi-tg/app"
rm -f "$HOME/.local/bin/byebyedpi-tg" \
      "$DATA_HOME/applications/$APP_ID.desktop" \
      "$DATA_HOME/icons/hicolor/scalable/apps/$APP_ID.svg" \
      "$CONFIG_HOME/autostart/$APP_ID.desktop"

if [[ "${1:-}" == "--purge" ]]; then
  rm -rf "$DATA_HOME/byebyedpi-tg" "$CONFIG_HOME/byebyedpi-tg" "${XDG_CACHE_HOME:-$HOME/.cache}/byebyedpi-tg"
  echo "Удалено вместе с настройками."
else
  echo "Удалено. Настройки сохранены (удалить: $0 --purge)."
fi
