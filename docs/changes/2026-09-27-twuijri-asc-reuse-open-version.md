# رفع بيانات آبل: إعادة تسمية النسخة المفتوحة بدل إنشاء نسخة جديدة
المسؤول: twuijri · الفرع: fix/asc-reuse-editable-version · الحالة: review

## المشكلة والهدف
تشغيل `ios-store-metadata.yml` برفع بيانات النسخة 1.1.4 فشل (التشغيل 36333611368):
`POST /appStoreVersions → 409 ENTITY_ERROR.RELATIONSHIP.INVALID: You cannot create a new version of the App in the current state`.
آبل لا تسمح إلا بنسخة واحدة غير منشورة قابلة للتعديل، وكانت عندنا نسخة سابقة لم تُرسل بعد. الهدف: رفع البيانات
وتجهيز الإرسال لأي إصدار جديد بدون تدخل يدوي في App Store Connect.

## القرار والموافقات
`ensureVersion` في `apps/ios/scripts/asc-review-detail.mjs`: إن لم توجد النسخة المطلوبة، ووُجدت نسخة iOS بحالة قابلة
للتعديل (نفس مجموعة `EDITABLE` في `asc-prepare-submission.mjs`)، تُغيَّر `versionString` لها إلى الرقم الجديد بـ PATCH.
وإلا تُنشأ نسخة جديدة كما كان. المالك وافق على الرفع والإرسال لـ 1.1.4 («ارفع وارسل»، 2026-09-27).

## العقد
لا شيء.

## الملفات والتأثير
- `apps/ios/scripts/asc-review-detail.mjs` — إعادة التسمية.
- `apps/ios/scripts/asc-prepare-submission.mjs` — تصدير `EDITABLE` فقط.
- `apps/ios/scripts/asc-review-detail.test.mjs` — ثلاث حالات: موجودة، مفتوحة تُعاد تسميتها، كلها منشورة/في المراجعة فتُنشأ جديدة.
لا يمس التطبيقات ولا المستخدمين.

## الفحوص
- `node --test apps/ios/scripts/asc-review-detail.test.mjs apps/ios/scripts/asc-prepare-submission.test.mjs` — 16/16 نجحت.

## القيود والخطوة التالية
لم يُجرَّب على App Store Connect الحقيقي بعد. بعد الدمج: `ios-store-metadata.yml` (upload، 1.1.4) ثم `ios-submit.yml`
(submit=false للفحص، ثم submit=true).
