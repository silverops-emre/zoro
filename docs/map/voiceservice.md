# Zoro Android: ses servisi, manifest ve alarm/receiver haritası

Kök dizin: `/home/emreds/Desktop/musteriler/zoro/zoro2/android/app/src/main/`. Aşağıda `Main`, `MainActivity.kt` demek; `Svc`, `java/ZoroVoiceService.kt` demek.

## 1. ZoroVoiceService (`java/ZoroVoiceService.kt`)

**Paket ve dizin**
- Paket `com.zaferyilmaz.zoro` (Svc:1). Dosya `java/` kökünde duruyor, `java/com/zaferyilmaz/zoro/` altında değil.
- Kotlin paket-dizin eşleşmesi zorlamaz, bu yüzden derlenir.
- namespace ve applicationId de `com.zaferyilmaz.zoro` (`build.gradle.kts:7,13`).
- `minSdk=24`, `targetSdk=37` (`build.gradle.kts:14-15`).

**Uyandırma kelimesi**
- Regex `\b(?:zoro|zorro|zora|zara)\b`, IGNORE_CASE (Svc:74-77). Eşleştirme `candidate.lowercase(tr)` üzerinde, 5 alternatifin hepsinde sırayla yapılıyor (Svc:767-780).
- Türkçe `lowercase` büyük "I" harfini noktasız "ı"ya çevirir. Bu `zoro` harflerini etkilemez.
- Komut, eşleşen kelimeden sonraki metindir; baştaki `, . ! ?` ve boşluk kırpılır (Svc:803-814).
- Komut doluysa hemen `sendVoiceCommand` çağrılır (Svc:816-821). Boşsa "wake-only" akışı başlar: `waitingForCommand=true`, 12 sn `commandTimeout` (Svc:825-834), ardından TTS ile "Dinliyorum." (Svc:848-881).
- Bekleme modunda sonraki konuşma wake kelimesi aranmadan komut sayılır (Svc:748-761).

**Dinleme döngüsü**
- Her seferinde tek `startListening` çalışır. Sürekli akış yok, kısa oturumlar art arda yeniden kurulur. `SpeechRecognizer.createSpeechRecognizer(this)` kullanılır (Svc:468), `EXTRA_PREFER_OFFLINE` yok.
- Intent (Svc:627-652): `ACTION_RECOGNIZE_SPEECH`, `LANGUAGE_MODEL_FREE_FORM`, `EXTRA_LANGUAGE="tr-TR"`, `PARTIAL_RESULTS=false`, `MAX_RESULTS=5`.
- `EXTRA_CALLING_PACKAGE`, `EXTRA_LANGUAGE_PREFERENCE` ve sessizlik süresi extra'ları YOK.
- Yeniden başlatma süreleri:
  - Servis açılışı 500 ms (Svc:256).
  - Sonuç işlendikten sonra: wake bulunamadı veya boş ise 900 ms (Svc:738, 793), komut gönderildiyse 750 ms (Svc:759, 821).
  - Hata durumunda: `NO_MATCH` ve `SPEECH_TIMEOUT` için 1200 ms, `RECOGNIZER_BUSY` için 3000 ms, diğerleri için 2000 ms (Svc:562-576).
  - TTS bitince 350 ms, TTS hatasında 500 ms (Svc:417, 435).
- Watchdog 14000 ms (Svc:668-671). Sonuç gelmezse `cancel()` yapıp 1800 ms sonra yeniden başlatır (Svc:129-157).
- `ERROR_INSUFFICIENT_PERMISSIONS` gelirse `stopSelf()` (Svc:543-556).

**Durum ve bayraklar**
- Bayraklar: `running`, `recognizing`, `speaking`, `waitingForCommand`, `ttsReady` (Svc:66-70).
- Yayınlanan durumlar (Svc:47-50): `OFF`, `STARTING`, `LISTENING`, `WAITING_COMMAND`.
- Durum SharedPreferences'a yazılır (`zoro_voice_status` / `current_status`, Svc:91-96) ve broadcast edilir.
- `speaking` yalnızca "Dinliyorum." cevabında true olur (Svc:865). `beginListening` başında `speaking` kontrol edilir (Svc:619-625). Yani yalnızca servisin kendi TTS'i için koruma var.

**Broadcast'ler**

| Action | Extra | Kullanım |
|---|---|---|
| `com.zaferyilmaz.zoro.VOICE_COMMAND` | `voice_command` (String) | Svc:902-914, `setPackage(packageName)` ile açık paket |
| `com.zaferyilmaz.zoro.VOICE_STATUS` | `voice_status` (String) | Svc:98-103, `setPackage(packageName)` |
| `START_VOICE` (Svc:30) | - | Main:567'de intent action olarak veriliyor |
| `STOP_VOICE` (Svc:33) | - | Servis içinde işleniyor (Svc:184) |

