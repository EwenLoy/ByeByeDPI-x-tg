"""Вторичные экраны: редактор, настройки, подбор стратегий, списки, логи."""
from __future__ import annotations

import ipaddress
import shlex
import threading

from gi.repository import Adw, GLib, Gtk

import system_proxy
import ui_common as ui
from storage import APP_VERSION, DPI_KEYS, TG_KEYS, find_ciadpi, load_strategies, load_test_results, parse_domains, save_test_results
from strategy_tester import StrategyResult, StrategyTester, sort_results
from tg_proxy import diagnose

SOURCE_URL = "https://github.com/EwenLoy/ByeByeDPI-x-tg"
BYEDPI_URL = "https://github.com/hufrea/byedpi"


class Page(Adw.NavigationPage):
    """Экран с заголовком и стрелкой «назад» — аналог Activity."""

    def __init__(self, win, title: str, content: Gtk.Widget | None = None,
                 end_widgets: list[Gtk.Widget] | None = None):
        super().__init__(title=title)
        self.win = win
        header = Adw.HeaderBar()
        for widget in end_widgets or []:
            header.pack_end(widget)
        self._view = Adw.ToolbarView()
        self._view.add_top_bar(header)
        if content is not None:
            self._view.set_content(content)
        self.set_child(self._view)

    def set_content(self, content: Gtk.Widget) -> None:
        self._view.set_content(content)

    def on_closed(self) -> None:
        """Вызывается, когда пользователь ушёл с экрана назад."""


def column(spacing: int = 12, margin: int = 16) -> Gtk.Box:
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=spacing)
    for side in ("top", "bottom", "start", "end"):
        getattr(box, f"set_margin_{side}")(margin)
    return box


def suffix_button(icon_name: str, tooltip: str, callback) -> Gtk.Button:
    button = Gtk.Button(icon_name=icon_name, tooltip_text=tooltip, valign=Gtk.Align.CENTER)
    button.add_css_class("flat")
    button.connect("clicked", lambda *_: callback())
    return button


def nav_row(title: str, subtitle: str, callback) -> Adw.ActionRow:
    row = Adw.ActionRow(title=title, subtitle=subtitle, activatable=True)
    row.add_suffix(Gtk.Image.new_from_icon_name("go-next-symbolic"))
    row.connect("activated", lambda *_: callback())
    return row


def switch_row(title: str, subtitle: str, active: bool, on_change) -> Adw.SwitchRow:
    row = Adw.SwitchRow(title=title, subtitle=subtitle, active=active)
    row.connect("notify::active", lambda r, _p: on_change(r.get_active()))
    return row


def spin_row(title: str, subtitle: str, low: int, high: int, value: int, on_change) -> Adw.SpinRow:
    row = Adw.SpinRow.new_with_range(low, high, 1)
    row.set_title(title)
    if subtitle:
        row.set_subtitle(subtitle)
    row.set_value(value)
    row.connect("notify::value", lambda r, _p: on_change(int(r.get_value())))
    return row


def normalize_cmd(text: str) -> str:
    return " ".join(text.split())


# ---------------------------------------------------------------------------
# Редактор командной строки
# ---------------------------------------------------------------------------

