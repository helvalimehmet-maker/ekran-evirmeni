# Ekran Çevirmeni

Tablette, ekranda seçtiğin bölgedeki İngilizce yazıları anında Türkçeye çeviren Android uygulaması.
Oyun, video, uygulama fark etmez: ekranda ne görünüyorsa onu okur.

**Nasıl çalışır?**
1. Android'in ekran kaydı özelliğiyle ekranı yakalar ve yalnızca senin seçtiğin çerçevenin içini keser.
2. Google ML Kit ile çerçevedeki yazıyı tabletin içinde (internetsiz) okur.
3. Okunan metni Google Gemini yapay zekâsına gönderip Türkçeye çevirtir. Önceki birkaç satırı da bağlam olarak gönderir, böylece diyaloglar tutarlı çevrilir.
4. Çeviriyi çerçevenin hemen altında (yer yoksa üstünde) altyazı olarak gösterir.

Aynı yazı ekranda dururken tekrar tekrar çeviri isteği gönderilmez. Bu hem hızı artırır hem de ücretsiz kotayı korur.

---

## 1. APK dosyasını oluşturma

Bu klasör uygulamanın kaynak kodu. Tablete kurmak için önce bir APK dosyasına dönüştürülmesi gerekiyor. İki yol var:

### Yol A: GitHub (bilgisayara program kurmadan)

1. Zip'i bilgisayarda ayıkla (sağ tık → Tümünü ayıkla).
2. github.com'da ücretsiz hesap aç.
3. Sağ üstteki **+** → **New repository**. Ad: `EkranCevirmeni`, **Private** seç, başka bir şey işaretleme → **Create repository**.
4. **uploading an existing file** bağlantısına tıkla. `EkranCevirmeni` klasörünün **içine gir**, her şeyi seç (Ctrl+A) ve tarayıcıya sürükle → **Commit changes**.
5. Depo sayfasında **Add file → Create new file**. Dosya adı olarak tam olarak `.github/workflows/apk.yml` yaz, aşağıdaki metni yapıştır → **Commit changes** (iki kez).
6. **Actions** sekmesinde "APK oluştur" 5–10 dakika çalışır. Yeşil tik çıkınca üstüne tıkla, en alttaki **Artifacts** bölümünden `EkranCevirmeni-apk` dosyasını indir. İçinde `app-debug.apk` var.

```yaml
name: APK oluştur

on:
  push:
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: "17"

      - name: APK derle
        run: |
          DIR=$(dirname "$(find . -maxdepth 3 -name gradlew | head -n 1)")
          cd "$DIR"
          chmod +x gradlew
          ./gradlew assembleDebug --no-daemon --stacktrace

      - uses: actions/upload-artifact@v4
        with:
          name: EkranCevirmeni-apk
          path: "**/app/build/outputs/apk/debug/*.apk"
```

### Yol B: Android Studio

1. developer.android.com/studio adresinden Android Studio'yu indirip kur.
2. **File → Open** ile `EkranCevirmeni` klasörünü aç ve alttaki Gradle senkronizasyonunun bitmesini bekle. İlk açılış uzun sürebilir.
3. **Build** menüsünden **Generate APKs**'i seç (eski sürümlerde **Build APK(s)**). APK, `app/build/outputs/apk/debug/` klasörüne çıkar.
   Tablet USB ile bağlıysa ve USB hata ayıklaması açıksa yeşil ▶ düğmesi uygulamayı doğrudan tablete kurar.

## 2. Tablete kurulum

1. `app-debug.apk` dosyasını tablete aktar (Google Drive, e-posta ya da USB ile).
2. Dosyayı aç. "Bilinmeyen uygulamaları yükle" izni istenirse ver.
3. Play Protect uyarısı çıkarsa **Yine de yükle**'ye bas. Uygulama Play Store'dan gelmediği için bu uyarı normaldir.

## 3. Gemini API anahtarı (ücretsiz)

1. Uygulamadaki **Ücretsiz anahtar al** düğmesine bas ya da tarayıcıda `aistudio.google.com/app/apikey` adresini aç.
2. Google hesabınla gir, **Create API key**'e bas ve anahtarı kopyala (`AIza…` ile başlar).
3. Anahtarı uygulamaya yapıştır ve **Bağlantıyı test et**'e bas. Örnek cümlenin Türkçesi görünmeli.

