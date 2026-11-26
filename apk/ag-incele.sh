#!/bin/sh
# Telefondan (Termux) ev agini ve modemi inceler, interneti neyin yavaslattigini bulur.
# Hicbir ayari degistirmez, yalnizca olcer. Telefon evdeki Wi-Fi'ye bagli olmali.
#
# Bir kerelik kurulum (Termux'ta):
#   pkg install -y curl nmap dnsutils termux-api jq
#   (Wi-Fi bilgisi icin F-Droid'den "Termux:API" uygulamasini da kur ve konum iznini ver)
# Kullanim:
#   sh ag-incele.sh
#   GW=192.168.0.1 sh ag-incele.sh     (modem adresi farkliysa)

SORUNLAR=""
sorun() { SORUNLAR="$SORUNLAR
- $1"; }
var() { command -v "$1" >/dev/null 2>&1; }

# ping ozetinden kayip (%) ve ortalama/oynama (ms) cikarir
ping_olc() { # $1=hedef $2=paket
  out=$(ping -c "$2" -i 0.2 -W 2 "$1" 2>/dev/null)
  LOSS=$(echo "$out" | sed -n 's/.* \([0-9.]*\)% packet loss.*/\1/p')
  AVG=$(echo "$out" | sed -n 's|.*= [0-9.]*/\([0-9.]*\)/[0-9.]*/\([0-9.]*\).*|\1|p')
  JIT=$(echo "$out" | sed -n 's|.*= [0-9.]*/[0-9.]*/[0-9.]*/\([0-9.]*\).*|\1|p')
  [ -z "$LOSS" ] && LOSS=100
}
buyuk() { awk -v a="$1" -v b="$2" 'BEGIN{exit !(a+0>b+0)}'; }

echo "=== 1/8  Telefonun Wi-Fi baglantisi ==="
IP=""
if var termux-wifi-connectioninfo && var jq; then
  w=$(termux-wifi-connectioninfo 2>/dev/null)
  ssid=$(echo "$w" | jq -r '.ssid // empty')
  freq=$(echo "$w" | jq -r '.frequency_mhz // empty')
  speed=$(echo "$w" | jq -r '.link_speed_mbps // empty')
  rssi=$(echo "$w" | jq -r '.rssi // empty')
  IP=$(echo "$w" | jq -r '.ip // empty')
  echo "Ag: ${ssid:-?}   IP: ${IP:-?}"
  echo "Frekans: ${freq:-?} MHz   Baglanti hizi: ${speed:-?} Mbps   Sinyal: ${rssi:-?} dBm"
  if [ -z "$freq" ] || [ "$freq" = "-1" ]; then
    echo "-> Wi-Fi'ye bagli gorunmuyor. Mobil veriyi kapatip ev Wi-Fi'sine baglan."
  else
    [ "$freq" -lt 3000 ] && sorun "2,4 GHz bandindasin (yavas ve kalabalik). Modemde 5 GHz ag varsa ona baglan."
    if [ -n "$rssi" ] && [ "$rssi" -lt -75 ]; then
      sorun "Wi-Fi sinyali zayif ($rssi dBm). Modeme yaklas, modemi yuksege/ortaya al, araya duvar/metal girmesin."
    fi
    if [ -n "$speed" ] && [ "$speed" -gt 0 ] && [ "$speed" -lt 72 ]; then
      sorun "Telefon-modem baglanti hizi dusuk ($speed Mbps). Sinyal/bant sorunu."
    fi
  fi
else
  echo "Atlandi: 'termux-api' ve 'jq' kurulu degil ya da Termux:API uygulamasi yok."
fi

# Modem adresi: elle verilen > yonlendirme tablosu > IP'nin .1'i > 192.168.1.1
if [ -z "$GW" ]; then
  GW=$(ip route get 1.1.1.1 2>/dev/null | sed -n 's/.* via \([0-9.]*\).*/\1/p')
  [ -z "$GW" ] && [ -n "$IP" ] && GW=$(echo "$IP" | sed 's/\.[0-9]*$/.1/')
  [ -z "$GW" ] && GW=192.168.1.1
fi

echo
echo "=== 2/8  Modem ($GW) ==="
title=$(curl -s -m 5 "http://$GW/" 2>/dev/null | tr -d '\r\n' | sed -n 's/.*<[Tt][Ii][Tt][Ll][Ee][^>]*>\([^<]*\)<.*/\1/p')
if [ -n "$title" ]; then echo "Modem arayuzu: $title  (tarayicida http://$GW)"
else echo "Modem arayuzu acilmadi ya da adi okunamadi (http://$GW)."; fi

echo
echo "=== 3/8  Telefon -> modem baglanti kalitesi (50 paket) ==="
ping_olc "$GW" 50
echo "Kayip: %$LOSS   Ortalama: ${AVG:-?} ms   Oynama: ${JIT:-?} ms"
GW_LOSS=$LOSS
if [ "$LOSS" = "100" ]; then
  echo "-> Modem ping'e cevap vermiyor (bazi modemler vermez). Bu adim yorumlanmadi."
  GW_LOSS=""
else
  buyuk "$LOSS" 1 && sorun "Telefon ile modem arasinda paket kaybi var (%$LOSS). Sorun Wi-Fi'de: sinyal, parazit ya da kanal kalabaligi."
  buyuk "${AVG:-0}" 15 && sorun "Modeme gecikme yuksek (${AVG} ms; normali 1-5 ms). Wi-Fi parazitli ya da modem yorgun."
  buyuk "${JIT:-0}" 15 && sorun "Modeme gecikme cok oynuyor (${JIT} ms). Wi-Fi parazitli ya da biri agi yogun kullaniyor."
fi

echo
echo "=== 4/8  Internet baglanti kalitesi (1.1.1.1 ve 8.8.8.8, 50'ser paket) ==="
ping_olc 1.1.1.1 50; L1=$LOSS; A1=$AVG; J1=$JIT
echo "1.1.1.1  Kayip: %$L1   Ortalama: ${A1:-?} ms   Oynama: ${J1:-?} ms"
ping_olc 8.8.8.8 50; L2=$LOSS; A2=$AVG
echo "8.8.8.8  Kayip: %$L2   Ortalama: ${A2:-?} ms"
IDLE=$A1
if buyuk "$L1" 1 && buyuk "$L2" 1; then
  if [ -n "$GW_LOSS" ] && ! buyuk "$GW_LOSS" 1; then
    sorun "Modeme kadar kayip yok ama internette var (%$L1). Sorun modemin hattinda ya da internet saglayicisinda: hat kablosunu/prizi kontrol et, saglayiciyi ara."
  else
    sorun "Internete giden paketlerde kayip var (%$L1)."
  fi
fi
buyuk "${A1:-0}" 60 && sorun "Internete gecikme yuksek (${A1} ms). Normali 10-30 ms."

echo
echo "=== 5/8  Hiz testi (Cloudflare, ~15'er saniye) ==="
DL=$(curl -s -o /dev/null -m 15 -w '%{speed_download}' 'https://speed.cloudflare.com/__down?bytes=200000000' 2>/dev/null)
DL=$(awk -v b="${DL:-0}" 'BEGIN{printf "%.1f", b*8/1000000}')
UL=$(head -c 30000000 /dev/zero | curl -s -o /dev/null -m 15 -X POST --data-binary @- -w '%{speed_upload}' 'https://speed.cloudflare.com/__up' 2>/dev/null)
UL=$(awk -v b="${UL:-0}" 'BEGIN{printf "%.1f", b*8/1000000}')
echo "Indirme: $DL Mbps   Yukleme: $UL Mbps"
echo "(Bunu internet paketinin hiziyla karsilastir. Cok dusukse ve 1-4. adimlar temizse sorun saglayicida.)"

echo
echo "=== 6/8  Yuk altinda gecikme (bufferbloat) ==="
curl -s -o /dev/null -m 14 'https://speed.cloudflare.com/__down?bytes=200000000' 2>/dev/null &
CPID=$!
sleep 2
ping_olc 1.1.1.1 40
kill "$CPID" 2>/dev/null; wait "$CPID" 2>/dev/null
echo "Bosta: ${IDLE:-?} ms   Indirme sirasinda: ${AVG:-?} ms"
if [ -n "$IDLE" ] && [ -n "$AVG" ]; then
  fark=$(awk -v a="$AVG" -v b="$IDLE" 'BEGIN{printf "%d", a-b}')
  if [ "$fark" -gt 100 ]; then
    sorun "Bufferbloat: biri bir sey indirince gecikme ${fark} ms artiyor. Agdaki tek bir cihazin indirmesi/yuklemesi (guncelleme, yedekleme, torrent, 4K yayin) herkesi yavaslatir. Modemde QoS/SQM/'akilli kuyruk' varsa ac."
  elif [ "$fark" -gt 40 ]; then
    sorun "Hafif bufferbloat: yuk altinda gecikme ${fark} ms artiyor. Oyun/goruntulu aramada takilma yapabilir."
  else
    echo "-> Iyi: yuk altinda gecikme pek artmiyor."
  fi
fi

echo
echo "=== 7/8  DNS hizi (site adlarini cozme suresi) ==="
if var dig; then
  for s in "$GW" 1.1.1.1 8.8.8.8; do
    t=0; n=0
    for d in google.com youtube.com wikipedia.org instagram.com; do
      q=$(dig @"$s" "$d" +tries=1 +time=2 2>/dev/null | sed -n 's/.*Query time: \([0-9]*\) msec.*/\1/p')
      [ -n "$q" ] && { t=$((t+q)); n=$((n+1)); }
    done
    if [ "$n" -gt 0 ]; then r=$((t/n)); echo "$s: ortalama $r ms"; else r=""; echo "$s: cevap yok"; fi
    case $s in 1.1.1.1) CF=$r;; 8.8.8.8) ;; *) MD=$r;; esac
  done
  if [ -z "$MD" ]; then
    echo "-> Modemin DNS'i cevap vermedi; telefon baska bir DNS kullaniyor olabilir."
  elif [ -n "$CF" ] && [ "$MD" -gt $((CF+50)) ]; then
    sorun "Modemin DNS'i yavas (${MD} ms, 1.1.1.1: ${CF} ms). Telefonda Ayarlar > Ozel DNS'e 'one.one.one.one' yaz ya da modemde DNS'i 1.1.1.1 yap."
  fi
