"""Пути, настройки, списки доменов и история команд.

Аналоги Android-классов: SharedPreferences, DomainListUtils, HistoryUtils.
Всё хранится в JSON:
  ~/.config/byebyedpi-tg/settings.json
  ~/.local/share/byebyedpi-tg/{domain_lists,cmd_history,proxy_test_results}.json
"""
from __future__ import annotations

import json
import os
import secrets
import shutil
import tempfile
from pathlib import Path
from typing import Any

APP_ID = "com.github.ewenloy.ByeByeDPI"
APP_NAME = "ByeByeDPI x tg"
APP_VERSION = "1.0.1-linux"

APP_DIR = Path(__file__).resolve().parent
DIST_DIR = APP_DIR / "dist"


def _xdg(var: str, fallback: str) -> Path:
    value = os.environ.get(var)
    return Path(value) if value else Path.home() / fallback


CONFIG_DIR = _xdg("XDG_CONFIG_HOME", ".config") / "byebyedpi-tg"
DATA_DIR = _xdg("XDG_DATA_HOME", ".local/share") / "byebyedpi-tg"
CACHE_DIR = _xdg("XDG_CACHE_HOME", ".cache") / "byebyedpi-tg"
AUTOSTART_DIR = _xdg("XDG_CONFIG_HOME", ".config") / "autostart"


def find_ciadpi() -> Path | None:
    local = DIST_DIR / "ciadpi"
    if local.is_file() and os.access(local, os.X_OK):
        return local
    system = shutil.which("ciadpi")
    return Path(system) if system else None


def tg_library_path() -> Path:
    return DIST_DIR / "libtgwsproxy.so"


def assets_dir() -> Path | None:
    """Стратегии и списки сайтов: dist/assets после build.sh, иначе из Android-проекта."""
    for candidate in (DIST_DIR / "assets", APP_DIR.parent / "app/src/main/assets"):
        if (candidate / "proxytest_strategies.list").is_file():
            return candidate
    return None


