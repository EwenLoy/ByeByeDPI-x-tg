<div align="center">
<img width="200" height="200" alt="iconapp" src="https://github.com/user-attachments/assets/5fe6b110-04ec-4b2b-8891-e73307cb556f" alt="Логотип ByeDPI" width="200" />


# ByeByeDPI-x-tg

**Android-приложение:** обход DPI через ByeDPI + опционально ускорение Telegram через WebSocket-туннель.

Форк **[ByeByeDPI](https://github.com/romanvht/ByeByeDPI)** · автор доработок **[EwenLoy](https://github.com/EwenLoy)** · репозиторий: **https://github.com/EwenLoy/ByeByeDPI-x-tg**

Русский

[![Release](https://img.shields.io/github/v/release/EwenLoy/ByeByeDPI-x-tg?label=release)](https://github.com/EwenLoy/ByeByeDPI-x-tg/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Upstream](https://img.shields.io/badge/upstream-ByeByeDPI-orange)](https://github.com/romanvht/ByeByeDPI)

</div>

---

## Что это

Два независимых прокси-сервера в одном приложении:
- **ByeDPI** (`1080`) — обход DPI для всего трафика (как в оригинале)
- **Telegram WS** (`1082`) — standalone прокси **только для Telegram** (работает как [tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) от Flowseal, без промежуточного хопа через ByeDPI)

Telegram WS работает автономно: подключается напрямую к WebSocket-шлюзам Telegram, при недоступности WS — прямой TCP к DC. Трафик Telegram **не проходит через ByeDPI**

| Компонент | Порт | Назначение |
|-----------|------|------------|
| **ByeDPI** (VPN/прокси приложения) | `127.0.0.1:1080` | Весь трафик через tun2socks или внешний прокси |
| **Telegram WS** (модуль `ewenloy.tgws`) | `127.0.0.1:1082` | Standalone SOCKS5 для Telegram: MTProto → WS к `kws*.web.telegram.org`, при недоступности WS — прямое TCP к DC |

Пакет приложения: `io.github.ewenloy.byedpixtg` (не пересекается с официальным ByeByeDPI).

Kotlin-реализация WS-прокси написана по мотивам **[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)** (MIT) от Flowseal. Архитектура полностью совпадает: standalone SOCKS5, прямой WS к шлюзам Telegram, TCP fallback к DC без промежуточных хопов. Подробности: [NOTICE](NOTICE), [AUTHORS.md](AUTHORS.md).

---

## Как это работает

```
Весь трафик → VPN (tun2socks) → ByeDPI (:1080)
Telegram    → SOCKS5 (:1082) → TG WS Proxy → WSS → Telegram DC
                                            └─ TCP fallback → Telegram DC (напрямую)
```

Telegram WS прокси работает **параллельно** с ByeDPI, но **независимо** — трафик Telegram не проходит через ByeDPI.

Переключатель «Ускорить Telegram через WS» можно включать **во время уже работающего VPN** — локальный SOCKS5 на `127.0.0.1:1082` поднимается или гасится без полного переподключения (как отдельный процесс у [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)). Строка в уведомлении читает тот же флаг, что и настройки, а не устаревшее состояние в памяти.

## Быстрый старт

1. Установить APK, один раз включить VPN и принять системный запрос.
2. **Настройки** → категория **Telegram** → включить **«Ускорить Telegram через WS»**.
3. Нажать **«Подключить прокси в Telegram»** — откроется ссылка с **127.0.0.1:1082**.
4. В Telegram должен быть SOCKS5 на **1082**; остальной трафик идёт через ByeDPI на **1080** как обычно.

---

## Возможности

- Telegram WS mode, диагностика, статус на главном экране и в уведомлении.
- Автозапуск после загрузки (`BOOT_COMPLETED`) и после разблокировки (`USER_UNLOCKED`), см. настройки.
- Всё наследие upstream: редактор стратегий, списки приложений, режим Proxy/VPN, тесты и т.д.

Приложение **не** является классическим VPN-сервисом с удалённым сервером: трафик обрабатывается **локально** (см. [документацию ByeDPI](https://github.com/hufrea/byedpi/blob/v0.13/README.md)).

---

## Сборка из исходников

Репозиторий содержит **только Android-проект** + сабмодули `byedpi` и `hev-socks5-tunnel` (см. `.gitmodules`). Python tg-ws-proxy сюда не клонируется.

```bash
git clone --recurse-submodules https://github.com/EwenLoy/ByeByeDPI-x-tg.git
cd ByeByeDPI-x-tg
```

В `local.properties` укажите `sdk.dir=...` (файл в `.gitignore`).

```bash
# Windows (PowerShell)
.\gradlew.bat assembleRelease "-Pandroid.overridePathCheck=true"
```

APK: `app/build/outputs/apk/release/` (per-ABI и universal). Релиз по умолчанию **без вашей подписи** — подпишите своим keystore для установки поверх или для распространения.

> Сборка нативной части под Windows может потребовать WSL или корректный NDK — как у оригинального ByeByeDPI.

---

## AdGuard и прочее

Режим **прокси** ByeByeDPI-x-tg: в AdGuard указать SOCKS5 `127.0.0.1:1080`. Telegram при этом настраивается **отдельно** на `1082`, если включён WS mode. Подробнее у сообщества: [ByeByeDPI-Manual](https://github.com/BDManual/ByeByeDPI-Manual).

---

## Лицензии

- Проект как целое: **GPL-3.0** ([LICENSE](LICENSE)), производное от ByeByeDPI.
- Атрибуция Flowseal (MIT): [third-party/licenses/Flowseal-tg-ws-proxy-MIT.txt](third-party/licenses/Flowseal-tg-ws-proxy-MIT.txt).

---

## Зависимости (upstream)

- [ByeDPI](https://github.com/hufrea/byedpi)  
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)  
- Цепочка форков: [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) → [ByeByeDPI](https://github.com/romanvht/ByeByeDPI) → **ByeByeDPI-x-tg**