class EditorPage(Page):
    def __init__(self, win):
        super().__init__(win, "Редактор командной строки")
        self.saved = normalize_cmd(str(win.settings.get("cmd_args")))

        box = column()
        box.append(ui.label("Аргументы ciadpi", "heading", xalign=0))
        self.view = ui.text_view(self.saved, "cmd-view", height=150)
        box.append(ui.framed(self.view))
        box.append(ui.label("Подсказка: {list:Имя} подставит домены из списка, например "
                            "-H \":{list:Discord}\". Адрес и порт берутся из настроек, если не заданы в строке.",
                            "dim-label", "caption", xalign=0))

        buttons = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER)
        save = Gtk.Button(label="Сохранить")
        save.add_css_class("suggested-action")
        save.add_css_class("pill")
        save.connect("clicked", lambda *_: self.save(show_toast=True))
        paste = Gtk.Button(label="Вставить")
        paste.add_css_class("pill")
        paste.connect("clicked", lambda *_: ui.paste_text(self, self._paste))
        clear = Gtk.Button(label="Очистить")
        clear.add_css_class("pill")
        clear.connect("clicked", lambda *_: self.view.get_buffer().set_text(""))
        for widget in (save, paste, clear):
            buttons.append(widget)
        box.append(buttons)

        self.status = ui.label("", "message")
        self.status.set_visible(False)
        box.append(self.status)

        box.append(ui.label("История команд", "heading", xalign=0))
        box.append(ui.label("Нажмите на команду, чтобы подставить её в редактор.", "dim-label", "caption", xalign=0))
        self.history_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.history_list.add_css_class("boxed-list")
        box.append(self.history_list)
        self.set_content(ui.scrolled(box))
        self.refresh_history()

    def _paste(self, text: str) -> None:
        self.view.get_buffer().set_text(normalize_cmd(text))

    def save(self, show_toast: bool) -> bool:
        cmd = normalize_cmd(ui.view_text(self.view))
        try:
            shlex.split(cmd)
        except ValueError as exc:
            ui.set_message(self.status, "error", f"Ошибка в строке: {exc}")
            return False
        ui.set_message(self.status, None, "")
        changed = cmd != self.saved
        self.saved = cmd
        self.win.settings.set("cmd_args", cmd)
        self.win.history.add(cmd)
        self.refresh_history()
        self.win.refresh_dpi_ui()
        if changed and self.win.restart_dpi_if_running():
            self.win.toast("Сервис перезапущен с новыми аргументами")
        elif show_toast:
            self.win.toast("Сохранено")
        return True

    def on_closed(self) -> None:
        if normalize_cmd(ui.view_text(self.view)) != self.saved:
            self.save(show_toast=False)

    def refresh_history(self) -> None:
        while (child := self.history_list.get_first_child()) is not None:
            self.history_list.remove(child)
        items = self.win.history.all()
        if not items:
            empty = Adw.ActionRow(title="История пуста")
            empty.add_css_class("dim-label")
            self.history_list.append(empty)
            return
        for item in items:
            command = item["text"]
            row = Adw.ActionRow(title=command, activatable=True)
            row.set_use_markup(False)
            row.set_title_lines(4)
            if item.get("pinned"):
                row.add_prefix(ui.icon("bbdpi-pin-symbolic", 16))
            row.connect("activated", lambda _r, c=command: self.view.get_buffer().set_text(c))
            pinned = bool(item.get("pinned"))
            row.add_suffix(suffix_button("bbdpi-pin-symbolic", "Открепить" if pinned else "Закрепить",
                                         lambda c=command, p=pinned: self._pin(c, not p)))
            row.add_suffix(suffix_button("edit-copy-symbolic", "Копировать",
                                         lambda c=command: (ui.copy_text(self, c), self.win.toast("Скопировано"))))
            row.add_suffix(suffix_button("user-trash-symbolic", "Удалить", lambda c=command: self._delete(c)))
            self.history_list.append(row)

    def _pin(self, command: str, pinned: bool) -> None:
        self.win.history.set_pinned(command, pinned)
        self.refresh_history()

    def _delete(self, command: str) -> None:
        self.win.history.delete(command)
        self.refresh_history()


# ---------------------------------------------------------------------------
# Настройки
# ---------------------------------------------------------------------------

THEMES = [("system", "Системная"), ("light", "Светлая"), ("dark", "Тёмная")]
MODES = [("proxy", "Прокси"), ("system", "Системный прокси")]


