"""Запуск и контроль дочерних процессов (ciadpi и TG-прокси).

Отличия от первой версии:
  * stderr/stdout не выбрасываются, а копятся в кольцевом логе — его видно в окне «Логи»;
  * процесс, упавший сразу после старта, распознаётся, и причина показывается пользователю;
  * дети получают SIGTERM, если GUI аварийно завершится (PR_SET_PDEATHSIG),
    поэтому «осиротевший» ciadpi не держит порт.
"""
from __future__ import annotations

import ctypes
import re
import shlex
import signal
import socket
import subprocess
import threading
import time
from collections import deque
from typing import Callable

_PR_SET_PDEATHSIG = 1

try:
    _libc = ctypes.CDLL("libc.so.6", use_errno=True)
except OSError:  # не glibc — просто без этой страховки
    _libc = None


def _child_setup() -> None:
    if _libc is not None:
        _libc.prctl(_PR_SET_PDEATHSIG, signal.SIGTERM)


class ManagedProcess:
    """Один дочерний процесс с логом и колбэком на каждую строку stdout."""

    def __init__(self, name: str, max_log_lines: int = 800):
        self.name = name
        self.proc: subprocess.Popen | None = None
        self.log: deque[str] = deque(maxlen=max_log_lines)
        self.on_stdout_line: Callable[[str], None] | None = None
        self._stopping = False
        self._lock = threading.Lock()
        self.last_stderr = ""

    # -- состояние ---------------------------------------------------------
    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    @property
    def died_unexpectedly(self) -> bool:
        return self.proc is not None and self.proc.poll() is not None and not self._stopping

    @property
    def returncode(self) -> int | None:
        return None if self.proc is None else self.proc.poll()

    def failure_reason(self) -> str:
        """Последняя строка stderr или код выхода — для сообщения пользователю."""
        code = self.returncode
        code_text = f"код выхода {code}" if code is not None and code >= 0 else f"сигнал {-code}" if code else ""
        text = self.last_stderr
        if self.proc is not None and isinstance(self.proc.args, list) and self.proc.args:
            text = text.removeprefix(f"{self.proc.args[0]}: ")
        return text or code_text

    def log_text(self) -> str:
        return "\n".join(self.log)

    def clear_log(self) -> None:
        self.log.clear()

    # -- управление --------------------------------------------------------
    def start(self, argv: list[str]) -> None:
        """Вызывать из главного потока: PDEATHSIG привязан к потоку-родителю."""
        with self._lock:
            if self.running:
                return
            self._stopping = False
            self.last_stderr = ""
            self._append(f"$ {shlex.join(argv)}")
            self.proc = subprocess.Popen(
                argv,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                errors="replace",
                bufsize=1,
                start_new_session=True,
                preexec_fn=_child_setup,
            )
            proc = self.proc
        threading.Thread(target=self._pump, args=(proc.stdout, True), daemon=True).start()
        threading.Thread(target=self._pump, args=(proc.stderr, False), daemon=True).start()

    def stop(self, timeout: float = 4.0) -> None:
        with self._lock:
            proc = self.proc
            self._stopping = True
        if proc is None or proc.poll() is not None:
            return
        proc.terminate()
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait()
        self._append(f"[{self.name}] остановлен (код {proc.returncode})")

    def _pump(self, stream, is_stdout: bool) -> None:
        try:
            for line in stream:
                line = line.rstrip("\n")
                if is_stdout and self.on_stdout_line is not None:
                    try:
                        self.on_stdout_line(line)
                    except Exception as exc:  # колбэк не должен ронять поток чтения
                        self._append(f"[callback error] {exc}")
                    # служебные строки протокола не засоряют лог
                    if line.startswith("STATS "):
                        continue
                elif line.strip():
                    self.last_stderr = line.strip()
                self._append(line)
        except (OSError, ValueError):
            pass

    def _append(self, line: str) -> None:
        self.log.append(f"{time.strftime('%H:%M:%S')} {line}")


