#!/bin/sh
# Ana ekrana duvar kagidi koyar. TV Android/data'ya dogrudan yazdirmadigi icin
# dosyalar ana ekranin kendi koprusu (ThemeProvider) uzerinden yazilir.
# Kullanim:  sh duvar-kagidi.sh          -> koy
#            sh duvar-kagidi.sh geri     -> kaldir

BASE=https://github.com/sadetrap001-del/Zvzb/raw/claude/selam-ben-ramazan-qkawj1/apk/tema
URI=content://com.gathin.tvhome.theme

restart_home() {
  adb shell am force-stop com.gathin.tvhome >/dev/null 2>&1
  adb shell am start -W -a android.intent.action.MAIN -c android.intent.category.HOME \
    -n com.gathin.tvhome/.MainActivity 2>&1 | tr -d '\r' | grep -E "Status|Error"
}

if ! adb devices | grep -q "device$"; then
  echo "TV bagli degil. Once: adb connect 192.168.1.113:5555"
  exit 1
fi

if [ "$1" = "geri" ]; then
  adb shell "content delete --uri $URI/wallpaper.jpg; content delete --uri $URI/config.json" >/dev/null 2>&1
  restart_home
  echo "Duvar kagidi kaldirildi."
  exit 0
fi

curl -fsSL -o wallpaper.jpg "$BASE/wallpaper.jpg" && curl -fsSL -o config.json "$BASE/config.json" \
  || { echo "HATA: dosyalar indirilemedi, interneti kontrol et."; exit 1; }

for f in wallpaper.jpg config.json; do
  size=$(wc -c < "$f" | tr -d ' ')
  adb push "$f" "/data/local/tmp/cyt-$f" >/dev/null || { echo "HATA: $f TV'ye gonderilemedi"; exit 1; }
  adb shell "content write --uri $URI/$f < /data/local/tmp/cyt-$f; rm -f /data/local/tmp/cyt-$f" >/dev/null 2>&1
  got=$(adb shell "content read --uri $URI/$f | wc -c" 2>/dev/null | tr -d '\r ')
  if [ "$got" = "$size" ]; then
    echo "$f -> yazildi ($size bayt)"
  else
    echo "HATA: $f yazilamadi (beklenen $size, okunan ${got:-0})"
    exit 1
  fi
done

restart_home
echo "Bitti. Ana ekranda yeni duvar kagidi gorunmeli."