class SettingsPage(Page):
    def __init__(self, win):
        super().__init__(win, "Настройки")
        s = win.settings
        self.snapshot = {key: s.get(key) for key in (*DPI_KEYS, *TG_KEYS)}
        prefs = Adw.PreferencesPage()

        # --- Общие ---------------------------------------------------------
        general = Adw.PreferencesGroup(title="Общие")
        theme = Adw.ComboRow(title="Тема", model=Gtk.StringList.new([t for _, t in THEMES]))
        theme.set_selected([k for k, _ in THEMES].index(s.get("theme")) if s.get("theme") in dict(THEMES) else 0)
        theme.connect("notify::selected", self._on_theme)
        general.add(theme)

        self.mode = Adw.ComboRow(title="Режим", model=Gtk.StringList.new([t for _, t in MODES]))
        self.mode.set_selected(1 if s.get("mode") == "system" else 0)
        self.mode.connect("notify::selected", self._on_mode)
        general.add(self.mode)
        self._update_mode_subtitle()
        prefs.add(general)

        # --- Автоматизация -------------------------------------------------
        auto = Adw.PreferencesGroup(title="Автоматизация")
        auto.add(switch_row("Автозапуск приложения при входе в систему", "", bool(s.get("autostart")),
                            self._on_autostart))
        auto.add(switch_row("Автоматическое подключение при открытии ByeByeDPI",
                            "Включает ByeDPI и, если он был включён, TG WS прокси",
                            bool(s.get("auto_connect")), lambda v: s.set("auto_connect", v)))
        prefs.add(auto)

        # --- ByeDPI --------------------------------------------------------
        byedpi = Adw.PreferencesGroup(title="ByeDPI")
        byedpi.add(nav_row("Редактор командной строки", "Аргументы ciadpi и история команд",
                           lambda: win.open_page("editor")))
        byedpi.add(nav_row("Подбор стратегий (Beta)", "Тестирование работы подготовленных аргументов командной строки",
                           lambda: win.open_page("test")))
        prefs.add(byedpi)

        # --- Прокси --------------------------------------------------------
        proxy = Adw.PreferencesGroup(title="Прокси")
        self.ip_row = Adw.EntryRow(title="Прослушиваемый IP", text=str(s.get("proxy_ip")))
        self.ip_row.connect("changed", self._on_ip)
        proxy.add(self.ip_row)
        proxy.add(spin_row("Порт", "", 1, 65535, int(s.get("proxy_port")), lambda v: s.set("proxy_port", v)))
        proxy.add(switch_row("HTTP прокси", "ByeDPI будет принимать HTTP CONNECT вместо SOCKS5 "
                             "(HTTPS или любой TCP трафик, кроме обычного HTTP)",
                             bool(s.get("http_connect")), lambda v: s.set("http_connect", v)))
        prefs.add(proxy)

        # --- Telegram ------------------------------------------------------
        tg = Adw.PreferencesGroup(title="Telegram",
                                  description="WebSocket-прокси для Telegram (MTProto). Работает независимо от ByeDPI.")
        tg.add(spin_row("Порт TG WS", "В Android-версии — 1082", 1024, 65535, int(s.get("tg_port")),
                        lambda v: s.set("tg_port", v)))
        tg.add(switch_row("CloudFlare CDN", "Маршрутизация через CF WebSocket-домены (стабильнее в некоторых сетях)",
                          bool(s.get("tg_cf_enabled")), self._on_cf))
        self.cf_domain = Adw.EntryRow(title="Свой CF-домен (необязательно)", text=str(s.get("tg_cf_domain") or ""))
        self.cf_domain.connect("changed", lambda r: s.set("tg_cf_domain", r.get_text().strip()))
        tg.add(self.cf_domain)
        self.dc_ips = Adw.EntryRow(title="IP дата-центров, напр. 2:149.154.167.220,4:149.154.167.220",
                                   text=str(s.get("tg_dc_ips") or ""))
        self.dc_ips.connect("changed", lambda r: s.set("tg_dc_ips", r.get_text().strip()))
        tg.add(self.dc_ips)
        self._on_cf(bool(s.get("tg_cf_enabled")), save=False)
        tg.add(spin_row("WS Pool размер", "Заранее открытые WebSocket-соединения (больше — быстрее первое подключение)",
                        2, 16, int(s.get("tg_pool_size")), lambda v: s.set("tg_pool_size", v)))

        self.secret_row = Adw.ActionRow(title="Секрет MTProto")
        self.secret_row.add_suffix(suffix_button("edit-copy-symbolic", "Копировать секрет", self._copy_secret))
        self.secret_row.add_suffix(suffix_button("view-refresh-symbolic", "Сгенерировать новый", self._new_secret))
        self._update_secret()
        tg.add(self.secret_row)
        tg.add(nav_row("Логи TG WS", "События ядра Telegram-прокси", lambda: win.open_page("logs-tg")))
        prefs.add(tg)

        # --- О программе ---------------------------------------------------
        about = Adw.PreferencesGroup(title="О программе")
        about.add(Adw.ActionRow(title="Версия", subtitle=APP_VERSION))
        about.add(nav_row("Исходный код", SOURCE_URL, lambda: ui.open_uri(SOURCE_URL)))
        about.add(nav_row("ByeDPI", BYEDPI_URL, lambda: ui.open_uri(BYEDPI_URL)))
        prefs.add(about)

        self.set_content(prefs)

    # -- обработчики -------------------------------------------------------
    def _on_theme(self, row, _pspec) -> None:
        key = THEMES[row.get_selected()][0]
        self.win.settings.set("theme", key)
        self.win.get_application().apply_theme(key)

    def _on_mode(self, row, _pspec) -> None:
        self.win.settings.set("mode", MODES[row.get_selected()][0])
        self._update_mode_subtitle()
        self.win.refresh_dpi_ui()

    def _update_mode_subtitle(self) -> None:
        if self.win.settings.get("mode") == "system":
            self.mode.set_subtitle(system_proxy.describe())
        else:
            self.mode.set_subtitle("Укажите SOCKS5 127.0.0.1:1080 в нужных приложениях (браузер и т.д.)")

    def _on_autostart(self, enabled: bool) -> None:
        try:
            self.win.set_autostart(enabled)
            self.win.settings.set("autostart", enabled)
        except OSError as exc:
            self.win.toast(f"Не удалось настроить автозапуск: {exc}")

    def _on_ip(self, row) -> None:
        text = row.get_text().strip()
        try:
            ipaddress.ip_address(text)
        except ValueError:
            row.add_css_class("error")
            return
        row.remove_css_class("error")
        self.win.settings.set("proxy_ip", text)

    def _on_cf(self, enabled: bool, save: bool = True) -> None:
        if save:
            self.win.settings.set("tg_cf_enabled", enabled)
        self.cf_domain.set_sensitive(enabled)
        self.dc_ips.set_sensitive(not enabled)

    def _update_secret(self) -> None:
        secret = self.win.settings.ensure_tg_secret()
        self.secret_row.set_subtitle(f"dd{secret[:6]}…{secret[-4:]}")

    def _copy_secret(self) -> None:
        ui.copy_text(self, "dd" + self.win.settings.ensure_tg_secret())
        self.win.toast("Секрет скопирован в буфер")

    def _new_secret(self) -> None:
        self.win.settings.regenerate_tg_secret()
        self._update_secret()
        self.win.toast("Новый секрет: прокси нужно заново добавить в Telegram")

    def on_closed(self) -> None:
        s = self.win.settings
        self.win.refresh_dpi_ui()
        restarted = []
        if any(s.get(k) != self.snapshot[k] for k in DPI_KEYS) and self.win.restart_dpi_if_running():
            restarted.append("ByeDPI")
        if any(s.get(k) != self.snapshot[k] for k in TG_KEYS) and self.win.restart_tg_if_running():
            restarted.append("TG WS")
        if restarted:
            self.win.toast("Перезапущено с новыми настройками: " + ", ".join(restarted))


