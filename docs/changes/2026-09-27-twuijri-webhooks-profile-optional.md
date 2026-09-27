# إصلاح عاجل: ترويسة البروفايل اختيارية في عمليات الـwebhooks الصادرة
المسؤول: twuijri · الفرع: fix/webhooks-profile-optional · الحالة: review

## المشكلة والهدف
في v1.1.3 (DECISIONS §115، سجل `2026-09-27-twuijri-apps-leftovers.md`) صارت عمليات `/notify/webhooks*` السبع
(`notify.listWebhooks`، `createWebhook`، `updateWebhook`، `deleteWebhook`، `testWebhook`، `listWebhookDeliveries`،
`redeliverWebhookDelivery`) تشير إلى المعامل المشترك `Profile` وهو `required: true`، وحُذف منها `x-scope: global`.
العميل المبني على v1.1.2 (تطبيقات الجوال القديمة، الـCLI، سكربتات الناس) لا يرسل الترويسة، فالعقد صار يعدّه
مخالفًا — تغيير كاسر يرصده حارس التوافق (PR #183، `compat.mjs`) بـ١٤ كسرًا بين v1.1.2 وmain. هذا يخالف قاعدة
المالك «لا تغييرات كاسرة» (٢٠٢٦-٠٩-٢٧).

الهدف: تعود الترويسة اختيارية؛ إن أُرسلت تُحترم كما في v1.1.3، وإن غابت يُجاب الطلب تمامًا كما أجاب v1.1.2.

## القرار والموافقات
ما كان يفعله المركز في v1.1.2 عند غياب الترويسة (من `scopeOf` في `packages/server/src/modules/notify/index.ts`، ولم
يتغير كود الخادم بين v1.1.2 وv1.1.3): العمليات كانت `x-scope: global` فلا يعمل حارس `requireWorkspace`، و`scopeOf`
يحلّ البروفايل من الترويسة، وإلا من قيمة `?profile=` في الاستعلام، وإلا `default`. في v1.1.3 صار الحارس يحلّ
الترويسة قبل المعالج (والغائبة = `default`)، فسقطت قراءة `?profile=` بصمت: سكربت كان يكتب في `work` بها صار
يكتب في `default`.

القرار (تعديل على §115، مقترح — للمالك أن يؤكد؛ المرجع ADR 0027 عند دمج #183):
- معامل جديد `components.parameters.ProfileOptional` (`X-Hub-Profile`، `required: false`) بوصف يشرح البديل؛ العمليات
  السبع تشير إليه بدل `Profile`، ويعود إليها `x-scope: global` كما في v1.1.2. `Profile` نفسه وكل العمليات الأخرى
  لم تتغير. إضافة فقط.
- الخادم: الحارس ما زال يعمل (المعامل مُعلن)، فالترويسة المرسلة تُحترم بالردود نفسها (`404 profile_not_found`
  لبروفايل غير موجود أو غير مسموح). وإن غابت الترويسة وجاءت `?profile=` يُقرأ منها كما في v1.1.2. الصيغة تبقى غير
  موثقة كمدخل للعملاء الجدد (§4)؛ أُبقيت لسكربت قديم فقط.
- العملاء المولّدون: صار المعامل الاختياري بعد المعاملات الإلزامية في Kotlin وSwift، فصار الويب/iOS/Android
  يمررون `xHubProfile` باسمه. نداءات Kotlin الموضعية القديمة كانت ستُترجم بصمت بترتيب خاطئ
  (`notifyDeleteWebhook(profile, id)`)، فحُوّلت كلها إلى وسائط مسماة.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
- `openapi.yaml`: إضافة `components.parameters.ProfileOptional`؛ العمليات السبع تشير إليه بدل `Profile` ويعود لها
  `x-scope: global`؛ وصف `notify.listWebhooks` يذكر أن الغياب = `default`.
- `docs/contracts/DECISIONS.md`: فقرة «Amended» تحت §115، وتصحيح جملة §4.

## الملفات والتأثير
- `packages/contracts/openapi.yaml` — ما سبق.
- `packages/server/src/modules/notify/index.ts` — `scopeOf` يقرأ `?profile=` حين تغيب الترويسة (`legacyProfileQuery`).
- `packages/server/src/modules/notify/notify.test.ts` — أربعة اختبارات: العمليات السبع بلا ترويسة تعمل وتنتهي في
  `default`؛ الترويسة تنقل إلى بروفايلها فقط؛ `?profile=` يُقرأ بلا ترويسة والترويسة تغلبه؛ `404 profile_not_found`
  فقط لبروفايل مسمّى غير موجود (بالترويسة أو بالاستعلام). اختباران منها يفشلان على كود main (نُفّذ).
- `packages/server/tests/contract/webhooks.contract.test.ts` — يتحقق أن العمليات السبع تشير إلى `ProfileOptional`
  لا `Profile`، وأنها `x-scope: global`، وأن المعامل `required: false`.
- `apps/android/.../settings/HubDataKit.kt`، `apps/ios/.../WebhooksPage.swift`، `apps/ios/.../WebhookSheet.swift` —
  ترتيب/تسمية الوسائط للتوقيعات المولّدة الجديدة؛ السلوك نفسه (الترويسة تُرسل دائمًا).
- `docs/STATUS.md` — سطر الـwebhooks.
- لا تغيير على البيانات أو الترحيلات أو الأسماء البيئية.

## الفحوص (الأوامر ونواتجها الفعلية)
حارس التوافق من PR #183 (شجرة `corehub-wt-compat` عند `eb13e650`) مشغّلًا على هذا الفرع. قبل التعديل ضد v1.1.2:
١٤ سطر `break` (`parameter-required … header.X-Hub-Profile` و`operation-profile-required` للعمليات السبع). بعده:
```
$ node /home/twuijri/project/corehub-wt-compat/packages/contracts/scripts/compat.mjs --root <this worktree> --base v1.1.2
contracts:compat  compared packages/contracts/openapi.yaml and 95 event schemas with v1.1.2
contracts:compat  OK — no breaking change against v1.1.2
$ … --base v1.1.3
contracts:compat  compared packages/contracts/openapi.yaml and 95 event schemas with v1.1.3
contracts:compat  OK — no breaking change against v1.1.3
```
محليًا (كلها عبر mj-run):
```
$ pnpm contracts:generate            # JDK 17
contracts:generate:native  kotlin: explicit nulls in 77 request model(s)
contracts:generate:native  swift: explicit nulls in 71 request model(s)
contracts:generate:native  OK
$ pnpm contracts:lint
Woohoo! Your API description is valid. 🎉
You have 1 warning.          # 7405: no-invalid-media-type-examples، سابق وغير متعلق
contracts:lint  OK
$ pnpm contracts:check-clients
check-clients  OK — 996 client file(s) scanned, 254 contract path(s) known.
$ pnpm --filter @corehub/contracts test
      Tests  58 passed (58)
$ pnpm contract:test
 Test Files  19 passed (19)
      Tests  405 passed (405)
$ pnpm --filter @corehub/server exec vitest run src/modules/notify/notify.test.ts
      Tests  18 passed (18)
# نفس الملف مع notify/index.ts من main:
     × reads a ?profile= value when the header is absent, as v1.1.2 did; the header wins
     × answers 404 profile_not_found only for a profile that is named and does not exist
      Tests  2 failed | 16 passed (18)
$ pnpm lint
All matched files use Prettier code style!
$ pnpm typecheck                     # EXIT=0
$ ./gradlew :app:testDebugUnitTest --tests '*KnowledgeReportsTest*'   # يترجم التطبيق مع العميل المولّد الجديد
tests="12" skipped="0" failures="0" errors="0"   # منها: webhooks carry the profile (X-Hub-Profile = work/home)
```
iOS: يُترجم ويُختبر في CI فقط (`ios.yml` يعمل عند تغيّر `openapi.yaml` و`apps/ios`).

CI على PR #186 (قبل commit هذا السطر):
```
$ gh pr checks 186 --repo twuijri/core-hub
15 pass, 1 skipping (Upload the listing to App Store Connect)
Android build, unit tests, lint  pass · Build and test on the iOS simulator  pass
Generate the Swift client (CoreHubClient)  pass · Server unit tests (shard 1/3, 2/3, 3/3)  pass
Lint, typecheck, contracts, client tests, build  pass · Web smoke journeys (Playwright)  pass
Docker image builds and answers /health  pass · db:generate + db:migrate  pass
```

## المخاطر والرجوع
- التطبيقات الحالية (v1.1.3) ترسل الترويسة فلا يتغير لها شيء؛ القديمة (v1.1.2) تعمل كما كانت.
- قراءة `?profile=` عادت لهذه العمليات السبع فقط وحين تغيب الترويسة فقط؛ الإشعارات والتفضيلات لم تتغير.
- الرجوع: إرجاع هذا الـcommit يعيد v1.1.3 كما هو (والكسر معه).

## التسليم والخطوة التالية
PR واحد إلى `main` بالإنجليزية. على المالك: تأكيد تعديل §115، ودمج #183 ليصبح الحارس جزءًا من CI.
