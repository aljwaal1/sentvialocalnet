# توقيع Android الآمن

لا يجب حفظ مفتاح التوقيع الخاص داخل المستودع، خصوصًا عندما يكون المستودع عامًا.

يدعم مسار البناء الحالي توقيع APK بمفتاح محفوظ في GitHub Actions Secrets. عند عدم وجود الأسرار، يُبنى APK بمفتاح Debug مؤقت لأغراض التجربة فقط.

## أسرار GitHub المطلوبة

أضف القيم التالية من:

`Repository Settings → Secrets and variables → Actions`

- `SVLN_KEYSTORE_B64`: ملف JKS أو PKCS12 بعد تحويله إلى Base64.
- `SVLN_STORE_PASSWORD`: كلمة مرور مخزن المفاتيح.
- `SVLN_KEY_ALIAS`: اسم المفتاح داخل المخزن.
- `SVLN_KEY_PASSWORD`: كلمة مرور المفتاح.

## تحويل المفتاح إلى Base64

### Windows PowerShell

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release-keystore.jks")) | Set-Clipboard
```

### Linux

```bash
base64 -w0 release-keystore.jks
```

بعد ضبط الأسرار، سيستخدم GitHub Actions المفتاح نفسه في كل بناء، وبذلك يمكن تثبيت التحديثات فوق الإصدارات السابقة الموقعة بالمفتاح نفسه.

## تنبيه

لا تشارك ملف المفتاح أو كلمات المرور، ولا ترفعها إلى المستودع. احتفظ بنسخة احتياطية آمنة؛ فقدان مفتاح الإصدار يعني عدم القدرة على تحديث التطبيق المثبت بالتوقيع نفسه.
