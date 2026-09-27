# الإعدادات ← الأسرار: للمالك وحده، بكلمة المرور في كل فتح
المسؤول: twuijri · الفرع: night/ui-sidebar-secrets (يُدمج في night/2026-09-28، طلب الدمج #209) · الحالة: review

## المشكلة والهدف
طلب المالك (2026-09-28): قسم «الأسرار» في الإعدادات، في الويب وسطح المكتب فقط، يراه المالك (`owner`) وحده —
لغيره لا يظهر المدخل أصلًا والواجهات ترفض — ويطلب كلمة مرور الحساب في **كل** مرة يُفتح، ويعرض الأسرار التي يحفظها
المركز أو يعرف أنها أسرار: مفاتيح المزوّدين المشفّرة، وتوكنات القنوات في ملفات `.env` لبروفايلات هرمز، وبيانات دخول
خوادم MCP (المعروضة `[stored]` في مكان آخر)، وأسرار الويبهوك الصادرة والواردة. مجمّعة بالنوع والبروفايل، القيم مخفية،
تُعرض واحدة في كل مرة وتختفي بعد نحو 30 ثانية، مع زرّ نسخ، وكل عرض يُكتب في سجل التدقيق (من، وأي سر، ومتى — لا
القيمة). لا قيم في السجلات ولا الأخطاء ولا الكاش.

## القرار والموافقات
- الطلب نفسه قرار المالك. التفاصيل المقترحة — ينتظر تأكيد المالك — في DECISIONS §125:
  - **خطوة تأكيد (step-up)**: `POST /auth/step-up` يتحقق من كلمة المرور ويعطي «إذنًا» مدته 5 دقائق، في ذاكرة المركز
    فقط (بصمته SHA-256)، مربوطًا بالشخص وجلسة الدخول والغرض؛ إذن جديد يلغي السابق، و`DELETE` يلغيه فورًا (الصفحة
    تناديه عند مغادرتها)، وإعادة تشغيل المركز تنهيه. رمز التطبيق (جوال مقترن أو تكامل) مرفوض. كلمة خاطئة تُعدّ على
    قفل الدخول نفسه لعنوان الجهاز (5 في 15 دقيقة ← قفل 15 دقيقة، ويشمل تسجيل الدخول)، والنجاح والفشل صفّا تدقيق بلا
    كلمة المرور.
  - القائمة والقيمة `POST /secrets/list` و`POST /secrets/reveal` والإذن في جسم الطلب (لا في عنوان ولا ترويسة)،
    والرد `Cache-Control: no-store`.
  - الإذن **ليس** لاستعمال واحد: يكفي لعرض القائمة وكشف عدة قيم خلال الدقائق الخمس، وإلا لطلبت كل قيمة كلمة المرور.
  - لا تُعرض آخر أربعة أحرف (جزء من سر بلا صفّ تدقيق). النسخ لقيمة مخفية يجلبها (ويُسجَّل كشفًا) دون عرضها.
  - مفاتيح مرسل الإشعارات (APNs/FCM) التي يحفظها `devices` مشفّرة غير مسرودة بعد.
- يعدّل ثابتة ARCHITECTURE «لا تُعاد الأسرار لعميل أبدًا» باستثناء واحد: `secrets.reveal` للمالك خلف التأكيد.
- لا كسر (ADR 0027): عمليات وحقول جديدة فقط؛ `pnpm contracts:compat` ناجح. الهواتف لا تناديها.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافات فقط في `openapi.yaml`: العمليات `auth.stepUp` و`auth.endStepUp` (`/auth/step-up`)، و`secrets.list`
(`/secrets/list`) و`secrets.reveal` (`/secrets/reveal`) تحت وسم جديد `secrets`؛ والمخططات `StepUpRequest`
و`StepUpPurpose` و`StepUpGrant` و`SecretsListRequest` و`SecretRevealRequest` و`SecretKind` و`SecretEntry` و`SecretList`
و`SecretValue`. العملاء المولَّدون يتجدّدون منها (TypeScript محليًا، وKotlin/Swift على CI).

## الملفات والتأثير
- الخادم: `modules/auth/step-up.ts` (جديد) و`auth/index.ts` (`stepUpFor`)؛ `modules/models/secrets-view.ts`
  و`models/stored-keys.ts` (جديدان) و`models/index.ts`؛ `modules/agents/secret-files.ts` (جديد) و`agents/mcp.ts`
  (`mcpCredentials`) و`agents/index.ts` (`hermesSecretsFor`)؛ `modules/notify/index.ts` (`webhookSecretsFor`)؛
  رسائل `auth.step_up_*` في `src/i18n/{ar,en}.json`؛ `auth/README.md`.
- الويب: `settings/SecretsTab.tsx` و`settings/secrets.ts` (جديدان)، `SettingsScreen.tsx`، `ui/icons.tsx` +
  `lucide.generated.ts` (Lucide `key-round`)، `i18n/{ar,en}.json` (`nav.secrets` و`secrets.*`).
- التنقّل: `docs/clients/navigation.json` (المصطلح `secrets`، الوجهة `secrets` تبويبًا بعد `privacy`،
  `roles: [owner]`، `surfaces: [web, desktop]`، ومسار الويب `/settings/secrets`)، `NAVIGATION.md`؛ و
  `apps/ios/CoreHub/i18n/{ar,en}.json` (`nav.secrets` فقط، لأن اختبار iOS يطلب كل مصطلح).
- الاختبارات: `packages/server/tests/unit/secrets-view.test.ts`، `tests/contract/secrets.contract.test.ts`،
  `packages/web/tests/secrets.test.tsx`، رحلة Playwright `e2e/zzzzzzzzzzzzzzzz-secrets.spec.ts`.
- `docs/contracts/DECISIONS.md` §125، `docs/ARCHITECTURE.md`، `docs/STATUS.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm contracts:lint
contracts:lint  OK
$ pnpm contracts:compat
contracts:compat  OK — no breaking change against v1.1.4
$ pnpm --filter @corehub/contracts test
      Tests  118 passed (118)
$ node packages/contracts/scripts/check-clients.mjs
check-clients  OK — 1067 client file(s) scanned, 265 contract path(s) known.
$ node scripts/navigation-check.mjs
nav:check  OK — 41 destinations, 2 pre-auth screens (login, setup), 47 terms, ar/en complete, routes for web, ios, android, desktop
$ node scripts/i18n-check.mjs | tail -1
i18n:check  OK
$ pnpm typecheck
exit 0
$ pnpm lint
All matched files use Prettier code style!
$ npx vitest run --maxWorkers=2 tests/unit/secrets-view.test.ts src/modules/agents/mcp.test.ts src/modules/notify/notify.test.ts src/modules/auth/auth.test.ts src/modules/auth/roles.test.ts   (packages/server)
 Test Files  5 passed (5)
      Tests  51 passed (51)
$ npx vitest run --project contract --maxWorkers=2 tests/contract/secrets.contract.test.ts tests/contract/contract.test.ts   (packages/server)
      Tests  369 passed (369)
$ npx vitest run --maxWorkers=2 tests/secrets.test.tsx tests/navigation.parity.test.tsx tests/terminal.test.tsx   (packages/web)
      Tests  29 passed (29)
$ pnpm build && PLAYWRIGHT_CHANNEL=chrome npx playwright test --workers=1 e2e/zzzzzzzzzzzzzzzz-secrets.spec.ts e2e/zzzzzzzzzzzzzzz-sidebar-tools.spec.ts
  ✓  1 … Tools folds away, Search sits by the toggle, and Workflows has its own page (1.6s)
  ✓  2 … the owner opens Secrets with the password each time, and reveals one value at a time (1.9s)
  2 passed (12.6s)
```
ما يثبته اختبار الخادم: المالك وحده (المشرف ورمز التطبيق مرفوضان)، كلمة المرور في كل مرة (بلا إذن، أو إذن منتهٍ
بعد 5 دقائق، أو إذن ملغى، أو إذن جلسة أخرى ← `403 step_up_required`)، القفل بعد 5 محاولات خاطئة (`429` ويشمل تسجيل
الدخول)، أنواع الأسرار الخمسة بالاسم ثم قيمها، صفوف التدقيق بلا قيم، ولا قيمة ولا كلمة مرور في أي سطر سجل.

## المخاطر والرجوع
- الصفحة تكشف قيمًا لم تكن تُعاد من قبل؛ الحماية: المالك وحده، جلسة ويب، كلمة المرور في كل فتح، 5 دقائق، تدقيق
  لكل كشف. إن رأى المالك غير ذلك: إخفاء الوجهة من `navigation.json` وإزالة المسارين — لا بيانات ولا ترحيل.
- الإذن في الذاكرة: هب بعدة نسخ خلف موزّع حمل لن يتعرّف على إذن نسخة أخرى (المركز اليوم نسخة واحدة).
- الرجوع: revert لهذا الجزء.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-28` (#209). بعد تأكيد المالك: ربما مفاتيح مرسل الإشعارات، ومدة الإذن ومدة الإظهار.
