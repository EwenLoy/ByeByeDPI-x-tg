<div align="center">

# ByeByeDPI-x-tg

**EwenLoy** çatalı · Telegram WebSocket + ByeDPI · **https://github.com/EwenLoy/ByeByeDPI-x-tg**

<a href="README.md">Русский</a> | <a href="README-en.md">English</a> | Türkçe

[![Release](https://img.shields.io/github/v/release/EwenLoy/ByeByeDPI-x-tg?label=release)](https://github.com/EwenLoy/ByeByeDPI-x-tg/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

</div>

ByeDPI'yi yerel olarak çalıştıran ve trafiği yönlendiren bir Android uygulaması; isteğe bağlı Telegram WS modu (`127.0.0.1:1082`). Ayrıntılar için [README.md](README.md) (Rusça) veya [README-en.md](README-en.md).

Kararlı bir çalışma için ayarları yapmanız gerekebilir. Farklı ayarlar hakkında daha fazla bilgiye [ByeDPI dökümantasyonundan](https://github.com/hufrea/byedpi/blob/v0.13/README.md) ulaşabilirsiniz.

Bu uygulama **VPN** değildir. Trafiği yönlendirmek için Android'in VPN modunu kullanır ancak herhangi bir veriyi uzak bir sunucuya iletmez. Trafiği şifrelemez veya IP adresinizi gizlemez.

Bu uygulama, [ByeDPIAndroid](https://github.com/dovecoteescapee/ByeDPIAndroid) uygulamasının bir çatallamasıdır.

---

### Özellikler
* Cihaz başlatıldığında hizmetin otomatik başlatılması
* Komut satırı parametrelerinin listelerinin kaydedilmesi
* Android TV/BOX ile geliştirilmiş uyumluluk
* Uygulama başına bölünmüş tünelleme
* Ayarları içe/dışa aktarma

### Kullanım
* Otomatik başlatmayı etkinleştirmek için ayarlarda seçeneği aktifleştirin.
* İlk başta VPN'e bağlanarak isteği kabul etmeniz önerilir.
* Bundan sonra, cihaz başlatıldığında, uygulama ayarlara göre (VPN/Proxy) hizmeti otomatik olarak başlatacaktır.
* Topluluktan kapsamlı talimatlar [ByeByeDPI-Manual (İngilizce)](https://github.com/BDManual/ByeByeDPI-Manual)

### Türkiye İle İlgili
* Uygulama Türkiyede uygulanan DPI'ı aşmak için şuanlık yeterlidir. İlk başta çalıştırdığınızda uygulama DPI'ı aşamayabilir. UI editöründen rastgele taktikler deneyebilirsiniz veya şuan Deneysel özellik olan proxy modundan bazı argümanlar alıp onları Komut satırı editöründe deneyebilirsiniz.
* Türkiye ile alakalı destek için Discord: [nyaex](https://github.com/nyaexx)


### ByeByeDPI'yi AdGuard ile nasıl kullanırım?
* ByeByeDPI'yi proxy modunda başlatın.
* ByeByeDPI'yi AdGuard dışlamalarına "Uygulama Yönetimi" sekmesinde ekleyin.
* AdGuard ayarlarında, proxy'i belirtin:
```plaintext
Proxy Türü: SOCKS5
Host: 127.0.0.1
Port: 1080 (varsayılan)
```

### Oluşturma
1. Depoyu alt modüllerle klonlayın:
```bash
git clone --recurse-submodules
```
2. Depo kökünden derleme betiğini çalıştırın:
```bash
./gradlew assemblyRelease
```
3. APK `app/build/outputs/apk/release/` dizininde olacaktır

> Not: hev_socks5_tunnel Windows altında derlenmeyecektir, WSL kullanmanız gerekecektir

### İmza Özeti
SHA-256:
`77:45:10:75:AC:EA:40:64:06:47:5D:74:D4:59:88:3A:49:A6:40:51:FA:F3:2E:42:F7:18:F3:F9:77:7A:8D:FB`

### Bağımlılıklar
- [ByeDPI](https://github.com/hufrea/byedpi)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