- Ek olarak `ACTION_STOP`, bildirimdeki "Dinlemeyi durdur" `PendingIntent.getService` ile gelir (Svc:324-339, 368-373).
- `onStartCommand` içinde `ACTION_START` ayrıca kontrol edilmiyor. Stop dışındaki her intent başlatma sayılır.

**Servisin dinledikleri**
- Dinlediği broadcast YOK.
- Girdisi yalnızca `onStartCommand` intent'leri (`START`/`STOP`) ve SpeechRecognizer/TTS callback'leridir.
- `onBind` null döner (Svc:159).

**Foreground servis**
- Tip `microphone`: Q+ için `startForeground(3050, n, FOREGROUND_SERVICE_TYPE_MICROPHONE)` (Svc:217-235). Manifest'te de `foregroundServiceType="microphone"` (Manifest:55).
- Kanal `zoro_voice`, `IMPORTANCE_LOW` (Svc:288-291).
- Bildirim "Zoro sesli dinleme", `setOngoing`, içerik intent'i `MainActivity`, bir de "Dinlemeyi durdur" aksiyonu var (Svc:358-374).
- Servis kendi `TextToSpeech`'ini `onCreate`'te açar (Svc:166, 381-460). Dil tr-TR, pitch 0.85, rate 0.95. `AudioAttributes` ayarlanmamış.
- Dönüş değeri her yerde `START_NOT_STICKY` (Svc:187, 193, 210, 252, 274).

**MainActivity kapalıyken komut işleme**
- Servis komutu sadece broadcast ile iletir (Svc:914). Servisin kendi başına komutu işleyecek bir kodu, HTTP çağrısı veya kuyruğu YOK.
- Alıcı `voiceCommandReceiver` dinamik kayıtlıdır (Main:196-229). Kayıt `onCreate`'te (Main:273, 513-531), `RECEIVER_NOT_EXPORTED`. Kayıt kaldırma `onDestroy`'da (Main:2098-2102).
- Activity yok edilmişse `askZoro` hiç çalışmaz. Broadcast sessizce düşer.
- Sunucu adresleri `http://192.168.1.108:5050/ask` ve `/tasks` (Main:116, 119), LAN ve cleartext. Sunucu çağrısı yalnızca MainActivity'de.

## 2. AndroidManifest.xml

**İzinler** (Manifest:4-14)
- `RECORD_AUDIO`, `INTERNET`, `ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, `RECEIVE_BOOT_COMPLETED`.
- `SCHEDULE_EXACT_ALARM`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`.
- `READ_CALENDAR`, `WRITE_CALENDAR`.
- YOK: `USE_EXACT_ALARM`, `WAKE_LOCK`, `USE_FULL_SCREEN_INTENT`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

**`<queries>`** (Manifest:16-24)
- `android.speech.RecognitionService` ve `android.intent.action.TTS_SERVICE` intent'leri.

**Application** (Manifest:26-35)
- `allowBackup=true`, `usesCleartextTraffic=true`.
- `dataExtractionRules` ve `fullBackupContent` tanımlı.

**Bileşenler**

| Bileşen | exported | Intent-filter | Not |
|---|---|---|---|
| `.MainActivity` (37) | true | MAIN + LAUNCHER | `windowSoftInputMode=adjustResize` |
| `.ZoroVoiceService` (51) | false | yok | `foregroundServiceType=microphone`, `enabled=true` |
| `.ReminderReceiver` (58) | false | yok | |
| `.ZoroCalendarReminderReceiver` (63) | false | yok | |
| `.ZoroMorningBriefingReceiver` (69) | false | yok | |
| `.BootReceiver` (75) | false | `BOOT_COMPLETED` | |

- `BootReceiver` `exported=false` ile `BOOT_COMPLETED` alıyor. Sistem broadcast'i olduğu için çalışır.
- `LOCKED_BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET` ve `TIMEZONE_CHANGED` filtreleri YOK. `directBootAware` da YOK.

## 3. Receiver ve scheduler'lar

Hiçbirinde sunucu çağrısı yok. `http`, `URL`, `OkHttp`, `Authorization` ve `X-` header araması bu dosyalarda sonuçsuz. Hepsi yerel veri (ReminderStore, CalendarManager) kullanıyor.

