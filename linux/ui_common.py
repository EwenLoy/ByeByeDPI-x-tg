"""Небольшие помощники для GTK-интерфейса."""
from __future__ import annotations

from gi.repository import Adw, Gdk, Gio, GLib, Gtk, Pango

MESSAGE_KINDS = ("error", "ok", "wait")


def icon(name: str, size: int = 32) -> Gtk.Image:
    image = Gtk.Image.new_from_icon_name(name)
    image.set_pixel_size(size)
    return image


def label(text: str = "", *classes: str, wrap: bool = True, xalign: float = 0.5,
          selectable: bool = False) -> Gtk.Label:
    widget = Gtk.Label(label=text, wrap=wrap, xalign=xalign, justify=Gtk.Justification.CENTER
                       if xalign == 0.5 else Gtk.Justification.LEFT)
    widget.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
    widget.set_selectable(selectable)
    for css in classes:
        widget.add_css_class(css)
    return widget


def set_message(widget: Gtk.Label, kind: str | None, text: str) -> None:
    """Показывает сообщение определённого вида; пустой текст скрывает метку."""
    for css in MESSAGE_KINDS:
        widget.remove_css_class(css)
    if kind:
        widget.add_css_class(kind)
    widget.set_text(text)
    widget.set_visible(bool(text))


def tile(icon_name: str, text: str, on_click) -> tuple[Gtk.Button, Gtk.Image, Gtk.Label]:
    """Карточка-кнопка, как на главном экране Android-версии."""
    box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, valign=Gtk.Align.CENTER)
    image = icon(icon_name, 34)
    text_label = label(text)
    box.append(image)
    box.append(text_label)
    button = Gtk.Button(child=box, hexpand=True)
    button.add_css_class("tile")
    button.connect("clicked", lambda *_: on_click())
    return button, image, text_label


def page(title: str, content: Gtk.Widget, tag: str | None = None,
         end_widgets: list[Gtk.Widget] | None = None) -> Adw.NavigationPage:
    """Страница с заголовком и кнопкой «назад» (как Activity в Android)."""
    header = Adw.HeaderBar()
    for widget in end_widgets or []:
        header.pack_end(widget)
    view = Adw.ToolbarView()
    view.add_top_bar(header)
    view.set_content(content)
    nav_page = Adw.NavigationPage(title=title, child=view)
    if tag:
        nav_page.set_tag(tag)
    return nav_page


def scrolled(child: Gtk.Widget) -> Gtk.ScrolledWindow:
    window = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, vexpand=True)
    window.set_child(child)
    return window


def copy_text(widget: Gtk.Widget, text: str) -> None:
    provider = Gdk.ContentProvider.new_for_bytes(
        "text/plain;charset=utf-8", GLib.Bytes.new(text.encode("utf-8"))
    )
    widget.get_clipboard().set_content(provider)


def paste_text(widget: Gtk.Widget, callback) -> None:
    clipboard = widget.get_clipboard()

    def done(source, result):
        try:
            text = source.read_text_finish(result)
        except GLib.Error:
            text = None
        if text:
            callback(text)

    clipboard.read_text_async(None, done)


def open_uri(uri: str) -> bool:
    try:
        return Gio.AppInfo.launch_default_for_uri(uri, None)
    except GLib.Error:
        return False


def has_handler(scheme: str) -> bool:
    return Gio.AppInfo.get_default_for_uri_scheme(scheme) is not None


def text_view(text: str = "", *classes: str, editable: bool = True, height: int = 120) -> Gtk.TextView:
    view = Gtk.TextView(editable=editable, cursor_visible=editable, wrap_mode=Gtk.WrapMode.WORD_CHAR,
                        top_margin=10, bottom_margin=10, left_margin=10, right_margin=10)
    view.get_buffer().set_text(text)
    view.set_size_request(-1, height)
    for css in classes:
        view.add_css_class(css)
    return view


def view_text(view: Gtk.TextView) -> str:
    buffer = view.get_buffer()
    return buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False)


def framed(child: Gtk.Widget) -> Gtk.Frame:
    frame = Gtk.Frame()
    frame.set_child(child)
    return frame
