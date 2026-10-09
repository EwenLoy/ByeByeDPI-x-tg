#!/usr/bin/env bash
# Сборка ByeByeDPI-x-tg-<версия>-x86_64.AppImage
#
# Внутрь кладутся: Python-код интерфейса, ciadpi, libtgwsproxy.so, списки и иконки.
# GTK4, libadwaita и python3-gobject берутся из системы (они есть в Fedora
# Workstation и Ubuntu 24.04+ из коробки), поэтому образ весит ~2 МБ.
#
#   ./linux/build-appimage.sh            — соберёт движки (если нужно) и AppImage
#   APPIMAGETOOL=/путь/к/appimagetool ./linux/build-appimage.sh
set -euo pipefail

LINUX_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$LINUX_DIR")"
APP_ID="com.github.ewenloy.ByeByeDPI"
VERSION="$(sed -n 's/^APP_VERSION = "\(.*\)"/\1/p' "$LINUX_DIR/storage.py")"
ARCH="$(uname -m)"
BUILD="$LINUX_DIR/.appimage-build"
APPDIR="$BUILD/AppDir"
OUT="$LINUX_DIR/out/ByeByeDPI-x-tg-${VERSION}-${ARCH}.AppImage"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

[[ "$ARCH" == "x86_64" || "$ARCH" == "aarch64" ]] || fail "неподдерживаемая архитектура $ARCH"

if [[ ! -x "$LINUX_DIR/dist/ciadpi" || ! -f "$LINUX_DIR/dist/libtgwsproxy.so" ]]; then
  say "Движки не собраны — запускаю build.sh"
  "$LINUX_DIR/build.sh"
fi

# --- appimagetool ---------------------------------------------------------------
TOOL="${APPIMAGETOOL:-}"
if [[ -z "$TOOL" ]]; then
  TOOL="$BUILD/appimagetool-$ARCH.AppImage"
  if [[ ! -x "$TOOL" ]]; then
    say "Скачиваю appimagetool"
    mkdir -p "$BUILD"
    curl -fL --retry 3 -o "$TOOL" \
      "https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-$ARCH.AppImage"
    chmod +x "$TOOL"
  fi
fi

# --- AppDir ----------------------------------------------------------------------
say "Сборка AppDir"
rm -rf "$APPDIR"
SHARE="$APPDIR/usr/share/byebyedpi-tg"
mkdir -p "$SHARE" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/scalable/apps"
cp "$LINUX_DIR"/*.py "$LINUX_DIR"/*.css "$SHARE/"
cp -r "$LINUX_DIR/icons" "$LINUX_DIR/dist" "$SHARE/"
rm -rf "$SHARE/__pycache__"

sed "s|^Exec=.*|Exec=byebyedpi-tg|" "$LINUX_DIR/$APP_ID.desktop" > "$APPDIR/$APP_ID.desktop"
cp "$APPDIR/$APP_ID.desktop" "$APPDIR/usr/share/applications/"
cp "$LINUX_DIR/icons/$APP_ID.svg" "$APPDIR/$APP_ID.svg"
cp "$LINUX_DIR/icons/$APP_ID.svg" "$APPDIR/usr/share/icons/hicolor/scalable/apps/"
ln -sf "$APP_ID.svg" "$APPDIR/.DirIcon"

cat > "$APPDIR/AppRun" <<'EOF'
#!/bin/sh
# Запуск ByeByeDPI x tg из AppImage на системном Python + GTK4 + libadwaita
HERE="$(dirname "$(readlink -f "$0")")"
APP="$HERE/usr/share/byebyedpi-tg/app.py"

missing() {
  msg="Для запуска ByeByeDPI x tg нужны GTK4, libadwaita 1.4+ и python3-gobject.

Fedora:   sudo dnf install python3-gobject gtk4 libadwaita curl
Ubuntu:   sudo apt install python3-gi gir1.2-gtk-4.0 gir1.2-adw-1 curl
Arch:     sudo pacman -S python-gobject gtk4 libadwaita curl"
  echo "$msg" >&2
  if command -v zenity >/dev/null 2>&1; then
    zenity --error --title="ByeByeDPI x tg" --text="$msg" 2>/dev/null
  elif command -v kdialog >/dev/null 2>&1; then
    kdialog --title "ByeByeDPI x tg" --error "$msg" 2>/dev/null
  elif command -v notify-send >/dev/null 2>&1; then
    notify-send "ByeByeDPI x tg" "Нужны GTK4, libadwaita и python3-gobject — см. терминал"
  fi
  exit 1
}

command -v python3 >/dev/null 2>&1 || missing
# Python из AppImage-окружений (conda, pyenv) часто без gi — берём системный
PY=python3
[ -x /usr/bin/python3 ] && PY=/usr/bin/python3

"$PY" - <<'PYCHECK' >/dev/null 2>&1 || missing
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw
assert (Adw.get_major_version(), Adw.get_minor_version()) >= (1, 4)
PYCHECK

# Не даём Python писать __pycache__ в read-only образ
export PYTHONDONTWRITEBYTECODE=1
exec "$PY" "$APP" "$@"
EOF
chmod +x "$APPDIR/AppRun"

# --- Упаковка ---------------------------------------------------------------------
say "Упаковка $OUT"
mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
# --appimage-extract-and-run: appimagetool работает и без FUSE (в контейнерах/CI)
ARCH="$ARCH" "$TOOL" --appimage-extract-and-run --no-appstream "$APPDIR" "$OUT"

say "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
echo "Запуск: chmod +x \"$OUT\" && \"$OUT\""