**BootReceiver** (`BootReceiver.kt`)
- Tetikleyici `BOOT_COMPLETED` (BootReceiver:26).
- `goAsync` + Thread içinde şunları yapar (BootReceiver:37-73): `restoreTaskReminders` (80-233), `restoreCalendarReminders` (239-274), `ZoroMorningBriefingScheduler.scheduleNext` (54).
- `restoreTaskReminders` geçmiş tek seferlik kayıtları siler. Tekrarlıları sonraki aralığa taşır.
- Alarm kurma `setExactAndAllowWhileIdle` veya `setAndAllowWhileIdle` ile yapılır (BootReceiver:280-361). `canScheduleExactAlarms` kontrolü var.
- `ZoroVoiceService`'i BAŞLATMIYOR.

**ReminderReceiver** (görev hatırlatma)
- Tetikleyici alarm intent'i. Extra'lar `alarm_id`, `task_text`, `repeat_interval` (ReminderReceiver:40, BootReceiver:175-188).
- Kayıt ReminderStore'dan okunur (ReminderReceiver:47-59). Bulunmazsa sessizce çıkar.
- Bildirim kanalı `zoro_reminders`. Kanal burada OLUŞTURULMUYOR, `MainActivity.createNotificationChannel` içinde oluşturuluyor (Main:486-505, çağrı Main:271).
- TTS doğrudan kullanılıyor (ReminderReceiver:163-292):
  - Metin "Zafer Bey, hatırlatmanız var. ..." biçiminde. Metinde sabit bir "21:35" düzeltmesi var (ReminderReceiver:152-160).
  - `USAGE_ASSISTANCE_ACCESSIBILITY` kullanılır (ReminderReceiver:209-217).
  - Zaman aşımı 8 sn (ReminderReceiver:33, 140-148).
- Tekrarlıysa bir sonraki alarmı kurar (ReminderReceiver:89-93, 374-476). Değilse kaydı siler (ReminderReceiver:95).
- Kayıt silme, TTS bitmeden önce yapılıyor.

**ZoroCalendarAlarmScheduler** (`ZoroCalendarAlarmScheduler.kt`)
- Action `com.zaferyilmaz.zoro.CALENDAR_REMINDER` (satır 30).
- Desteklenen süreler 5, 10, 15, 30, 60, 120 dk; varsayılan 30 (satır 26, 36-43).
- `schedule` (satır 136-): sadece `SOURCE_ZORO`, tüm gün olmayan etkinlikler için kurar. Tetik = başlangıç - dakika. `exactAllowed` ise `setExactAndAllowWhileIdle`, değilse `setAndAllowWhileIdle`.
- `restoreFutureAlarms` (satır 440-476) boot'ta çağrılır.
- PendingIntent flag'leri `UPDATE_CURRENT | IMMUTABLE`.

**ZoroCalendarReminderReceiver** (`ZoroCalendarReminderReceiver.kt`)
- Tetikleyici takvim alarmı. Extra'lar `zoro_calendar_event_id`, `_title`, `_time`; kimlik veya başlık boşsa çıkar (satır 25-73).
- Mesaj "Zafer Bey, saat X için Y randevunuz var. Randevunuza N dakika kaldı." (satır 100-111).
- Kanal `zoro_calendar_reminders` (HIGH) bu receiver içinde oluşturulur (satır 164-185).
- TTS doğrudan (satır 249-422): tr-TR, pitch 0.85, `goAsync`, 8 sn zaman aşımı. AudioAttributes ayarlanmamış.
- Kullanıcıya dönük bildirim izni yoksa bildirim atlanır (satır 144-157), TTS yine çalışır.

**ZoroCalendarReminderVoice** (`ZoroCalendarReminderVoice.kt`)
- Receiver değil, `object`. Sesli komuttan hatırlatma süresini değiştirir; `isReminderChangeCommand` (satır ~79) ve `handleCommand` (satır ~357-). `ZoroCalendarAlarmScheduler.setReminderMinutes` / `reschedule` çağırır.
- TTS yok, sunucu yok. Sonucu çağırana döndürür.

## 4. Sabah brifingi

**Zamanlama** (`ZoroMorningBriefingScheduler.kt`)
- Her gün 10:00, `Europe/Istanbul` (satır 18-19, 36-38). Geçmişse ertesi gün (satır 64-72).
- Action `com.zaferyilmaz.zoro.MORNING_BRIEFING`, requestCode 101000. Kesin alarm veya fallback (satır 91-111).
- Kurulum noktaları: `MainActivity.onCreate` (Main:247), `BootReceiver` (BootReceiver:54), receiver'ın kendisi (`ZoroMorningBriefingReceiver.kt:40`, ertesi günü yeniden kurar).

