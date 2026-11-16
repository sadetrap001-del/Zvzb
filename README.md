# Kor Cast

Telefondaki oyunu aynı Wi-Fi ağındaki Android TV'de büyük ekranda oynamak için ekran yansıtma uygulaması.
Tek APK hem telefona hem TV'ye kurulur: TV'de alıcı, telefonda gönderici olarak açılır.

## Kullanım

1. APK'yı hem telefona hem Android TV'ye kur.
2. TV'de Kor Cast'i aç; "Telefon bekleniyor" ekranı ve TV'nin IP adresi görünür.
3. Telefonda Kor Cast'i aç. TV listede çıkar; çıkmazsa IP adresini elle yaz.
4. Kaliteyi seç ve "Yayını başlat"a bas, ekran paylaşımına izin ver, oyunu aç.

## Bilinmesi gerekenler

- Görüntü H.265 (TV desteklemiyorsa H.264) ile kodlanır; TV'nin çözemediği kalite seçilirse otomatik düşürülür.
- "4K" seçeneği 4K akış gönderir, ama oyun telefonun kendi çözünürlüğünde çizildiği için görüntü büyütülmüştür.
- Oyun sesi Android 10+ telefonlarda aktarılır; bazı oyunlar ses yakalamayı engeller.
- Kontroller telefonda kalır (dokunmatik ya da telefona bağlı oyun kolu).
- Gecikme Wi-Fi kalitesine bağlıdır; 5 GHz ağ önerilir.

## Derleme

```
./gradlew assembleDebug
```

Her push'ta GitHub Actions APK'yı derler; dosya ilgili çalıştırmanın "Artifacts" bölümünden indirilir.
