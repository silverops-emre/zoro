# Zoro 2.1 — Kurulum ve geçiş kılavuzu

Bu kılavuz Zafer Bey'in sunucusuna (Hetzner, Helsinki) yeni arka ucu ve telefonuna yeni uygulamayı kurmak içindir. **Hiçbir adım henüz yapılmadı.** Her adım Emre'nin "bas" demesiyle yapılır.

## 0. Önce elde olması gerekenler

| Ne | Kimden | Neden |
|---|---|---|
| Sunucuya SSH erişimi | Zafer Bey | Anahtar şu an yalnız onun Windows bilgisayarında (`%USERPROFILE%\.ssh\zoro_hetzner`). Bizim açık anahtarımızı `/root/.ssh/authorized_keys` dosyasına eklemesi en temizi. |
| Caddy basic-auth kullanıcı adı ve şifresi | Zafer Bey (telefondaki uygulamaya ChatGPT ile girilmişti) | Yeni APK sunucuya bununla bağlanır. Caddyfile'da şifre yalnızca hash olarak duruyor. |
| Telefondaki APK'yı imzalayan anahtar | Zafer Bey'in bilgisayarı | Aynı anahtarla imzalarsak uygulama **güncelleme** olarak kurulur, telefondaki takvim, hatırlatma ve hafıza kaybolmaz. Android Studio'da "Run" ile kurduysa dosya `%USERPROFILE%\.android\debug.keystore` (şifre `android`). |
| OpenAI anahtarı | Sunucudaki `.env` | Değişmiyor. Model de aynı kalıyor (`gpt-6-luna`), istenirse `.env` içinde `ZORO_MODEL` ile değişir. |

## 1. Sunucuyu tanı (salt okunur)

```bash
scp backend/deploy/inspect.sh root@37.27.156.54:/root/
ssh root@37.27.156.54 'bash /root/inspect.sh' > zoro-inspect.txt
```

Çıktıdan şunları not et: servis adı, `WorkingDirectory` (APP_DIR), venv yolu, gunicorn `--timeout`, `app.py sha256`.

**`app.py` hash'i `65f702a1…` değilse** sunucuda bizde olmayan değişiklik var demektir. Önce o dosyayı indir (`scp root@…:APP_DIR/app.py sunucu-app.py`) ve farka bak. Deploy betiği bu durumda kendiliğinden durur.

## 2. Arka ucu kur (eski uygulama çalışmaya devam eder)

```bash
bash backend/deploy/make-release.sh                       # dist/zoro2-backend-<sha>.tgz
scp dist/zoro2-backend-*.tgz root@37.27.156.54:/root/
ssh root@37.27.156.54
  mkdir -p /root/zoro2-release && tar xzf /root/zoro2-backend-*.tgz -C /root/zoro2-release
  APP_DIR=<inspect çıktısı> SERVICE=<servis adı> RELEASE=/root/zoro2-release bash /root/zoro2-release/deploy/deploy.sh
```

Betik sırasıyla şunları yapar:

1. `/root/zoro-backups/` altına yedek alır.
2. Paketleri kurar.
3. Verinin bir **kopyasıyla** deneme çalıştırması yapar.
4. Kodu değiştirir.
5. Servisi yeniden başlatır.
6. Sağlık ve veri kontrolü yapar.
7. Herhangi bir adım bozulursa yedekten geri alır.

**Yerelde denendi:** systemd + 2 worker'lı gunicorn ile v1'den v2'ye geçiş yapıldı, bozuk sürüm yüklenince otomatik geri dönüş çalıştı.