# ---------------------------------------------------------------------------
# Подбор стратегий
# ---------------------------------------------------------------------------

class ResultCard(Gtk.Box):
    def __init__(self, page: "TestPage", result: StrategyResult):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        self.add_css_class("result-card")
        self.page = page
        self.result = result

        self.command = ui.label(result.command, xalign=0)
        self.append(self.command)

        bar_row = Gtk.Box(spacing=12)
        self.bar = Gtk.ProgressBar(hexpand=True, valign=Gtk.Align.CENTER)
        self.count = ui.label("", "result-count", wrap=False)
        bar_row.append(self.bar)
        bar_row.append(self.count)
        self.append(bar_row)

        self.error = ui.label("", "message", xalign=0)
        self.append(self.error)

        self.details = ui.label("", "caption", xalign=0, selectable=True)
        self.expander = Gtk.Expander(label="Показать детали", child=self.details)
        self.expander.connect("notify::expanded", lambda e, _p: e.set_label(
            "Скрыть детали" if e.get_expanded() else "Показать детали"))
        self.append(self.expander)

        actions = Gtk.Box(spacing=6, halign=Gtk.Align.END)
        self.apply = Gtk.Button(label="Применить")
        self.apply.add_css_class("flat")
        self.apply.connect("clicked", lambda *_: page.apply(self.result.command))
        copy = Gtk.Button(label="Копировать")
        copy.add_css_class("flat")
        copy.connect("clicked", lambda *_: (ui.copy_text(self, self.result.command), page.win.toast("Скопировано")))
        actions.append(copy)
        actions.append(self.apply)
        self.append(actions)
        self.update()

    def update(self) -> None:
        r = self.result
        if r.total:
            shown = r.success if r.completed else r.progress
            self.bar.set_fraction((r.success / r.total) if r.completed else (r.progress / r.total))
            self.count.set_text(f"{r.success}/{r.total}" if r.completed or r.progress == r.total
                                else f"{shown}/{r.total}…")
        else:
            self.bar.set_fraction(0)
            self.count.set_text("—")
        ui.set_message(self.error, "error" if r.error else None, r.error)
        sites = sorted(r.sites, key=lambda x: (x["ok"] == x["n"], x["site"]))
        self.details.set_text("\n".join(
            f"{'✓' if s['ok'] == s['n'] else '✗'}  {s['site']}  {s['ok']}/{s['n']}" for s in sites
        ) or "Нет данных")
        self.apply.set_sensitive(not self.page.testing)


