# أساس اللغات: سجل واحد، رجوع للإنجليزية، لغات تجريبية، وقواعد مساحة مقيسة (المرحلة الأولى)
المسؤول: twuijri · الفرع: feat/i18n-languages-foundation · الحالة: review

## المشكلة والهدف
طلب المالك (2026-09-28): مطورون من الخارج سيضيفون لغات (الصينية، الفرنسية، …)، ويجب أن تكون
الإضافة احترافية وآمنة: تُملأ ملفات الترجمة فتظهر اللغة في كل مكان (الويب، سطح المكتب، رسائل
الخادم، الطرفية، iOS، أندرويد)، ولا تنكسر الواجهة بنص أطول أو أعرض أو أعلى — ثقة ~95%. وكان قد
رأى عنوان عمود في لوحة المهام على الآيفون نزل آخر حرف منه لسطر ثانٍ (PR #210).

قبل هذا: العربية والإنجليزية مكتوبتان في الكود (`Language = 'ar' | 'en'` في الويب، `LANGUAGES`
في فحص الترجمة، أزرار تقلب `ar ⇄ en`، و`language === 'ar'` للاتجاه)، وكل حزمة فيها نسختها من
البحث عن المفتاح، ولا شيء يقيس هل تتسع الكلمات لمكانها.

هذه المرحلة الأولى: الويب وسطح المكتب والخادم والطرفية. المرحلة الثانية (أندرويد وiOS) في
`feat/i18n-languages-apps` بعد هذه.

## القرار والموافقات
ADR 0028 وDECISIONS §129 — مقترح، بانتظار تأكيد المالك. الخلاصة:
- **سجل واحد** `locales/languages.json` (+ مخطط JSON): الرمز BCP 47، الاسم بالإنجليزية وبلغته،
  الاتجاه، الحالة (`complete`/`partial`)، `required` (العربية والإنجليزية فقط)، سلسلة الرجوع،
  و`numerals`. كل حزمة TypeScript تقرؤه عبر `@corehub/contracts` (يُولَّد إلى
  `generated/ts/languages.ts` مع كل بناء)، وملف `catalogues.ts` مولَّد في كل منصة يستورد ملفاتها.
  الويب يحمل العربية والإنجليزية في الحزمة ويجلب غيرهما عند اختياره (47 كيلوبايت، 13 مضغوطة).
- **لا مفتاح خام أبدًا**: البحث على سلسلة اللغة ثم الإنجليزية؛ النص الفارغ = غير مترجم؛ صيغة
  الجمع الناقصة ترجع لـ`other` من اللغة نفسها قبل غيرها.
- **الجمع**: مفاتيح CLDR (`zero … other`) مع `Intl.PluralRules` كما هي اليوم — لا ICU (بلا محلّل
  في الحزم، وتقابل `<plurals>` و`.stringsdict` في المرحلة الثانية). **الأرقام لاتينية** في كل لغة
  غير لاتينية الخط (`-u-nu-latn`، §113)؛ `numerals: native` اقتراح للمترجمين والقرار للمالك.
- **قواعد المساحة مقيسة لا مخمَّنة**: مواصفة Playwright اختيارية تمشي الشاشات بالإنجليزية بلغة
  تجريبية `en-XK` (مفتاح كل نص ملحق بمحارف صفرية العرض، فيُعرف مفتاح كل تسمية بلا تغيير بكسل)،
  وتضع نصًا طويلًا جدًا في كل تسمية تنتهي بـ«…» لحظةً وتقرأ عرضها الأقصى. `pnpm i18n:limits
  --measure` يكتب `locales/limits.json` وجدول المترجمين `locales/limits.md` (56 تسمية مقيسة).
  `pnpm i18n:limits` يشكّل نص كل لغة بـHarfBuzz (`harfbuzzjs`، ويب أسمبلي، اعتمادية تطوير) بخطوط
  Noto لكل خط كتابة، ويفشل إن كانت الترجمة أعرض من مكانها **وأعرض من الإنجليزية**؛ العربية
  والإنجليزية تُذكران ولا تُفشِلان. بلا خطوط على جهاز المساهم: تقدير يقول إنه تقدير؛ CI لا يقدّر.
- **تحصين الويب**: التسمية المقصوصة بـ«…» تُقص أفقيًا فقط فلا تُقص نقاط الحروف العربية تحت السطر
  (خلل قائم اليوم وجدته الرحلة)، الشارات تنكمش بـ«…»، صف عنوان بطاقة البروفايل ينكمش، وصف رأس صفحة
  البروفايلات يلتف؛ التسمية المقصوصة تُظهر نصها كاملًا في `title` ما دامت مقصوصة فقط
  (`src/i18n/truncation.ts`)؛ `text-wrap: pretty`؛ خطوط `:lang()` للصينية المبسطة والتقليدية
  واليابانية والكورية والتايلندية والديفاناغارية والعبرية، وأسطر أعلى للخطوط ذات العلامات
  المكدسة (ومنها ارتفاعات `text-*` في Tailwind). **لا ملف خط مضمَّن**: لا زيادة في حجم الصورة أو
  المثبّتات.
- **لغات تجريبية للاختبار فقط**: `en-XA` (أطول ≥40% بحروف منبرة وأقواس)، `ar-XB` (عربية مطوّلة
  بالتطويل، RTL)، `zh-XC` (رموز صينية كاملة العرض بلا مسافات)، `th-XD` (تايلندية بعلامات مكدسة)،
  و`en-XK` للقياس. لا تظهر في أي قائمة، وتُفعَّل من تخزين المتصفح فقط.
- **رحلة Playwright** تمشي 18 شاشة رئيسية بكل لغة تجريبية على عرض سطح المكتب والهاتف، وتفشل على:
  تسمية سطر واحد تفيض بلا «…»، نص مقصوص بلا «…» (أفقيًا، أو حبر الحروف مقصوص من أعلى/أسفل —
  يُقاس بـ`measureText` لا بصندوق الخط)، حرف وحيد في آخر سطر تسمية قصيرة، عناصر تحكم فوق بعضها،
  وتمرير أفقي للصفحة. الفاحص يثبت نفسه أولًا على صفحة مكسورة عمدًا. كل اللقطات artifact في CI.
- **المساهمون**: `pnpm i18n:new <code>` يسجل اللغة (الاسمان والاتجاه من `Intl`) ويكتب ملف كل منصة
  بمفاتيح الإنجليزية ونصوص فارغة؛ دليل `docs/guides/add-a-language.md`، قسم في CONTRIBUTING،
  ونموذج بلاغ «New language».
- **ما يبقى عربيًا وإنجليزيًا** (ADR 0027): `Locale` في العقد وتعداد `Accept-Language` لا يتغيران
  (توسيع تعداد مغلق يكسر عملاء الجوال المولَّدين). الخادم يقرأ أي لغة مسجلة من `Accept-Language`
  لرسائل الخطأ (`request.uiLanguage`)، ويخزّن أقرب العربية/الإنجليزية في `locale`، والويب يحفظ
  اللغة المختارة في المتصفح كما كان يحفظ لغة العرض.
- **لا تغيير لمستخدمي اليوم**: السجل فيه العربية والإنجليزية فقط، فزر اللغة يبقى زرًا يقلب بين
  الاثنتين بالنص نفسه، والعرض يبقى تحكمًا مقسّمًا بالخيارين. بلغة ثالثة يصير الزر قائمة والعرض
  قائمة منسدلة (مثبت باختبار بسجل بديل).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
مستند OpenAPI والأحداث: **لا شيء**. في الحزمة: وحدة `src/languages.ts` جديدة (تصدير من
`index.ts` ومن المسار الفرعي `@corehub/contracts/languages` لنافذة سطح المكتب الأولى التي لا تحمل
وحدات Node)، `generate-ts.mjs` يولّد `generated/ts/languages.ts` من السجل، وخيار `language` في
عميل TypeScript صار `string` بدل `'ar' | 'en'` (السلك نفسه). DECISIONS §129 يوثق قراءة
`Accept-Language`.

## الملفات والتأثير
- السجل والأدوات: `locales/languages.json`، `languages.schema.json`، `limits.json`، `limits.md`؛
  `scripts/i18n-check.mjs` (مُعاد كتابته على السجل)، `scripts/i18n/{registry,generate,new,limits}.mjs`،
  `scripts/i18n.test.mjs`؛ `package.json` (`i18n:new`، `i18n:generate`، `i18n:limits`،
  `harfbuzzjs` للتطوير).
- العقد: `packages/contracts/src/languages.ts`، `index.ts`، `client.ts`، `package.json` (مسار
  فرعي)، `scripts/generate-ts.mjs`، `tests/languages.test.ts`.
- الخادم: `i18n/index.ts` (`pickUiLanguage`، `t` على السلسلة)، `i18n/catalogues.ts`، `lib/errors.ts`،
  `app/routes.ts` (`uiLanguage` لأغلفة الأخطاء)، `Dockerfile` (نسخ السجل)، اختبارات.
- الطرفية: `i18n/index.ts`، `i18n/catalogues.ts`، اختبار.
- سطح المكتب: `shared/i18n.ts`، `shared/config.ts`، `main/{config-store,controller}.ts`،
  `preload/index.ts`، `renderer/welcome.ts` (زر أو قائمة)، `i18n/catalogues.ts`، مفتاح
  `welcome.language_label` بالعربية والإنجليزية، اختبار.
- الويب: `i18n/{index,context,catalogues,LanguageSwitch,truncation}.ts(x)`، `design/theme.tsx`،
  `app.tsx`، `main.tsx`، شاشات الدخول والإعداد، الشريط الجانبي، `DisplayTab`، `SplitPane`
  (الاتجاه من السجل)، إشعارات المتصفح (`serverLocale`)، `WorkspacesTab`، `starters.ts`،
  `ChannelPlatformPicker.tsx`، `usage/report.ts`، `bridge-types.ts`، `styles/languages.css`،
  اختبارات `languages.test.tsx` و`languages-menu.test.tsx`؛ e2e: `i18n-audit.ts`،
  `pseudo-screens.ts`، رحلة اللغات التجريبية، ومواصفة القياس الاختيارية.
- CI: وظيفة `i18n-space` (خطوط Noto + `pnpm i18n:limits`) ضمن البوابة؛ خطوط Noto في وظيفة e2e،
  ولقطات اللغات التجريبية artifact دائمًا.
- المستندات: ADR 0028، DECISIONS §129، STATUS (قسم Languages)، `validation.md`، CONTRIBUTING،
  `docs/guides/add-a-language.md`، `.github/ISSUE_TEMPLATE/new_language.yml`.

## الفحوص (الأوامر ونواتجها الفعلية)
محليًا (الذاكرة عبر mj-run، عمال vitest=2، Playwright ‏`--workers=1`):
```
pnpm lint                                    All matched files use Prettier code style!
pnpm typecheck                               exit 0 (every package)
pnpm i18n:check                              registry: 2 languages (ar, en), 5 test-only pseudo-locales
                                             server 216 · cli 252 · web 3358 · desktop 105 · ios 2706 keys, ar/en in parity
                                             i18n:check  OK
pnpm i18n:limits                             ar: 56 measured labels, 0 too wide, 4 cut with an ellipsis as in English
                                             en: 56 measured labels, 0 too wide, 6 cut with an ellipsis as in English
                                             i18n:limits  OK
pnpm scripts:test                            tests 102 · pass 102 · fail 0 (8 of them scripts/i18n.test.mjs)
pnpm nav:check                               nav:check  OK — 41 destinations …
pnpm contracts:lint                          contracts:lint  OK
pnpm contracts:compat                        contracts:compat  OK — no breaking change against v1.1.4
pnpm contracts:check-clients                 check-clients  OK — 1090 client file(s) scanned, 267 contract path(s) known.
contracts  vitest tests/languages.test.ts    Tests 11 passed (11)
server     vitest i18n-languages + http      Tests 10 passed (10)
cli        vitest tests/i18n.test.ts         Tests 4 passed (4)
desktop    vitest tests/unit/shared.test.ts  Tests 34 passed (34)
web        vitest (14 files: languages, languages-menu, i18n, latin-digits, theme, setup-screen, people,
           browser-push-resume, devices-push, channels-picker, usage-reports, sidebar-rail, composer,
           run-changes)                      Test Files 14 passed (14) · Tests 114 passed (114)
pnpm build                                   desktop build: apps/desktop/dist/hub ready (exit 0)
playwright pseudo-locales (5 tests)          the audit self-test + en-XA, ar-XB, zh-XC, th-XD: passed
playwright smoke + zz-design + zz-task-board + zzzz-sidebar-rail   26 passed (1.9m)
```
ما وجدته الرحلة قبل الإصلاح (ولم يعد): شارتا «الحالي/الافتراضي» تُقصّان بلا «…» في بطاقة البروفايل
على الهاتف، صف رأس صفحة البروفايلات يفيض 4px، شريحة مجلد العمل تقص علامات التايلندية العلوية
2px (ارتفاع سطر Tailwind)، وحرف وحيد في آخر سطر بعض النصوص الصينية القصيرة.

وفي CI (حيث تضيف الرحلات الأخرى بيانات عربية) وجدت الرحلة ما لم يظهر محليًا: **نقاط الحروف العربية
تحت السطر تُقصّ اليوم** في عناوين البطاقات ونتائج البحث وسطور الإشعارات (نقطتا «ي» في «في» تختفيان
فتُقرأ «فى»)، لأن `overflow: hidden` مع ارتفاع سطر 1.25 يقص حبر الحروف. الإصلاح: التسميات المقصوصة
بـ«…» تُقص أفقيًا فقط (`overflow-x: clip; overflow-y: visible`) — النقاط والتخطيط والـ«…» كما هي.
بعده: `playwright smoke + zz-task-board + pseudo-locales` → `27 passed (4.0m)`.

أثر `text-wrap: pretty` على العربية والإنجليزية اليوم: 72 لقطة (18 شاشة × لغتين × عرضين) مع
الخاصية وبدونها — لا سطر انتقل؛ أقصى فرق 99 بكسلًا بدلتا لون ≤ 8/255 (تنعيم حواف فقط)، ولقطة
التحكم بلا تغيير صفرية الفرق.

CI على الطلب: يُضاف بعد الدفع.

## المخاطر والرجوع
- القياس بخطوط Noto (من أعرض خطوط الواجهة)؛ Segoe UI وSF أضيق، فما ينجح هنا يتسع هناك. خط
  النظام للغة جديدة قد يختلف.
- 56 تسمية فقط لها مساحة ثابتة مقيسة على 18 شاشة؛ الباقي يلتف أو ينمو، وتغطيه رحلة اللغات
  التجريبية على الشاشات نفسها. النوافذ المنبثقة والقوائم غير مقيسة بعد.
- `limits.json` يُعاد قياسه إذا تغير تخطيط شاشة (الأمر في الدليل).
- رسائل الخادم المكتوبة في الكود (الإشعارات والعناوين) تبقى عربية/إنجليزية؛ نقلها إلى الكتالوج
  عمل لاحق.
- الرجوع: التراجع عن الدمج؛ لا بيانات ولا ترحيلات ولا عقد تغيّر، والسجل بلغتين يعطي الواجهة نفسها.

## التسليم والخطوة التالية
- طلب دمج إلى `main` (الإنجليزية). لا دمج ولا صورة.
- المرحلة الثانية `feat/i18n-languages-apps`: نصوص أندرويد تُولَّد من JSON وقت البناء، iOS يقرأ
  السجل ويرجع بالطريقة نفسها، قواعد المساحة نفسها، اختبارات Compose وXCTest باللغات التجريبية،
  واختيار اللغة لكل تطبيق.
- للمالك: تأكيد ADR 0028 وDECISIONS §129، واقتراح `numerals: native`.