- **Veri:** İlk açılışta `zoro_memory.json`, `zoro_tasks.json` ve `zoro_personal_memory.json` aynı kimliklerle `zoro.db` içine aktarılır. JSON dosyaları silinmez.
- **`.env`:** Dokunulmaz. İstenirse eklenebilecek ayarlar `.env.example` dosyasında:
  - `ZORO_REASONING_EFFORT=low`: model "reasoning" tipindeyse gecikmeyi düşürür.
  - `ZORO_WEB_SEARCH`, `ZORO_TTS_VOICE`, `ZORO_MONTHLY_BUDGET_USD`: yalnızca ilk değerlerdir. Sonrasını telefondaki **Ayarlar ve maliyet** ekranı belirler (`zoro.db` içinde saklanır).
  - `ZORO_TTS_MODEL=gpt-4o-mini-tts`: doğal ses modeli. **OpenAI bu modeli 6 Ocak 2027'de kapatıyor.** O tarihten sonra telefon kendi sesine döner; sağlayıcı değişikliği ayrı iş.
  - `ZORO_PRICE_OVERRIDES`: OpenAI fiyatı değişirse maliyet ekranı için düzeltme.
- **Yeni bağımlılık yok.** `requirements.txt` aynı.
- **Maliyet ölçümü:** Her OpenAI çağrısının token/arama sayısı `zoro.db` içindeki `usage` tablosuna yazılır. Telefonun maliyet ekranı bunu liste fiyatıyla (31.10.2026'da `developers.openai.com/api/docs/pricing` sayfasından) dolara, TCMB kuruyla liraya çevirir. Kesin fatura OpenAI panelindedir.
- **Eski uygulama da çalışır:** Telefondaki mevcut uygulama yeni arka uçla konuşabilir. Hatırlatma, görev ve sohbet çalışır. Navigasyon, arama ve takvim değişikliği için yeni APK gerekir. Arka uç, eski uygulama için cevabı 9 saniyede keser, çünkü o uygulama 10 saniyede vazgeçiyor.

**Telefonda dene (eski uygulamayla):** "Zoro, yarın sabah 9'da su içmemi hatırlat" → alarm kurulmalı.

**Geri alma:**

```bash
APP_DIR=… SERVICE=… BACKUP=/root/zoro-backups/zoro-<tarih>.tgz bash rollback.sh
```

v2 çalışırken eklenen kayıtlar `zoro.db` içinde kalır. Geri dönülen v1 bunları görmez.

## 3. Yeni APK'yı derle

```bash
cp android/zoro.properties.example android/zoro.properties
# zoro.serverUrl, zoro.basicUser, zoro.basicPassword, (varsa) zoro.keystore.path doldur
cd android && JAVA_HOME=~/.local/jdks/jdk-25.0.3+9 ANDROID_HOME=~/Android/Sdk ./gradlew assembleDebug
# çıktı: android/app/build/outputs/apk/debug/app-debug.apk
```

`zoro.properties` git'e girmez. Şifreler APK'nın içine gömülür, bu yüzden APK'yı kimseyle paylaşma.

## 4. Telefona kur

**A. İmza anahtarı bizdeyse (önerilen):** `adb install -r app-debug.apk` ya da dosyayı telefona atıp güncelle. Veriler kalır. Uygulama ilk açılışta telefondaki "bunu unutma" kayıtlarını bir kez sunucuya yükler.

**B. Anahtar yoksa:** Eski uygulamayı kaldırmak gerekir ve bu telefondaki yerel takvim, hatırlatma ve hafızayı siler. Önce dışa aktar. Kurulu uygulama debug derlemesiyse (Android Studio "Run") şu çalışır:

```bash
adb exec-out run-as com.zaferyilmaz.zoro tar cf - shared_prefs > zoro-prefs-yedek.tar
tar tf zoro-prefs-yedek.tar          # zoro_calendar.xml, zoro_reminder_store.xml, zoro_personal_memory.xml ...
adb uninstall com.zaferyilmaz.zoro
adb install app-debug.apk
adb shell am force-stop com.zaferyilmaz.zoro
adb exec-in run-as com.zaferyilmaz.zoro tar xf - < zoro-prefs-yedek.tar
adb reboot                            # alarmlar açılışta BootReceiver ile yeniden kurulur
```

Emülatörde denendi. Dışa aktarma, kaldırma, kurma ve geri yükleme adımlarından sonra takvim ve hatırlatma kayıtları yerindeydi. Kaldırma alarmları da sildiği için en sonda telefon yeniden başlatılmalı.

`run-as` "package not debuggable" derse kurulu sürüm release imzalıdır. O zaman imza anahtarı mutlaka gerekir, yoksa yerel takvim elle taşınır.

**İlk açılışta izinler:** mikrofon, bildirim, rehber, arama, takvim ve arama kaydı.
- Rehber izni "Okay'ı ara" ve "Okay'a yaz" için gerekir.
- Takvim izni Google Takvim'i görmek ve tek seferlik etkinlikleri taşıyıp silmek için gerekir.
- Arama kaydı izni "Beni kim aradı?" için gerekir.
- **Ayarlar ve maliyet** ekranında "Pil kısıtlamasını kaldır" düğmesine bir kez basılmalı; yoksa telefon kilitliyken Zoro'yu durdurabilir.

## 5. Yeni APK kurulduktan sonra (isteğe bağlı sıkılaştırma)

Sunucu `.env` dosyasına `ZORO_API_KEY=<rastgele uzun değer>` ekle, aynı değeri `zoro.properties` içindeki `zoro.apiKey` alanına yaz ve APK'yı yeniden derleyip kur. Böylece Caddy şifresi sızsa bile uygulama anahtarı olmadan istek geçmez. Eski uygulama bu anahtarı göndermediği için **bu adım yeni APK'dan sonra** yapılır.

## 6. Telefonda kabul testi (yeni APK)

| Söyle | Beklenen |
|---|---|
| "Zoro, beni Kadıköy'e götür" | Google Haritalar yol tarifi açılır |
| "Zoro, Okay'ı ara" | Rehberde tek Okay varsa arama başlar; iki tane varsa hangisi diye sorar |
| "Zoro, Okay'a yarın geliyorum diye WhatsApp'tan yaz" | WhatsApp taslağı açılır, gönder tuşuna sen basarsın |
| "Zoro, toplantım ne zaman?" | Takvimden okur |
| "Zoro, cuma 3'te Ahmet Bey'le görüşmeyi takvime ekle" | Takvimde görünür |
| "Zoro, o görüşmeyi sil" | "… silinsin mi?" diye sorar, "evet" deyince siler |
| "Zoro, her gün akşam 8'de ilacımı hatırlat" | 20:00'de sesli hatırlatma, "içtim" deyince seri sürer |
| "Zoro, yarın 7'de beni uyandır" | Saat uygulamasında 07:00 alarmı |
| "Zoro, hava nasıl?" / "Dolar kaç?" | Open-Meteo / TCMB'den okur |
| İnternet kapalıyken "takvimde ne var" | Telefondaki yerel cevap |
| **2.1** | |
| Ana ekran → **Ayarlar ve maliyet** | Bu ayki tutar ($ ve ₺), ay sonu tahmini, bütçe, özellik düğmeleri |
| Doğal ses aç → "Seçili sesi dinle" | Örnek cümle doğal sesle okunur |
| "Zoro, beni kim aradı?" → "Onu geri ara" | Son aramayı söyler, aynı numarayı arar |
| "Zoro, yarınki toplantıyı 3'e al" (Google Takvim'deki tek seferlik etkinlik) | Google Takvim'de 15:00'e taşınır |
| "Zoro, toplantıyı sil" → "evet" | Google Takvim'den silinir; tekrarlayan etkinlikte "Zoro değiştiremez" der |
| Güncel bilgiyi aç → "Kadıköy'de nöbetçi eczane hangisi?" | Web'den bulup söyler, bağlantı okumaz |
| Uygulamadan geri tuşuyla çık, ekranı kilitle, "Zoro, yarın 9'da ilacımı hatırlat" | Ekran açılmadan alarm kurulur ve cevap okunur |
| Ekran kilitliyken "Zoro, Okay'ı ara" | Ekran açılmadan arama başlar (**gerçek telefonda ilk kez denenecek**) |
| Sabah özeti saatini 2 dk sonraya al | Hava, takvim, cevapsız aramalar, kur okunur; bildirim düşer |
