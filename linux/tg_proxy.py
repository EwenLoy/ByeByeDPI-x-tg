"""Управление Telegram WS-прокси и его состояниями.

Состояния (то, что видит пользователь):
  OFF        — выключен;
  WAITING    — прокси слушает порт и ЖДЁТ, пока Telegram подключится
               (пользователь ещё не добавил прокси в Telegram);
  CONNECTED  — Telegram уже ходит через прокси;
  ERROR      — запуск не удался или процесс упал; текст ошибки не пропадает,
               пока пользователь сам не нажмёт кнопку снова.
"""
from __future__ import annotations

import socket
import sys
import threading
from urllib.parse import urlencode

from process_manager import ManagedProcess, connect_host, port_is_free
from storage import APP_DIR, CACHE_DIR, Settings, tg_library_path

OFF, WAITING, CONNECTED, ERROR = "off", "waiting", "connected", "error"

RUNNER = APP_DIR / "tg_proxy_runner.py"


class TgProxyError(Exception):
    pass


def parse_stats(raw: str) -> dict[str, str]:
    stats: dict[str, str] = {}
    for part in raw.split():
        if "=" in part:
            key, value = part.split("=", 1)
            stats[key] = value
    return stats


def _int(value: str | None) -> int:
    try:
        return int(value or 0)
    except ValueError:
        return 0


class TgProxyController:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.process = ManagedProcess("tg-ws-proxy")
        self.process.on_stdout_line = self._on_line
        self._lock = threading.Lock()
        self._ready = False
        self._start_error = ""
        self.stats: dict[str, str] = {}
        self.secret_with_prefix = ""
        self.host = "127.0.0.1"
        self.port = 1082

    # -- запуск/остановка --------------------------------------------------
    def start(self) -> None:
        lib = tg_library_path()
        if not lib.is_file():
            raise TgProxyError(
                f"Не найдено ядро Telegram-прокси:\n{lib}\nСоберите его: ./linux/build.sh"
            )
        self.host = "127.0.0.1"
        self.port = int(self.settings.get("tg_port"))
        if not port_is_free(self.host, self.port):
            raise TgProxyError(f"Порт {self.port} уже занят другой программой. Смените порт в настройках.")

        secret = self.settings.ensure_tg_secret()
        cf_enabled = bool(self.settings.get("tg_cf_enabled"))
        cache_dir = CACHE_DIR / "cfproxy"
        cache_dir.mkdir(parents=True, exist_ok=True)

        with self._lock:
            self._ready = False
            self._start_error = ""
            self.stats = {}
            self.secret_with_prefix = "dd" + secret

        self.process.start([
            sys.executable, "-u", str(RUNNER),
            "--lib", str(lib),
            "--host", self.host,
            "--port", str(self.port),
            "--secret", secret,
            "--pool-size", str(int(self.settings.get("tg_pool_size"))),
            "--cf", "1" if cf_enabled else "0",
            "--cf-domain", str(self.settings.get("tg_cf_domain") or "").strip() if cf_enabled else "",
            # Как в Android: при CloudFlare DC-адреса не передаются
            "--dc-ips", "" if cf_enabled else str(self.settings.get("tg_dc_ips") or "").strip(),
            "--cache-dir", str(cache_dir),
        ])

    def stop(self) -> None:
        self.process.stop(timeout=5)
        with self._lock:
            self._ready = False
            self.stats = {}

    # -- состояние ---------------------------------------------------------
    @property
    def running(self) -> bool:
        return self.process.running

    @property
    def ready(self) -> bool:
        return self._ready and self.process.running

    def state(self) -> tuple[str, str]:
        """(состояние, подробный текст ошибки)."""
        with self._lock:
            start_error = self._start_error
        if start_error:
            return ERROR, start_error
        if self.process.died_unexpectedly:
            return ERROR, f"Прокси Telegram неожиданно завершился ({self.process.failure_reason()})"
        if not self.process.running:
            return OFF, ""
        if not self._ready:
            return WAITING, ""
        if _int(self.stats.get("total")) > 0:
            return CONNECTED, ""
        return WAITING, ""

    def traffic_text(self) -> str:
        s = self.stats
        if not s:
            return ""
        return f"акт: {_int(s.get('active'))} · ↑{s.get('up', '0B')} ↓{s.get('down', '0B')}"

    def link(self, https: bool = False) -> str:
        secret = self.secret_with_prefix or "dd" + self.settings.ensure_tg_secret()
        query = urlencode({"server": connect_host(self.host), "port": self.port, "secret": secret})
        return f"https://t.me/proxy?{query}" if https else f"tg://proxy?{query}"

    def _on_line(self, line: str) -> None:
        with self._lock:
            if line.startswith("READY"):
                parts = line.split(maxsplit=1)
                if len(parts) == 2 and parts[1].startswith("dd"):
                    self.secret_with_prefix = parts[1]
                self._ready = True
            elif line.startswith("STATS "):
                self.stats = parse_stats(line[6:])
            elif line.startswith("ERROR"):
                parts = line.split(maxsplit=2)
                detail = parts[2] if len(parts) > 2 else line
                if parts[1:2] == ["-3"]:
                    detail = f"порт {self.port} занят"
                self._start_error = f"Прокси Telegram не запустился: {detail}"


