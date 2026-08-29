# نقل محلي Pro — SendViaLocalNet

منظومة محلية لإرسال واستقبال الملفات بين **Windows وAndroid وiPhone/iPad** داخل شبكة Wi‑Fi، دون Cloud ودون رفع الملفات إلى الإنترنت.

## Android

التطبيق Native Java ويدعم الاستقبال والإرسال المباشر، واكتشاف الأجهزة عبر UDP على المنفذ 5052 مع HTTP احتياطي على 5051.

## Windows

برنامج Desktop مستقل يرسل ويستقبل الملفات ويعلن عن نفسه تلقائيًا للأجهزة الموجودة على نفس الشبكة.

## iPhone وiPad — Native

أصبحت هناك نسخة iOS Native مستقلة داخل `ios/`، ولا تحتاج إلى Windows كوسيط. تستطيع:

- استقبال الملفات مباشرة على المنفذ `5051`.
- إرسال الملفات مباشرة إلى Android أو Windows أو iPhone آخر.
- اكتشاف الأجهزة القريبة عبر UDP على المنفذ `5052`.
- اختيار عدة ملفات من Files وإرسالها إلى جهاز أو عدة أجهزة.
- حفظ الملفات المستلمة داخل مجلد التطبيق الظاهر في Files.

نسخة Web القديمة تبقى كخيار مساعد، لكن النقل المباشر iPhone ⇄ Android ⇄ Windows يعتمد على التطبيق Native.

## بروتوكول النقل

```http
POST /upload
Content-Type: application/octet-stream
X-File-Name: encoded-file-name
X-File-Size: file-size
```

المنفذ: `5051`.

### اكتشاف الأجهزة

```text
SVLN_DISCOVER|request-id
SVLN_DEVICE|device-name|device-type|ip|5051|device-id
```

منفذ الاكتشاف: `5052/UDP`.

## البناء التلقائي

- Android: `.github/workflows/build-apk.yml`
- Windows: `.github/workflows/build-desktop.yml`
- iOS Native: `.github/workflows/build-ios.yml`

بناء iOS في GitHub Actions ينتج IPA غير موقّع للتحقق من سلامة الكود. التثبيت الحقيقي على iPhone يحتاج توقيع Apple/TestFlight/App Store.

## الخصوصية

- النقل داخل الشبكة المحلية فقط.
- لا توجد قاعدة بيانات سحابية.
- لا يتم رفع الملفات إلى الإنترنت.
- الملفات الكبيرة تُنقل Streaming بدل تحميلها كاملة في الذاكرة.
