# Oyun Studio

HDMI ile harici ekrana bağlanınca, harici ekranda (destekliyorsa 4K) RedMagic Studio tarzı tam ekran bir oyun başlatıcısı açar.

## Özellikler
- Harici ekranda kırmızı-siyah oyun arayüzü: saat, pil, çözünürlük, oyun kutucukları
- Ekranın desteklediği en yüksek çözünürlüğü (4K) otomatik ister
- Yüklü oyunları kendiliğinden bulur, son oynananları başa alır (Y tuşuyla tüm uygulamalar)
- Gamepad desteği: yön tuşları / analog = gez, A = aç, Y = filtre, START = Studio'ya dön
- Telefon ekranı kumanda olarak çalışır (▲▼◀▶ + AÇ)
- İki mod:
  - **Ayna modu (varsayılan):** oyun telefonda açılır, HDMI ile ekrana aynalanır. Her telefonda çalışır.
  - **Masaüstü modu:** oyun doğrudan harici ekranda açılır, telefon serbest kalır. Bunun için Geliştirici seçeneklerinde "İkincil ekranlarda masaüstü modunu zorla" ayarının açık olması gerekir.

## APK'yı telefondan derleme (GitHub)
1. github.com'da ücretsiz bir hesap aç ve **New repository** ile yeni bir depo oluştur (örneğin `OyunStudio`).
2. **Add file → Create new file** seçeneğine dokun. Dosya adı kutusuna aşağıdaki yolları **aynen** yaz (`/` yazınca klasör kendiliğinden oluşur), içeriğini yapıştır ve **Commit** et. Bunu her dosya için tekrarla:
   - `settings.gradle`
   - `app/build.gradle`
   - `app/src/main/AndroidManifest.xml`
   - `app/src/main/java/com/oyun/studio/MainActivity.java`
   - `.github/workflows/build.yml`
3. Son dosyayı da ekleyince **Actions** sekmesinde derleme kendiliğinden başlar (3–5 dk sürer). Önceki dosyalarda başlayan derlemeler eksik dosya yüzünden kırmızı (başarısız) görünebilir, bu normal.
4. Yeşil tik çıkınca deponun ana sayfasındaki **Releases** bölümüne gir, **app-debug.apk** dosyasını indir ve kur. Telefon "bilinmeyen kaynak" izni isterse izin ver.
