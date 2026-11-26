# Hazır APK'lar

- `KorHome.apk`, `KorPlay.apk`: bu depodaki `tv` ve `app` modüllerinden derlenir.
- `TvHome.apk`: [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) deposundaki `launcher/`
  (MIT lisanslı, c2d6e79 sürümü) kaynağından derlenmiştir. Tek değişiklik `config.xml` içindeki dock sırasıdır:
  YouTube, Netflix, Prime Video.

## Telefon hızlandırma (Termux)

`telefon-hizlandir.sh`: Redmi telefonu Kablosuz hata ayıklama ile hızlandırır. Termux Redmi'nin kendisinde (127.0.0.1) ya da aynı Wi-Fi'daki başka bir telefonda (Redmi: 192.168.1.186 ya da 192.168.1.130) çalışabilir; Xiaomi olmayan cihazlarda durur.
Arayüze ve animasyonlara dokunmaz. MIUI reklam (msa), analiz ve Facebook servislerini kapatır (silmez); uygulamaları önceden derler, önbelleği temizler.
`sh telefon-hizlandir.sh geri` hepsini eski haline getirir.
