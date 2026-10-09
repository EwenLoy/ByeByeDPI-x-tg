"""Системный прокси — Linux-замена Android-режима «VPN».

GNOME (и производные: Unity, Cinnamon, Budgie, Pantheon) → gsettings org.gnome.system.proxy
KDE Plasma 5/6                                           → kioslaverc через kwriteconfig6/5
Остальные окружения                                       → не поддерживается, прокси указывается вручную

Перед включением сохраняется прежнее состояние (в settings.json), поэтому
выключение и даже запуск после аварийного завершения возвращают всё как было.
"""
from __future__ import annotations

import os
import shutil
import subprocess

GNOME, KDE, UNSUPPORTED = "gnome", "kde", "unsupported"

_GNOME_FAMILY = ("GNOME", "UNITY", "CINNAMON", "BUDGIE", "PANTHEON", "X-CINNAMON")

DESKTOP_TITLES = {GNOME: "GNOME", KDE: "KDE Plasma", UNSUPPORTED: ""}


def _run(argv: list[str]) -> str:
    result = subprocess.run(argv, capture_output=True, text=True, timeout=5)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip() or f"{argv[0]} завершился с кодом {result.returncode}")
    return result.stdout.strip()


def _kde_tools() -> tuple[str, str] | None:
    for suffix in ("6", "5"):
        writer, reader = shutil.which(f"kwriteconfig{suffix}"), shutil.which(f"kreadconfig{suffix}")
        if writer and reader:
            return writer, reader
    return None


def detect_desktop() -> str:
    desktops = os.environ.get("XDG_CURRENT_DESKTOP", "").upper().split(":")
    session = os.environ.get("DESKTOP_SESSION", "").upper()
    if "KDE" in desktops or "PLASMA" in session:
        return KDE if _kde_tools() else UNSUPPORTED
    if any(name in desktops for name in _GNOME_FAMILY) or "GNOME" in session:
        return GNOME if shutil.which("gsettings") else UNSUPPORTED
    return UNSUPPORTED


def describe() -> str:
    desktop = detect_desktop()
    if desktop == UNSUPPORTED:
        name = os.environ.get("XDG_CURRENT_DESKTOP") or "неизвестное окружение"
        return f"{name}: автоматическая настройка недоступна"
    return f"Будет настроен прокси {DESKTOP_TITLES[desktop]}"


# ---------------------------------------------------------------------------
# GNOME
# ---------------------------------------------------------------------------

_G_KEYS = [
    ("org.gnome.system.proxy", "mode"),
    ("org.gnome.system.proxy.socks", "host"),
    ("org.gnome.system.proxy.socks", "port"),
    ("org.gnome.system.proxy.http", "host"),
    ("org.gnome.system.proxy.http", "port"),
    ("org.gnome.system.proxy.https", "host"),
    ("org.gnome.system.proxy.https", "port"),
]


def _gnome_backup() -> dict:
    return {f"{schema} {key}": _run(["gsettings", "get", schema, key]) for schema, key in _G_KEYS}


def _gnome_set(schema: str, key: str, value: str) -> None:
    _run(["gsettings", "set", schema, key, value])


def _gnome_apply(host: str, port: int, http: bool) -> None:
    quoted = f"'{host}'"
    if http:
        for schema in ("org.gnome.system.proxy.http", "org.gnome.system.proxy.https"):
            _gnome_set(schema, "host", quoted)
            _gnome_set(schema, "port", str(port))
        _gnome_set("org.gnome.system.proxy.socks", "host", "''")
    else:
        _gnome_set("org.gnome.system.proxy.socks", "host", quoted)
        _gnome_set("org.gnome.system.proxy.socks", "port", str(port))
        # иначе HTTP-трафик пошёл бы мимо ByeDPI на старый HTTP-прокси
        _gnome_set("org.gnome.system.proxy.http", "host", "''")
        _gnome_set("org.gnome.system.proxy.https", "host", "''")
    _gnome_set("org.gnome.system.proxy", "mode", "'manual'")


def _gnome_restore(backup: dict) -> None:
    # mode в конце, чтобы не было момента «manual без адреса»
    items = sorted(backup.items(), key=lambda kv: kv[0] == "org.gnome.system.proxy mode")
    for name, value in items:
        schema, key = name.split(" ", 1)
        _gnome_set(schema, key, value)


# ---------------------------------------------------------------------------
# KDE
# ---------------------------------------------------------------------------

_K_KEYS = ["ProxyType", "socksProxy", "httpProxy", "httpsProxy"]


def _kde_read(key: str) -> str:
    _, reader = _kde_tools()
    return _run([reader, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key])


def _kde_write(key: str, value: str) -> None:
    writer, _ = _kde_tools()
    _run([writer, "--file", "kioslaverc", "--group", "Proxy Settings", "--key", key, value])


def _kde_notify() -> None:
    # Сообщаем KIO, что настройки прокси изменились (как делает System Settings)
    if shutil.which("dbus-send"):
        subprocess.run(
            ["dbus-send", "--type=signal", "/KIO/Scheduler",
             "org.kde.KIO.Scheduler.reparseSlaveConfiguration", "string:"],
            capture_output=True, timeout=5,
        )


def _kde_backup() -> dict:
    return {key: _kde_read(key) for key in _K_KEYS}


def _kde_apply(host: str, port: int, http: bool) -> None:
    # Формат KIO: «схема://хост порт» (пробел перед портом)
    if http:
        _kde_write("httpProxy", f"http://{host} {port}")
        _kde_write("httpsProxy", f"http://{host} {port}")
        _kde_write("socksProxy", "")
    else:
        _kde_write("socksProxy", f"socks://{host} {port}")
        _kde_write("httpProxy", "")
        _kde_write("httpsProxy", "")
    _kde_write("ProxyType", "1")
    _kde_notify()


def _kde_restore(backup: dict) -> None:
    for key in _K_KEYS:
        if key != "ProxyType":
            _kde_write(key, backup.get(key, ""))
    _kde_write("ProxyType", backup.get("ProxyType") or "0")
    _kde_notify()


# ---------------------------------------------------------------------------
# Публичный интерфейс
# ---------------------------------------------------------------------------

def enable(host: str, port: int, http: bool) -> dict:
    """Включает системный прокси и возвращает резервную копию прежних значений."""
    desktop = detect_desktop()
    if desktop == GNOME:
        backup = _gnome_backup()
        _gnome_apply(host, port, http)
    elif desktop == KDE:
        backup = _kde_backup()
        _kde_apply(host, port, http)
    else:
        raise RuntimeError("Окружение рабочего стола не поддерживается — укажите SOCKS5-прокси в приложениях вручную")
    return {"desktop": desktop, "values": backup}


def restore(backup: dict | None) -> None:
    if not backup:
        return
    desktop, values = backup.get("desktop"), backup.get("values") or {}
    if desktop == GNOME and shutil.which("gsettings"):
        _gnome_restore(values)
    elif desktop == KDE and _kde_tools():
        _kde_restore(values)
