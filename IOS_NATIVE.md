# iPhone Native — SendViaLocalNet

أضيفت نسخة iPhone/iPad Native داخل مجلد `ios/` لتعمل مباشرة مع Android وWindows على نفس شبكة Wi‑Fi.

## ما الذي تدعمه

- استقبال مباشر على TCP/HTTP المنفذ `5051`.
- إرسال مباشر إلى Android أو Windows أو iPhone آخر.
- اكتشاف الأجهزة عبر UDP Broadcast على المنفذ `5052`.
- نفس بروتوكول Android/Windows الحالي:
  - `SVLN_DISCOVER|request-id`
  - `SVLN_DEVICE|device-name|device-type|ip|5051|device-id`
- اختيار عدة ملفات من تطبيق Files.
- اختيار جهاز أو عدة أجهزة والإرسال إليها.
- حفظ الملفات المستلمة داخل Documents/SendViaLocalNet، وتظهر للمستخدم عبر تطبيق Files لأن File Sharing مفعّل.
- Streaming أثناء الاستقبال وعدم تحميل الملف كاملًا في الذاكرة.

## البناء

Workflow باسم `Build iOS Native` يولد مشروع Xcode بواسطة XcodeGen ثم يبني Release بدون توقيع للتأكد من سلامة الكود.

ملف الـIPA الناتج من CI غير موقّع، لذلك لا يثبت على iPhone مباشرة. للتثبيت الحقيقي يلزم Apple signing/Developer account أو توزيع TestFlight/App Store.

## الشبكة المحلية

عند أول تشغيل سيطلب iOS إذن الوصول إلى الشبكة المحلية. يجب السماح به حتى يعمل اكتشاف الأجهزة والإرسال والاستقبال.
