#!/usr/bin/env python3
"""ByeByeDPI x tg для Linux (GTK4 + libadwaita).

Главный экран повторяет Android-версию: большая кнопка включения ByeDPI по
центру, под ней статус, внизу карточки «Редактор», «Настройки», «Подбор»,
«Списки», «TG WS Прокси», «Подключить прокси в Telegram».
"""
from __future__ import annotations

import shlex
import signal
import sys
import threading

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

import system_proxy  # noqa: E402
import ui_common as ui  # noqa: E402
from process_manager import (  # noqa: E402
    ManagedProcess, build_ciadpi_argv, cmd_address, connect_host, port_is_free, substitute_lists,
    wait_for_port,
)
from storage import (  # noqa: E402
    APP_DIR, APP_ID, APP_NAME, APP_VERSION, AUTOSTART_DIR, CommandHistory, DomainLists, Settings,
    find_ciadpi,
)
from tg_proxy import CONNECTED, ERROR, OFF, WAITING, TgProxyController, TgProxyError, socks_link  # noqa: E402

MIN_ADW = (1, 4)

# Состояния ByeDPI
DPI_OFF, DPI_STARTING, DPI_ON = "off", "starting", "on"



class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application):
        super().__init__(application=app, title=APP_NAME)
        # Пропорции телефона: узкое высокое окно
        self.set_default_size(430, 880)
        self.set_size_request(360, 640)

        self.settings = Settings()
        self.lists = DomainLists()
        self.history = CommandHistory()
        self.dpi = ManagedProcess("ciadpi")
        self.tg = TgProxyController(self.settings)
        self.dpi_state = DPI_OFF
        self.dpi_address = ("127.0.0.1", 1080)
        self.tg_busy = False
        self.tg_user_error = ""      # ошибка запуска — держится до следующего нажатия
        self._dpi_gen = 0            # защита от устаревших таймеров запуска

        self.toasts = Adw.ToastOverlay()
        self.nav = Adw.NavigationView()
        self.toasts.set_child(self.nav)
        self.set_content(self.toasts)

        self.nav.add(self._build_main_page())
        self.nav.connect("popped", self._on_page_popped)

        self._recover_system_proxy()
        self.refresh_dpi_ui()
        self.refresh_tg_ui()
        GLib.timeout_add(1000, self._tick)

    # ------------------------------------------------------------------
    # Главный экран
    # ------------------------------------------------------------------
    def _build_main_page(self) -> Adw.NavigationPage:
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=0)
        root.set_margin_start(18)
        root.set_margin_end(18)
        root.set_margin_bottom(18)

        center = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, vexpand=True,
                         valign=Gtk.Align.CENTER)
        center.set_margin_top(24)
        center.set_margin_bottom(24)

        self.power_button = Gtk.Button(halign=Gtk.Align.CENTER, child=ui.icon("bbdpi-power-symbolic", 76))
        self.power_button.add_css_class("power-button")
        self.power_button.set_tooltip_text("Включить или выключить обход DPI")
        self.power_button.connect("clicked", lambda *_: self.toggle_dpi())
        center.append(self.power_button)

        spacer = Gtk.Box()
        spacer.set_size_request(-1, 14)
        center.append(spacer)

        self.dpi_title = ui.label("", "status-title")
        self.dpi_addr = ui.label("", "status-address", selectable=True)
        self.dpi_message = ui.label("", "message")
        center.append(self.dpi_title)
        center.append(self.dpi_addr)
        center.append(self.dpi_message)

        # Блок Telegram: статус + подсказка + «Копировать ссылку»
        tg_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        tg_box.set_margin_top(16)
        self.tg_title = ui.label("", "message")
        self.tg_hint = ui.label("", "dim-label")
        self.tg_hint.add_css_class("caption")
        self.tg_copy = Gtk.Button(label="Копировать ссылку для Telegram", halign=Gtk.Align.CENTER)
        self.tg_copy.add_css_class("pill")
        self.tg_copy.connect("clicked", lambda *_: self.copy_tg_link())
        tg_box.append(self.tg_title)
        tg_box.append(self.tg_hint)
        tg_box.append(self.tg_copy)
        center.append(tg_box)

        root.append(center)

        grid = Gtk.Grid(column_spacing=12, row_spacing=12, column_homogeneous=True)
        editor, _, _ = ui.tile("bbdpi-tune-symbolic", "Редактор", lambda: self.open_page("editor"))
        settings, _, _ = ui.tile("bbdpi-gear-symbolic", "Настройки", lambda: self.open_page("settings"))
        test, _, _ = ui.tile("bbdpi-speed-symbolic", "Подбор", lambda: self.open_page("test"))
        lists, _, _ = ui.tile("bbdpi-filter-symbolic", "Списки", lambda: self.open_page("lists"))
        self.tg_tile, _, self.tg_tile_label = ui.tile("bbdpi-power-symbolic", "TG WS Прокси", self.toggle_tg)
        self.connect_tile, _, _ = ui.tile("bbdpi-dot-symbolic", "Подключить прокси в Telegram",
                                          self.connect_telegram)
        for index, widget in enumerate((editor, settings, test, lists, self.tg_tile, self.connect_tile)):
            grid.attach(widget, index % 2, index // 2, 1, 1)
        root.append(grid)

        menu = Gio.Menu()
        menu.append("Логи ByeDPI", "app.logs-dpi")
        menu.append("Логи TG WS", "app.logs-tg")
        menu.append("О программе", "app.about")
        menu_button = Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu,
                                     tooltip_text="Меню")

        return ui.page(APP_NAME, ui.scrolled(root), tag="main", end_widgets=[menu_button])

    def open_page(self, name: str) -> None:
        import pages  # отложенный импорт: страницы создаются по требованию
        builders = {
            "editor": pages.EditorPage,
            "settings": pages.SettingsPage,
            "test": pages.TestPage,
            "test-settings": pages.TestSettingsPage,
            "lists": pages.ListsPage,
            "logs-dpi": lambda win: pages.LogsPage(win, "dpi"),
            "logs-tg": lambda win: pages.LogsPage(win, "tg"),
        }
        self.nav.push(builders[name](self))

    def _on_page_popped(self, _nav, page) -> None:
        handler = getattr(page, "on_closed", None)
        if handler:
            handler()

    def toast(self, text: str, timeout: int = 3) -> None:
        toast = Adw.Toast(title=text)
        toast.set_timeout(timeout)
        self.toasts.add_toast(toast)

    # ------------------------------------------------------------------
    # ByeDPI
    # ------------------------------------------------------------------
    def toggle_dpi(self) -> None:
        if self.dpi_state == DPI_OFF:
            self.start_dpi()
        else:
            self.stop_dpi()

    def _dpi_argv(self) -> list[str]:
        binary = find_ciadpi()
        if binary is None:
            raise RuntimeError("Не найден ciadpi. Соберите движки: ./linux/build.sh")
        cmd = substitute_lists(str(self.settings.get("cmd_args")), self._list_domains)
        return build_ciadpi_argv(
            str(binary), cmd, str(self.settings.get("proxy_ip")), int(self.settings.get("proxy_port")),
            bool(self.settings.get("http_connect")),
        )

    def _list_domains(self, name: str) -> list[str] | None:
        item = self.lists.get_by_name(name)
        return item.get("domains") if item else None

    def start_dpi(self) -> None:
        self._set_dpi_message(None, "")
        try:
            argv = self._dpi_argv()
        except ValueError as exc:
            self._set_dpi_message("error", f"Ошибка в командной строке: {exc}")
            return
        except RuntimeError as exc:
            self._set_dpi_message("error", str(exc))
            return

        ip, port = cmd_address(shlex.join(argv[1:]), str(self.settings.get("proxy_ip")),
                               int(self.settings.get("proxy_port")))
        self.dpi_address = (ip, port)
        if not port_is_free(ip, port):
            self._set_dpi_message("error", f"Порт {port} уже занят. Закройте другую копию ByeDPI или смените порт в настройках.")
            return
        try:
            self.dpi.start(argv)
        except OSError as exc:
            self._set_dpi_message("error", f"Не удалось запустить ciadpi: {exc}")
            return

        self._dpi_gen += 1
        gen = self._dpi_gen
        self.dpi_state = DPI_STARTING
        self.refresh_dpi_ui()
        threading.Thread(target=self._wait_dpi_ready, args=(gen, connect_host(ip), port), daemon=True).start()

    def _wait_dpi_ready(self, gen: int, host: str, port: int) -> None:
        ok = wait_for_port(host, port, 4.0, alive=lambda: self.dpi.running)
        GLib.idle_add(self._dpi_ready_result, gen, ok)

    def _dpi_ready_result(self, gen: int, ok: bool) -> bool:
        if gen != self._dpi_gen or self.dpi_state != DPI_STARTING:
            return False
        if not ok:
            reason = self.dpi.failure_reason() or "не ответил на порту"
            self.dpi.stop()
            self.dpi_state = DPI_OFF
            self._set_dpi_message("error", f"ByeDPI не запустился: {reason}")
            self.refresh_dpi_ui()
            return False
        self.dpi_state = DPI_ON
        self.refresh_dpi_ui()
        if self.settings.get("mode") == "system":
            threading.Thread(target=self._apply_system_proxy, daemon=True).start()
        return False

    def _apply_system_proxy(self) -> None:
        host, port = self.dpi_address
        try:
            # если резервная копия уже есть (перезапуск) — не затираем исходные значения
            backup = self.settings.get("system_proxy_backup")
            fresh = system_proxy.enable(connect_host(host), port, bool(self.settings.get("http_connect")))
            if not backup:
                self.settings.set("system_proxy_backup", fresh)
            GLib.idle_add(self._set_dpi_message, "ok", "Системный прокси включён")
        except Exception as exc:  # noqa: BLE001 — показываем любую причину
            GLib.idle_add(self._set_dpi_message, "wait",
                          f"ByeDPI работает, но системный прокси не настроен: {exc}")

    def _restore_system_proxy(self) -> None:
        backup = self.settings.get("system_proxy_backup")
        if not backup:
            return
        try:
            system_proxy.restore(backup)
        except Exception as exc:  # noqa: BLE001
            self._set_dpi_message("error", f"Не удалось вернуть системный прокси: {exc}")
            return
        self.settings.set("system_proxy_backup", None)

    def _recover_system_proxy(self) -> None:
        """Прошлый запуск завершился аварийно и оставил системный прокси включённым."""
        if self.settings.get("system_proxy_backup"):
            self._restore_system_proxy()
            GLib.idle_add(lambda: self.toast("Системный прокси восстановлен после прошлого запуска") and False)

    def stop_dpi(self, quiet: bool = False) -> None:
        self._dpi_gen += 1
        self._restore_system_proxy()
        self.dpi.stop()
        self.dpi_state = DPI_OFF
        if not quiet:
            self._set_dpi_message(None, "")
        self.refresh_dpi_ui()

    def restart_dpi_if_running(self) -> bool:
        if self.dpi_state == DPI_OFF:
            return False
        self.stop_dpi(quiet=True)
        self.start_dpi()
        return True

    def _set_dpi_message(self, kind: str | None, text: str) -> bool:
        ui.set_message(self.dpi_message, kind, text)
        return False

    def refresh_dpi_ui(self) -> None:
        mode = "Системный прокси" if self.settings.get("mode") == "system" else "Прокси"
        if self.dpi_state == DPI_ON:
            title = f"Подключено ({mode})"
            self.power_button.add_css_class("on")
        elif self.dpi_state == DPI_STARTING:
            title = "Подключение…"
            self.power_button.remove_css_class("on")
        else:
            title = f"Отключено ({mode})"
            self.power_button.remove_css_class("on")
        if self.dpi_state == DPI_STARTING:
            self.power_button.add_css_class("busy")
        else:
            self.power_button.remove_css_class("busy")
        self.dpi_title.set_text(title)
        if self.dpi_state == DPI_OFF:
            ip, port = cmd_address(str(self.settings.get("cmd_args")), str(self.settings.get("proxy_ip")),
                                   int(self.settings.get("proxy_port")))
        else:
            ip, port = self.dpi_address
        kind = "HTTP" if self.settings.get("http_connect") else "SOCKS5"
        self.dpi_addr.set_text(f"{ip}:{port} · {kind}")

    # ------------------------------------------------------------------
    # Telegram
    # ------------------------------------------------------------------
    def toggle_tg(self) -> None:
        if self.tg_busy:
            return
        self.tg_user_error = ""      # новая попытка — старая ошибка больше не нужна
        if self.tg.running:
            self.settings.set("tg_enabled", False)
            self._stop_tg_async()
        else:
            self.start_tg()
        self.refresh_tg_ui()

    def start_tg(self) -> None:
        try:
            self.tg.start()
            self.settings.set("tg_enabled", True)
        except (TgProxyError, OSError) as exc:
            self.tg_user_error = str(exc)
        self.refresh_tg_ui()

    def _stop_tg_async(self, then=None) -> None:
        self.tg_busy = True
        self.refresh_tg_ui()

        def work():
            self.tg.stop()
            GLib.idle_add(done)

        def done():
            self.tg_busy = False
            self.refresh_tg_ui()
            if then:
                then()
            return False

        threading.Thread(target=work, daemon=True).start()

    def restart_tg_if_running(self) -> bool:
        if not self.tg.running:
            return False
        self._stop_tg_async(then=self.start_tg)
        return True

    def refresh_tg_ui(self) -> None:
        state, error = self.tg.state()
        if self.tg_user_error:
            state, error = ERROR, self.tg_user_error

        active = self.tg.running
        for css, on in (("active", active), ("attention", False)):
            (self.tg_tile.add_css_class if on else self.tg_tile.remove_css_class)(css)
        self.tg_tile.set_sensitive(not self.tg_busy)
        self.tg_tile_label.set_text("TG WS Прокси\nвключён" if active else "TG WS Прокси")
        self.connect_tile.remove_css_class("attention")
        self.tg_copy.set_visible(False)
        self.tg_hint.set_visible(False)

        if self.tg_busy:
            ui.set_message(self.tg_title, None, "Telegram: остановка прокси…")
        elif state == ERROR:
            ui.set_message(self.tg_title, "error", error)
        elif state == OFF:
            ui.set_message(self.tg_title, None, "Telegram WS: выключено")
            self.tg_title.add_css_class("dim-label")
            return
        elif state == WAITING:
            ui.set_message(self.tg_title, "wait", f"Telegram: прокси запущен на 127.0.0.1:{self.tg.port} и ждёт подключения")
            self.tg_hint.set_text("Добавьте прокси в Telegram: нажмите «Подключить прокси в Telegram» "
                                  "или скопируйте ссылку и откройте её в Telegram.")
            self.tg_hint.set_visible(True)
            self.tg_copy.set_visible(True)
            self.connect_tile.add_css_class("attention")
        elif state == CONNECTED:
            ui.set_message(self.tg_title, "ok", f"Telegram подключён · {self.tg.traffic_text()}")
            self.tg_copy.set_visible(True)
        self.tg_title.remove_css_class("dim-label")

    def copy_tg_link(self) -> None:
        if not self.tg.running:
            self.toast("Сначала включите «TG WS Прокси»")
            return
        ui.copy_text(self, self.tg.link(https=True))
        self.toast("Ссылка скопирована — откройте её в Telegram")

    def connect_telegram(self) -> None:
        if self.tg.running:
            link = self.tg.link()
            ui.copy_text(self, self.tg.link(https=True))
            if ui.has_handler("tg") and ui.open_uri(link):
                self.toast("Подтвердите добавление прокси в Telegram (ссылка также скопирована)")
            elif ui.open_uri(self.tg.link(https=True)):
                self.toast("Telegram Desktop не найден — ссылка открыта в браузере и скопирована")
            else:
                self.toast("Ссылка скопирована — вставьте её в любой чат Telegram и нажмите на неё")
        elif self.dpi_state == DPI_ON:
            # Как в Android: без TG WS предлагаем Telegram SOCKS5 самого ByeDPI
            host, port = self.dpi_address
            link = socks_link(host, port)
            ui.copy_text(self, link)
            if not (ui.has_handler("tg") and ui.open_uri(link)):
                self.toast("Ссылка SOCKS5 скопирована — откройте её в Telegram")
        else:
            self.toast("Сначала включите «TG WS Прокси»")

    # ------------------------------------------------------------------
    # Общее
    # ------------------------------------------------------------------
    def _tick(self) -> bool:
        if self.dpi_state != DPI_OFF and self.dpi.died_unexpectedly:
            reason = self.dpi.failure_reason()
            self._restore_system_proxy()
            self.dpi_state = DPI_OFF
            self._set_dpi_message("error", f"ByeDPI неожиданно завершился: {reason}")
            self.refresh_dpi_ui()
        if not self.tg_busy:
            self.refresh_tg_ui()
        return True

    def auto_connect(self) -> None:
        if not self.settings.get("auto_connect"):
            return
        if self.dpi_state == DPI_OFF:
            self.start_dpi()
        if self.settings.get("tg_enabled") and not self.tg.running:
            self.start_tg()

    def shutdown(self) -> None:
        tester = getattr(self, "tester", None)
        if tester is not None:
            tester.stop()
        self._restore_system_proxy()
        self.dpi.stop()
        self.tg.stop()

    def set_autostart(self, enabled: bool) -> None:
        path = AUTOSTART_DIR / f"{APP_ID}.desktop"
        if not enabled:
            path.unlink(missing_ok=True)
            return
        AUTOSTART_DIR.mkdir(parents=True, exist_ok=True)
        path.write_text(
            "[Desktop Entry]\n"
            "Type=Application\n"
            f"Name={APP_NAME}\n"
            f"Exec={sys.executable} {GLib.shell_quote(str(APP_DIR / 'app.py'))} --autostart\n"
            f"Icon={APP_ID}\n"
            "X-GNOME-Autostart-enabled=true\n"
            "X-GNOME-Autostart-Delay=5\n"
            "Terminal=false\n",
            encoding="utf-8",
        )