# ---------------------------------------------------------------------------
# Сеть
# ---------------------------------------------------------------------------

def port_is_free(host: str, port: int) -> bool:
    family = socket.AF_INET6 if ":" in host else socket.AF_INET
    with socket.socket(family, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((host, port))
        except OSError:
            return False
    return True


def free_port(host: str = "127.0.0.1") -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind((host, 0))
        return sock.getsockname()[1]


def wait_for_port(host: str, port: int, timeout: float, alive: Callable[[], bool] = lambda: True) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not alive():
            return False
        try:
            with socket.create_connection((host, port), timeout=0.3):
                return True
        except OSError:
            time.sleep(0.1)
    return False


def connect_host(host: str) -> str:
    """Куда подключаться клиенту, если сервер слушает 0.0.0.0/::."""
    return {"0.0.0.0": "127.0.0.1", "::": "::1", "": "127.0.0.1"}.get(host, host)


# ---------------------------------------------------------------------------
# Командная строка ciadpi (ByeDpiProxyCmdPreferences)
# ---------------------------------------------------------------------------

_LIST_RE = re.compile(r"\{list:([^}]+)\}")


def substitute_lists(cmd: str, lookup: Callable[[str], list[str] | None]) -> str:
    """{list:Имя} → домены списка через пробел (как в Android-версии)."""
    def repl(match: re.Match) -> str:
        domains = lookup(match.group(1).strip())
        return " ".join(domains) if domains else ""
    return _LIST_RE.sub(repl, cmd)


def _has_option(tokens: list[str], short: str, long: str) -> bool:
    for token in tokens:
        if token == short or token == long or token.startswith(long + "="):
            return True
        if token.startswith(short) and len(token) > 2 and not token.startswith("--"):
            return True
    return False


def build_ciadpi_argv(binary: str, cmd: str, ip: str, port: int, http_connect: bool,
                      force_address: bool = False) -> list[str]:
    """Собирает argv; адрес из настроек добавляется, если в команде его нет.

    force_address=True — адрес дописывается в конец и перекрывает -i/-p из команды
    (нужно для подбора стратегий на отдельном порту).
    """
    cmd = cmd.strip()
    first_dash = cmd.find("-")
    if first_dash > 0:            # «ciadpi -d1 ...» → «-d1 ...»
        cmd = cmd[first_dash:]
    tokens = shlex.split(cmd)

    extra: list[str] = []
    if force_address or not _has_option(tokens, "-i", "--ip"):
        extra += ["--ip", ip]
    if force_address or not _has_option(tokens, "-p", "--port"):
        extra += ["--port", str(port)]
    if http_connect and not _has_option(tokens, "-G", "--http-connect"):
        extra.append("--http-connect")

    return [binary, *tokens, *extra] if force_address else [binary, *extra, *tokens]


def cmd_address(cmd: str, default_ip: str, default_port: int) -> tuple[str, int]:
    """Какой адрес реально будет слушать ciadpi (учитывая -i/-p внутри команды)."""
    ip, port = default_ip, default_port
    try:
        tokens = shlex.split(cmd)
    except ValueError:
        return ip, port
    for i, token in enumerate(tokens):
        nxt = tokens[i + 1] if i + 1 < len(tokens) else ""
        if token in ("-i", "--ip") and nxt:
            ip = nxt
        elif token.startswith("--ip="):
            ip = token.split("=", 1)[1]
        elif token.startswith("-i") and len(token) > 2 and not token.startswith("--"):
            ip = token[2:]
        elif token in ("-p", "--port") and nxt.isdigit():
            port = int(nxt)
        elif token.startswith("--port=") and token.split("=", 1)[1].isdigit():
            port = int(token.split("=", 1)[1])
        elif token.startswith("-p") and token[2:].isdigit():
            port = int(token[2:])
    return ip, port
