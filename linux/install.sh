#!/usr/bin/env bash
# Установка для текущего пользователя (без sudo):
#   ~/.local/share/byebyedpi-tg/app   — программа
#   ~/.local/bin/byebyedpi-tg         — команда запуска
#   ~/.local/share/applications/…     — ярлык в меню GNOME/KDE
set -euo pipefail

LINUX_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ID="com.github.ewenloy.ByeByeDPI"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
PREFIX="$DATA_HOME/byebyedpi-tg/app"
BIN_DIR="$HOME/.local/bin"
LAUNCHER="$BIN_DIR/byebyedpi-tg"

if [[ ! -x "$LINUX_DIR/dist/ciadpi" || ! -f "$LINUX_DIR/dist/libtgwsproxy.so" ]]; then
  echo "Движки ещё не собраны — запускаю build.sh"
  "$LINUX_DIR/build.sh"
fi

echo "Установка в $PREFIX"
rm -rf "$PREFIX"
mkdir -p "$PREFIX" "$BIN_DIR" "$DATA_HOME/applications" "$DATA_HOME/icons/hicolor/scalable/apps"
cp "$LINUX_DIR"/*.py "$LINUX_DIR"/*.css "$PREFIX/"
cp -r "$LINUX_DIR/icons" "$LINUX_DIR/dist" "$PREFIX/"

cat > "$LAUNCHER" <<EOF
#!/bin/sh
exec python3 "$PREFIX/app.py" "\$@"
EOF
chmod +x "$LAUNCHER"

install -m 0644 "$LINUX_DIR/icons/$APP_ID.svg" "$DATA_HOME/icons/hicolor/scalable/apps/$APP_ID.svg"
sed "s|^Exec=.*|Exec=$LAUNCHER|" "$LINUX_DIR/$APP_ID.desktop" > "$DATA_HOME/applications/$APP_ID.desktop"

# Если включён автозапуск — обновим путь в нём
AUTOSTART="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/$APP_ID.desktop"
if [[ -f "$AUTOSTART" ]]; then
  sed -i "s|^Exec=.*|Exec=python3 $PREFIX/app.py --autostart|" "$AUTOSTART"
fi

command -v update-desktop-database >/dev/null && update-desktop-database -q "$DATA_HOME/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$DATA_HOME/icons/hicolor" 2>/dev/null || true

echo "Готово. Ищите «ByeByeDPI x tg» в меню приложений или запустите: byebyedpi-tg"
case ":$PATH:" in
  *":$BIN_DIR:"*) ;;
  *) echo "Примечание: $BIN_DIR не в PATH — запускайте через меню или по полному пути $LAUNCHER" ;;
esac
