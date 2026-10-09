#!/usr/bin/env python3
"""Отдельный процесс, в котором живёт MTProto→WebSocket прокси для Telegram.

tg-ws-proxy-rs собирается как библиотека (cdylib → libtgwsproxy.so), а не как
программа, поэтому раньше «бинарник tg-ws-proxy» и не находился. Android-версия
грузит эту библиотеку через JNA; здесь то же самое делается через ctypes.

Отдельный процесс нужен, чтобы паника в Rust (panic = "abort") не роняла GUI,
а логи ядра (eprintln!) попадали в окно «Логи TG WS».

Протокол stdout (по строке):
  READY <секрет с префиксом dd>
  ERROR <код> <описание>
  STATS total=.. active=.. ws=.. ... up=.. down=..
"""
from __future__ import annotations

import argparse
import ctypes
import signal
import sys
import time

ERRORS = {
    -1: "прокси уже запущен в этом процессе",
    -3: "не удалось занять порт (уже используется?)",
}


def load(lib_path: str) -> ctypes.CDLL:
    lib = ctypes.CDLL(lib_path)
    lib.StartProxy.argtypes = [ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_int]
    lib.StartProxy.restype = ctypes.c_int
    lib.StopProxy.argtypes = []
    lib.StopProxy.restype = ctypes.c_int
    lib.SetPoolSize.argtypes = [ctypes.c_int]
    lib.SetPoolSize.restype = None
    lib.SetCfProxyCacheDir.argtypes = [ctypes.c_char_p]
    lib.SetCfProxyCacheDir.restype = None
    lib.SetCfProxyConfig.argtypes = [ctypes.c_int, ctypes.c_int, ctypes.c_char_p]
    lib.SetCfProxyConfig.restype = None
    # Строки, выделенные в Rust, освобождаются только через FreeString,
    # поэтому возвращаемый тип — сырой указатель, а не c_char_p.
    lib.GetStats.argtypes = []
    lib.GetStats.restype = ctypes.c_void_p
    lib.GetSecretWithPrefix.argtypes = []
    lib.GetSecretWithPrefix.restype = ctypes.c_void_p
    lib.FreeString.argtypes = [ctypes.c_void_p]
    lib.FreeString.restype = None
    return lib


def take_string(lib: ctypes.CDLL, ptr: int | None) -> str:
    if not ptr:
        return ""
    try:
        return ctypes.string_at(ptr).decode("utf-8", "replace")
    finally:
        lib.FreeString(ptr)


PIPE_BROKEN = False


def emit(line: str) -> None:
    global PIPE_BROKEN
    try:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()
    except OSError:  # GUI исчез — штатно гасим прокси
        PIPE_BROKEN = True


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--lib", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=1082)
    parser.add_argument("--secret", required=True)
    parser.add_argument("--pool-size", type=int, default=4)
    parser.add_argument("--cf", type=int, default=1)
    parser.add_argument("--cf-domain", default="")
    parser.add_argument("--dc-ips", default="")
    parser.add_argument("--cache-dir", default="")
    parser.add_argument("--stats-interval", type=float, default=1.0)
    args = parser.parse_args()

    try:
        lib = load(args.lib)
    except OSError as exc:
        emit(f"ERROR -100 не удалось загрузить {args.lib}: {exc}")
        return 2
    except AttributeError as exc:
        emit(f"ERROR -101 в библиотеке нет нужной функции: {exc}")
        return 2

    stop_requested = False

    def on_signal(_signum, _frame):
        nonlocal stop_requested
        stop_requested = True

    signal.signal(signal.SIGTERM, on_signal)
    signal.signal(signal.SIGINT, on_signal)

    lib.SetPoolSize(args.pool_size)
    if args.cache_dir:
        lib.SetCfProxyCacheDir(args.cache_dir.encode())
    lib.SetCfProxyConfig(1 if args.cf else 0, 1, args.cf_domain.encode())

    rc = lib.StartProxy(args.host.encode(), args.port, args.dc_ips.encode(), args.secret.encode(), 1)
    if rc != 0:
        emit(f"ERROR {rc} {ERRORS.get(rc, 'неизвестная ошибка')}")
        return 3

    emit(f"READY {take_string(lib, lib.GetSecretWithPrefix())}")

    next_stats = 0.0
    while not stop_requested and not PIPE_BROKEN:
        now = time.monotonic()
        if now >= next_stats:
            emit(f"STATS {take_string(lib, lib.GetStats())}")
            next_stats = now + args.stats_interval
        time.sleep(0.1)

    lib.StopProxy()
    emit("STOPPED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
