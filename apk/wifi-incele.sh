#!/bin/sh
# TV'nin gozunden Wi-Fi'i inceler. Hicbir ayari degistirmez, yalnizca olcer.
# Kullanim:  sh wifi-incele.sh        (once: adb connect 192.168.1.113:5555)

GW=${GW:-192.168.1.1}

if ! adb devices | grep -q "device$"; then
  echo "TV bagli degil. Once: adb connect 192.168.1.113:5555"
  exit 1
fi

echo "=== 1/4  TV'nin Wi-Fi baglantisi ==="
info=$(adb shell "dumpsys wifi | grep -m1 mWifiInfo" | tr -d '\r')
ssid=$(echo "$info" | sed -n 's/.*SSID: "\([^"]*\)".*/\1/p')
freq=$(echo "$info" | sed -n 's/.*Frequency: \([0-9]*\)MHz.*/\1/p')
speed=$(echo "$info" | sed -n 's/.*Link speed: \([0-9]*\)Mbps.*/\1/p')
rssi=$(echo "$info" | sed -n 's/.*RSSI: \(-[0-9]*\).*/\1/p')
echo "Ag: $ssid"
echo "Frekans: ${freq:-?} MHz   Baglanti hizi: ${speed:-?} Mbps   Sinyal: ${rssi:-?} dBm"
if [ -n "$freq" ]; then
  if [ "$freq" -lt 3000 ]; then echo "-> 2,4 GHz bandi: yavas ve kalabalik. 5 GHz daha iyi olur."
  else echo "-> 5 GHz bandi: iyi."; fi
fi

echo
echo "=== 2/4  Modeme baglanti kalitesi ($GW, 20 paket) ==="
adb shell "ping -c 20 -i 0.3 $GW" | tr -d '\r' | tail -2

echo
echo "=== 3/4  Internete baglanti kalitesi (8.8.8.8, 20 paket) ==="
adb shell "ping -c 20 -i 0.3 8.8.8.8" | tr -d '\r' | tail -2

echo
echo "=== 4/4  Cevredeki Wi-Fi aglari ve kanal kalabaligi ==="
adb shell "cmd wifi start-scan" >/dev/null 2>&1
sleep 4
adb shell "cmd wifi list-scan-results" | tr -d '\r' > wifi-tarama.tmp 2>/dev/null || true
if [ ! -s wifi-tarama.tmp ]; then
  echo "Tarama sonucu alinamadi (bu TV komutu desteklemiyor olabilir)."
else
  total=$(awk 'NR>1 && $2+0>2000 {n++} END{print n+0}' wifi-tarama.tmp)
  echo "Bulunan ag sayisi: $total"
  echo
  echo "Kanal (MHz)   Ag sayisi   En guclu sinyal"
  awk 'NR>1 && $2+0>2000 { c[$2]++; if (!($2 in m) || $3+0>m[$2]+0) m[$2]=$3 } END { for (f in c) printf "%-13s %-11s %s dBm\n", f, c[f], m[f] }' wifi-tarama.tmp | sort -n
  echo
  n24=$(awk 'NR>1 && $2+0>2000 && $2+0<3000 {n++} END{print n+0}' wifi-tarama.tmp)
  n5=$(awk 'NR>1 && $2+0>=5000 {n++} END{print n+0}' wifi-tarama.tmp)
  echo "2,4 GHz'te $n24 ag, 5 GHz'te $n5 ag goruluyor."
  [ "$n24" -ge 8 ] && echo "-> 2,4 GHz kalabalik: komsu aglar hizi dusuruyor olabilir."
fi
rm -f wifi-tarama.tmp
echo
echo "Bitti. Bu ekranin fotografini at."
