# ByeByeDPI x TG

Приложение для Android, которое помогает открывать Telegram и сайты, заблокированные провайдером.

Внутри два инструмента:

1. **ByeDPI** — обход блокировок сайтов (VPN или локальный прокси).
2. **TG WS Proxy** — отдельный прокси только для Telegram: трафик мессенджера идёт через защищённое WebSocket-соединение (Cloudflare), поэтому мессенджер часто оживает даже там, где обычный прокси не спасает.

Оба инструмента работают независимо: можно включить только TG WS, только ByeDPI или оба сразу.

## Быстрый старт

1. [Скачайте APK](https://github.com/EwenLoy/ByeByeDPI-x-tg/releases) и установите его (разрешите установку из неизвестных источников).
2. Откройте приложение и нажмите большую кнопку — запустится ByeDPI.
3. Для Telegram: включите зелёный переключатель **TG WS**, затем нажмите «Применить в Telegram» и подтвердите прокси в мессенджере.

Готово. Статус обоих инструментов виден на главном экране, там же кнопка теста.

## Как работает TG WS

```
Telegram → 127.0.0.1:1082 (MTProto) → движок на Rust → WebSocket/TLS → Telegram
```

- Приложение поднимает на телефоне локальный MTProto-прокси (порт `1082`).
- Нативный движок на Rust принимает трафик Telegram и пересылает его к серверам Telegram через WebSocket с шифрованием — при необходимости через Cloudflare.
- Ссылка вида `tg://proxy?server=127.0.0.1&port=1082&secret=dd...` добавляется в ваш Telegram-клиент одной кнопкой.

Поддерживаются клиенты: Telegram, AyuGram, Plus Messenger, NekoGram, ExtremeGram и другие форки.

## Если что-то не работает

- **Telegram не подключается**: включите TG WS и нажмите «Проверить TG WS» в меню теста — приложение покажет, что именно сломалось (сервис, порт, ядро, доступ к серверу).
- **Нужны подробности**: меню ⋮ → «Логи TG WS» — там живые логи движка, можно поделиться ими.
- **Сайты не открываются**: попробуйте сменить режим VPN/Proxy в настройках или другую стратегию в тесте.
- **Приложение выгрузили из памяти**: сервисы перезапускаются сами; если нет — включите игнорирование оптимизации батареи (предложится при первом запуске).

Требования: Android 5.0+. Для работы TG WS нужен телефон на ARM (все реальные устройства); на эмуляторах x86 TG WS недоступен.

## Сборка из исходников

**Автоматически:** при каждом пуше GitHub Actions собирает APK — вкладка [Actions](https://github.com/EwenLoy/ByeByeDPI-x-tg/actions), артефакт `ByeByeDPI-x-tg-debug`. Пуш тега `v*` создаёт Release.

**Локально:**
```bash
git clone https://github.com/EwenLoy/ByeByeDPI-x-tg.git
cd ByeByeDPI-x-tg
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

Нужны JDK 17 и Android SDK (NDK скачается сам). Готовые `.so` движка лежат в репозитории (`app/src/main/tglibs/`), собирать Rust отдельно не требуется.

## Благодарности

- [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy) — основная идея
- [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) — Android-версия TG WS + Rust-движок TG WS
- [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) и оригинальный [ByeByeDPI](https://github.com/hufrea/byedpi) — обход блокировок

## Лицензия

GPLv3 — см. [LICENSE](LICENSE).