def _atomic_write_json(path: Path, data: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as fh:
            json.dump(data, fh, ensure_ascii=False, indent=2)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def _read_json(path: Path, default: Any) -> Any:
    try:
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return default


# ---------------------------------------------------------------------------
# Настройки
# ---------------------------------------------------------------------------

DEFAULT_CMD = "-Ku -a1 -An -o1 -At,r,s -d1"

DEFAULTS: dict[str, Any] = {
    # Общие
    "theme": "system",               # system | light | dark
    "mode": "proxy",                 # proxy | system  (аналог «Режим» VPN/Proxy)
    # Автоматизация
    "autostart": False,
    "auto_connect": False,
    # ByeDPI
    "cmd_args": DEFAULT_CMD,
    "proxy_ip": "127.0.0.1",
    "proxy_port": 1080,
    "http_connect": False,
    # Telegram WS
    "tg_enabled": False,             # запоминаем, был ли включён TG при выходе
    "tg_port": 1082,
    "tg_secret": "",
    "tg_pool_size": 4,
    "tg_cf_enabled": True,
    "tg_cf_domain": "",
    "tg_dc_ips": "",                 # "2:149.154.167.220,4:149.154.167.220" — если CF выключен
    # Подбор стратегий
    "test_delay": 1,
    "test_requests": 1,
    "test_limit": 20,
    "test_timeout": 5,
    "test_sni": "google.com",
    "test_user_commands": False,
    "test_commands": "",
    # Служебное: что было в системном прокси до нас (для восстановления после сбоя)
    "system_proxy_backup": None,
}


# Какие настройки требуют перезапуска соответствующего сервиса
DPI_KEYS = ("cmd_args", "proxy_ip", "proxy_port", "http_connect", "mode")
TG_KEYS = ("tg_port", "tg_secret", "tg_pool_size", "tg_cf_enabled", "tg_cf_domain", "tg_dc_ips")


class Settings:
    def __init__(self, path: Path | None = None):
        self.path = path or CONFIG_DIR / "settings.json"
        stored = _read_json(self.path, {})
        self._data: dict[str, Any] = dict(DEFAULTS)
        if isinstance(stored, dict):
            self._data.update({k: v for k, v in stored.items() if k in DEFAULTS})

    def get(self, key: str) -> Any:
        return self._data.get(key, DEFAULTS.get(key))

    def set(self, key: str, value: Any) -> None:
        if self._data.get(key) == value:
            return
        self._data[key] = value
        self.save()

    def save(self) -> None:
        _atomic_write_json(self.path, self._data)

    def ensure_tg_secret(self) -> str:
        secret = str(self.get("tg_secret") or "").strip().lower()
        if len(secret) == 32 and all(c in "0123456789abcdef" for c in secret):
            return secret
        secret = secrets.token_hex(16)
        self.set("tg_secret", secret)
        return secret

    def regenerate_tg_secret(self) -> str:
        self.set("tg_secret", "")
        return self.ensure_tg_secret()


# ---------------------------------------------------------------------------
# Списки доменов (DomainListUtils)
# ---------------------------------------------------------------------------

DEFAULT_ACTIVE_LISTS = {"youtube", "googlevideo"}


class DomainLists:
    def __init__(self, path: Path | None = None):
        self.path = path or DATA_DIR / "domain_lists.json"
        # Первый запуск (или файл создан до сборки ассетов) — заполняем встроенными списками
        if not self.path.exists() or not self.all():
            self.reset_to_defaults()

    @staticmethod
    def builtin() -> list[dict]:
        result = []
        folder = assets_dir()
        if folder is None:
            return result
        for file in sorted(folder.glob("proxytest_*.sites")):
            list_id = file.stem.removeprefix("proxytest_")
            domains = [line.strip() for line in file.read_text(encoding="utf-8").splitlines() if line.strip()]
            result.append({
                "id": list_id,
                "name": list_id[:1].upper() + list_id[1:],
                "domains": domains,
                "active": list_id in DEFAULT_ACTIVE_LISTS,
                "builtin": True,
            })
        return result

    def reset_to_defaults(self) -> None:
        custom = [item for item in self.all() if not item.get("builtin")] if self.path.exists() else []
        self.save(self.builtin() + custom)

    def all(self) -> list[dict]:
        data = _read_json(self.path, [])
        return [item for item in data if isinstance(item, dict) and "id" in item] if isinstance(data, list) else []

    def save(self, lists: list[dict]) -> None:
        _atomic_write_json(self.path, lists)

    def active_domains(self) -> list[str]:
        seen: dict[str, None] = {}
        for item in self.all():
            if item.get("active"):
                for domain in item.get("domains", []):
                    seen.setdefault(domain, None)
        return list(seen)

    def get_by_name(self, name: str) -> dict | None:
        for item in self.all():
            if str(item.get("name", "")).lower() == name.strip().lower():
                return item
        return None

    def set_active(self, list_id: str, active: bool) -> None:
        lists = self.all()
        for item in lists:
            if item["id"] == list_id:
                item["active"] = active
        self.save(lists)

    def add(self, name: str, domains: list[str]) -> bool:
        lists = self.all()
        list_id = name.strip().lower().replace(" ", "_")
        if not list_id or any(item["id"] == list_id for item in lists):
            return False
        lists.append({"id": list_id, "name": name.strip(), "domains": domains, "active": True, "builtin": False})
        self.save(lists)
        return True

    def update(self, list_id: str, name: str, domains: list[str]) -> bool:
        lists = self.all()
        for item in lists:
            if item["id"] == list_id:
                item["name"] = name.strip() or item["name"]
                item["domains"] = domains
                self.save(lists)
                return True
        return False

    def delete(self, list_id: str) -> bool:
        lists = self.all()
        remaining = [item for item in lists if item["id"] != list_id]
        if len(remaining) == len(lists):
            return False
        self.save(remaining)
        return True


def parse_domains(text: str) -> list[str]:
    result: dict[str, None] = {}
    for raw in text.replace(",", "\n").split():
        domain = raw.strip()
        for prefix in ("https://", "http://"):
            domain = domain.removeprefix(prefix)
        domain = domain.strip("/")
        if domain:
            result.setdefault(domain, None)
    return list(result)


# ---------------------------------------------------------------------------
# История команд (HistoryUtils)
# ---------------------------------------------------------------------------

MAX_HISTORY = 40


class CommandHistory:
    def __init__(self, path: Path | None = None):
        self.path = path or DATA_DIR / "cmd_history.json"

    def all(self) -> list[dict]:
        data = _read_json(self.path, [])
        items = [item for item in data if isinstance(item, dict) and item.get("text")] if isinstance(data, list) else []
        # Закреплённые сверху, порядок внутри групп сохраняется
        return sorted(items, key=lambda item: not item.get("pinned", False))

    def _save(self, items: list[dict]) -> None:
        _atomic_write_json(self.path, items)

    def add(self, command: str) -> None:
        command = command.strip()
        if not command:
            return
        items = self.all()
        if any(item["text"] == command for item in items):
            return
        items.insert(0, {"text": command, "name": "", "pinned": False})
        while len(items) > MAX_HISTORY:
            unpinned = [item for item in items if not item.get("pinned")]
            if not unpinned:
                break
            items.remove(unpinned[-1])
        self._save(items)

    def set_pinned(self, command: str, pinned: bool) -> None:
        items = self.all()
        for item in items:
            if item["text"] == command:
                item["pinned"] = pinned
        self._save(items)

    def delete(self, command: str) -> None:
        self._save([item for item in self.all() if item["text"] != command])


# ---------------------------------------------------------------------------
# Результаты подбора
# ---------------------------------------------------------------------------

RESULTS_PATH = DATA_DIR / "proxy_test_results.json"


def load_test_results() -> list[dict]:
    data = _read_json(RESULTS_PATH, [])
    return data if isinstance(data, list) else []


def save_test_results(results: list[dict]) -> None:
    _atomic_write_json(RESULTS_PATH, results)


def load_strategies(settings: Settings) -> list[str]:
    sni = str(settings.get("test_sni") or "google.com").strip() or "google.com"
    if settings.get("test_user_commands"):
        content = str(settings.get("test_commands") or "")
    else:
        folder = assets_dir()
        content = (folder / "proxytest_strategies.list").read_text(encoding="utf-8") if folder else ""
    return [line.strip() for line in content.replace("{sni}", sni).splitlines() if line.strip()]
