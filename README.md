# PS4 PKG Background Streamer

تطبيق أندرويد يعمل في الخلفية (**Flutter + Kotlin Foreground Service**) يقوم بدور **وسيط بث مباشر (Smart Streaming Proxy)** لتثبيت ملفات `.pkg` من روابط التحميل المباشرة إلى جهاز **PS4 (GoldHEN + Remote Package Installer)** بدون استهلاك مساحة تخزين الجوال.

## المميزات
- **العمل في الخلفية (Foreground Service)**: إشعار دائم يعرض سرعة النقل وحجم البيانات المنقولة مع تفعيل `WakeLock` و `WifiLock` للاستمرار حتى عند إغلاق الشاشة.
- **بث مباشر بدون تخزين (Zero-Disk Streaming Proxy)**: يحوّل روابط `HTTPS/HTTP` (مع التوجيهات التلقائية `301/302/307/308` والترويسات المخصصة مثل `Cookie`, `Referer`, `User-Agent`) إلى رابط محلي `http://<Phone-IP>:8888/pkg/<id>.pkg`.
- **دعم الإيقاف المؤقت والاستئناف (Pause / Resume)**: تمرير كامل لترويسات `Range: bytes=...` واستجابات `206 Partial Content`.
- **اكتشاف تلقائي لجهاز PS4**: فحص الشبكة المحلية تلقائياً للعثور على جهاز PS4 على المنفذ `12800`.
- **متابعة التقدم والتحكم بالمهام**: شريط تقدم حي وأزرار إيقاف مؤقت، استئناف، وإلغاء عبر واجهة Remote Package Installer (RPI).

## التحميل المباشر (APK للأندرويد)
- [تحميل PS4 PKG Streamer v1.0.0 (Release APK)](https://github.com/AhhmedLM/ps4-pkg-streamer/raw/main/apk/ps4-pkg-streamer-v1.0.0-release.apk)

## طريقة الاستخدام
1. حمّل ملف الـ APK من الرابط أعلاه وثبّته على هاتف الأندرويد.
2. تأكد أن الهاتف وجهاز PS4 متصلان بنفس شبكة الواي فاي.
3. شغّل **Remote Package Installer (RPI)** على جهاز PS4 (GoldHEN).
4. افتح التطبيق، اضغط **بحث تلقائي** لاكتشاف IP الـ PS4، الصق رابط الـ `.pkg` المباشر، ثم اضغط **بدء التثبيت المباشر على PS4**.