**İçerik** (`ZoroMorningBriefingReceiver.kt:74-189`)
- "Zafer Bey, günaydın." + bugünün takvim etkinlikleri.
  - Kaynak `CalendarManager.getAllEvents(gün başı, gün sonu)`; yerel Zoro takvimi ve cihaz takvimi (satır 98-107).
  - İlk 10 etkinlik "Saat HH:mm, başlık" olarak okunur. Tüm gün olanlar "Tüm gün: ...". 10'dan fazlaysa "Takvimim bölümünden görebilirsiniz" notu eklenir.
  - Etkinlik yoksa "bir randevu görünmüyor" der.
- `ReminderStore.getAllReminders` sayısı: "kayıtlı N görev hatırlatmanız bulunuyor" (satır 162-174). Tamamlanma bilgisi doğrulanmadığı için "bekleyen" denmiyor.
- Kapanış: "Güzel ve verimli bir gün dilerim."
- Sunucudan veri ÇEKİLMİYOR. Hava durumu, Ata'nın görevleri vb. YOK.

**Çıktı**
- Bildirim kanalı `zoro_morning_briefing` (HIGH), receiver içinde oluşturulur (satır 213-223). Bildirim izni kontrolü var (satır 200-207).
- TTS doğrudan (satır 269-394): tr-TR, 0.95/0.85. `goAsync` ve 8 sn zaman aşımı.
- Uzun brifing 8 sn'de `finishSafely` ile kesilir (satır 301-304). `tts.stop()` çağrıldığı için konuşma ortada kesilebilir.
- Logda `ZORO_MORNING_TEST` etiketi duruyor (satır 38).

## 5. Riskler

**(a) TTS'in kendini duyması**
- Servisin "Dinliyorum." cevabı sırasında `speaking=true` olduğu için yeni oturum başlamaz (Svc:619-625, 865).
- Ama recognizer o ana kadar boşta bekliyor olabilir. `onResults` tetiklendikten sonra `speakWakeReply` çağrılır ve `recognizing` zaten false (Svc:583). Burada sorun yok.
- Asıl boşluk servis dışı TTS'ler:
  - Receiver'lar (sabah brifingi, hatırlatma, takvim) ayrı `TextToSpeech` açıyor ve servise haber vermiyor.
  - Servis çalışırken (mic açık) bu konuşmalar yapılırsa Zoro kendi sesini duyar. Cümlede "zoro" geçmese bile ("Zafer Bey, ...") yanlış komut riski düşük, ama sesli komut bekleme modundaysa (12 sn) TTS metni komut olarak gönderilir (Svc:748-761).
  - `MainActivity.speak` (Main:462-476) cevabı okurken de servis dinlemeye devam ediyor. Servis ile aktivite arasında "speaking" senkronizasyonu YOK; Svc:68 yalnızca servisin kendi TTS'ini kapsıyor.
  - Cevapta "zoro" kelimesi geçerse (ör. "Ben Zoro...") kendini tetikleyebilir. Regex kelime sınırlı olduğu için `\bzoro\b` bunu yakalar.
- TTS bitişi ile restart arasındaki pay: 350 ms. Hoparlör kuyruğu bu sürede tamamen susmayabilir.

**(b) Activity kapalıyken komut kaybı**
- Activity `onDestroy` olunca receiver kaldırılır (Main:2098). Foreground servis çalışmaya devam eder ve dinler, ama komut broadcast'i alıcısız kalır. Kullanıcıya hata veya bildirim gösterilmez, durum `LISTENING` olarak görünmeye devam eder.
- Activity arka plandayken (onStop) receiver hâlâ kayıtlıdır. Ancak süreç önbelleğe alınır veya öldürülürse `askZoro` (Compose/coroutine, sunucu çağrısı) kesilebilir.
- Wake-only akışında "Dinliyorum." duyulur ama komut broadcast'i kaybolabilir.
- Çözüm yönü: servis içinde komutu doğrudan sunucuya iletmek veya `PendingIntent` ile Activity başlatmak (Android 10+ arka plandan Activity başlatma kısıtı var; `USE_FULL_SCREEN_INTENT` veya bildirim aksiyonu gerekir).

