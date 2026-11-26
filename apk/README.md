# Hazır APK'lar

- `KorHome.apk`, `KorPlay.apk`: bu depodaki `tv` ve `app` modüllerinden derlenir.
- `TvHome.apk`: [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) deposundaki `launcher/`
  (MIT lisanslı, c2d6e79 sürümü) kaynağından derlenmiştir. Tek değişiklik `config.xml` içindeki dock sırasıdır:
  YouTube, Netflix, Prime Video.

## Telefon hızlandırma (Termux)

`telefon-hizlandir.sh`: Redmi telefonu Termux'tan, Kablosuz hata ayıklama ile kendine bağlanarak hızlandırır.
Arayüze ve animasyonlara dokunmaz. MIUI reklam (msa), analiz ve Facebook servislerini kapatır (silmez); uygulamaları önceden derler, önbelleği temizler.
`sh telefon-hizlandir.sh geri` hepsini eski haline getirir.
