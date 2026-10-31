# Zoro 2.1 — güncelleme raporu (31 Ekim 2026)

2.0'ın üstüne eklendi. Sunucuya ve telefona **hiçbir şey kurulmadı**. Kurulum aynı: Zafer Bey'den 3 şey bekleniyor (`GECE-2026-10-31.md`) ve senin "bas"ın. Sıra ve komutlar `RUNBOOK.md` içinde.

Commit'ler: `cd028a2` (sunucu), `10368f4` (Android), `d36658a` (inceleme turu 3 düzeltmeleri).

## Ne eklendi

| Özellik | Nasıl çalışıyor | Ücret |
|---|---|---|
| **Ayarlar ve maliyet ekranı** | Bu ayki tutar ($ + TCMB kuruyla ₺), ay sonu tahmini, aylık bütçe, her özellik için aç/kapa ve "açıkken aylık ≈". | — |
| **Maliyet ölçümü** | Her OpenAI çağrısının token ve arama sayısı `zoro.db/usage` tablosuna yazılır, liste fiyatıyla dolara çevrilir. Fiyatlar `developers.openai.com/api/docs/pricing` sayfasından 31.10.2026'da alındı: gpt-6-luna $0,10 / $0,01 / $0,50, gpt-4o-mini-tts $0,60 / $12, web araması $10/1k. Tahmin olan her rakam "tahmin" diye işaretli. | — |
| **Bütçe sınırı** | Ay içi harcama bütçeye ulaşınca web araması ve doğal ses kendiliğinden kapanır. Beyin çalışmaya devam eder. Varsayılan 10 $, 0 = sınırsız. | — |
| **Güncel bilgi** | OpenAI `web_search` aracı (konum TR). Kaynak ve bağlantı okunmaz. | ölçülen ≈ 1,3 sent/arama |
| **Doğal ses** | `/tts` → gpt-4o-mini-tts, 24 kHz PCM akışı → AudioTrack. Sorun olursa telefonun en iyi çevrimdışı Türkçe sesine düşer. 8 ses seçeneği ve önizleme var. | ölçülen ≈ 0,28 sent/cevap |
| **Son aramalar** | "Beni kim aradı?", "Onu geri ara." Son 48 saatin en fazla 10 araması gider, yalnızca söz aramalarla ilgiliyse ve sabah özetinde. Rehberde olmayan numara doğrudan aranmaz, numara çeviricide açılır. | — |
| **Google Takvim'de değişiklik** | Tek seferlik, tüm gün olmayan ve düzenleyicisi Başkan olan etkinlikler sesle taşınır; silmeden önce sorar. Yalnızca değişen alan yazılır. Tekrarlayan ve davetli olunan etkinliklere dokunmaz. | — |
| **Kilitliyken / kapalıyken çalışma** | Ekran tamamen kapatılmışsa komutu ses servisi kendisi işler: hatırlatma kurar, cevabı okur. Ekran açamadığı işler bildirime düşer. | — |
| **Sabah özeti (sunucu)** | Saat ayardan seçilir (varsayılan 10:00, v1 ile aynı). Hava, bugünkü takvim ve hatırlatmalar, cevapsız aramalar, dolar ve euro okunur; güncel bilgi açıksa bir haber eklenir. Yalnızca okuma araçları kullanılır. Sunucu ya da yapay zekâ yoksa telefonun kendi özeti okunur. | ölçülen ≈ 0,25 sent + ses |

## Önemli karar: doğal ses modeli

