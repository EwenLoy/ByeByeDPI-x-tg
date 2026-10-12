# MIGRATION-TGWS — перенос TG WS слоя на базу апстрима v1.8.2

**Рабочая папка:** `D:\byу bye dpi mod by ewenloy\ByeByeDPI-x-tg-v2`
**Эталон апстрима (чистый, не трогаем):** `D:\byу bye dpi mod by ewenloy\ByeByeDPISource` — master = `96a3c1f` (v.1.8.2)
**Старый форк (не трогаем, источник TG-слоя):** `D:\byу bye dpi mod by ewenloy\ByeByeDPI` — main = `2b0b419`
**Ветка работы:** `tgws-v2` (от master / `96a3c1f`)
**Механика:** `git merge ewenloy/main` — merge-base `0dd7a98`; авто-слито ~64 файла, конфликты в 19.

**Легенда:** `[x]` готово · `[~]` в работе · `[ ]` todo

---

## Фаза 0 — Площадка и git
- [x] 0.1 Копия `ByeByeDPISource` → `ByeByeDPI-x-tg-v2` (включая .git и сабмодули)
- [x] 0.2 Ветка `tgws-v2` от `96a3c1f`
- [x] 0.3 Remote `ewenloy` добавлен, fetch (main + теги v1.0.0…v1.0.1-build5)
- [x] 0.4 Merge-base проверен = `0dd7a98` (v1.7.3+1)
- [x] 0.5 Референсные патчи форка сохранены: `%TEMP%\bbd_cmp\fork_patch\*.patch`
- [x] 0.6 `local.properties` с `sdk.dir` — создан для локальной сборки
- [ ] 0.7 Решить, куда пушим (ветка `tgws-v2` в ewenloy или замена `main`) — в конце

## Фаза 1 — Merge: авто-часть (проверка)
- [x] 1.1 `git merge ewenloy/main` выполнен, авто-слито ~64 файла
- [x] 1.2 TG-слой на месте: `ewenloy/tgws/*` (5 файлов), `TgWsLogsActivity.kt`, `activity_tg_ws_logs.xml`
- [x] 1.3 Бинарники/ассеты: `tglibs/*.so` (2 ABI), `release.keystore`, иконки mipmap, `ic_cloud/ic_key/ic_pool`, `AUTHORS.md`, `NOTICE`, `third-party/`
- [x] 1.4 CI: `.github/workflows/build.yml`
- [x] 1.5 `gradle.properties` (`android.overridePathCheck=true` — нужен из-за кириллического пути), `settings.gradle.kts` (`rootProject.name = ByeByeDPI-x-tg`)
- [ ] 1.6 Визуально проверить авто-слитые файлы: `main_settings.xml`, `byedpi_ui_settings.xml`, `arrays.xml`, `CMakeLists.txt`
- [x] 1.7 `README-en.md` / `README-tr.md` — удалены (как в форке); решить позже, возвращать ли обновлённые

