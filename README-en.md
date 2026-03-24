<div align="center">

# ByeByeDPI-x-tg

Android app: **ByeDPI** DPI circumvention + optional **Telegram over WebSocket** (SOCKS5 on `127.0.0.1:1082`, ByeDPI on `:1080`).

Fork of **[ByeByeDPI](https://github.com/romanvht/ByeByeDPI)** by **[EwenLoy](https://github.com/EwenLoy)** · **https://github.com/EwenLoy/ByeByeDPI-x-tg**

<a href="README.md">Русский</a> | English | <a href="README-tr.md">Türkçe</a>

[![Release](https://img.shields.io/github/v/release/EwenLoy/ByeByeDPI-x-tg?label=release)](https://github.com/EwenLoy/ByeByeDPI-x-tg/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
[![Upstream](https://img.shields.io/badge/upstream-ByeByeDPI-orange)](https://github.com/romanvht/ByeByeDPI)

</div>

WS logic derived from **[tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy)** (MIT); see [NOTICE](NOTICE), [AUTHORS.md](AUTHORS.md), [third-party/licenses/](third-party/licenses/). Full quick start (Russian): [README.md](README.md).

For stable operation, you may need to adjust the settings. You can read more about different settings in the [ByeDPI documentation](https://github.com/hufrea/byedpi/blob/v0.13/README.md).

This application is **not** a VPN. It uses Android's VPN mode to route traffic but does not transmit anything to a remote server. It does not encrypt traffic or hide your IP address.

This application is a fork of [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid).

---

### Features
* Autostart service on device boot
* Saving lists of command-line parameters
* Improved compatibility with Android TV/BOX
* Per-app split tunneling
* Import/export settings

### Usage
* To enable auto-start, activate the option in settings.
* It is recommended to connect to the VPN once to accept the request.
* After that, upon device startup, the application will automatically launch the service based on settings (VPN/Proxy).
* Comprehensive instruction from the community [ByeByeDPI-Manual (En)](https://github.com/BDManual/ByeByeDPI-Manual)

### How to use ByeByeDPI with AdGuard?
* Start ByeByeDPI in proxy mode.
* Add ByeByeDPI to AdGuard exclusions on the "App Management" tab.
* In AdGuard settings, specify the proxy:
```plaintext
Proxy Type: SOCKS5
Host: 127.0.0.1
Port: 1080 (default)
```

### Building
1. Clone the repository with submodules:
   ```bash
   git clone --recurse-submodules
   ```
2. Run the build script from the root of the repository:
   ```bash
   ./gradlew assembleRelease
   ```
3. The APK will be in `app/build/outputs/apk/release/`

> P.S.: hev_socks5_tunnel will not build under Windows, you will need to use WSL

### Signature Hash
SHA-256:
`77:45:10:75:AC:EA:40:64:06:47:5D:74:D4:59:88:3A:49:A6:40:51:FA:F3:2E:42:F7:18:F3:F9:77:7A:8D:FB`

### Dependencies
- [ByeDPI](https://github.com/hufrea/byedpi)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
