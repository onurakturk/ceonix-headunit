# CEONIX Ride — Settings Menü Haritası

> **Ne işe yarar:** Settings menüsünü yeniden düzenlerken referans dokümanı. Her satırın **sabit bir
> numarası** var (`S1.2.3`). Karar vermek için `KARARIN` sütununu doldur, ya da sohbete
> "S2.3.4'ü kaldır" gibi yaz.
>
> **v1.2.0'da (2026-08-31):** ürün adı **"CEONIX Ride"** oldu, `applicationId` = `com.ceonix.ride`.
> Aşağıdaki v1.0.84/85 notları o günkü durumu anlatıyor; `HEAD_UNIT_MAKE/_MODEL` **artık
> `"CEONIX Ride"`**. Markanın kodun neresinde geçtiği ve neyin değiştirilebilir olduğu için →
> **`docs/BRANDING_MAP.md`**.
>
> **v1.0.84'te:** `Vehicle information` ekranındaki **Head Unit kategorisi (Make + Model) kaldırıldı**
> ve değerler koda gömüldü (`ServiceDiscoveryResponse.HEAD_UNIT_MAKE/_MODEL` = "CEON" /
> "CEON Headunit"). Telefonun AA cihaz listesinde gösterdiği isim ürünün markası; kullanıcının
> yazacağı bir şey değil. `Settings.headUnitMake/Model` property'leri ve yedek anahtarları da
> silindi — eski bir yedek gömülü markayı ezmesin. **S3 artık 5 satır.** (v1.0.85: make de
> `"CEON Headunit"` yapıldı, cihazda kayıtlı olan buydu — telefonun gördüğü ad değişmesin diye.)
>
> **v1.0.83'te:** **Vehicle ID**, `Vehicle information` ekranından (eski S3.1.5) **Developer'a**
> taşındı — yeni **S2.8 Vehicle** kategorisi, Audio ile Debug'ın tam arasında. Diğer 7 araç alanı
> yerinde kaldı; hiçbir değer değişmedi. Sebep: bu alan telefonun aracı tanıdığı **eşleştirme
> anahtarı**, son kullanıcının kurcalayacağı bir şey değil (v1.0.42 saha kaydı).
>
> **v1.0.82'de: Save butonu KALKTI — her ayar anında kaydediliyor.** Altı ekran (`SettingsFragment`
> bölümleri, Dark Mode, Auto-start, Auto-Connect, Mikrofon, Vehicle information) `pending*` +
> Save modelinden, `LauncherModeFragment`'ın yıllardır kullandığı **doğrudan yazma** modeline
> geçti. "Kaydedilmemiş değişiklik" diyaloğu da kalktı — kaydedilmemiş bir şey kalmıyor.
> Oturumun yeniden kurulmasını gerektiren ayarlarda (bkz. `projectionRestartKeys`) **eskiden
> Save'in yaptığı işin aynısı** yapılıyor: AA bağlıysa `ACTION_STOP_SERVICE` + kısa bilgi çubuğu
> (`settings_session_restart_notice`); bağlı değilse sessizce kaydediyor.
> Ölen kaynaklar: `layout_save_button`, `menu_settings_save`, `btn_save_background/text` ve
> `save_and_restart` · `unsaved_changes(_message)` · `settings_saved` · `stopping_service` ·
> `discard` · `import_settings_discard_pending` string'leri (20 dosyadan).
>
> **v1.0.81'de:** Settings'in aksan rengi **teal → GS altını** (`brand_teal` → `brand_accent`,
> değeri `m3_primary` ile aynı; üstüne yazılan her şey için `brand_on_accent`). Etkilenen: hub
> ikonları, **on/off anahtarları**, kategori başlıkları, butonlar, dialoglar — hepsi tema
> üzerinden. Ayrıca **listenin altındaki Save butonu kaldırıldı**; tek Save toolbar'da.
>
> **Durum:** **v1.0.85** koddan türetildi. Satır sırası koddaki `items.add(...)` sırasıyla birebir aynı.
>
> **v1.0.80'de — ana ekran HUB oldu (hub-and-spoke).** S1 artık ayar listesi değil, **7 kategori
> kartı**; her kart kendi alt ekranını açar. Ayarlar **taşındı, kopyalanmadı**. Yeni ağaç:
> `General · Display · Audio · Connection · Backup · Advanced · Info`.
> - **Developer'dan yukarı çıkanlar:** Screen mode + Stretch to fill → **Display**;
>   Audio Sink + Static Audio Focus → **Audio**. Bunlar Developer'dan **silindi**.
> - **Static BSSID (eski S2.1.9) Developer'dan kaldırıldı** — aynı değer zaten Wi-Fi Setup
>   ekranında (S11.2) duruyor ve helper oraya yazıyor. Tek giriş noktası oldu.
> - Eski *Automation* / *Dark Mode* / *Launcher Mode* / *Wi-Fi Setup* kategori başlıkları ana
>   ekrandan kalktı; satırları General / Display / Connection içine dağıldı.
> - **Connection ve Advanced kartları doğrudan hedefe gider** (tek çocukları var):
>   Connection → Wi-Fi Setup ekranı (S11), Advanced → Developer Settings (S2).
> - Alt ekranlarda `CategoryHeader` **kullanılmıyor** — başlığı toolbar taşıyor.
>
> **v1.0.72-78'de:** Ana ekrana **Wi-Fi Setup** kategorisi eklendi (S1.6), Backup ile Advanced
> arasına → *Advanced ve Info bir aşağı kaydı* (eski S1.6 → **S1.7**, eski S1.7 → **S1.8**).
> (v1.0.80'de bu kategori **Connection** kartına dönüştü.)
> Yeni **S11 — Wi-Fi Setup** ekranı: HU'ya kendi Wi-Fi adresini öğretir; telefondaki **CEONIX Ride Helper**
> uygulamasından Bluetooth ile otomatik alır, elle giriş yedek yol olarak durur. Developer'daki
> **Static BSSID (S2.1.9) aynen duruyor** — aynı değeri gösterir, kaldırılmadı.
> v1.0.76'da bu ekrandan *Connect now* butonu kaldırıldı (sessizdi; bağlanma ana ekranın işi).
>
> **v1.0.65'te:** Ekran ayarları **tek kola indirildi**. Yeni *Interface size* (S1.1.1) AA'nın
> buton büyüklüğünü belirliyor ve hedef tuval genişliğinden çözünürlük + density'yi **birlikte**
> seçiyor. Karşılığında *Auto-Optimize Settings* (kurulum sihirbazı, eski S1.1.1) ile Developer'daki
> *Resolution* ve *Pixel density* (eski S2.4.1-2) **kaldırıldı** — ikisi de Interface size'ı
> görünmez biçimde eziyordu, o yüzden ezme kodu da silindi. Sihirbazın taşıdığı iki varsayılan
> (`view-mode`: SDK 21-27'de GLES · `screen-orientation`: LANDSCAPE) getter varsayılanına taşındı.
> S2.4 buna göre yeniden numaralandı (S2.4.1 Custom Insets … S2.4.7 Loading screen).
>
> **v1.0.52'de:** *UI Scale* (S1.1.4) **tamamen kaldırıldı** — launcher ana ekranının düzenini
> bozuyordu; ekran yoğunluğunu değiştiren kod da silindi. *USB* kategorisi (eski S2.2, *Use Native
> USB Driver*) **menüden kaldırıldı**; `Settings.useLibusb` ve tüm USB bağlantı kodu duruyor.
> Developer kategorileri buna göre yeniden numaralandı (S2.2 Automation … S2.9 Reset).
> **Kapsam:** Yalnız Settings ağacı. (Home kartları, Ride ekranı, AA exit menüsü kapsam dışı.)
>
> **v1.0.50'de ne değişti** (tüm öneriler + Wireless Mode kararı uygulandı):
> - Auto-start settings ve Auto-Connect Settings → **ana menüye** (yeni "Automation" kategorisi)
> - Wireless Connection kategorisi (Wireless Mode, Bluetooth Adapter, Server Mode, hotspot,
>   helper stratejisi, wait-for-wifi, Static BSSID) → **Developer'a**
> - Use Native USB Driver → **Developer** ("USB" kategorisi altında)
> - 2.4GHz uyarı kutusu → **kaldırıldı** (ekrandan)
> - Developer Settings satırı artık **"Advanced"** başlığı altında (başlıksız yüzmüyor)
> - Microphone Settings → Audio kategorisinin **sonuna**
> - Auto Start'ta USB satırları (3), Auto-Connect'te Self Mode + tek-USB → **gizlendi**
>
> Gizlenen/taşınan hiçbir şeyin **kodu silinmedi**; ayar değerleri de aynen duruyor. Helper modu
> ileride gerekirse Developer'dan seçilebilir.

---

## Nasıl komut veririm?

| İstediğin | Örnek komut |
|---|---|
| Satırı kaldır | `S2.4.7 kaldır` |
| Satırı taşı | `S2.2.1'i S1.2'nin altına al` |
| Kategoriyi taşı | `S2.5 (Input) komple Developer'da kalsın` |
| Sırayı değiştir | `S1.1: sıra = 2, 1, 4, 3` |
| Yeniden adlandır | `S1.1.5 → "Ekran Ölçeği"` |
| Kategori birleştir | `S2.6 (Audio) ile S2.4'ü birleştir` |

**"Gizle" vs "Kaldır" farkı** (önemli):
- **GİZLE** = Settings ekranından çıkar, **kod ve ayar durur**. Geri açması tek satır. Riski yok.
- **KALDIR** = UI + `Settings` property + string'ler silinir. Kalıcı, geri alması iş.
→ Emin olmadığın şeylerde **GİZLE** de.

---

## Özet: mevcut ağaç

| Ekran | Nereden açılır | Kategori | Satır |
|---|---|---|---|
| **S1** Settings (hub) | Home → Settings | — | 7 kart |
| **S1.1** General | S1 kartı | — | 5 |
| **S1.2** Display | S1 kartı | — | 4 |
| **S1.3** Audio | S1 kartı | — | 2 (1'i koşullu) |
| **S1.5** Backup | S1 kartı | — | 2 |
| **S1.7** Info | S1 kartı | — | 1 |
| **S2** Developer Settings | S1 → Advanced kartı | 10 | 29 |
| **S3** Vehicle Information | S1.1.5 | 1 | 5 |
| **S4** Auto-start settings | S1.1.2 | 1 | 6 (3'ü gizli) |
| **S5** Auto-Connect Settings | S1.1.3 | — | 1 (2'si gizli) |
| **S6** Dark Mode Settings | S1.2.2 | 2 | 11 |
| **S7** Launcher Mode | S1.1.1 | 1 | 1 |
| **S8** Microphone Settings | S2.7.7 | 1 | 5 |
| **S9** Keymap | S2.6.1 | — | 24 tuş |
| **S10** Loading screen | S2.4.5 | — | 8 kontrol |
| **S11** Wi-Fi Setup | S1 → Connection kartı | 1 | 4 (+2 koşullu) |

**Native-AA sütunu:** ✅ anlamlı · ⚠️ nadiren gerekir · ❌ senin kullanımında karşılığı yok
(kablosuz Native AA, telefonda helper app yok, USB yok)

---

## S1 — Settings (hub)

Son kullanıcının göreceği ilk ekran. **Ayar yok, yalnız 7 kategori kartı** (`SettingItem.NavTile`:
solda ikon kutusu, başlık + kısa açıklama, sağda chevron). Kartların hepsi aynı nav destination'a
(`settingsFragment`) farklı `section` argümanıyla gider; ikisi doğrudan başka ekrana atlar.

| # | Kart | İkon | Gider | Açıklama string'i |
|---|---|---|---|---|
| S1.1 | **General** | `ic_m3_general` | `section=GENERAL` | `settings_hub_general_desc` |
| S1.2 | **Display** | `ic_m3_display` | `section=DISPLAY` | `settings_hub_display_desc` |
| S1.3 | **Audio** | `ic_m3_audio` | `section=AUDIO` | `settings_hub_audio_desc` |
| S1.4 | **Connection** | `ic_m3_wifi` | **doğrudan S11** (Wi-Fi Setup) | `settings_hub_connection_desc` |
| S1.5 | **Backup** | `ic_m3_backup` | `section=BACKUP` | `settings_hub_backup_desc` |
| S1.6 | **Advanced** | `ic_m3_advanced` | **doğrudan S2** (`section=DEVELOPER`) | `settings_hub_advanced_desc` |
| S1.7 | **Info** | `ic_m3_info` | `section=INFO` | `settings_hub_info_desc` |

### S1.1 — General
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S1.1.1 | Launcher Mode | → S7 | CEONIX Ride'ı cihazın ana ekranı (launcher) yapma | her zaman | ✅ | |
| S1.1.2 | Auto-start settings | → S4 | Açılışta / ekran açılınca / Bluetooth ile otomatik başlatma | her zaman | ✅ **en kritik** | |
| S1.1.3 | Auto-Connect Settings | → S5 | Otomatik bağlanma önceliği | her zaman | ✅ | |
| S1.1.4 | App Language | dialog | Uygulama dilini seçer (boş = sistem dili) | her zaman | ✅ | |
| S1.1.5 | Vehicle information | → S3 | Telefona gönderilen araç kimliği (marka/model/yıl) | her zaman | ✅ | |

### S1.2 — Display
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S1.2.1 | **Interface size** | dialog | AA'nın kendi butonlarının büyüklüğü (Küçük/Orta/**Büyük**). Hedef tuval genişliği; çözünürlük + density'yi birlikte belirler. Yeniden bağlanma ister. **v1.0.64** | her zaman | ✅ | |
| S1.2.2 | Dark Mode Settings | → S6 | Uygulama teması + AA gece modu ayarları | her zaman | ✅ | |
| S1.2.3 | Screen mode | dialog | Sistem çubukları: tam ekran / normal / karma. **Anında yazar + `recreate()`** *(v1.0.80'de Developer'dan geldi)* | her zaman | ✅ | |
| S1.2.4 | Stretch to fill screen | toggle | Görüntüyü ekrana yayar (oran bozulabilir); `requiresRestart` *(v1.0.80'de Developer'dan geldi)* | her zaman | ✅ | |

### S1.3 — Audio
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S1.3.1 | Audio Sink | toggle | Sesi HU'dan çal (kapalıysa telefondan çalar) *(v1.0.80'de Developer'dan geldi)* | her zaman | ✅ | |
| S1.3.2 | Static Audio Focus | toggle | Ses odağını sürekli tutar (kesilmeleri önler) *(v1.0.80'de Developer'dan geldi)* | S1.3.1 açıkken | ✅ | |

### S1.5 — Backup
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S1.5.1 | Export settings | aksiyon | Tüm ayarları dosyaya yedekler | her zaman | ✅ | |
| S1.5.2 | Import settings | aksiyon | Yedekten ayarları geri yükler | her zaman | ✅ | |

### S1.7 — Info
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S1.7.1 | Version | salt-okunur | Uygulama sürümü | her zaman | ✅ | |
| S1.7.2 | Open-source licence | diyalog | AGPL bildirimi: üst-kaynak atıfları, garanti reddi, kaynak deposu adresi. Satır adı ve açıklaması 19 dilde; **bildirim gövdesi her dilde İngilizce** (`translatable="false"`) *(v1.2.29)* | her zaman | ✅ | |

---

## S2 — Developer Settings

### S2.1 — Wireless Connection  *(v1.0.50'de ana menüden buraya taşındı)*

> **v1.1.12'den beri ekranda görünen tek satır S2.1.2 (Bluetooth Adapter).** Wireless Mode
> seçicisi kaldırıldı; mod artık her zaman **Native (3)** olduğu için ona bağlı S2.1.3-S2.1.8
> **erişilemez** hale geldi (kodda `if` içinde duruyorlar, hiç render edilmiyorlar).
> Kod tamamen duruyor: `Settings.wifiConnectionMode`, yedek anahtarı ve bağlantı katmanındaki
> 20+ okuma yeri ellenmedi — Helper/Server ileride gerekirse satırı geri eklemek yeterli.
> ⚠️ Arayüzden mod değiştirilemediği için, `wifi-connection-mode` bir şekilde 3 dışına düşerse
> (pratikte yalnız eski bir yedeğin geri yüklenmesi) tek çare **Reset**. Değer bilerek serbest
> bırakıldı: açılışta 3'e zorlamak, ADB ile deneme yapma imkânını da kapatırdı.

| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| ~~S2.1.1~~ | ~~Wireless Mode~~ | — | **v1.1.12'de KALDIRILDI.** Uygulama zaten Native geliyor; seçeneği göstermek çalışan bağlantıyı tek dokunuşluk riske sokuyordu. `Settings.wifiConnectionMode` ve tüm mod kodu duruyor | — | ✅ **hep Native (3)** | |
| S2.1.2 | Bluetooth Adapter | dialog | Çift BT radyolu cihazlarda hangi adaptör kullanılsın | yalnız **Native** modda | ⚠️ | |
| S2.1.3 | Server Mode | segmented | Server modunun alt seçimi: Manual / Auto | yalnız **Server** modda | ❌ | |
| S2.1.4 | Auto-Enable Hotspot | toggle | Bağlanırken hotspot açmayı dener | yalnız **Server(Auto)** | ❌ | |
| S2.1.5 | Helper Connection Strategy | dialog | Helper app hangi yolla bağlansın | yalnız **Helper** modda | ❌ | |
| S2.1.6 | Auto-Enable Hotspot | toggle | (S2.1.4'ün Helper karşılığı) | Helper + Headunit Hotspot | ❌ | |
| S2.1.7 | Wait for WiFi before WiFi Direct | toggle | P2P öncesi WiFi bağlantısını bekler | Helper + WiFi Direct | ❌ | |
| S2.1.8 | WiFi wait timeout | slider | Yukarıdaki bekleme süresi (5-30 sn) | S2.1.7 açıkken | ❌ | |
| ~~S2.1.9~~ | ~~Static BSSID~~ | — | **v1.0.80'de buradan KALDIRILDI** (duplicate'ti). Değer artık yalnız **S11.2**'de düzenlenir; `Settings.staticBSSID` ve save yolu aynen duruyor | — | ✅ **kritik, S11'de** | |

### S2.2 — Automation
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.2.1 | Close app on disconnect | toggle | Bağlantı kesilince uygulamayı kapat | her zaman | ✅ | |

### S2.3 — Navigation
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.3.1 | Gps for navigation | toggle | HU'nun GPS'ini AA navigasyonuna verir | her zaman | ✅ | |
| S2.3.2 | Navigation notifications | toggle | Navigasyon bildirimlerini gösterir | her zaman | ✅ | |
| S2.3.3 | Disable Video-Lock (Fake Speed) | toggle | AA'nın hız kilidini atlatmak için sahte hız gönderir | her zaman | ✅ | |

### S2.4 — Graphic
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
> *v1.0.80: Screen mode ve Stretch to fill **Display**'e (S1.2.3 / S1.2.4) taşındı; kategori
> buna göre yeniden numaralandı.*

| S2.4.1 | Custom Insets (Margins) | dialog | Ekran kenar boşlukları (canlı önizlemeli) | her zaman | ⚠️ | |
| S2.4.2 | View mode | dialog | Video render yöntemi (Surface/Texture/GLES) | her zaman | ⚠️ | |
| S2.4.3 | Screen orientation | dialog | Ekran yönü kilidi | her zaman | ✅ | |
| S2.4.4 | Forced Scale (Legacy Fix) | toggle | Eski cihazlarda ölçek düzeltmesi | yalnız **SurfaceView**'da | ⚠️ | |
| S2.4.5 | Loading screen | → S10 | AA açılırken gösterilen özel görsel/video | her zaman | ✅ | |

### S2.5 — Video
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.5.1 | Force Software Decoding | toggle | Donanım yerine yazılım video çözücü | her zaman | ⚠️ | |
| S2.5.2 | Software Decoder Source | dialog | Yazılım çözücü: FFmpeg / cihazın kendi | S2.5.1 açıkken | ⚠️ | |
| S2.5.3 | Video Codec | dialog | H.264 / H.265 / Otomatik | her zaman | ✅ | |
| S2.5.4 | FPS Limit | dialog | 30 / 60 fps | her zaman | ✅ | |

### S2.6 — Input Settings
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.6.1 | Keymap | → S9 | Fiziksel tuş eşlemeleri (direksiyon/rotary) | her zaman | ⚠️ | |
| S2.6.2 | Enable Rotary Controller | toggle | Döner düğme desteği | her zaman | ⚠️ | |

### S2.7 — Audio
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
> *v1.0.80: Audio Sink ve Static Audio Focus **Audio** kartına (S1.3.1 / S1.3.2) taşındı;
> kategori buna göre yeniden numaralandı.*

| S2.7.1 | Separate Audio Streams | toggle | Medya/navigasyon/asistan ayrı kanallarda | her zaman | ⚠️ | |
| S2.7.2 | Use AAC Audio | toggle | AAC ses codec'i (deneysel) | her zaman | ⚠️ | |
| S2.7.3 | AA now playing on media session | toggle | Çalan parçayı sistem medya oturumuna yansıtır | her zaman | ✅ | |
| S2.7.4 | Audio Volume Offset | dialog | Medya/asistan/navigasyon ses seviyeleri (%0-200) | her zaman | ✅ | |
| S2.7.5 | Audio Latency Multiplier | dialog | Ses tampon gecikmesi (1x-8x) | her zaman | ⚠️ | |
| S2.7.6 | Audio Queue Capacity | dialog | Ses kuyruğu kapasitesi | her zaman | ⚠️ | |
| S2.7.7 | Microphone Settings | → S8 | Mikrofon ayarları *(v1.0.50'de kategori sonuna alındı)* | her zaman | ✅ | |

### S2.8 — Vehicle  *(v1.0.83'te Vehicle information ekranından buraya taşındı)*
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.8.1 | Vehicle ID | dialog | Aracın kimliği. Telefon aracı bununla tanıyor | her zaman | ✅ **⚠️ değiştirme!** telefon "yeni araç" sanıp oto-bağlanmayı keser | |

### S2.9 — Debug
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.9.1 | Show FPS Counter | toggle | Yayında FPS sayacı gösterir | her zaman | ✅ | |
| S2.9.2 | Log Level | dialog | Log ayrıntı seviyesi (Silent…Verbose) | her zaman | ✅ | |
| S2.9.3 | Log Source | dialog | Logcat / dosyaya yaz | her zaman | ✅ **teşhis için gerekli** | |
| S2.9.4 | Start/Stop Capturing Logs | aksiyon | Log kaydını başlatır/durdurur | her zaman | ✅ | |
| S2.9.5 | Export Logs | aksiyon | Logları dosyaya çıkarır/paylaşır | her zaman | ✅ | |

### S2.10 — Reset
| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S2.10.1 | Reset to Defaults | aksiyon | Tüm ayarları sıfırlar, uygulamayı yeniden başlatır | her zaman | ✅ | |

---

## S3 — Vehicle Information  *(S1.1.3'ten)*

| # | Satır | Tür | Ne yapar | Native-AA | Önerim | KARARIN |
|---|---|---|---|---|---|---|
| S3.0 | (bilgi kutusu) | bilgi | "Bu bilgiler her bağlantıda AA'ya gönderilir" | ✅ | KALSIN | |
| S3.1.1 | Display name | dialog | Telefonda görünen araç adı | ✅ | KALSIN | |
| S3.1.2 | Make | dialog | Araç markası | ✅ | KALSIN | |
| S3.1.3 | Model | dialog | Araç modeli | ✅ | KALSIN | |
| S3.1.4 | Year | dialog | Model yılı (sayısal) | ✅ | KALSIN | |
| S3.1.6 | Right-hand drive | toggle | Sağ direksiyon (AA arayüz düzeni) | ✅ | KALSIN | |

---

## S4 — Auto-start settings  *(S1.2.1'den)*

| # | Satır | Tür | Ne yapar | Görünürlük | Native-AA | KARARIN |
|---|---|---|---|---|---|---|
| S4.0 | (bilgi kutusu) | bilgi | Bazı markalarda arka plan kısıtı uyarısı | her zaman | ✅ | |
| S4.1 | Start on boot | toggle | Cihaz açılınca servisi başlat | her zaman | ✅ | |
| S4.2 | Start on screen on | toggle | Ekran açılınca bağlanmayı dene | her zaman | ✅ **önemli** | |
| S4.3 | Auto-start on Bluetooth | çoklu seçim | Hangi telefon(lar) BT ile bağlanınca başlasın | her zaman | ✅ **kritik** — Native AA'nın tetikleyicisi | |
| S4.4 | (WiFi uyarı kutusu) | bilgi | Android 13+ kısıtı uyarısı | yalnız **SDK ≤ 32** | ❌ | |
| S4.5 | Auto-start on WiFi | toggle | Belirli WiFi ağına bağlanınca başlat | yalnız **SDK ≤ 32** | ❌ | |
| S4.6 | WiFi SSID | dialog | Yukarıdaki ağın adı | SDK ≤ 32 + S4.5 açık | ❌ | |

> **v1.0.50'de gizlendi** (kod duruyor, ayar değerleri değişmedi): *Listen for USB devices*,
> *Auto-start on USB*, *Reopen on reconnection* — kablosuz-only head unit olduğu için.
>
> **Not:** Overlay izni kapalıysa bu ekrandaki tüm ayarlar otomatik kapatılır (kod: `onResume`).

---

## S5 — Auto-Connect Settings  *(S1.2.2'den)*

Sürükle-sırala listesi: sıra = deneme önceliği. Her satırın kendi aç/kapa anahtarı var.

| # | Satır | Ne yapar | Native-AA | KARARIN |
|---|---|---|---|---|
| S5.1 | Auto-connect last session | Son bağlanılan yöntemi tekrar dene | ✅ | |

> **v1.0.50'de gizlendi:** *Auto-start Self Mode* ve *Auto-connect single USB device*.
> Toggle değerleri **değişmedi** ve öncelik listesindeki sıraları korunuyor (`hiddenOrder`),
> yani `hiddenMethods` kümesinden çıkarmak satırları geri getirmeye yeter.

---

## S6 — Dark Mode Settings  *(S1.3.1'den)*

> **v1.1.11'de sadeleşti.** Her iki seçici de kısa ve aynı dili konuşuyor; *Use Extreme Dark*,
> *Make Android Auto Monochrome* ve *Desaturation* satırları **ekrandan kaldırıldı** (ayarlar,
> tema overlay'i ve AA filtresi kodda duruyor — geri getirmek satırı geri eklemek).
> Menüden çıkarılan modlar (Ekstra Koyu · Manuel Saat · Işık Sensörü · Ekran Parlaklığı ·
> Araç ILL+) **kayıtlı değer olarak hâlâ geçerli**, sadece artık teklif edilmiyor → onlara bağlı
> alt satırlar (S6.1.3-5, S6.2.2-4) pratikte **erişilemez**.
> Seçicilerin sırası artık enum sırasından **bağımsız** (`AppThemeManager.APP_THEME_MENU` /
> `NIGHT_MODE_MENU`).

### S6.1 — App Theme (uygulamanın kendi teması)
| # | Satır | Tür | Ne yapar | Görünürlük | Önerim | KARARIN |
|---|---|---|---|---|---|---|
| S6.1.1 | Dark Mode (Application) | dialog | **4 seçenek, bu sırayla:** Automatic (System) · Automatic (Sunrise/Sunset) · **Light** · Dark | her zaman | KALSIN | |
| ~~S6.1.2~~ | ~~Use Extreme Dark~~ | — | **v1.1.11'de KALDIRILDI**. `Settings.useExtremeDarkMode` + `ThemeOverlay_ExtremeDark` duruyor; bayrağı temizleyen iki yol kaldı (tema seçicide Light/Dark seçmek, status bar tema butonu) | — | — | |
| S6.1.3 | (eşik ayarı) | slider | Işık/parlaklık eşiği | Işık Sensörü / Parlaklık temasında → **artık seçilemez** | ölü | |
| S6.1.4 | Night Mode Start Time | saat seçici | Koyu tema başlangıç saati | Manuel Saat temasında → **artık seçilemez** | ölü | |
| S6.1.5 | Night Mode End Time | saat seçici | Koyu tema bitiş saati | Manuel Saat temasında → **artık seçilemez** | ölü | |

### S6.2 — Dark Mode (Android Auto) — AA yayınının gece modu
| # | Satır | Tür | Ne yapar | Görünürlük | Önerim | KARARIN |
|---|---|---|---|---|---|---|
| S6.2.1 | Dark Mode (Android Auto) | dialog | **3 seçenek:** Automatic (Sunrise/Sunset) · **Light** · Dark. **System modu AA'da YOK** (kodda öyle bir `NightMode` değeri hiç olmadı; eklemek yeni enum + yeni dallar demek) | her zaman | KALSIN | |
| S6.2.2 | (eşik ayarı) | slider | AA gece modu eşiği | sensör/parlaklık modunda → **artık seçilemez** | ölü | |
| S6.2.3 | Night Mode Start Time | saat seçici | AA gece modu başlangıcı | Manuel Saat → **artık seçilemez** | ölü | |
| S6.2.4 | Night Mode End Time | saat seçici | AA gece modu bitişi | Manuel Saat → **artık seçilemez** | ölü | |
| ~~S6.2.5~~ | ~~Make Android Auto Monochrome~~ | — | **v1.1.11'de KALDIRILDI**. `aaMonochromeEnabled` + `AapProjectionActivity` filtresi duruyor. ⚠️ Bayrağı temizleyen **başka bir yol yok** — eski bir yedek `true` ile geri yüklenirse tek çare Reset | — | — | |
| ~~S6.2.6~~ | ~~Desaturation~~ | — | **v1.1.11'de KALDIRILDI** (S6.2.5'in içine yuvalıydı). `aaDesaturationLevel` duruyor | — | — | |

> **Not:** S6.1 = **uygulamanın** teması, S6.2 = **AA yayınının** gece modu. İkisi de "Dark Mode"
> adını taşıdığı için karışıyor — adlandırma netleştirilebilir.

---

## S7 — Launcher Mode  *(S1.4.1'den)*
| # | Satır | Tür | Ne yapar | Önerim | KARARIN |
|---|---|---|---|---|---|
| S7.1 | Use as launcher | toggle | CEONIX Ride'ı cihazın ana ekranı yapar (anında uygulanır, Save yok) | KALSIN | |

---

## S8 — Microphone Settings  *(S2.7.9'dan)*
| # | Satır | Tür | Ne yapar | Önerim | KARARIN |
|---|---|---|---|---|---|
| S8.1 | Mic sample rate | dokundukça değişir | Mikrofon örnekleme hızı (16k/48k) | KALSIN | |
| S8.2 | Mic input source | dialog | Hangi mikrofon girişi kullanılsın | KALSIN | |
| S8.3 | Echo canceler | toggle | Yankı engelleme | KALSIN | |
| S8.4 | Noise suppressor | toggle | Gürültü bastırma | KALSIN | |
| S8.5 | Auto gain control | toggle | Otomatik kazanç | KALSIN | |

> S8.3–S8.5 cihaz desteklemiyorsa otomatik olarak pasif görünür.

---

## S9 — Keymap  *(S2.6.1'den)*
24 satırlık tuş listesi (Soft Left/Right, D-Pad ×5, Media Play/Pause/Next/Prev, Search, Call,
End Call, Music, Nav, Night, Enter, Home, App Switch, Button A/B). Her satıra dokunup fiziksel
tuşa basarak eşleme yapılır. Toolbar'da **Reset** var.
**Önerim:** KALSIN (motosiklette direksiyon/rotary tuşu kullanacaksan gerekli).

---

## S10 — Loading screen  *(S2.4.9'dan)*
| # | Satır | Tür | Ne yapar | Görünürlük | Önerim | KARARIN |
|---|---|---|---|---|---|---|
| S10.1 | (önizleme + öneriler) | salt-okunur | Önerilen çözünürlük/en-boy oranı bilgisi | her zaman | KALSIN | |
| S10.2 | Select file | aksiyon | Görsel/GIF/video seçer (maks 10 MB) | her zaman | KALSIN | |
| S10.3 | Show text | toggle | Görselin üstünde yazı göster | medya seçiliyse | KALSIN | |
| S10.4 | Keep aspect ratio | toggle | En-boy oranını koru | medya seçiliyse | KALSIN | |
| S10.5 | Scale | slider | Görsel boyutu | medya seçiliyse | KALSIN | |
| S10.6 | Loop video | toggle | Videoyu döngüye al | video seçiliyse | KALSIN | |
| S10.7 | Remove | aksiyon | Seçili medyayı kaldırır | medya seçiliyse | KALSIN | |

---

## S11 — Wi-Fi Setup  *(S1.6.1'den; v1.0.72)*

Android 10+ bir uygulamanın **kendi** Wi-Fi Direct arayüzünün MAC'ini okumasını engelliyor, ama AA
el sıkışması o adresi telefona göndermek zorunda. Yanlışsa telefon gruba **sessizce** katılmıyor.
Bu ekran adresi dışarıdan almanın yolu.

| # | Satır | Tür | Ne yapar | Görünürlük | KARARIN |
|---|---|---|---|---|---|
| S11.0 | (bilgi kutusu) | bilgi | Neden gerektiği + prosedür; ekranın kendiliğinden kapanabileceği uyarısı | her zaman | |
| S11.1 | Network name | salt-okunur | HU'nun o anki grup adı (`DIRECT-xx-CEONIX`). 3rd-party analizörde hangi ağa bakılacağını söyler. Grup yoksa ana ekranı işaret eder | her zaman | |
| S11.2 | Static BSSID | dialog | Kayıtlı adres; **elle giriş** buradan. S2.1.9 ile aynı değer | her zaman | |
| S11.3 | Phone helper | salt-okunur | Dinleyici durumu: *Opening… / Listening — X:XX left / hata metni / Received …* + kaç telefon bağlandı | her zaman | |
| S11.4 | Cross-check | salt-okunur | Telefonun iki yolu (WiFi taraması vs P2P keşfi) aynı adresi mi verdi; vermediyse **neden** | yalnız sonuç geldiyse | |
| S11.5 | Listen for the phone | aksiyon | Dinleme penceresini yeniden kurar | dinlemiyorken **ve** AA bağlı değilken | |

> **Dinleyicinin ömrü (v1.0.75):** ekranı açmak **10 dakikalık** bir pencere kurar; pencere ekran
> kapansa da yaşar (AA bağlanmaya çalışırken `FLAG_ACTIVITY_CLEAR_TOP` bu ekranı kapatıyor),
> süre dolunca ya da **geri tuşuyla** çıkınca kapanır. Servis yok, boot kancası yok: **ekran hiç
> açılmazsa tek satır çalışmaz.** Adres geldiğinde **Toast** çıkar (ekran çoktan kapanmış olabilir).
>
> **v1.0.76'da kaldırıldı:** *Connect now* butonu — ana ekranla aynı intent'i atıyordu ama
> `SettingsActivity`'de bağlanma overlay'i olmadığı için hiçbir geri bildirim vermiyordu.

---

## Ana ekranın son hâli (v1.0.80)

```
Settings (hub — 7 kart)
├── General       Launcher Mode · Auto-start settings · Auto-Connect Settings · App Language · Vehicle information
├── Display       Interface size · Dark Mode Settings · Screen mode* · Stretch to fill screen*
├── Audio         Audio Sink* · Static Audio Focus*
├── Connection    → Wi-Fi Setup ekranı (doğrudan; Static BSSID elle giriş orada)
├── Backup        Export / Import settings
├── Advanced      → Developer Settings (doğrudan)
└── Info          Version · Open-source licence

* = v1.0.80'de Developer'dan yukarı taşındı, Developer'dan SİLİNDİ.
```

Developer’da duranlar: `Wireless Connection` (Wireless Mode dahil, Static BSSID hariç), Automation,
Navigation, Graphic'in gerisi, Video, Input, Audio'nun gerisi + Microphone Settings, Debug, Reset.
Ekrandan kaldırılanlar: 2.4GHz uyarı kutusu, UI Scale (v1.0.52), USB kategorisi (v1.0.52),
**Auto-Optimize Settings + Resolution + Pixel density (v1.0.65)**.
Gizlenenler: S4'te 3 USB satırı, S5'te Self Mode + tek-USB.

---

## Teknik notlar (uygulama sırasında dikkat)

1. **Bütün S1.x ekranları ve S2 aynı dosyada, aynı nav destination'da.** `SettingsFragment.kt`
   artık `section` (String) nav argümanına bakıyor (`ARG_SECTION`; `ROOT/GENERAL/DISPLAY/AUDIO/
   BACKUP/INFO/DEVELOPER`) ve `updateSettingsList()` içindeki `when (section)` ile ilgili
   `addXxxItems(items)` fonksiyonunu çağırıyor. **Satır taşımak = `items.add(...)` çağrısını
   diğer `addXxx` fonksiyonuna almak.** Developer bloğu hâlâ tek parça
   (`addDeveloperItems`, içinde `if (showDeveloperCategories)`).
   *Yeni kategori eklemek:* sabit + `addXxxItems` + `addRootTiles`'a bir `NavTile` +
   `setupToolbar()`'daki başlık `when`'i + 2 string (başlık, açıklama) × 20 dosya.
   **`SettingsActivity` `recreate()` sonrası section'ı da geri yüklüyor** — hepsi aynı
   destination id'de olduğu için argüman olmadan kullanıcı hub'a düşerdi.
2. **Save mekanizması YOK (v1.0.82'den beri).** Her satır dokunulduğu anda `settings.x = v` yazar ve
   ardından o ekranın `applyChange()` / `applyThemeChange()` / `applyNightModeChange()` /
   `applyAutoStartChange()` yardımcısını çağırır; yardımcı `settings.commit()` yapar ve varsa yan
   etkiyi (servis re-init, tema yöneticisi, gece modu broadcast'i, device-protected mirror) tetikler.
   **Satır taşırken taşınacak olan bu ikili**: yazma satırı + doğru `apply*` çağrısı.
   Oturum yenilenmesi gereken satırlarda `applyChange(needsSessionRestart = true)` verilir; kanonik
   liste `SettingsBackupManager.projectionRestartKeys`.
3. **Koşullu görünürlük** (`Görünürlük` sütunu) kod içinde `if` ile yapılıyor; taşırken koşulu da
   taşımak gerek, yoksa satır yanlış yerde/yanlış zamanda görünür.
4. **Bağlantı modu Developer'da:** `wifiConnectionMode` değeri **3 (Native)** olarak duruyor;
   son kullanıcı ana menüden yanlışlıkla değiştiremez (güvenlik artısı), gerekirse Developer'dan
   seçilebilir. Helper/Server kodu tamamen korundu.
5. **Gizlenen satırlar davranışı değiştirmez:** ayar değerleri aynen duruyor, yalnız UI'dan
   çıkarıldılar. S5'te öncelik listesindeki sıraları da `hiddenOrder` ile korunuyor.
6. **Kaldırılmaması gerekenler:** `Static BSSID` (**artık yalnız S11.2** — A10+ cihazlarda bu değer
   olmadan AA hiç bağlanmaz), `Vehicle ID` (değişirse telefon "yeni araç" sanar),
   `Auto-start on Bluetooth` (Native AA tetikleyicisi), `Log Source/Level` (teşhis için).
   `pendingStaticBSSID` `SettingsFragment`'ta hâlâ yükleniyor + save'de yazılıyor ve
   `onResume`'da tazeleniyor (yoksa S11'de yazılan değeri bir Save geri alırdı).
7. **S11 ayrı bir Fragment** (`BssidSetupFragment`), `SettingsFragment` değil — ama aynı
   `SettingsAdapter`/`SettingItem` bileşenlerini kullanıyor, o yüzden görsel dil aynı. Save
   mekanizmasına bağlı **değil**: yazdığı tek şey `Settings.staticBSSID` ve anında yazıyor.
8. **S11 v1.0.79'da 19 dile çevrildi** (25 key). Ekranın **adı** da çevrildiği için,
   `native_aa_wifi_join_failed` uyarısı adı artık **format argümanı** olarak alıyor
   (`AapService.warnPhoneDidNotJoinWifi()` geçiyor) — S11'i yeniden adlandırırsan uyarı metni
   kendiliğinden takip eder, 20 dili elle düzeltmen gerekmez.
9. **Araç kimliği alanlarının hepsi telefona gidiyor** (`ServiceDiscoveryResponse.kt:229-252`);
   "gereksiz olanı sil" diye bir şey yok, çoğu proto2'de `required`. Head unit make/model artık
   ayar değil, o dosyadaki sabitler.
10. Bu doküman **v1.0.85** koduna göre. Kodda değişiklik olursa güncellenmeli.