class TestPage(Page):
    def __init__(self, win):
        gear = Gtk.Button(icon_name="bbdpi-gear-symbolic", tooltip_text="Настройки подбора")
        gear.connect("clicked", lambda *_: self._open_settings())
        super().__init__(win, "Подбор стратегий (Beta)", end_widgets=[gear])
        self.testing = False
        self.cards: list[ResultCard] = []
        self.tester: StrategyTester | None = None

        box = column(spacing=10)
        self.start_button = Gtk.Button(label="НАЧАТЬ ПРОВЕРКУ", halign=Gtk.Align.CENTER)
        for css in ("pill", "suggested-action", "pill-wide"):
            self.start_button.add_css_class(css)
        self.start_button.connect("clicked", lambda *_: self.toggle())
        self.tg_button = Gtk.Button(label="ПРОВЕРИТЬ TG WS", halign=Gtk.Align.CENTER)
        for css in ("pill", "pill-wide"):
            self.tg_button.add_css_class(css)
        self.tg_button.connect("clicked", lambda *_: self.check_tg())
        self.progress = ui.label("", "dim-label")
        self.tg_report = ui.label("", "message", xalign=0, selectable=True)
        self.tg_report.set_visible(False)
        for widget in (self.start_button, self.tg_button, self.progress, self.tg_report):
            box.append(widget)

        self.list_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        box.append(self.list_box)
        self.set_content(ui.scrolled(box))

        previous = [StrategyResult.from_dict(d) for d in load_test_results() if isinstance(d, dict)]
        if previous:
            self.progress.set_text("Проверка завершена")
            self.show_results(sort_results(previous))
        else:
            self.progress.set_text(
                "Проверка перебирает готовые стратегии и проверяет сайты из выбранных списков. "
                "Основное подключение при этом не прерывается.")

    def _open_settings(self) -> None:
        if self.testing:
            self.win.toast("Настройки недоступны во время проверки")
        else:
            self.win.open_page("test-settings")

    def show_results(self, results: list[StrategyResult]) -> None:
        while (child := self.list_box.get_first_child()) is not None:
            self.list_box.remove(child)
        self.cards = [ResultCard(self, r) for r in results]
        for card in self.cards:
            self.list_box.append(card)

    def toggle(self) -> None:
        if self.testing:
            self.progress.set_text("Остановка…")
            if self.tester:
                self.tester.stop()
            return
        self.start()

    def start(self) -> None:
        s = self.win.settings
        ciadpi = find_ciadpi()
        if ciadpi is None:
            self.progress.set_text("Не найден ciadpi — запустите ./linux/build.sh")
            return
        sites = self.win.lists.active_domains()
        if not sites:
            self.win.toast("Выберите хотя бы один список доменов")
            return
        commands = load_strategies(s)
        if not commands:
            self.win.toast("Список стратегий пуст")
            return

        self.tester = StrategyTester(
            str(ciadpi),
            on_update=lambda i, r: GLib.idle_add(self._on_update, i),
            on_progress=lambda text: GLib.idle_add(self.progress.set_text, text),
            on_finished=lambda results, stopped: GLib.idle_add(self._on_finished, stopped),
        )
        try:
            self.tester.start(commands, sites, int(s.get("test_requests")), int(s.get("test_timeout")),
                              int(s.get("test_limit")), int(s.get("test_delay")))
        except RuntimeError as exc:
            self.progress.set_text(str(exc))
            return
        self.win.tester = self.tester
        self.testing = True
        self.start_button.set_label("ОСТАНОВИТЬ")
        self.start_button.remove_css_class("suggested-action")
        self.start_button.add_css_class("destructive-action")
        self.show_results(self.tester.results)

    def _on_update(self, index: int) -> bool:
        if 0 <= index < len(self.cards):
            self.cards[index].update()
        return False

    def _on_finished(self, stopped: bool) -> bool:
        self.testing = False
        self.win.tester = None
        self.start_button.set_label("НАЧАТЬ ПРОВЕРКУ")
        self.start_button.remove_css_class("destructive-action")
        self.start_button.add_css_class("suggested-action")
        results = sort_results(self.tester.results if self.tester else [])
        save_test_results([r.to_dict() for r in results if r.completed])
        self.progress.set_text("Проверка остановлена" if stopped else "Проверка завершена")
        self.show_results(results)
        return False

    def apply(self, command: str) -> None:
        self.win.settings.set("cmd_args", command)
        self.win.history.add(command)
        self.win.refresh_dpi_ui()
        if self.win.restart_dpi_if_running():
            self.win.toast("Стратегия применена, сервис перезапущен")
        else:
            self.win.toast("Стратегия применена — включите ByeDPI на главном экране")

    def check_tg(self) -> None:
        self.tg_button.set_sensitive(False)
        ui.set_message(self.tg_report, None, "Проверка TG WS…")

        def work():
            ok, lines = diagnose(self.win.tg)
            GLib.idle_add(done, ok, lines)

        def done(ok, lines):
            text = "\n".join(("✓ " if good else "✗ ") + line for good, line in lines)
            ui.set_message(self.tg_report, "ok" if ok else "error", text)
            self.tg_button.set_sensitive(True)
            self.win.toast("TG WS туннель работает" if ok else "TG WS: есть проблемы, см. отчёт")
            return False

        threading.Thread(target=work, daemon=True).start()

    def on_closed(self) -> None:
        if self.testing and self.tester:
            self.tester.stop()


