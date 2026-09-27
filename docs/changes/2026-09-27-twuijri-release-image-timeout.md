# الإصدار 1.1.3: مهلة بناء الصورة، ومجموعة TestFlight التلقائية
المسؤول: twuijri · الفرع: fix/release-image-timeout-tf-groups · الحالة: review

## المشكلة والهدف
وسم `v1.1.3` (٢٠٢٦-٠٩-٢٧):
- «Release» (صورة `ghcr.io/twuijri/core-hub` لمنصتين amd64 وarm64) انقطع عند حدّ ٣٠ دقيقة (run 36308150240، ٠٩:٠٣ → ٠٩:٣٤). أُعيد تشغيله يدويًا.
- رفع TestFlight (run 36308617461) رفع النسخة، ثم فشل عند إضافتها لمجموعة «Owner»:
  `422 ENTITY_UNPROCESSABLE: Builds cannot be assigned to this internal group. — Cannot add internal group to a build.`
  المجموعة داخلية ومضبوطة على «كل النسخ تلقائيًا» (`hasAccessToAllBuilds`)، فالنسخة عندها أصلًا وأبل ترفض الإضافة اليدوية.

## القرار والموافقات
- مهلة مهمة الصورة ٧٥ دقيقة.
- `testflight-distribute.mjs`: المجموعة الداخلية التي تأخذ كل النسخ لا يُضاف إليها شيء، ويُكتب أنها أخذت النسخة تلقائيًا، وتبقى بقية المجموعات تُضاف كما كانت. لا يتغيّر شيء آخر.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
`.github/workflows/release.yml`، `apps/ios/scripts/testflight-distribute.mjs` واختباره.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ node --test apps/ios/scripts/testflight-distribute.test.mjs
ℹ tests 17
ℹ pass 17
ℹ fail 0
```

## المخاطر والرجوع
لا شيء يكسر: مهلة أطول فقط، ومجموعة لا يُرسل لها طلب لا تحتاجه. الرجوع بإرجاع الطلب.

## التسليم والخطوة التالية
طلب إلى `main`. الإصدار التالي يبني الصورة ضمن المهلة، ولا يفشل رفع TestFlight بسبب «Owner».