class ByeByeDPIApp(Adw.Application):
    def __init__(self, autostart: bool):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.autostart = autostart
        self.window: MainWindow | None = None
        self._dark_provider: Gtk.CssProvider | None = None

    def do_startup(self):
        Adw.Application.do_startup(self)
        display = Gdk.Display.get_default()
        Gtk.IconTheme.get_for_display(display).add_search_path(str(APP_DIR / "icons"))

        provider = Gtk.CssProvider()
        provider.load_from_path(str(APP_DIR / "style.css"))
        Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self._dark_provider = Gtk.CssProvider()
        self._dark_provider.load_from_path(str(APP_DIR / "style-dark.css"))
        style = Adw.StyleManager.get_default()
        style.connect("notify::dark", lambda *_: self._sync_dark())
        self.apply_theme(Settings().get("theme"))

        for name, callback in (
            ("about", self._on_about),
            ("logs-dpi", lambda *_: self.window and self.window.open_page("logs-dpi")),
            ("logs-tg", lambda *_: self.window and self.window.open_page("logs-tg")),
            ("quit", lambda *_: self.quit()),
        ):
            action = Gio.SimpleAction.new(name, None)
            action.connect("activate", callback)
            self.add_action(action)
        self.set_accels_for_action("app.quit", ["<Control>q"])

    def apply_theme(self, theme: str) -> None:
        scheme = {"light": Adw.ColorScheme.FORCE_LIGHT, "dark": Adw.ColorScheme.FORCE_DARK}.get(
            theme, Adw.ColorScheme.DEFAULT)
        Adw.StyleManager.get_default().set_color_scheme(scheme)
        self._sync_dark()

    def _sync_dark(self) -> None:
        display = Gdk.Display.get_default()
        if Adw.StyleManager.get_default().get_dark():
            Gtk.StyleContext.add_provider_for_display(display, self._dark_provider,
                                                      Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
        else:
            Gtk.StyleContext.remove_provider_for_display(display, self._dark_provider)

    def do_activate(self):
        first = self.window is None
        if first:
            self.window = MainWindow(self)
        self.window.present()
        if first:
            self.window.auto_connect()
            if self.autostart:
                GLib.timeout_add(300, lambda: self.window.minimize() and False)

    def do_shutdown(self):
        if self.window is not None:
            self.window.shutdown()
        Adw.Application.do_shutdown(self)

    def _on_about(self, *_):
        kwargs = dict(
            application_name=APP_NAME,
            application_icon=APP_ID,
            version=APP_VERSION,
            developer_name="EwenLoy, romanvht, hufrea и участники",
            website="https://github.com/EwenLoy/ByeByeDPI-x-tg",
            issue_url="https://github.com/EwenLoy/ByeByeDPI-x-tg/issues",
            license_type=Gtk.License.GPL_3_0,
            comments="Обход DPI (ByeDPI) и ускорение Telegram через WebSocket-прокси. Linux-версия.",
        )
        if (Adw.get_major_version(), Adw.get_minor_version()) >= (1, 5):
            Adw.AboutDialog(**kwargs).present(self.window)
        else:
            Adw.AboutWindow(transient_for=self.window, **kwargs).present()


def main() -> int:
    if (Adw.get_major_version(), Adw.get_minor_version()) < MIN_ADW:
        print(f"Нужен libadwaita >= {MIN_ADW[0]}.{MIN_ADW[1]} (Fedora 39 и новее).", file=sys.stderr)
        return 1
    autostart = "--autostart" in sys.argv
    argv = [arg for arg in sys.argv if arg != "--autostart"]
    app = ByeByeDPIApp(autostart)
    # Ctrl+C в терминале тоже корректно останавливает прокси и возвращает настройки
    for sig in (signal.SIGINT, signal.SIGTERM):
        GLib.unix_signal_add(GLib.PRIORITY_DEFAULT, sig, lambda *_: (app.quit(), False)[1])
    return app.run(argv)


if __name__ == "__main__":
    sys.exit(main())
