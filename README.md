Прошло много времени с момента последнего обновления. Сейчас я снова сажусь за этот проект. Спасибо за донаты и комментарии! Но чтобы я мог быстрее всё исправлять, пожалуйста, пишите мне в Telegram, а не на почту — @ewenloy. Возможно, в будущем появится отдельная группа или канал для этого приложения. Спасибо!


<div align="center">
  
# ByeByeDPI × TG

**Обход блокировок сайтов и прокси для Telegram — в одном приложении**

<br>

<a href="https://github.com/EwenLoy/ByeByeDPI-x-tg/releases/latest">
  <img src="https://img.shields.io/github/v/release/EwenLoy/ByeByeDPI-x-tg?style=for-the-badge&logo=github&label=Release&color=0aa1dd" alt="Release">
</a>
<img src="https://img.shields.io/badge/Android-SDK_21--36-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android SDK">
<img src="https://img.shields.io/badge/Rust-1.70%2B-000000?style=for-the-badge&logo=rust&logoColor=white" alt="Rust Version">
<img src="https://img.shields.io/badge/Kotlin-Native-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin">
<a href="https://github.com/EwenLoy/ByeByeDPI-x-tg/stargazers">
  <img src="https://img.shields.io/github/stars/EwenLoy/ByeByeDPI-x-tg?style=for-the-badge&logo=github&color=ffca28&labelColor=24292e" alt="Stars">
</a>

</div>
<br>

**ByeByeDPI × TG** — это два инструмента под одной крышей: **обход блокировок сайтов** на ядре `byedpi` и локальный **MTProto-прокси для Telegram**, который перенаправляет трафик мессенджера через защищённые CloudFlare WebSocket-соединения. YouTube открывается, Telegram оживает — нажатием одной кнопки каждое.

<!-- Скриншот главного экрана: сделай скрин приложения и вставь сюда
<img width="..." src="..." />
-->

---

## Возможности

- **Два переключателя вместо десяти настроек:** сайты и Telegram управляются отдельно, статус обоих виден на главном экране.
- **Интеграция с Telegram:** кнопка **«Применить в Telegram»** передаёт прокси в совместимые клиенты через `tg://proxy` (официальный, AyuGram, Plus Messenger, NekoGram и другие).
- **Проверка стратегий:** приложение само перебирает варианты обхода и показывает рабочие — как на реальных телефонах, так и «из коробки».
- **Диагностика TG WS одной кнопкой:** сервис, порт, нативное ядро и доступность серверов проверяются сами, с человеческим вердиктом.
- **Лог-вьюер:** живые логи обоих движков с фильтрами — видно, что происходит с подключением прямо сейчас.
- **Тёмная и светлая темы,** автозапуск при включении телефона и защита от выгрузки из памяти.

---

## Как это работает

```text
Telegram → 127.0.0.1:1082 (MTProto) → движок на Rust → WSS (через CloudFlare или напрямую) → Telegram DC
```

1. Приложение поднимает локальный MTProto-прокси средствами нативного движка на языке **Rust** (порт по умолчанию — `1082`, секрет генерируется сам).
2. Извлекает из пакета номер датацентра и устанавливает защищённое WebSocket-соединение с нужным DC, при необходимости проксируя через CloudFlare.
3. Держит пул соединений и переподключается при обрывах — чтобы в реальных сетях не отваливалось.

Сайтами в это же время занимается ядро [byedpi](https://github.com/hufrea/byedpi): desync-методы fake/split/oob и списки доменов настраиваются прямо в приложении.

---

## Быстрый старт

1. Скачайте актуальный APK со **[страницы релизов](https://github.com/EwenLoy/ByeByeDPI-x-tg/releases/latest)**.
2. Установите приложение (Android попросит разрешить установку — это нормально, приложение мимо Google Play).
3. Нажмите большую кнопку ⏻ — заработают сайты.
4. Для Telegram включите зелёный переключатель **TG WS**.
5. Нажмите **«Применить в Telegram»** и подтвердите прокси в мессенджере.
6. Не завелось? Меню теста → **«Проверить TG WS»** — приложение само покажет, где затык.

---

* **Краши и проблемы:** если приложение вылетает или не ставится — сохраните логи (меню ⋮ → «Логи TG WS») и приложите их к issue.

> [!NOTE]
> ### Отчёты об ошибках
> Приложение адаптировано под мобильные сети, однако фоновой работе могут мешать агрессивные оптимизации батареи — разрешите приложению их игнорировать при первом запуске.
>
> Мелкие ошибки в логах при нормально работающем прокси можно игнорировать. Перед созданием issue загляните в «Проверку стратегий» и «Проверить TG WS» — часто ответ уже там.

---

<details>
<summary>🔨 <b>Сборка из исходников</b></summary>

<br>

Каждый пуш собирается GitHub Actions ([вкладка Actions](https://github.com/EwenLoy/ByeByeDPI-x-tg/actions)), пуш тега `v*` создаёт релиз, коммит с `[skip ci]` сборку пропускает.

Локально:

```bash
git clone https://github.com/EwenLoy/ByeByeDPI-x-tg.git
cd ByeByeDPI-x-tg
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/
```

Нужны JDK 17 и Android SDK (NDK скачается сам). Собирать Rust **не нужно** — готовые `.so` движка уже лежат в `app/src/main/tglibs/`.

</details>

<details>
<summary>💙 <b>Цепочка форков и благодарности</b></summary>

<br>

**Сайты:** [hufrea/byedpi](https://github.com/hufrea/byedpi) (ядро) → [romanvht/ByeByeDPI](https://github.com/romanvht/ByeByeDPI) (Android-обёртка) → **этот форк**

**Telegram:** идея — [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy), Android-версия с Rust-движком — [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android) → интегрировано в этот форк

Спасибо авторам оригинальных проектов 💙

</details>

## Лицензия

Этот форк распространяется под лицензией **GPLv3** (см. [LICENSE](LICENSE)).

- **ByeByeDPI для Android** и его разработчик [romanvht](https://github.com/romanvht) — спасибо за основу приложения 💙
- Ядро [byedpi](https://github.com/hufrea/byedpi) от [hufrea](https://github.com/hufrea)
- Оригинальный `tg-ws-proxy` от [Flowseal](https://github.com/Flowseal) доступен под **MIT**
- Android-порт с Rust-движком — [amurcanov/tg-ws-proxy-android](https://github.com/amurcanov/tg-ws-proxy-android)

---

<div align="center">

Помогло? Кинь ⭐ репозиторию и/или [поддержи автора](https://dalink.to/ewenloy) ❤️

</div>
