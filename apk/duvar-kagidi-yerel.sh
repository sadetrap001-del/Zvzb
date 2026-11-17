#!/bin/sh
# Telefondaki bir resmi ana ekranin duvar kagidi yapar (kendi resmin, internetten indirmez).
# Kullanim:  sh duvar-kagidi-yerel.sh            (KorDuvarKagidi* dosyasini kendisi bulur)
#            sh duvar-kagidi-yerel.sh /yol/resim.jpg
URI=content://com.gathin.tvhome.theme
F="$1"

# Yol verilmediyse telefonun indirilenler, resimler ve kamera klasorlerinde KorDuvarKagidi* dosyasini ara.
if [ -z "$F" ]; then
  F=$(ls -t ~/storage/downloads/KorDuvarKagidi* ~/storage/pictures/KorDuvarKagidi* \
        ~/storage/dcim/KorDuvarKagidi* ~/storage/shared/Download/KorDuvarKagidi* 2>/dev/null | head -1)
fi
if [ -z "$F" ] || [ ! -f "$F" ]; then
  echo "Resim bulunamadi. Once KorDuvarKagidi.jpg dosyasini telefona indir (Indirilenler klasorune)."
  echo "Telefonun indirilenler klasorundekiler:"
  ls ~/storage/downloads 2>&1 | tail -15
  exit 1
fi
echo "Kullanilan dosya: $F"
if ! adb devices | grep -q "device$"; then
  echo "TV bagli degil. Once: adb connect 192.168.1.113:5555"
  exit 1
fi

size=$(wc -c < "$F" | tr -d ' ')
adb push "$F" /data/local/tmp/cyt-wallpaper.jpg >/dev/null || { echo "HATA: dosya TV'ye gonderilemedi"; exit 1; }
adb shell "content write --uri $URI/wallpaper.jpg < /data/local/tmp/cyt-wallpaper.jpg; rm -f /data/local/tmp/cyt-wallpaper.jpg" >/dev/null 2>&1
got=$(adb shell "content read --uri $URI/wallpaper.jpg | wc -c" 2>/dev/null | tr -d '\r ')
if [ "$got" != "$size" ]; then
  echo "HATA: duvar kagidi yazilamadi (beklenen $size, okunan ${got:-0})"
  exit 1
fi
echo "wallpaper.jpg -> yazildi ($size bayt)"
adb shell am force-stop com.gathin.tvhome >/dev/null 2>&1
adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME \
  -n com.gathin.tvhome/.MainActivity 2>&1 | tr -d '\r' | grep -E "Status|Error"
echo "Bitti. Ana ekranda yeni duvar kagidi gorunmeli."
