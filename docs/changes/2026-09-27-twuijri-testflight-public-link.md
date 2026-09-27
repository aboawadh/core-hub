# رابط TestFlight العام: كل نسخة تُرفع تذهب لمجموعة خارجية برابط عام وتُرسل لمراجعة Beta
المسؤول: twuijri · الفرع: ci/testflight-public-link · الحالة: review

## المشكلة والهدف
المالك (٢٠٢٦-٠٩-٢٧) يريد أن يرسل للناس رابطًا واحدًا بدل إضافة كل مختبِر بيده في App Store Connect.
اليوم يرفع `ios-signed.yml` النسخة إلى TestFlight ويضيفها لمجموعة «Owner» الداخلية فقط، ولا توجد
مجموعة خارجية، ولا يُرسل شيء لمراجعة Beta App Review.

## القرار والموافقات
- الفكرة قرار المالك (٢٠٢٦-٠٩-٢٧). التفاصيل «proposed — owner to confirm» في DECISIONS §117.
- سكربت جديد `apps/ios/scripts/testflight-public.mjs` (يعيد استعمال عميل App Store Connect في
  `testflight-distribute.mjs`)، ويعمل خطوةً مستقلة بعد رفع النسخة ومجموعاتها الداخلية وبعد حفظ
  الـ artifact، فلا يمسّها فشله:
  1. يتأكد أن المجموعة الخارجية (الافتراضي `Public`، مدخل `external_group`، فارغ = تخطٍّ) موجودة
     ورابطها العام مفعّل بحد `public_link_limit` (الافتراضي 1000، أبل تسمح 1–10000) والملاحظات
     مفعّلة؛ ينشئها أول مرة، ويقرأها ثانيةً ويطبع `publicLink` في السجل وملخص التشغيل.
  2. ينتظر معالجة النسخة (فورًا إن كانت خطوة المجموعات انتظرت).
  3. يضبط «What to Test» للنسخة بالإنجليزية والعربية: `Core Hub X.Y.Z — what's new: <رابط الإصدار>`؛
     إن رفضت أبل العربية فتحذير فقط.
  4. يفحص معلومات الاختبار: يملأ وصف النسخة التجريبية ورابط الخصوصية من
     `apps/ios/fastlane/metadata` إن كانا فارغين (غير شخصيين)، ولا يخترع أبدًا بريد الملاحظات ولا
     جهة التواصل ولا الحساب التجريبي؛ إن نقص شيء منها تفشل الخطوة وتسمّي كل حقل ومكانه:
     App Store Connect → Apps → Core Hub → TestFlight → Test Information، ولا يُرسل شيء.
  5. يرسل النسخة لـ Beta App Review إلا إن كانت أُرسلت أو اعتُمدت (حالة `externalBuildState` أو
     `betaAppReviewSubmissions?filter[build]`)، ثم يضيفها للمجموعة.
- «Sign-in required» مع الحساب التجريبي وملاحظات المراجعة تُعدّ مطلوبة، لأن التطبيق لا يعمل دون
  خادم — مثل ما يفعله `asc-prepare-submission.mjs` لمراجعة المتجر.
- دفع الوسوم لا يرفع إلى TestFlight اليوم، فلم يتغير سلوكه.
- مبني فوق الطلب #184 (`fix/release-image-timeout-tf-groups`): مجموعة «Owner» التلقائية لا تُمسّ.
- نقاط Apple الموثقة فقط (تحققت من صفحات التوثيق): `POST/PATCH/GET /v1/betaGroups`,
  `POST /v1/betaGroups/{id}/relationships/builds`, `GET /v1/builds/{id}/betaBuildLocalizations`,
  `POST/PATCH /v1/betaBuildLocalizations`, `GET /v1/apps/{id}/betaAppLocalizations`,
  `POST/PATCH /v1/betaAppLocalizations`, `GET /v1/apps/{id}/betaAppReviewDetail`,
  `GET/POST /v1/betaAppReviewSubmissions`.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `apps/ios/scripts/testflight-public.mjs` واختباره `testflight-public.test.mjs` (جديدان).
- `.github/workflows/ios-signed.yml`: مدخلان جديدان `external_group` و`public_link_limit`، ومعرّف
  `upload` لخطوة الرفع، وخطوة «TestFlight public link and Beta App Review»، ومهلة المهمة ١٣٠ دقيقة
  بدل ٩٥ (٣٠ دقيقة انتظار إضافية إن لم تنتظر خطوة المجموعات).
- `docs/RELEASING.md` (الخطوة ٧، وما يملؤه المالك مرة واحدة، وخطوة كل إصدار)،
  `docs/contracts/DECISIONS.md` §117، `docs/STATUS.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ node --test apps/ios/scripts/testflight-public.test.mjs
ℹ tests 16
ℹ pass 16
ℹ fail 0

$ node --test apps/ios/scripts/*.test.mjs
ℹ tests 46
ℹ pass 46
ℹ fail 0

$ pnpm lint
$ eslint . && prettier --check .
Checking formatting...
All matched files use Prettier code style!

$ python3 -c "import yaml; yaml.safe_load(open('.github/workflows/ios-signed.yml'))"
(no error)
```
`actionlint` غير مثبت على الجهاز فلم يُشغَّل. لم يُشغَّل سير العمل على App Store Connect الحقيقي
(ممنوع في هذه المهمة)؛ كل الاختبارات على خادم App Store Connect مزيّف.

الاختبارات تغطي: إنشاء المجموعة/إيجادها، تفعيل الرابط والحد، ضبط whatsNew (إنشاء وتحديث، ورفض
العربية)، إضافة النسخة، الإرسال للمراجعة وترتيبه قبل الإضافة، تخطّي المعتمدة والمنتظرة، نقص تفاصيل
المراجعة → خطأ واضح برمز ١ دون إرسال ودون طباعة القيم، ملء الوصف والخصوصية من القائمة دون البريد،
انتهاء المهلة → تحذير برمز ٠، اسم مجموعة داخلية مرفوض، حد غير صالح، وعدم لمس «Owner» التلقائية (#184).

## المخاطر والرجوع
- الرابط العام يظهر في سجل Actions العام (المستودع عام)؛ هذا مقصود، والحد يقيّد عدد المنضمّين،
  والمالك يستطيع إيقاف الرابط من App Store Connect.
- التشغيل التالي يعيد ضبط حد الرابط من المدخل إن غيّره المالك يدويًا.
- لم يُجرَّب على أبل الحقيقية: إن كانت أبل لا تعيد `demoAccountPassword` في القراءة فستعدّه الخطوة
  ناقصًا (نفس افتراض `asc-prepare-submission.mjs`)؛ يظهر ذلك في أول تشغيل ويُصلح بسطر.
- لا كسر: تشغيل بلا المدخلين الجديدين يأخذ الافتراضي؛ `external_group` فارغ يعيد السلوك القديم تمامًا.
  الرجوع بإرجاع الطلب أو بترك `external_group` فارغًا.

## التسليم والخطوة التالية
طلب واحد إلى `main` مبني فوق #184. بعد الدمج: المالك يملأ Test Information مرة واحدة (بريد
الملاحظات، جهة التواصل، Sign-in required مع الحساب التجريبي، ملاحظات المراجعة) ثم يشغّل
*iOS signed build* مع `upload_testflight`، ويأخذ الرابط من ملخص التشغيل.
