# تجهيز إرسال آبل: كل الدول في طلب التوفّر، والمستثناة «غير متاح»
المسؤول: twuijri · الفرع: fix/asc-availability-all-territories · الحالة: review

## المشكلة والهدف
فحص تجهيز 1.1.4 (`ios-submit.yml`، submit=false) بعد #200 ضبط السعر المجاني ثم توقف عند التوفّر:
`POST /appAvailabilities → 409 ENTITY_ERROR.RELATIONSHIP.INVALID … expects an included resource with type 'territories' and id 'CHN' but no matching resource was included`.
السكربت كان يحذف الدول المستثناة (الصين) من الطلب، وآبل تريد كل الدول فيه. الهدف: يكتمل التوفّر والصين مستثناة.

## القرار والموافقات
`ensureAvailability` يرسل كل دولة من `/territories`؛ المستثناة في `EXCLUDED_TERRITORIES` بـ `available: false`، والباقي
`true`، مع `availableInNewTerritories: true` كما كان. الاستثناء نفسه لم يتغير. المالك وافق (2026-09-27، «ايه»).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `apps/ios/scripts/asc-prepare-submission.mjs` — `ensureAvailability`.
- `apps/ios/scripts/asc-prepare-submission.test.mjs` — الاختبار يتحقق أن كل الدول مرسلة وأن المستثناة غير متاحة.
أدوات المتجر فقط.

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
ℹ duration_ms 281.892917
```
eslint وprettier نظيفة. لم يُجرَّب على App Store Connect الحقيقي بعد (مفتاح الـAPI في أسرار المستودع فقط).

## المخاطر والرجوع
إن رفضت آبل الشكل الجديد يفشل الفحص قبل الإرسال كما فشل الآن؛ لا أثر على المستخدمين. الرجوع: revert.

## التسليم والخطوة التالية
ينتظر دمج المالك. بعده: `ios-submit.yml` (1.1.4، submit=false) ثم submit=true.