else
  echo "Atlandi: 'dnsutils' kurulu degil."
fi

echo
echo "=== 8/8  Agdaki cihazlar ve cevredeki Wi-Fi aglari ==="
if var nmap; then
  NET=$(echo "${IP:-$GW}" | sed 's/\.[0-9]*$/.0\/24/')
  nmap -sn -T4 "$NET" 2>/dev/null | sed -n 's/^Nmap scan report for /  /p' > cihazlar.tmp
  cnt=$(wc -l < cihazlar.tmp | tr -d ' ')
  echo "Cevap veren cihaz: $cnt  ($NET)"
  cat cihazlar.tmp
  echo "(Uyuyan telefonlar listede gorunmeyebilir. Tanimadigin cihaz varsa Wi-Fi sifreni degistir.)"
  [ "$cnt" -gt 15 ] && sorun "Agda cok cihaz var ($cnt). Kullanilmayanlari kapat; tanimadigin varsa Wi-Fi sifresini degistir."
  rm -f cihazlar.tmp
else
  echo "Cihaz taramasi atlandi: 'nmap' kurulu degil."
fi
echo
if var termux-wifi-scaninfo && var jq; then
  scan=$(termux-wifi-scaninfo 2>/dev/null)
  if echo "$scan" | jq -e 'type=="array" and length>0' >/dev/null 2>&1; then
    echo "Kanal (MHz)   Ag sayisi   En guclu sinyal"
    echo "$scan" | jq -r 'group_by(.frequency_mhz)[] | "\(.[0].frequency_mhz) \(length) \(map(.rssi)|max)"' |
      awk '{printf "%-13s %-11s %s dBm\n", $1, $2, $3}'
    n24=$(echo "$scan" | jq '[.[]|select(.frequency_mhz<3000)]|length')
    n5=$(echo "$scan" | jq '[.[]|select(.frequency_mhz>=5000)]|length')
    echo "2,4 GHz'te $n24 ag, 5 GHz'te $n5 ag goruluyor."
    if [ -n "$freq" ] && [ "$freq" != "-1" ]; then
      ayni=$(echo "$scan" | jq --argjson f "$freq" '[.[]|select(.frequency_mhz==$f)]|length')
      [ "$ayni" -gt 3 ] && sorun "Senin kanalinda ($freq MHz) $((ayni-1)) baska ag daha var. Modem ayarlarinda daha bos bir kanal sec (2,4 GHz'te 1, 6 ya da 11)."
    fi
    [ "$n24" -ge 10 ] && sorun "2,4 GHz cok kalabalik ($n24 ag). Komsu aglar hizi dusuruyor; mumkunse 5 GHz kullan."
  else
    echo "Wi-Fi taramasi alinamadi (Termux:API'ye konum izni ver, konumu ac)."
  fi
else
  echo "Wi-Fi taramasi atlandi: 'termux-api' ve 'jq' kurulu degil."
fi

echo
echo "================ SONUC ================"
if [ -z "$SORUNLAR" ]; then
  echo "Belirgin bir sorun bulunmadi. Yavaslik belirli saatlerdeyse o saatte tekrar calistir."
else
  echo "Interneti yavaslatan bulunanlar:$SORUNLAR"
fi
echo
echo "Bitti. Bu ekranin tamamini kopyalayip at."
