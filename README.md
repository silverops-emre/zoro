# Zoro 2.1

Zafer Bey'in sesli asistanı Zoro: Android uygulaması ve sunucu tarafı.

| Klasör | İçerik |
|---|---|
| `backend/` | Sunucu (Python / Flask). Kurulum betikleri `backend/deploy/` içinde. |
| `android/` | Android uygulaması (Kotlin). |
| `docs/` | Rapor, kurulum kılavuzu ve test kayıtları. |

## Başlarken

- **Ne değişti:** [`docs/ZORO-2.1.md`](docs/ZORO-2.1.md)
- **Kurulum ve geçiş:** [`docs/RUNBOOK.md`](docs/RUNBOOK.md) (sunucuyu tanı, arka ucu kur, APK derle, telefona kur, kabul testi)

## Gizli bilgiler

Anahtar ve şifreler repoda yoktur. Kurulumda örnek dosyalar kopyalanıp doldurulur:

- `backend/.env.example` → `backend/.env`
- `android/zoro.properties.example` → `android/zoro.properties`

Hazırlayan: [SilverOps](https://silverops.co)
