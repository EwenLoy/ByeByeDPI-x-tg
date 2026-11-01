# Апстрим и границы форка

## База

- **ByeByeDPI (romanvht)** — `v1.8.2`, коммит `96a3c1f`, тег `upstream-base-1.8.2`.
- **Ядро byedpi (hufrea)** — `ba53229` (сабмодуль `app/src/main/cpp/byedpi`).
- **TG WS слой** — перенесён из `amurcanov/tg-ws-proxy-android` (форк `Flowseal/tg-ws-proxy`, MIT), Rust-ядро лежит в `third-party/tg-ws-proxy-rs`.

Репозиторий отвязан от апстрима намеренно: развиваемся отдельно, мердж целиком не делаем.

## Remote'ы

| Имя | Репозиторий |
|---|---|
| `origin` | `EwenLoy/ByeByeDPI-x-tg` (наш) |
| `upstream` | `romanvht/ByeByeDPI` (только для чтения) |

## Что берём из апстрима

Только то, что не зависит от его UI и структуры:

1. **Ядро byedpi** — обновление сабмодуля `app/src/main/cpp/byedpi` + пересборка.
2. **Android-платформенные фиксы** — foreground service types, Always-On VPN, поведение при загрузке.

Всё остальное (экраны, локализации, темы, настройки) — наше.

## Как забирать

```bash
git fetch upstream
git log --oneline upstream/master --since="3 months ago" -- \
  app/src/main/java/io/github/romanvht/byedpi/services \
  app/src/main/AndroidManifest.xml app/build.gradle.kts
```

Смотрим заголовки, берём нужное поштучно:

```bash
git cherry-pick -x <sha>
```

Полный мердж `upstream/master` не делаем: 87 файлов и ~9000 строк уже разошлись.

## Точки интеграции TG WS

Наш слой держим изолированным, чтобы черри-пики оставались дешёвыми:

- `app/src/main/java/io/github/romanvht/byedpi/ewenloy/tgws/`
- `app/src/main/java/io/github/romanvht/byedpi/utility/TelegramProxyLink.kt`
- подключение: `BootReceiver`, `ByeDpiVpnService`, `ByeDpiProxyService`, `MainActivity`, `MainSettingsFragment`

Новый TG-код вне этих файлов не добавляем.
