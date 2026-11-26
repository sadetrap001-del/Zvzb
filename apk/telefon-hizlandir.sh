#!/bin/sh
# Redmi telefonu (MIUI 12.5, Android 11) Termux'tan kendi kendine hizlandirir ve reklamlari kapatir.
# Hicbir sey silinmez, arayuz ve animasyonlar oldugu gibi kalir.
# Termux Redmi'nin kendisinde ya da ayni Wi-Fi'daki baska bir telefonda calisabilir.
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

# Hedef Redmi'yi sec. Termux Redmi'nin kendisindeyse 127.0.0.1, baska bir telefondaysa Redmi'nin
# Wi-Fi adresi (192.168.1.130). TV (192.168.1.113) hic secilmez. Elle secmek icin: TELEFON=ip:port sh ...
REDMI_IP=${REDMI_IP:-192.168.1.130}
DEVS=$(adb devices | tr -d '\r' | awk '$2=="device" {print $1}')
SERIAL=$TELEFON
[ -z "$SERIAL" ] && SERIAL=$(echo "$DEVS" | grep -E '^(127\.0\.0\.1|localhost):' | head -1)
[ -z "$SERIAL" ] && SERIAL=$(echo "$DEVS" | grep "^$REDMI_IP:" | head -1)
# Redmi'nin adresi Wi-Fi agina gore degisebiliyor; bagli cihazlar arasinda Xiaomi olani ara.
if [ -z "$SERIAL" ]; then
  for d in $DEVS; do
    case "$(adb -s "$d" shell getprop ro.product.brand | tr -d '\r' | tr 'A-Z' 'a-z')" in
      xiaomi|redmi|poco) SERIAL=$d; break ;;
    esac
  done
fi
if [ -z "$SERIAL" ]; then
  echo "Redmi bagli degil. Redmi'de Kablosuz hata ayiklama ekranindaki portla:"
  echo "  Termux Redmi'deyse:          adb connect 127.0.0.1:<port>"
  echo "  Termux baska telefondaysa:   adb connect $REDMI_IP:<port>"
  echo "Ilk kezse once eslestir:  adb pair <ayni adres>:<eslestirme portu> <kod>"
  exit 1
fi
A="adb -s $SERIAL"
BRAND=$($A shell getprop ro.product.brand | tr -d '\r')
case "$(echo "$BRAND" | tr 'A-Z' 'a-z')" in
  xiaomi|redmi|poco) ;;
  *) echo "Bagli cihaz Redmi degil ($BRAND, $SERIAL). Bu betik yalnizca Redmi/Xiaomi icindir, durduruldu."
     exit 1 ;;
esac
echo "Telefon: $BRAND $($A shell getprop ro.product.model | tr -d '\r')  ($SERIAL)"

if [ "$1" = "geri" ]; then
  for p in $LIST; do
    echo "$p -> $($A shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done
  # Betigin eski surumu animasyonlari 0.5x yapiyordu; onu da varsayilana dondur.
  $A shell settings put global window_animation_scale 1.0
  $A shell settings put global transition_animation_scale 1.0
  $A shell settings put global animator_duration_scale 1.0
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

echo "3/4 Uygulamalar onceden derleniyor, daha hizli acilacaklar (birkac dakika surebilir)"
$A shell cmd package bg-dexopt-job 2>&1 | tr -d '\r' | tail -1

echo "4/4 Onbellek temizleniyor, arka plan uygulamalari kapatiliyor"
$A shell pm trim-caches 4096G >/dev/null 2>&1
$A shell am kill-all >/dev/null 2>&1

echo "Bitti. Telefonu bir kez yeniden baslat. Geri almak icin:  sh telefon-hizlandir.sh geri"
