# Kor Play

Telefonu USB-C → HDMI kablosuyla TV'ye bağlayıp oyunları doğrudan TV ekranında açan Android başlatıcısı.
Oyun TV ekranında açıldığı için, telefonun ekranı yansıtılmak yerine TV'nin çözünürlüğünde çizilir.

## Kullanım

1. Telefonu USB-C → HDMI kablosu ya da adaptörüyle TV'ye bağla.
2. Kor Play'i aç. "Bağlı ekran" kartı TV'nin şu anki çıkışını ve 4K'nın etkin olup olmadığını gösterir.
3. İstersen "TV'de test ekranı göster" ile TV'nin gerçek çözünürlükte görüntü aldığını doğrula.
4. Listeden oyuna dokun; oyun TV'de açılır.

## Bilinmesi gerekenler

- Telefonun USB-C üzerinden görüntü çıkışı (DisplayPort Alt Mode) vermesi gerekir.
- 4K için kablo ya da adaptör, telefon ve TV'nin üçü de 4K desteklemelidir.
- TV'de açılan oyunda dokunmatik çalışmaz; telefona bağlı bir oyun kolu gerekir.
- Bazı oyunlar ve bazı telefonlar ikinci ekranda açılmaya izin vermez.

## Derleme

```
./gradlew assembleDebug
```
