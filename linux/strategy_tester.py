"""Подбор стратегий — порт TestActivity + SiteCheckUtils.

Для каждой стратегии поднимается отдельный ciadpi на свободном порту
(основное подключение при этом не трогается), затем через него параллельно
запрашиваются сайты из активных списков. Запрос засчитывается, если curl
скачал ответ целиком: обрыв, сброс TLS или «недокачка» (curl код 18) —
типичные признаки работы DPI — считаются неудачей.
"""
from __future__ import annotations

import shlex
import shutil
import subprocess
import tempfile
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import asdict, dataclass, field
from typing import Callable

from process_manager import _child_setup, build_ciadpi_argv, free_port, wait_for_port


@dataclass
class StrategyResult:
    command: str
    success: int = 0
    total: int = 0
    progress: int = 0
    completed: bool = False
    error: str = ""
    sites: list[dict] = field(default_factory=list)   # {"site", "ok", "n"}

    @property
    def ratio(self) -> float:
        return self.success / self.total if self.total else 0.0

    def to_dict(self) -> dict:
        return asdict(self)

    @classmethod
    def from_dict(cls, data: dict) -> "StrategyResult":
        known = {k: data[k] for k in cls.__dataclass_fields__ if k in data}
        return cls(**known)


def sort_results(results: list[StrategyResult]) -> list[StrategyResult]:
    return sorted(results, key=lambda r: (not r.completed, -r.ratio, -r.success))


class StrategyTester:
    def __init__(
        self,
        ciadpi: str,
        on_update: Callable[[int, StrategyResult], None],
        on_progress: Callable[[str], None],
        on_finished: Callable[[list[StrategyResult], bool], None],
    ):
        self.ciadpi = ciadpi
        self.on_update = on_update
        self.on_progress = on_progress
        self.on_finished = on_finished
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._children: set[subprocess.Popen] = set()
        self._children_lock = threading.Lock()
        self.results: list[StrategyResult] = []

    @property
    def running(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self, commands: list[str], sites: list[str], requests: int, timeout: int,
              limit: int, delay: int) -> None:
        if self.running:
            return
        if not shutil.which("curl"):
            raise RuntimeError("Для подбора нужен curl: sudo dnf install curl")
        self._stop.clear()
        self.results = [StrategyResult(command=c) for c in commands]
        self._thread = threading.Thread(
            target=self._run, args=(sites, max(1, requests), max(1, timeout), max(1, limit), max(0, delay)),
            daemon=True,
        )
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        with self._children_lock:
            for child in list(self._children):
                if child.poll() is None:
                    child.kill()

    # ---------------------------------------------------------------------
    def _spawn(self, argv: list[str], **kwargs) -> subprocess.Popen:
        proc = subprocess.Popen(argv, stdin=subprocess.DEVNULL, preexec_fn=_child_setup, **kwargs)
        with self._children_lock:
            self._children.add(proc)
        return proc

    def _forget(self, proc: subprocess.Popen) -> None:
        with self._children_lock:
            self._children.discard(proc)

    def _run(self, sites, requests, timeout, limit, delay) -> None:
        total = len(self.results)
        try:
            for index, result in enumerate(self.results):
                if self._stop.is_set():
                    break
                self.on_progress(f"Проверка {index + 1} из {total}")
                self._test_one(index, result, sites, requests, timeout, limit)
                result.completed = not self._stop.is_set()
                self.on_update(index, result)
                if delay and not self._stop.is_set():
                    self._stop.wait(delay * 0.5)
        finally:
            self.on_finished(self.results, self._stop.is_set())

    def _test_one(self, index, result, sites, requests, timeout, limit) -> None:
        result.total = len(sites) * requests
        port = free_port()
        try:
            argv = build_ciadpi_argv(self.ciadpi, result.command, "127.0.0.1", port, False, force_address=True)
        except ValueError as exc:
            result.error = f"ошибка в команде: {exc}"
            return
        self.on_update(index, result)

        # stderr в файл, а не в pipe: ciadpi пишет perror() на каждую оборванную
        # связь, и непрочитанный pipe рано или поздно заблокировал бы процесс.
        errlog = tempfile.TemporaryFile(mode="w+", encoding="utf-8", errors="replace")
        server = self._spawn(argv, stdout=subprocess.DEVNULL, stderr=errlog)
        try:
            if not wait_for_port("127.0.0.1", port, 3.0, alive=lambda: server.poll() is None):
                errlog.seek(0)
                lines = [line.strip() for line in errlog.read().splitlines() if line.strip()]
                result.error = ("ciadpi не запустился: " + (lines[-1] if lines else "нет ответа")).strip()
                return

            lock = threading.Lock()

            def check(site: str) -> None:
                ok = 0
                for _ in range(requests):
                    if self._stop.is_set():
                        break
                    if self._check_site(site, port, timeout):
                        ok += 1
                with lock:
                    result.success += ok
                    result.progress += requests
                    result.sites.append({"site": site, "ok": ok, "n": requests})
                self.on_update(index, result)

            with ThreadPoolExecutor(max_workers=limit) as pool:
                list(pool.map(check, sites))
        finally:
            if server.poll() is None:
                server.terminate()
                try:
                    server.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
            self._forget(server)
            errlog.close()

    def _check_site(self, site: str, port: int, timeout: int) -> bool:
        url = site if site.startswith(("http://", "https://")) else f"https://{site}"
        argv = [
            "curl", "--silent", "--output", "/dev/null",
            "--socks5-hostname", f"127.0.0.1:{port}",
            "--connect-timeout", str(timeout), "--max-time", str(timeout * 2),
            "--location", "--max-redirs", "5",
            "--header", "Connection: close",
            url,
        ]
        proc = self._spawn(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            return proc.wait() == 0
        finally:
            self._forget(proc)


def parse_strategy_text(command: str) -> bool:
    """True, если команду можно разобрать (кавычки сбалансированы)."""
    try:
        shlex.split(command)
        return True
    except ValueError:
        return False