## Фаза 2 — Разрешение конфликтов (19 файлов, ~40 блоков) — ЗАВЕРШЕНО ✅
- [x] 2.1 `README.md` — взят вариант форка (обновить текст позже)
- [x] 2.2 `app/build.gradle.kts` — база апстрима + JNA, DataStore, tglibs, keystore, splits; версия 2.0.0 (код 20)
- [x] 2.3 `AndroidManifest.xml` — база апстрима + WAKE_LOCK, queries Telegram, TgWsProxyService (specialUse), TgWsLogsActivity
- [x] 2.4 `BaseActivity.kt` — версия апстрима
- [x] 2.5 `core/ByeDpiProxyPreferences.kt` — чистая версия апстрима (fork-овый override API не использовался)
- [x] 2.6 `services/QuickTileService.kt` — апстрим + дебаунс форка
- [x] 2.7 `receiver/BootReceiver.kt` — версия форка (tryAutostart + USER_UNLOCKED); TG WS автозапуск — см. 3.7
- [x] 2.8 `utility/NotificationUtils.kt` — апстрим (broadcast-интенты) + fork-овский `statusLine` (bigText)
- [x] 2.9 `fragments/MainSettingsFragment.kt` — апстрим + TG-настройки, секрет, диагностика, ссылка, статус-поллинг
- [x] 2.10 `services/ByeDpiProxyService.kt` — чистый апстрим (tgWsExt-хук — см. 3.5)
- [x] 2.11 `services/ByeDpiVpnService.kt` — чистый апстрим (tgWsExt-хук — см. 3.5)
- [x] 2.12 `res/menu/menu_main.xml` — добавлен пункт «Логи TG WS»
- [x] 2.13 `res/layout/activity_proxy_test.xml` — апстрим-структура + кнопка `checkTgWsButton`
- [x] 2.14 `res/layout/activity_main.xml` — апстрим + ряд TG (тумблер + подключить Telegram) + `tg_ws_status_text`
- [x] 2.15 `activities/MainActivity.kt` — апстрим + TG init/статус/тумблер/кнопки/меню
- [x] 2.16 `activities/TestActivity.kt` — взят апстрим; TG-блок теста (кнопка пока не подключена) — см. 3.9
- [x] 2.17 `res/values/strings.xml` — апстрим + ключи `tg_ws_*`/`ewenloy_*` (дубликатов нет)

## Фаза 3 — Адаптация под новую архитектуру апстрима
- [x] 3.1 Решение: `TgWsProxyService` остаётся в **основном процессе** (не `:native`) — нужен доступ к prefs/UI
- [x] 3.2 Решение: FGS-тип `TgWsProxyService` = `specialUse` (у `dataSync` лимит ~6 ч/сутки в Android 15+)
- [x] 3.3 Решение: переключение TG WS — прямыми вызовами из UI (`tgWsExt.start/stop/refresh`), сервисы апстрима не трогаем
- [x] 3.4 `EwenloyTgWsServiceExtension` совместим с новыми API (подтверждено компиляцией)
- [x] 3.5 `TgWsProxyService`/`LogManager`/`NativeProxy` компилируются на новой базе
- [x] 3.6 «Меню ⋮ → Логи TG WS» + обработчик в новой MainActivity
- [x] 3.7 Бут-автозапуск TG WS в `BootReceiver` (стартует независимо от BBD при включённом тумблере)
- [ ] 3.8 QS-плитка: как в форке; фича issue #14 («BBD + TG WS одной плиткой») — отдельным шагом
- [x] 3.9 Блок «Проверить TG WS» в TestActivity (порт из форка: сервис, порт, протокол, секрет, удалённый хост)
- [x] 3.10 Единое уведомление: `statusLine` проброшен в `createConnectionNotification` из ByeDpiVpnService/ByeDpiProxyService
- [ ] 3.11 Рантайм-проверка: статусы на главном, логи TG WS, диагностика, секрет (нужен девайс)

