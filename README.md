# نقل محلي Pro — SendViaLocalNet

منظومة محلية لإرسال واستقبال الملفات بين **Windows وAndroid وiPhone/iPad** داخل شبكة Wi‑Fi، دون Cloud ودون رفع الملفات إلى الإنترنت.

## المنصات

### Android

- تطبيق Native Java احترافي.
- يدعم **Android 4.4 (API 19) فما فوق**.
- حفظ عدة أجهزة بعناوين IP وأسماء اختيارية.
- تحديد عدة أجهزة وإرسال عدة ملفات إليها.
- استقبال تلقائي على المنفذ `5051`.
- Streaming للملفات الكبيرة دون تحميل الملف كاملًا في الذاكرة.
- يحفظ الملفات في:

```text
Download/SendViaLocalNet
```

### Windows

برنامج Desktop مستقل يثبت مثل أي برنامج عادي، ولا يحتاج المستخدم إلى Python أو PowerShell بعد تثبيته.

- تشغيل الاستقبال تلقائيًا.
- ملف تثبيت مع خيار إنشاء اختصار على سطح المكتب.
- دفتر أجهزة محفوظة.
- بحث تلقائي عن أجهزة Android داخل الشبكة.
- إرسال إلى عدة أجهزة مباشرة.
- استقبال ملفات Android وiPhone.
- عرض رابط وQR لفتح نسخة الهاتف.
- حفظ الملفات في:

```text
Downloads/SendViaLocalNet
```

### iPhone وiPad

نسخة Web App تعمل من Safari عبر برنامج Windows المحلي:

1. شغّل برنامج Windows.
2. امسح رمز QR الظاهر في البرنامج أو افتح الرابط المحلي.
3. من قائمة المشاركة في Safari اختر **إضافة إلى الشاشة الرئيسية**.
4. تظهر الأداة بأيقونة وتفتح كواجهة مستقلة.

يمكن للآيفون:

- إرسال ملفات إلى الكمبيوتر.
- استقبال ملفات يرسلها برنامج Windows.
- إرسال مباشر إلى Android محفوظ بعنوان IP.
- حفظ اسم الجهاز والوجهات محليًا.

> يلزم بقاء برنامج Windows مفتوحًا ليكون مركز الاتصال وصندوق الملفات الخاص بالآيفون.

## بروتوكول النقل المباشر

المنفذ الافتراضي:

```text
5051
```

إرسال الملف يتم كـ Raw Stream:

```http
POST /upload
Content-Type: application/octet-stream
X-File-Name: encoded-file-name
X-File-Size: file-size
```

## البناء التلقائي

### Android APK

GitHub Actions يبني التطبيق ويضع النسخة النهائية في:

```text
apk/send-via-local-net.apk
```

### Windows Installer

GitHub Actions يبني:

```text
desktop/dist/SendViaLocalNet.exe
desktop/release/SendViaLocalNet-Setup.exe
```

ملف `Setup.exe` هو النسخة الموصى بها للمستخدم النهائي.

## هيكل المشروع

```text
app/                  تطبيق Android Native Java
desktop/app.py        تطبيق Windows والخادم المحلي
desktop/web/          واجهة iPhone وPWA
desktop/installer.iss إعداد ملف تثبيت Windows
.github/workflows/    بناء Android وWindows تلقائيًا
```

## الخصوصية

- النقل يتم داخل الشبكة المحلية فقط.
- لا توجد قاعدة بيانات سحابية.
- لا تُرسل الملفات إلى خادم خارجي.
- الملفات المؤقتة الخاصة بصندوق iPhone تُحذف تلقائيًا بعد 24 ساعة.
