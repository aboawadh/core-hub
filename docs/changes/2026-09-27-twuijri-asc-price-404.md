# تجهيز إرسال آبل: جدول سعر بلا أسعار ليس خطأ
المسؤول: twuijri · الفرع: fix/asc-price-schedule-404 · الحالة: review

## المشكلة والهدف
فحص تجهيز النسخة 1.1.4 (`ios-submit.yml`، submit=false) أرفق البناء 118 وأجاب التصنيف العمري، ثم توقف:
`GET /appPriceSchedules/<id>/manualPrices → 404 NOT_FOUND`. التطبيق لم يُحدَّد سعره قط؛ آبل تُرجع جدول السعر (معرّفه
معرّف التطبيق) لكن أسعاره 404. الهدف: يُعامل هذا كـ«لا سعر بعد» فيُضبط مجانيًا ويكمل الفحص.

## القرار والموافقات
`ensureFree` في `apps/ios/scripts/asc-prepare-submission.mjs` يلتقط 404 على `manualPrices` ويعدّها قائمة فارغة، ثم
يضبط السعر المجاني كما كان يفعل حين لا يوجد جدول. المالك وافق على الرفع والإرسال لـ 1.1.4 («ارفع وارسل»، 2026-09-27).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `apps/ios/scripts/asc-prepare-submission.mjs` — التقاط 404.
- `apps/ios/scripts/asc-prepare-submission.test.mjs` — حالة جديدة: الجدول يجيب وأسعاره 404 ← يُضبط المجاني.
أدوات المتجر فقط؛ لا يمس التطبيقات ولا المستخدمين.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ node --test apps/ios/scripts/asc-prepare-submission.test.mjs
ℹ tests 14
ℹ suites 1
ℹ pass 14
ℹ fail 0
ℹ cancelled 0
ℹ skipped 0
ℹ todo 0
ℹ duration_ms 268.606534
```
eslint وprettier على الملفين: نظيفة.

## المخاطر والرجوع
لا تأثير على مستخدمين. الرجوع: revert؛ ويمكن ضبط السعر يدويًا في App Store Connect.

## التسليم والخطوة التالية
ينتظر دمج المالك. بعده: `ios-submit.yml` (1.1.4، submit=false) ثم submit=true.