## Фаза 4 — Сборка и проверка
- [x] 4.1 `gradlew :app:compileDebugKotlin -x runNdkBuild` — BUILD SUCCESSFUL (34 c)
- [x] 4.2 `gradlew :app:assembleDebug` — BUILD SUCCESSFUL (4 мин); APK: 5 split'ов + universal (8.4–11 МБ)
- [x] 4.3 `gradlew :app:assembleRelease` (подписанный, кейстор) — ✅ BUILD SUCCESSFUL (3м26с), APK 2.8–5.4 МБ
- [ ] 4.4 Смоук на устройстве: старт/стоп, тумблер TG, «Применить в Telegram», логи, диагностика
- [x] 4.5 Новое ядро byedpi (`ba532298`) + HEV собрались через NDK (в логе только warning'и)

## Фаза 5 — Git и релиз-процесс
- [x] 5.1 Коммит мерджа `2c1c284` «Merge upstream ByeByeDPI v1.8.2 into TG WS base (2.0.0)»
- [ ] 5.2 Обновить `README.md` (база v1.8.2, новые фичи, что изменилось)
- [ ] 5.3 Версия: `2.0.0` / versionCode `20` (подтвердить)
- [ ] 5.4 Push: ветка `tgws-v2` в `ewenloy/ByeByeDPI-x-tg`, потом решение по `main`
- [x] 5.5 `sbox.md` — локальная копия перенесена, команды ADB переписаны под `io.github.ewenloy.byedpixtg`

## Фаза 7 — Публикация (по красоте)
- [x] 7.1 Чистка локального мусора: build-логи удалены, `*.log` в .gitignore; в репо нет temp-файлов
- [x] 7.2 `.github/FUNDING.yml` → `dalink.to/ewenloy`; `README.md` — пометка базы (1.8.2 / ba53229)
- [x] 7.3 Push ветки `tgws-v2` в `ewenloy/ByeByeDPI-x-tg`
- [x] 7.4 Бэкап старой main → ветка **`v1-legacy`**
- [x] 7.5 Замена **main на v2** (`force-with-lease`, commit `5e4d590`) → запущена облачная сборка
- [x] 7.6 CI собрал и опубликовал релиз **`v2.0.0-build9`** (pre-release, 5 APK, universal 5.4 МБ)
- [x] 7.7 Ченджлог вставлен в релиз `v2.0.0-build9` (через `gh`), релиз помечен как **latest** (не pre-release)
- [ ] 7.8 Решить: встроенный диалог донатов в настройках всё ещё ведёт на реквизиты romanvht — менять или оставить
- [ ] 7.9 Решить: возвращать ли `README-en.md` / `README-tr.md` (сейчас удалены, как в форке)

## Фаза 6 — Issue на странице ewenloy (закрывать ПОСЛЕ сборки v2)
- [ ] 6.1 #11 (Samsung A55, «не удалось запустить vpn») — проверить на новой базе (FGS-типы, Always-On из v1.7.6–1.8.2)
- [ ] 6.2 #10 (расход батареи) — проверить после переноса (LogManager, statsJob, WakeLock)
- [ ] 6.3 #14 (QS-плитка: BBD + TG WS вместе) — реализовать в v2
- [ ] 6.4 PR #13 (Fedora-порт) — закрыть/перенести

---

### Заметки по решениям
- `applicationId` не меняем: `io.github.ewenloy.byedpixtg` (обновление ставится поверх старой версии).
- `namespace` остаётся `io.github.romanvht.byedpi` (как в апстриме).
- Новое ядро: `byedpi` `ba532298` (UDP/ICMP fix, auto-mode, кэш групп, delay connect и т.д.). `hev-socks5-tunnel` без изменений (2.14.4).
- Референс-патчи: форк — `%TEMP%\bbd_cmp\fork_patch\*.patch`, апстрим — `%TEMP%\bbd_cmp\upstream_patch\*.patch`.
- Артефакт Windows: в сабмодуле `hev-socks5-tunnel` git показывает `T include/hev-socks5-tunnel.h` и грязные вложенные сабмодули — это следствие `core.symlinks=false` при checkout под Windows + shallow-клонов. На сборку не влияет.

### Журнал прогресса
- **Этап 1**: площадка `ByeByeDPI-x-tg-v2` создана, ветка `tgws-v2`, merge апстрима `96a3c1f`, разрешены все 19 конфликтов, версия 2.0.0 (20), собраны debug и release APK, коммит `2c1c284`.
- **Этап 2**: TG-диагностика в TestActivity (3.9), автозапуск TG WS на бут (3.7), TG-строка в общем уведомлении (3.10), `sbox.md` под новый package (5.5). Сборка `assembleDebug` ✅, коммит `791de4f`.
- **Этап 3**: смоук на устройстве ✅ (по отзыву автора); чистка мусора (логи), `FUNDING.yml` → dalink.to/ewenloy, README-пометка базы; ветка `tgws-v2` и **`main`** переведены на v2 (коммит `5e4d590`), старая main сохранена как **`v1-legacy`**; запущена облачная сборка. Ченджлог-драфт: `..\RELEASE-NOTES-v2.0.0.md`.
- Следующие шаги: 7.6 (дождаться CI и вставить ченджлог), 7.8/7.9 (доны/README-en-tr), 3.8 (QS-плитка — фича #14), фаза 6 (issues), новый интерфейс v2.3 (обсуждение).
