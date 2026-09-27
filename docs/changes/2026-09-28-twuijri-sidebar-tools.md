# القائمة الجانبية: البحث بجانب زرّ الطيّ، ومجموعة «الأدوات»، و«سير العمل» صفحة مستقلة
المسؤول: twuijri · الفرع: night/ui-sidebar-secrets (يُدمج في night/2026-09-28، طلب الدمج #209) · الحالة: review

## المشكلة والهدف
تصميم المالك المعتمد 2026-09-28 للقائمة الجانبية في الويب وسطح المكتب (الهواتف لاحقًا في تغيير منفصل بعد أن
يراه المالك):
- أيقونة البحث بجانب زرّ طيّ القائمة في الأعلى، وإذا انطوت القائمة ينزل البحث تحت «محادثة جديدة» مباشرة.
- مدخل واحد قابل للطيّ «الأدوات» / "Tools" فيه بالترتيب: الوكلاء (أولًا، للمالك والمشرف كما هو اليوم — العضو لا
  يراه)، المهام، سير العمل، الجدولة.
- «سير العمل» كان تبويبًا داخل الجدولة؛ صار مدخلًا ووجهة مستقلة، مع بقاء عنوان التبويب القديم عاملًا بالتحويل.
- الضغط على «الأدوات» يطويها لتأخذ قائمة المحادثات المكان، والحالة محفوظة لكل جهاز، وإذا كانت مطويّة والصفحة
  الحالية داخلها تُميَّز «الأدوات».

## القرار والموافقات
- التصميم نفسه قرار المالك (2026-09-28). التفاصيل المقترحة — ينتظر تأكيد المالك — مسجّلة في DECISIONS §126:
  - «الأدوات» مفتوحة افتراضيًا، والحالة في `localStorage` (مع try/catch) لكل جهاز لا لكل حساب.
  - `workflows` في قائمة جديدة `railExtra` لا في `rail`، لأن اختبار تكافؤ أندرويد يقارن `rail` حرفيًا والهواتف لم
    ترسم الصفحة بعد؛ فلا يتغير على الهواتف شيء.
  - `brandRow` و`sidebarGroups` في `navigation.json` وصفٌ للعرض فوق الشريط، لا وجهات.
  - درج الويب على الشاشة الضيقة (الذي يرسم المكوّن نفسه) يأخذ الترتيب نفسه: البحث أيقونة في صفّ العلامة.
  - «الأدوات» مطويّة داخل صفحاتها تُعلَّم بـ`active` و`aria-current="true"`؛ مفتوحةً يُعلَّم صفّ الصفحة نفسه.
- لا إضافة عقد API. لا كسر (ADR 0027): `rail` كما هو، والعناوين القديمة تعمل.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء في `packages/contracts`. عقد التنقّل `docs/clients/navigation.json` زاد فقط: المصطلحان `workflows` و`tools`،
الوجهة `workflows` (`surfaces: web, desktop`)، والمفاتيح `railExtra` و`brandRow` و`sidebarGroups`، و`workflows` في
`profileScope.alwaysAll`، ومسار الويب `/workflows`.

## الملفات والتأثير
- `docs/clients/navigation.json`، `docs/clients/NAVIGATION.md`، `scripts/navigation-check.mjs` (يتحقق من القوائم الجديدة).
- `packages/web/src/navigation/manifest.ts` (`railIds`، `brandRowIds`، `sidebarGroups`، `pathIsUnder`)، `routes.tsx`.
- `packages/web/src/shell/Sidebar.tsx` (صفّ العلامة، المجموعة)، `shell/sidebarGroups.ts` (الحالة المحفوظة)، `styles/kit.css`.
- `packages/web/src/screens/WorkflowsScreen.tsx` (الصفحة الجديدة)، `schedules/SchedulesScreen.tsx` (بلا تبويبات؛
  `?section=workflows…` يحوّل إلى `/workflows` ببقية العنوان).
- `packages/web/src/ui/icons.tsx` + `lucide.generated.ts` (Lucide: `workflow`، و`wrench` للأدوات).
- `packages/web/src/i18n/{ar,en}.json` (`nav.workflows`، `nav.tools`؛ حُذف مفتاحان لم يعد لهما استعمال).
- الاختبارات: `tests/sidebar-tools.test.tsx` (جديد)، `tests/navigation.parity.test.tsx`، `tests/sidebar-rail.test.tsx`،
  `tests/agents-top-level.test.tsx`، `tests/workflow-editor.test.tsx`؛ رحلة Playwright
  `e2e/zzzzzzzzzzzzzzz-sidebar-tools.spec.ts` (جديدة)، وتحديث `e2e/zzz-agents-top-level.spec.ts` و
  `e2e/zzzzzz-workflow-editor.spec.ts`؛ لقطات الشريط المحدّثة.
- `apps/ios/CoreHub/i18n/{ar,en}.json` (المصطلحان فقط، لأن اختبار iOS يطلب كل مصطلح).
- `docs/contracts/DECISIONS.md` §126، `docs/STATUS.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ node scripts/navigation-check.mjs
nav:check  OK — 40 destinations, 2 pre-auth screens (login, setup), 46 terms, ar/en complete, routes for web, ios, android, desktop
$ node scripts/i18n-check.mjs | tail -1
i18n:check  OK
$ node packages/contracts/scripts/check-clients.mjs
check-clients  OK — 1061 client file(s) scanned, 257 contract path(s) known.
$ pnpm lint
All matched files use Prettier code style!
$ pnpm typecheck        (الجذر، كل الحزم)
exit 0
$ npx vitest run --maxWorkers=2 tests/sidebar-tools.test.tsx tests/sidebar-rail.test.tsx tests/agents-top-level.test.tsx tests/navigation.parity.test.tsx
      Tests  33 passed (33)
$ npx vitest run --maxWorkers=2 tests/workflow-editor.test.tsx
      Tests  12 passed (12)
$ npx vitest run --maxWorkers=2 tests/desktop-surface.test.tsx tests/lucide-icons.test.tsx tests/ui-layer.test.ts tests/schedule-runs.test.tsx tests/schedule-extras.test.tsx tests/pending-actions.test.tsx tests/subagents-background.test.tsx
      Tests  70 passed (70)
$ pnpm build && PLAYWRIGHT_CHANNEL=chrome npx playwright test --workers=1 e2e/zzzzzzzzzzzzzzz-sidebar-tools.spec.ts e2e/zzz-agents-top-level.spec.ts e2e/zzzz-sidebar-rail.spec.ts e2e/zzzzzz-workflow-editor.spec.ts
  ✓  1 … 27. Agents above Tasks: a card chip opens the agent with its own side list, and back (1.8s)
  ✓  2 … 27b. a member sees no Agents entry, and the Agents pages send them home (1.4s)
  ✓  3 … the sidebar folds into a rail, remembers it, and stands on the reading side (5.2s)
  ✓  4 … 32. a two-step workflow drawn on the canvas runs, and its run is read on the canvas (4.9s)
  ✓  5 … 32b. a new workflow is checked before it has a name, then named, saved and run by hand (5.6s)
  ✓  6 … Tools folds away, Search sits by the toggle, and Workflows has its own page (1.5s)
  6 passed (30.7s)
```
اختبارات تكافؤ iOS وأندرويد لم تُشغَّل محليًا: `rail` لم يتغير، والوجهة الجديدة خارج سطحيهما، والمصطلحات الجديدة
تُولَّد لأندرويد وقت البناء. أول تشغيل على CI في #209 أسقط اختبار iOS `testEveryNavigationTermIsTheManifestsWord`
(يطلب كل مصطلح في `navigation.json` في ملفي لغة التطبيق)؛ أُضيف `nav.tools` و`nav.workflows` إلى
`apps/ios/CoreHub/i18n/{ar,en}.json` — نصوص فقط، لا يتغير في التطبيق شيء.

## المخاطر والرجوع
- من يعتمد على تبويب «سير العمل» في الجدولة يجده في «الأدوات»، والرابط القديم يحوّل. روابط `?workflow_run=` على
  الجدولة (الإشعارات والإجراءات المعلّقة) باقية كما هي.
- الرجوع: revert لهذا الجزء؛ لا بيانات ولا ترحيل.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-28` (#209). بعد أن يرى المالك التصميم: تغيير منفصل للهواتف يتبنّى `brandRow` و`sidebarGroups`
ويضيف `workflows` إلى أسطحها.