- OpenAI `gpt-4o-mini-tts`, `tts-1` ve `tts-1-hd` modellerini **6 Ocak 2027'de kapatıyor**. Önerdiği yol Realtime API (`gpt-realtime-2.1-mini`).
- Senin anahtarında bu model yok. Aynı aileden `gpt-realtime-mini` ile 6 deneme yaptım ve sonuçlar `gpt-4o-mini-tts` ile karşılaştırıldı (scratchpad'deki denemeler):
  - **Realtime model metni aynen okumadı.** 6 denemenin 3'ünde başa kendi sözünü ekledi ("Tabii, şimdi okuyorum…", "Anladım, o halde metni seslendiriyorum…").
  - **`gpt-4o-mini-tts` metni birebir okudu.** Geri dinletip yazıya çevirerek kontrol ettim. İlk ses 0,6–0,8 saniyede geldi.
- Bu yüzden şimdilik `gpt-4o-mini-tts` kullanılıyor. Kapanma tarihinden sonra telefon kendi sesine döner, uygulama bozulmaz. Uygulamada bu not da var. Kapanmadan önce kalıcı bir ses sağlayıcısı seçilmeli.

## Doğrulama

- **Sunucu:** 124 test geçiyor (2.0'da 83). Yeni testler maliyet, bütçe, ses akışı, arama kaydı, takvim yazma ve inceleme bulgularının regresyonlarını kapsıyor.
- **Gerçek model:** Sentetik veriyle `gpt-5-mini` kullanıldı. Web araması, "kim aradı" ve "geri ara", doğal ses ve sabah özeti denendi.
- **Emülatör (Android 16, gerçek uygulama, yerel sunucu, gerçek model):**
  - Ayarlar ekranı (ekran görüntüsü `docs/eval/e2e/v21-*.png`), düğmeden doğal sesi açma, ses önizleme (AudioTrack 24 kHz, ses odağı alınıp bırakıldı).
  - "Beni kim aradı", "onu geri ara". Kayıtlı kişi doğrudan, bilinmeyen numara numara çeviricide açıldı.
  - Google Takvim:
    - etkinlik 10:00 → 15:00 taşındı,
    - "evet" ile silindi,
    - tekrarlayan etkinlik reddedildi,
    - davetli olunan toplantı reddedildi,
    - 142 karakterlik konum bilgisi bozulmadı.
  - Ekran kapalı ve uygulama kapatılmışken:
    - hatırlatma kuruldu, cevap doğal sesle okundu,
    - "Kadıköy'e götür" bildirime düştü,
    - sabah özeti (sunucu) okundu.
  - Sunucu kapalıyken sabah özetinin yerel yedeği okundu ve bildirimi düştü.
  - Bütçe 0,01 $'a çekilince web araması modele sunulmadı ve ses 402 döndü. Cevabı telefonun sesi okudu, ekranda "Bütçe doldu" uyarısı çıktı.
  - Bütçe alanına "2,5" yazınca 2,5 $; "1.000" yazınca 1000 olarak okundu.
  - Emülatörde sesle komut verilemediği için komutlar yalnızca debug derlemede açılan, dışa kapalı bir servis kancasıyla verildi. Bunu da root gerektiriyor.
- **Android lint:** 0 hata.
- **Bağımsız inceleme (tur 3):** 4 açıdan bakıldı, 28 ajan çalıştı (inceleme Opus, doğrulama Sonnet). 24 bulgu doğrulandı, 0'ı çürütüldü. Yüksek önemdeki ikisi:
  - sunucuya ulaşılamazsa sabah özeti hiç okunmuyordu,
  - kayıtlı olmayan numara (tek çalıp kapanan ücretli hat riski) doğrudan aranıyordu.

  Hepsi düzeltildi ve tekrar denendi. Ayrıntı: `docs/eval/review-r3.json`.
- **OpenAI harcaması (senin anahtarın):** Ölçülen 0,075 $, deneme scriptleri dahil tahminen ≈ 0,10 $.

## Bilinen sınırlar

- **Gerçek telefonda denenmedi:** Kilitliyken sesli komut (mikrofon) ve `TelecomManager` ile arama yalnızca emülatörde ve kancayla denendi.
- **Pil tasarrufu:** Telefon Zoro'yu arka planda durdurabilir. Kurulumda ayarlardaki "Pil kısıtlamasını kaldır" düğmesine basılmalı.
- **Sesle eklenen yeni toplantılar:** Sesli hatırlatma kaybolmasın diye hâlâ Zoro'nun kendi takviminde tutuluyor, Google Takvim'e yazılmıyor.
- **Maliyet tahmini:** İlk günlerde "henüz az veri" uyarısıyla gösteriliyor. Kesin tutar OpenAI panelinde.
- **Uyandırma kelimesi:** Hâlâ Google tanıyıcısıyla çalışıyor.