def socks_link(host: str, port: int) -> str:
    return "tg://socks?" + urlencode({"server": connect_host(host), "port": port})


# ---------------------------------------------------------------------------
# «Проверить TG WS» — те же пять шагов, что и в Android TestActivity
# ---------------------------------------------------------------------------

def _reachable(host: str, port: int, timeout: float) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _detect_protocol(host: str, port: int) -> str:
    """MTProto-прокси молчит на SOCKS5-greeting, SOCKS5-сервер отвечает 0x05."""
    try:
        with socket.create_connection((host, port), timeout=3) as sock:
            sock.settimeout(2.5)
            sock.sendall(b"\x05\x01\x00")
            data = sock.recv(1)
            if data == b"\x05":
                return "socks5"
            return "mtproto" if data == b"" else "unknown"
    except socket.timeout:
        return "mtproto"
    except OSError:
        return "unknown"


def diagnose(controller: TgProxyController) -> tuple[bool, list[tuple[bool, str]]]:
    """Выполняется в рабочем потоке. Возвращает (всё ок, [(ок, строка)])."""
    port = int(controller.settings.get("tg_port"))
    lines: list[tuple[bool, str]] = []

    running = controller.running
    lines.append((running, "Сервис TG WS: запущен" if running else "Сервис TG WS: НЕ запущен — включите «TG WS Прокси»"))

    local_ok = _reachable("127.0.0.1", port, 3)
    lines.append((local_ok, f"Локальный порт 127.0.0.1:{port}: {'отвечает' if local_ok else 'НЕ отвечает'}"))

    proto = _detect_protocol("127.0.0.1", port) if local_ok else "unknown"
    proto_text = {
        "mtproto": f"Тип порта {port}: MTProto-прокси — верно",
        "socks5": f"Внимание: порт {port} отвечает как SOCKS5, ожидался MTProto",
        "unknown": f"Тип порта {port}: не определён",
    }[proto]
    lines.append((proto == "mtproto", proto_text))

    lib = tg_library_path()
    secret = controller.secret_with_prefix
    core_ok = lib.is_file() and running and secret.startswith("dd") and len(secret) == 34
    if not lib.is_file():
        lines.append((False, f"Нативное ядро: не найдено ({lib.name}) — запустите build.sh"))
    elif core_ok:
        lines.append((True, f"Нативное ядро: загружено, секрет {secret[:10]}…"))
    else:
        lines.append((False, "Нативное ядро: не запущено"))

    remote = "kws1.web.telegram.org"
    remote_ok = _reachable(remote, 443, 4)
    lines.append((remote_ok, f"WS-сервер Telegram {remote}: {'доступен' if remote_ok else 'недоступен (нет сети или блокировка)'}"))

    return running and local_ok and proto == "mtproto" and core_ok, lines