class TestSettingsPage(Page):
    def __init__(self, win):
        super().__init__(win, "Настройки подбора")
        s = win.settings
        prefs = Adw.PreferencesPage()

        params = Adw.PreferencesGroup(title="Параметры")
        params.add(spin_row("Задержка между стратегиями, с", "", 0, 10, int(s.get("test_delay")),
                            lambda v: s.set("test_delay", v)))
        params.add(spin_row("Запросов на сайт", "", 1, 10, int(s.get("test_requests")),
                            lambda v: s.set("test_requests", v)))
        params.add(spin_row("Одновременных запросов", "", 1, 50, int(s.get("test_limit")),
                            lambda v: s.set("test_limit", v)))
        params.add(spin_row("Таймаут запроса, с", "", 1, 30, int(s.get("test_timeout")),
                            lambda v: s.set("test_timeout", v)))
        sni = Adw.EntryRow(title="SNI для стратегий ({sni})", text=str(s.get("test_sni")))
        sni.connect("changed", lambda r: s.set("test_sni", r.get_text().strip() or "google.com"))
        params.add(sni)
        prefs.add(params)

        domains = Adw.PreferencesGroup(title="Домены")
        domains.add(nav_row("Списки доменов", "Какие сайты проверять", lambda: win.open_page("lists")))
        prefs.add(domains)

        commands = Adw.PreferencesGroup(title="Стратегии")
        self.commands_view = ui.text_view(str(s.get("test_commands") or ""), "cmd-view", height=180)
        self.commands_view.get_buffer().connect(
            "changed", lambda *_: s.set("test_commands", ui.view_text(self.commands_view)))
        self.commands_frame = ui.framed(self.commands_view)
        self.commands_frame.set_margin_top(12)
        commands.add(switch_row("Свои стратегии", "По одной команде на строку, {sni} заменяется на SNI",
                                bool(s.get("test_user_commands")), self._on_user_commands))
        commands.add(self.commands_frame)
        self.commands_frame.set_visible(bool(s.get("test_user_commands")))
        prefs.add(commands)

        reset = Adw.PreferencesGroup()
        clear = Adw.ActionRow(title="Очистить результаты прошлой проверки", activatable=True)
        clear.connect("activated", lambda *_: (save_test_results([]), win.toast("Результаты очищены")))
        reset.add(clear)
        prefs.add(reset)
        self.set_content(prefs)

    def _on_user_commands(self, enabled: bool) -> None:
        self.win.settings.set("test_user_commands", enabled)
        self.commands_frame.set_visible(enabled)