Ücretsiz kullanımda dakikalık ve günlük istek sınırları var. Güncel sınırlarını AI Studio'da görebilirsin.
Ücretsiz kullanımda Google, gönderilen metinleri ürünlerini geliştirmek için kullanabilir. Bu yüzden çerçeveyi kişisel bilgilerin üstüne koyma.

İnternet yoksa ya da anahtar girmek istemezsen **Cihaz içi çeviri**'yi seç. İlk kullanımda yaklaşık 30 MB'lık bir model indirir, sonra internetsiz çalışır. Bu modda çeviri daha basittir.

## 4. Kullanım

1. **Başlat**'a bas ve izinleri ver:
   - "Diğer uygulamaların üzerinde göster": İzni açıp uygulamaya geri dön ve tekrar **Başlat**'a bas.
   - Bildirim izni.
   - Ekran kaydı: **Tüm ekran** seçili olsun.
2. Mavi çerçeve çıkar. Çerçeveyi sürükleyerek altyazının olduğu yere taşı, sağ alt köşesinden boyutlandır ve paneldeki **Tamam**'a bas.
3. Oyuna ya da uygulamaya geç. Çeviri çerçevenin altında görünür.

**Panel düğmeleri:** `≡` paneli taşır. **Duraklat/Devam** çeviriyi durdurup sürdürür. **Bölge** çerçeveyi yeniden ayarlamanı sağlar. **Kapat** uygulamayı tamamen durdurur.
**Durum noktası:** yeşil = hazır, sarı = çevriliyor, kırmızı = hata, gri = duraklatıldı.

### Ayarlar
- **Model:** Varsayılan `gemini-3.5-flash-lite`, en hızlı model. Daha iyi çeviri için `gemini-3.8-flash` yazabilirsin, ama daha yavaştır.
- **Okuma aralığı:** Ekranın kaç milisaniyede bir okunacağını belirler. Varsayılan 500'dür. Kota çabuk doluyorsa 800–1000 yap.
- **Metin netleşince çevir:** Harf harf beliren altyazılarda yazının tamamlanmasını bekler.
- **İngilizce aslını göster:** Çevirinin üstünde orijinal metni küçük yazıyla gösterir.
- **Gemini çalışmazsa cihaz içi çeviriye geç:** İnternet kesilir ya da kota dolarsa çeviri yine de devam eder.

Ayar değişiklikleri bir sonraki **Başlat**'ta geçerli olur.

## 5. Sorun giderme

| Sorun | Çözüm |
|---|---|
| Çeviri hiç gelmiyor, durum noktası hep yeşil | Bazı uygulamalar ekran kaydını engeller (ör. Netflix). Onlarda yakalanan görüntü siyah gelir, bu uygulama da çalışamaz. |
| "Model bulunamadı" | Model adını `gemini-3.1-flash-lite` ya da `gemini-3.8-flash` yap. Güncel adlar ai.google.dev/gemini-api/docs/models sayfasında. |
| "Kota veya hız sınırı doldu" | Biraz bekle ya da okuma aralığını artır. |
| Yanlış harfler, saçma çeviriler | Çerçeveyi yalnızca altyazıyı kapsayacak kadar dar tut. Para, harita gibi arayüz yazıları çerçeveye girmesin. |
| Dokunmalar oyuna geçmiyor | Paneldeki **Tamam**'a bastığından emin ol. Düzenleme modunda çerçeve dokunmaları kendisi alır. |
| Ekranı döndürünce çerçeve kaydı | **Bölge**'ye basıp yeniden ayarla. |
| APK derlenirken hata | Hata metnini Claude'a gönder, birlikte düzeltelim. |

## Proje yapısı

```
app/src/main/java/com/ekrancevirmeni/
  MainActivity.kt       Ayarlar ekranı, izinler, başlat/durdur
  CaptureService.kt     Ekran yakalama, OCR, çeviri döngüsü (ön plan hizmeti)
  OverlayController.kt  Seçim çerçevesi, altyazı şeridi, kontrol paneli
  Translators.kt        Gemini (çevrimiçi) ve ML Kit (çevrimdışı) çevirmenler
  Prefs.kt              Ayarların saklanması
  Util.kt               Yardımcı işlevler
```

Gereksinim: Android 8.0 ve üzeri. Kod, Android 14 ve 15'in ekran kaydı ve kaplama (overlay) kurallarına göre yazıldı.
