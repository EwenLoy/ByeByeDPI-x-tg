#!/usr/bin/env bash
# Сборка движков для Linux (Fedora):
#   linux/dist/ciadpi            — ByeDPI (C)
#   linux/dist/libtgwsproxy.so   — ядро Telegram WS-прокси (Rust, cdylib)
#   linux/dist/assets/           — стратегии и списки сайтов для «Подбора»
#
# Зависимости сборки: sudo dnf install -y git gcc make cargo rust
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/linux/dist"
BYEDPI_URL="https://github.com/hufrea/byedpi.git"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31mОшибка:\033[0m %s\n' "$*" >&2; exit 1; }

for tool in gcc make cargo; do
  command -v "$tool" >/dev/null || fail "не найден $tool. Установите: sudo dnf install -y git gcc make cargo rust"
done

mkdir -p "$OUT/assets"

# --- ByeDPI ------------------------------------------------------------------
say "Исходники ByeDPI"
BYEDPI_SRC=""
if [[ -d "$ROOT/.git" ]] && command -v git >/dev/null; then
  git -C "$ROOT" submodule update --init app/src/main/cpp/byedpi || true
fi
for candidate in "$ROOT/app/src/main/cpp/byedpi" "$ROOT/third-party/byedpi"; do
  if [[ -f "$candidate/Makefile" ]]; then BYEDPI_SRC="$candidate"; break; fi
done
if [[ -z "$BYEDPI_SRC" ]]; then
  # Архив без .git (скачан ZIP) — берём upstream
  command -v git >/dev/null || fail "нет исходников ByeDPI и не установлен git"
  say "Субмодуль недоступен, клонирую $BYEDPI_URL в third-party/byedpi"
  git clone --depth 1 "$BYEDPI_URL" "$ROOT/third-party/byedpi"
  BYEDPI_SRC="$ROOT/third-party/byedpi"
fi

say "Сборка ciadpi ($BYEDPI_SRC)"
make -C "$BYEDPI_SRC" clean >/dev/null 2>&1 || true
make -C "$BYEDPI_SRC" -j"$(nproc)"
install -m 0755 "$BYEDPI_SRC/ciadpi" "$OUT/ciadpi"

# --- Telegram WS proxy ---------------------------------------------------------
# tg-ws-proxy-rs — библиотека (crate-type = ["cdylib"]), отдельного бинарника
# у неё нет. GUI загружает libtgwsproxy.so через ctypes, как Android через JNA.
TG_SRC="$ROOT/third-party/tg-ws-proxy-rs"
[[ -f "$TG_SRC/Cargo.toml" ]] || fail "не найден $TG_SRC/Cargo.toml"

say "Сборка libtgwsproxy.so (cargo, первый раз — несколько минут)"
cargo build --manifest-path "$TG_SRC/Cargo.toml" --release
TG_LIB="$TG_SRC/target/release/libtgwsproxy.so"
[[ -f "$TG_LIB" ]] || fail "не найден ожидаемый файл: $TG_LIB"
install -m 0755 "$TG_LIB" "$OUT/libtgwsproxy.so"

# Проверяем, что нужные функции экспортированы
if command -v nm >/dev/null; then
  SYMBOLS="$(nm -D --defined-only "$OUT/libtgwsproxy.so")"
  for sym in StartProxy StopProxy GetStats GetSecretWithPrefix FreeString SetPoolSize SetCfProxyConfig SetCfProxyCacheDir; do
    grep -qw "$sym" <<<"$SYMBOLS" || fail "в libtgwsproxy.so нет функции $sym"
  done
fi

# --- Ассеты «Подбора» -----------------------------------------------------------
say "Копирование стратегий и списков сайтов"
cp "$ROOT"/app/src/main/assets/proxytest_* "$OUT/assets/"

# --- Проверка окружения для GUI -------------------------------------------------
if ! python3 - <<'PY' 2>/dev/null
import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw
assert (Adw.get_major_version(), Adw.get_minor_version()) >= (1, 4)
PY
then
  printf '\033[1;33mВнимание:\033[0m для интерфейса нужны пакеты: sudo dnf install -y python3-gobject gtk4 libadwaita curl\n'
fi

say "Готово: $OUT"
ls -la "$OUT"
echo
echo "Запуск без установки:  python3 $ROOT/linux/app.py"
echo "Установка в меню:      $ROOT/linux/install.sh"