# ---------------------------------------------------------------------------
# Списки доменов
# ---------------------------------------------------------------------------

class ListsPage(Page):
    def __init__(self, win):
        reset = Gtk.Button(icon_name="view-refresh-symbolic", tooltip_text="Восстановить встроенные списки")
        reset.connect("clicked", lambda *_: self._reset())
        super().__init__(win, "Списки доменов", end_widgets=[reset])
        box = column()

        add_list = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        add_list.add_css_class("boxed-list")
        add_list.append(nav_row("Добавить список", "Создать пользовательский список доменов для тестирования",
                                lambda: win.nav.push(ListEditPage(win, None))))
        box.append(add_list)

        self.rows = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        self.rows.add_css_class("boxed-list")
        box.append(self.rows)
        self.set_content(ui.scrolled(box))
        self.connect("showing", lambda *_: self.refresh())
        self.refresh()

    def refresh(self) -> None:
        while (child := self.rows.get_first_child()) is not None:
            self.rows.remove(child)
        for item in self.win.lists.all():
            domains = item.get("domains", [])
            preview = "\n".join(domains[:5]) + ("\n…" if len(domains) > 5 else "")
            row = Adw.ActionRow(title=item.get("name", item["id"]), subtitle=preview or "пусто")
            row.set_use_markup(False)
            row.set_subtitle_lines(7)
            check = Gtk.CheckButton(active=bool(item.get("active")), valign=Gtk.Align.CENTER)
            check.connect("toggled", lambda c, list_id=item["id"]: self.win.lists.set_active(list_id, c.get_active()))
            row.add_suffix(suffix_button("document-edit-symbolic", "Изменить",
                                         lambda list_id=item["id"]: self.win.nav.push(ListEditPage(self.win, list_id))))
            row.add_suffix(check)
            row.set_activatable_widget(check)
            self.rows.append(row)

    def _reset(self) -> None:
        self.win.lists.reset_to_defaults()
        self.refresh()
        self.win.toast("Встроенные списки восстановлены")