**(c) Android 14/15/16 (targetSdk 37)**
- **Foreground service**
  - `FOREGROUND_SERVICE_MICROPHONE` izni ve `microphone` tipi tanımlı, doğru.
  - Android 14+ mikrofon tipli servis arka plandan BAŞLATILAMAZ (while-in-use kısıtı). Boot'ta, alarmda veya receiver'dan başlatma `ForegroundServiceStartNotAllowedException` / `SecurityException` fırlatır.
  - Servis yalnızca `MainActivity` ön plandayken başlatılıyor (Main:575). Bu şu an güvenli. Boot sonrası otomatik dinleme YOK ve BootReceiver'dan eklemek çalışmaz.
  - Android 15'te `BOOT_COMPLETED` receiver'ından mikrofon tipli FGS başlatmak açıkça yasak.
  - `startForeground` çağrısı try/catch içinde; hata `stopSelf()` ile sonuçlanır (Svc:263-272). Bildirim izni reddedilmişse servis çalışır ama bildirim görünmez.
- **Kesin alarm**
  - `SCHEDULE_EXACT_ALARM` Android 14+'ta yeni kurulumlarda varsayılan olarak REDDEDİLİR. Kullanıcı Ayarlar'dan açmalı.
  - Kod `canScheduleExactAlarms()` ile kontrol edip `setAndAllowWhileIdle`'a düşüyor (ör. Scheduler:91-111). Düşünce alarm dakikalarca gecikebilir (sabah 10:00 brifingi dahil).
  - `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` ile kullanıcıyı yönlendiren kod `MainActivity.kt` içinde arandı: eşleşme YOK (yalnızca `canScheduleExact` kontrolü Main:1761).
  - Hatırlatma ve brifing gibi kullanım için `USE_EXACT_ALARM` düşünülebilir (Play politikası: alarm/takvim uygulaması).
  - İzin iptal edilirse sistem tüm kesin alarmları siler ve `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` yayınlar. Bu dinlenmiyor, yeniden kurma YOK.
  - Saat veya saat dilimi değişince (`TIME_SET` / `TIMEZONE_CHANGED`) ve uygulama güncellenince (`MY_PACKAGE_REPLACED`) alarmlar yeniden kurulmuyor. Güncelleme sonrası alarmlar silinir; yalnızca uygulamayı açınca brifing yeniden kurulur (Main:247). Görev ve takvim alarmları boot'a kadar kayıp kalır.
- **POST_NOTIFICATIONS**
  - Android 13+ çalışma zamanı izni. Main:378-395'te isteniyor.
  - Receiver'lar izin yoksa bildirimi atlar. TTS yine konuşur ama kullanıcı görsel kayıt göremez.
  - `ReminderReceiver` kanalı (`zoro_reminders`) yalnızca `MainActivity` açıldığında oluşur. Uygulama hiç açılmadan boot sonrası alarm gelirse kanal yoktur, bildirim düşer.
  - `ReminderReceiver` bildirim sırasında kanal yoksa Android 8+ bildirimi yutar.
- **Receiver export bayrakları**
  - Manifest receiver'ları `exported=false`; `MainActivity`'deki dinamik receiver `RECEIVER_NOT_EXPORTED` (Main:526-531). Doğru.
  - Dinamik receiver `onCreate`'te kaydedilip `onDestroy`'da kaldırılıyor. `unregisterReceiver` çift çağrı veya kayıtsız hata riski `commandReceiverRegistered` bayrağıyla korunmuş.
  - PendingIntent'ler `FLAG_IMMUTABLE`; alarm intent'leri açık bileşen (`Intent(context, X::class.java)`), güvenli.
- **Arka plandan TTS ve ses**
  - Receiver'larda `goAsync` penceresi yaklaşık 10 sn; 8 sn sabit zaman aşımı kullanılıyor. Uzun brifing kesilir.
  - Android 12+ arka plandan ses odağı ve TTS bazı OEM'lerde sessiz kalabilir. Sabah brifingi ve hatırlatma için foreground servis veya yüksek öncelikli bildirim kullanılmıyor.
  - Doze'da `setAndAllowWhileIdle` throttle edilir (yaklaşık 9 dk).
- **Diğer**
  - `ACCESS_LOCAL_NETWORK` (Android 16/17 yerel ağ izni) manifestte var, ama çalışma zamanı isteği kodda arandı: bu araştırmada `MainActivity.kt` içinde eşleşme bulunmadı. Sunucu `192.168.1.108` yerel ağda olduğu için Android 17+ (targetSdk 37) bu izin verilmeden bağlantıyı engelleyebilir. Bunu doğrulamadım, açık nokta.
  - `usesCleartextTraffic=true` ve LAN HTTP, IP sabit kodlu.
  - Pil optimizasyonu muafiyeti ve OEM (Samsung vb.) arka plan kısıtları ele alınmamış.
  - Servis `START_NOT_STICKY`. Sistem servisi öldürürse otomatik geri dönmez.