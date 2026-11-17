#!/bin/sh
# Beko B43B880B: TV'yi hizlandirir ve ana ekrana duvar kagidi koyar. Hicbir sey silinmez.
# Kapatilan paketlerin listesi: github.com/Gathin23/cleanyourtv examples/beko-b43b880b (MIT)
# Kullanim:  sh tv-hizlandir.sh         -> uygula
#            sh tv-hizlandir.sh geri    -> hepsini eski haline getir

BASE=https://github.com/sadetrap001-del/Zvzb/raw/claude/selam-ben-ramazan-qkawj1/apk/tema
DIR=/sdcard/Android/data/com.gathin.tvhome/files
LIST="
tv.anoki.acr.controller
com.cltv.fast
com.beko.tv.channel
com.google.android.feedback
com.google.android.partnersetup
android.autoinstalls.config.arcelik.an
com.mstar.android.tv.disclaimercustomization
com.google.android.syncadapters.calendar
com.google.android.onetimeinitializer
com.google.android.apps.nbu.smartconnect.tv
com.android.printspooler
com.google.android.tvrecommendations
"

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
  for p in $LIST; do
    echo "$p -> $(adb shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done
  adb shell settings put global window_animation_scale 1.0
  adb shell settings put global transition_animation_scale 1.0
  adb shell settings put global animator_duration_scale 1.0
  adb shell "rm -f $DIR/wallpaper.jpg $DIR/config.json"
  adb shell sync
  restart_home
  echo "Hepsi eski haline dondu. TV'yi yeniden baslat: adb reboot"
  exit 0
fi

echo "1/6 Yedek aliniyor"
adb shell pm list packages -d | tr -d '\r' > kapali-onceki.txt
adb shell pm list packages | tr -d '\r' > kurulu-onceki.txt

echo "2/6 Reklam ve izleme servisleri kapatiliyor"
for p in $LIST; do
  if grep -qx "package:$p" kurulu-onceki.txt; then
    echo "  $p -> $(adb shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')"
  else
    echo "  $p -> bu TV'de yok, atlandi"
  fi
done

echo "3/6 Animasyonlar hizlandiriliyor"
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5

echo "4/6 Onbellek temizleniyor, arka plan uygulamalari kapatiliyor"
adb shell pm trim-caches 4096G >/dev/null 2>&1
adb shell am kill-all >/dev/null 2>&1

echo "5/6 Ana ekran onceden derleniyor (1-2 dakika)"
adb shell cmd package compile -m speed -f com.gathin.tvhome 2>&1 | tr -d '\r' | tail -1

echo "6/6 Duvar kagidi koyuluyor"
if curl -fsSL -o wallpaper.jpg "$BASE/wallpaper.jpg" && curl -fsSL -o config.json "$BASE/config.json"; then
  adb shell "mkdir -p $DIR"
  if adb push wallpaper.jpg "$DIR/wallpaper.jpg" >/dev/null && adb push config.json "$DIR/config.json" >/dev/null; then
    echo "  duvar kagidi TV'ye gonderildi"
  else
    echo "  UYARI: duvar kagidi TV'ye yazilamadi (izin reddedildi?). Digerleri uygulandi."
  fi
else
  echo "  UYARI: duvar kagidi indirilemedi, internet baglantini kontrol et."
fi

echo "Disk yazimi bekleniyor (15 sn)..."
adb shell sync
sleep 15
restart_home
echo "Bitti. Simdi TV'yi yeniden baslat:  adb reboot"
