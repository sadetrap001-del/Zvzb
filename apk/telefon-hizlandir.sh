#!/bin/sh
# Redmi telefonu (MIUI 12.5, Android 11) Termux'tan kendi kendine hizlandirir. Hicbir sey silinmez.
# Telefon, Kablosuz hata ayiklama ile kendisine adb uzerinden baglanir.
#
# Bir kerelik hazirlik (Termux'ta):
#   pkg install android-tools
#   Gelistirici secenekleri -> Kablosuz hata ayiklama -> ac -> "Eslestirme koduyla cihaz esle"
#   adb pair 127.0.0.1:<eslestirme portu> <6 haneli kod>
#   adb connect 127.0.0.1:<Kablosuz hata ayiklama ekranindaki port>
#
# Kullanim:  sh telefon-hizlandir.sh        -> uygula
#            sh telefon-hizlandir.sh geri   -> hepsini eski haline getir

# Reklam, izleme ve hata raporu servisleri. Telefonun calismasi icin gerekli degiller.
LIST="
com.miui.msa.global
com.miui.analytics
com.miui.daemon
com.miui.bugreport
com.facebook.appmanager
com.facebook.services
com.facebook.system
com.google.android.feedback
"

# Ayni anda TV de bagli olabilir; yalnizca telefonun kendisini sec.
SERIAL=$(adb devices | tr -d '\r' | awk '$2=="device" && ($1 ~ /^127\.0\.0\.1:/ || $1 ~ /^localhost:/) {print $1; exit}')
if [ -z "$SERIAL" ]; then
  echo "Telefon kendisine bagli degil."
  echo "Kablosuz hata ayiklama ekranindaki portla:  adb connect 127.0.0.1:<port>"
  echo "Ilk kezse once eslestir:  adb pair 127.0.0.1:<eslestirme portu> <kod>"
  exit 1
fi
A="adb -s $SERIAL"
echo "Telefon: $($A shell getprop ro.product.model | tr -d '\r')  ($SERIAL)"

anim() {
  $A shell settings put global window_animation_scale "$1"
  $A shell settings put global transition_animation_scale "$1"
  $A shell settings put global animator_duration_scale "$1"
  now=$($A shell settings get global window_animation_scale | tr -d '\r')
  if [ "$now" != "$1" ]; then
    echo "  UYARI: animasyonlar degismedi. 'USB hata ayiklama (Guvenlik ayarlari)' acik mi?"
  fi
}

if [ "$1" = "geri" ]; then
  for p in $LIST; do
    echo "$p -> $($A shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done
  anim 1.0
  echo "Hepsi eski haline dondu. Telefonu yeniden baslatman iyi olur."
  exit 0
fi

echo "1/4 Yedek aliniyor (~/telefon-kapali-onceki.txt)"
$A shell pm list packages -d | tr -d '\r' > "$HOME/telefon-kapali-onceki.txt"
$A shell pm list packages | tr -d '\r' > "$HOME/telefon-kurulu-onceki.txt"

echo "2/4 Reklam ve izleme servisleri kapatiliyor"
for p in $LIST; do
  if grep -qx "package:$p" "$HOME/telefon-kurulu-onceki.txt"; then
    echo "  $p -> $($A shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')"
  else
    echo "  $p -> bu telefonda yok, atlandi"
  fi
done

echo "3/4 Animasyonlar hizlandiriliyor (0.5x)"
anim 0.5

echo "4/4 Onbellek temizleniyor, arka plan uygulamalari kapatiliyor"
$A shell pm trim-caches 4096G >/dev/null 2>&1
$A shell am kill-all >/dev/null 2>&1

echo "Bitti. Telefonu bir kez yeniden baslat. Geri almak icin:  sh telefon-hizlandir.sh geri"
