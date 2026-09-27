# مشغّلات Webhook واردة لسير العمل (ClickUp أولًا) وشرط بعدة قواعد
المسؤول: twuijri · الفرع: feat/workflow-webhook-trigger (مدموج في night/2026-09-28، PR #209) · الحالة: review

## المشكلة والهدف
هدف المالك (2026-09-28، عام لكل مستخدم): أنظمة خارجية — ClickUp أولًا — ترسل أحداثها إلى
**سير عمل** في Core Hub، وسير العمل **يصفّيها** (أغلبها لا يهم) فلا يصل إلى خطوة الوكيل إلا ما
يطابق. لم يكن في المركز باب لذلك: `workflows.trigger_kind='event'` موجود ولا يُستعمل، وشرط سير
العمل مقارنة واحدة فقط، ولا ربط بين التشغيل والحدث الذي بدأه.

## القرار والموافقات
DECISIONS §123 (مقترح — بانتظار تأكيد المالك):
- **المشغّلات**: لكل سير عمل مشغّل أو أكثر، لكل منها عنوان عام ثابت
  `POST /api/v1/workflow-hooks/{id}` (`security: []`). إعداد مسبق للتحقق: `clickup`
  (`X-Signature` = HMAC-SHA256 سداسي للجسم الخام) و`github` (`X-Hub-Signature-256`)
  و`generic_hmac` (ترويسة وترميز hex/base64 وبادئة) و`token`. الجسم يُقرأ خامًا (حد 1 MiB)
  والتوقيع يُفحص على البايتات كما وصلت بمقارنة ثابتة الزمن، ثم فقط يُقرأ JSON.
- **السر**: يُلصق بعد إنشاء المشغّل (ClickUp يولّده عند تسجيل الـWebhook بالعنوان)، يُشفَّر بحلقة
  مفاتيح المركز، لا يُعاد أبدًا (`secret_stored`، الويب يعرض `[stored]`)، ويُستبدل.
- **الاستقبال**: التوقيع (401) ← منع التكرار بمفتاح ثابت يُحفظ 7 أيام (ClickUp: `webhook_id` +
  معرّفات `history_items` مرتبة؛ وإلا ترويسة معرّف التسليم؛ وإلا SHA-256 للجسم) ← مرشّح أحداث
  المشغّل ← يُصفّ التشغيل ويُرد `202` فورًا والخطوات بعده.
- **سجل التسليمات** بالحالات المطلوبة، و«أرسل حدثًا تجريبيًا» يمر بالمسار كله دون الاتصال بأحد.
- **شرط بعدة قواعد** `rules: {match: all|any, items}` إضافي؛ القديم كما هو؛ «لا» بلا ما بعدها
  = تشغيل ناجح «مُصفّى» بلا تنبيه. تطبيق قديم يحفظ عقدة شرط بلا الحقل ← المركز يُبقي قواعدها.
- **الربط**: `event_id` و`task_id` و`workflow_trigger_id` و`delivery_id` و`filtered` حقول
  اختيارية جديدة في `WorkflowRun`، و`listWorkflowRuns` يقبل `event_id` و`task_id`.
- قرار مقترح: من يملك إنشاء سير عمل في البروفايل يملك إضافة مشغّل له (كسير العمل نفسه، بلا
  `x-roles`). التشغيل يعمل باسم منشئ المشغّل.
- لم يُستعمل `trigger_kind='event'`/`event_key` (اسمهما لأحداث المركز الداخلية، والمشغّلات
  متعددة). لا قيم جديدة في `RunTrigger.kind` (تطبيق قديم قد لا يعرفها).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافات فقط (`pnpm contracts:compat` ناجح مقابل v1.1.4):
- 7 عمليات: `schedules.listWorkflowTriggers` و`createWorkflowTrigger` و`updateWorkflowTrigger`
  و`deleteWorkflowTrigger` و`listWorkflowTriggerDeliveries` و`testWorkflowTrigger`
  و`receiveWorkflowTrigger`.
- مخططات: `WorkflowTrigger*`، `WorkflowTriggerDelivery*`، `WorkflowTriggerReceipt`،
  `WorkflowRules`، `WorkflowRule` (`operator` نص عادي حتى تحمله كل العملاء المولَّدة).
- `WorkflowNode.rules` اختياري، وحقول `WorkflowRun` الخمسة اختيارية (تطبيق جديد مع مركز قديم
  يفك الترميز دون أخطاء)، ومعاملا استعلام اختياريان في `listWorkflowRuns`.
- ترحيل 0033 إضافي: جداول `workflow_triggers` و`workflow_trigger_deliveries`
  و`workflow_trigger_seen` وثلاثة أعمدة في `workflow_runs` (`migrations:guard` ناجح).

## الملفات والتأثير
- الخادم: `modules/schedules/triggers.ts` (التحقق، استخراج الحدث، منع التكرار، العينات)،
  `trigger-desk.ts` (الجداول ومسار الاستقبال والسجل)، `index.ts` (المسارات والباب العام في نطاق
  بمحلل جسم خام)، `workflow-engine.ts` (القواعد، `filtered`، بيانات الحدث)، `expr.ts`
  (`evaluateRules`)، `service.ts` (الفحص، إبقاء القواعد، مرشّحات التشغيلات)، `schema.ts`، ترحيل
  0033، `modules/index.ts` (إعارة حلقة المفاتيح).
- الويب: `WorkflowTriggers.tsx` (لوحة المشغّلات)، `StepPanel.tsx` (محرّر القواعد)،
  `WorkflowEditor.tsx` (اللوحة، البحث في التشغيلات بمعرّف المهمة/الحدث، شارات)،
  `WorkflowCanvas.tsx`، `model.ts`، `queries.ts`، `i18n/{ar,en}.json`.
- iOS/Android: عرض المشغّلات للقراءة مع نسخ العنوان، وعرض القواعد للقراءة وإبقاؤها عند الحفظ،
  والنصوص عربي/إنجليزي. تحرير القواعد والمشغّلات من الهاتف: عمل لاحق.
- المستندات: `docs/guides/clickup-webhook-trigger.md` (إنجليزي: تسجيل Webhook في ClickUp عبر
  `POST /team/{team_id}/webhook`)، DECISIONS §123، STATUS.
- الاختبارات: `workflow-triggers.test.ts` (11)، اختبار عقد، اختبارات ويب، رحلة Playwright 33،
  واختبارات iOS/Android.

## الفحوص (الأوامر ونواتجها الفعلية)
بعد دمج `origin/night/2026-09-28`:
```
pnpm typecheck                           exit 0
pnpm lint                                All matched files use Prettier code style!
pnpm contracts:lint                      contracts:lint  OK
pnpm contracts:compat                    contracts:compat  OK — no breaking change against v1.1.4
pnpm contracts:check-clients             check-clients  OK — 1059 client file(s) scanned, 262 contract path(s) known.
pnpm i18n:check                          i18n:check  OK
pnpm nav:check                           nav:check  OK — 39 destinations, ...
node scripts/migrations-guard.mjs        migrations:guard  OK — no breaking change against v1.1.4
vitest --project unit src/modules/schedules/ tests/unit/status.test.ts
      Tests  118 passed | 3 skipped (121)
vitest --project contract tests/contract/
      Tests  419 passed (419)
vitest (web) workflow-editor.test.tsx workflow-editor-model.test.ts auth-client.test.ts
      Tests  32 passed (32)
PLAYWRIGHT_CHANNEL=chrome playwright test e2e/zzzzzz-workflow-editor.spec.ts e2e/zzzzzz-workflow-triggers.spec.ts --workers=1
  ✓  32. a two-step workflow drawn on the canvas runs, and its run is read on the canvas
  ✓  32b. a new workflow is checked before it has a name, then named, saved and run by hand
  ✓  33. a ClickUp trigger starts the workflow; a test event, a filtered event and a repeat are logged
  3 passed (20.6s)
```
iOS وAndroid لا يُبنيان على هذا الجهاز (لا Swift ولا Java): يتحقق منهما CI على PR #209.

## المخاطر والرجوع
- العنوان عام: الحماية هي السر والتوقيع؛ كل رفض يُسجَّل بلا جسم، والسجل محدود (500 سطر/7 أيام).
- تسمية الحقل `operator` في Swift/Kotlin: الهاتف يقرأ القواعد عبر JSON النموذج نفسه فلا يعتمد
  على اسم الخاصية المولَّد.
- الرجوع: إرجاع الدمج؛ الترحيل إضافي (جداول وأعمدة جديدة لا يقرأها الإصدار السابق).

## التسليم والخطوة التالية
مدموج في `night/2026-09-28` (PR #209) بانتظار CI ومراجعة المالك. التالي في الليلة نفسها: عقدة
«أرسل رسالة» (§124)، ثم المرحلة والتنبيه واختبار الخطوة والدليل (قطعة التجميع).