class ListEditPage(Page):
    def __init__(self, win, list_id: str | None):
        item = next((x for x in win.lists.all() if x["id"] == list_id), None) if list_id else None
        super().__init__(win, "Изменить список" if item else "Новый список")
        self.item = item
        box = column()

        group = Gtk.ListBox(selection_mode=Gtk.SelectionMode.NONE)
        group.add_css_class("boxed-list")
        self.name = Adw.EntryRow(title="Название", text=item.get("name", "") if item else "")
        group.append(self.name)
        box.append(group)

        box.append(ui.label("Домены — по одному на строку", "heading", xalign=0))
        self.domains = ui.text_view("\n".join(item.get("domains", [])) if item else "", "cmd-view", height=260)
        box.append(ui.framed(self.domains))

        buttons = Gtk.Box(spacing=8, halign=Gtk.Align.CENTER)
        save = Gtk.Button(label="Сохранить")
        save.add_css_class("suggested-action")
        save.add_css_class("pill")
        save.connect("clicked", lambda *_: self.save())
        buttons.append(save)
        if item and not item.get("builtin"):
            delete = Gtk.Button(label="Удалить")
            delete.add_css_class("destructive-action")
            delete.add_css_class("pill")
            delete.connect("clicked", lambda *_: self.delete())
            buttons.append(delete)
        box.append(buttons)
        self.set_content(ui.scrolled(box))

    def save(self) -> None:
        name = self.name.get_text().strip()
        domains = parse_domains(ui.view_text(self.domains))
        if not name:
            self.win.toast("Укажите название списка")
            return
        if not domains:
            self.win.toast("Добавьте хотя бы один домен")
            return
        if self.item:
            self.win.lists.update(self.item["id"], name, domains)
        elif not self.win.lists.add(name, domains):
            self.win.toast("Список с таким названием уже есть")
            return
        self.win.nav.pop()

    def delete(self) -> None:
        self.win.lists.delete(self.item["id"])
        self.win.nav.pop()


# ---------------------------------------------------------------------------
# Логи
# ---------------------------------------------------------------------------

class LogsPage(Page):
    def __init__(self, win, source: str):
        self.source = source
        copy = Gtk.Button(icon_name="edit-copy-symbolic", tooltip_text="Копировать лог")
        copy.connect("clicked", lambda *_: (ui.copy_text(self, self._text()), win.toast("Лог скопирован")))
        clear = Gtk.Button(icon_name="user-trash-symbolic", tooltip_text="Очистить")
        clear.connect("clicked", lambda *_: (self._process().clear_log(), self._refresh()))
        super().__init__(win, "Логи ByeDPI" if source == "dpi" else "Логи TG WS", end_widgets=[clear, copy])

        self.view = ui.text_view("", "log-view", editable=False, height=200)
        self.view.set_vexpand(True)
        self.scroller = ui.scrolled(self.view)
        self.set_content(self.scroller)
        self._last = None
        self._alive = True
        self._refresh()
        GLib.timeout_add(1000, self._refresh)

    def _process(self):
        return self.win.dpi if self.source == "dpi" else self.win.tg.process

    def _text(self) -> str:
        text = self._process().log_text()
        if text:
            return text
        if self.source == "dpi":
            return "Логов пока нет.\nВключите ByeDPI — сюда попадут ошибки ciadpi."
        return ("Логов пока нет.\nВключите «TG WS Прокси» и подключите Telegram — события появятся здесь.")

    def _refresh(self) -> bool:
        if not self._alive:
            return False
        text = self._text()
        if text != self._last:
            self._last = text
            buffer = self.view.get_buffer()
            buffer.set_text(text)
            adj = self.scroller.get_vadjustment()
            GLib.idle_add(lambda: adj.set_value(adj.get_upper()) or False)
        return True

    def on_closed(self) -> None:
        self._alive = False
