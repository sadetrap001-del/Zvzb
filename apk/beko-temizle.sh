#!/bin/sh
# Beko B43B880B icin reklam ve izleme servislerini kapatir (silmez, devre disi birakir).
# Liste: github.com/Gathin23/cleanyourtv  examples/beko-b43b880b (MIT)
# Kullanim:  sh beko-temizle.sh         -> kapat
#            sh beko-temizle.sh geri    -> hepsini geri ac

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

if ! adb devices | grep -q "device$"; then
  echo "TV bagli degil. Once: adb connect 192.168.1.113:5555"
  exit 1
fi

if [ "$1" = "geri" ]; then
  for p in $LIST; do
    echo "$p -> $(adb shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done
  adb shell sync
  echo "Hepsi geri acildi. TV'yi yeniden baslat: adb reboot"
  exit 0
fi

adb shell pm list packages -d | tr -d '\r' > kapali-onceki.txt
adb shell pm list packages | tr -d '\r' > kurulu-onceki.txt
echo "Yedek alindi: kapali-onceki.txt, kurulu-onceki.txt"

for p in $LIST; do
  if grep -qx "package:$p" kurulu-onceki.txt; then
    echo "$p -> $(adb shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')"
  else
    echo "$p -> bu TV'de yok, atlandi"
  fi
done

echo "Disk yazimi bekleniyor (15 sn)..."
adb shell sync
sleep 15
echo "Bitti. Simdi TV'yi yeniden baslat:  adb reboot"
