# تجميع مسار ClickUp ← مرشّح ← وكيل ← رسالة: المرحلة، تنبيه الفشل، اختبار الخطوة، الدليل
المسؤول: twuijri · الفرع: feat/workflow-assembly (مدموج في night/2026-09-28، PR #209) · الحالة: review

## المشكلة والهدف
بعد المشغّلات (§123) وخطوة «أرسل رسالة» (§124) طلب المالك قطعة التجميع: مرحلة للتشغيل يفهمها
الشخص (استُلم، يحلّل، ينتظر ردك، وُوفق عليه، ينفّذ، اكتمل، فشل) من تقدّم المشغّل والخطوات،
وتنبيه على مستوى سير العمل عند الفشل (الوارد وقناة اختيارية)، و«اختبر هذه الخطوة» بمدخل
تجريبي، ودليل إنجليزي لبناء المسار كاملًا مع أدوات ClickUp للقراءة فقط عبر قائمة أدوات الخادم.
عام لكل مستخدم.

## القرار والموافقات
DECISIONS §127 (مقترح — بانتظار تأكيد المالك):
- `WorkflowRun.phase` نص اختياري يُحسب عند القراءة من الحالة والخطوات، بلا عمود مخزّن.
- `on_failure` في سير العمل (`WorkflowFailureAlert`: `inbox` و`send` اختياري بوجهات «أرسل
  رسالة»)، يُحفظ في التعريف كالحدود؛ غيابه عند الحفظ يُبقيه و`null` يزيله؛ يُرسل مرة واحدة لكل
  تشغيل. بلا تنبيه افتراضي (سير العمل القديم لا يتغير سلوكه).
- `schedules.testWorkflowStep`: خطوة وحدها بعينة؛ لا حفظ ولا تشغيل؛ الوكيل لا يعمل فعليًا إلا
  مع `execute: true`؛ خطوة الإرسال تُعرض ولا تُرسل.
- الدليل: `docs/guides/clickup-agent-flow.md`.
- إصلاح على الفرع الليلي: `COREHUB_TELEGRAM_API_BASE` صار يُقرأ في `config.ts` (الملف الوحيد
  الذي يقرأ البيئة؛ اختبار `config.test.ts` كشف قراءتي له في `modules/index.ts`).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافات فقط (`contracts:compat` ناجح مقابل v1.1.4): عملية `schedules.testWorkflowStep`، مخططات
`WorkflowFailureAlert` و`WorkflowStepTest` و`WorkflowStepTestResult`، وحقول اختيارية
`Workflow.on_failure` و`WorkflowWrite.on_failure` و`WorkflowCheck.on_failure`
و`WorkflowRun.phase`. لا ترحيل.

## الملفات والتأثير
- الخادم: `schedules/index.ts` (`phaseOf`، `on_failure` في الاستجابة، مسار الاختبار)،
  `workflow-engine.ts` (`alertFailure`، `testStep`)، `service.ts` (`failureAlertOf`، الحفظ
  والفحص)، `schema.ts`، `app/config.ts` و`tests/unit/config.test.ts` (المتغيّر الجديد)،
  `modules/index.ts`.
- الويب: `SendForm.tsx` (`SendTargets` مشترك، `FailureAlertForm`)، `StepTest.tsx`، `StepPanel.tsx`،
  `WorkflowEditor.tsx` (لوحة التنبيه، شارة المرحلة)، `model.ts`، `queries.ts`، `i18n/{ar,en}.json`.
- iOS/Android: شارة المرحلة والمهمة في عرض التشغيل، ونصوصها.
- المستندات: DECISIONS §127، STATUS (العدد 369)، الدليل.

## الفحوص (الأوامر ونواتجها الفعلية)
بعد دمج `origin/night/2026-09-28`:
```
pnpm typecheck                     typecheck 0
pnpm lint                          All matched files use Prettier code style!
pnpm contracts:lint / compat       contracts:lint  OK / contracts:compat  OK — no breaking change against v1.1.4
pnpm contracts:check-clients       check-clients  OK — 1070 client file(s) scanned, 267 contract path(s) known.
pnpm i18n:check / nav:check        i18n:check  OK / nav:check  OK — 41 destinations, ...
pnpm change-record:check           change-record  OK — 6 record(s) valid
node scripts/migrations-guard.mjs  migrations:guard  OK — no breaking change against v1.1.4
vitest --project unit src/modules/schedules/ tests/unit/status.test.ts tests/unit/config.test.ts
      Tests  140 passed | 3 skipped (143)
vitest --project contract tests/contract/
      Tests  428 passed (428)
vitest (web) workflow-editor.test.tsx workflow-editor-model.test.ts auth-client.test.ts
      Tests  36 passed (36)
PLAYWRIGHT_CHANNEL=chrome playwright test (workflow-editor, workflow-triggers, workflow-send) --workers=1
  4 passed (30.6s)
```
قبل الدمج:
```
vitest --project unit src/modules/schedules/          Tests  128 passed | 3 skipped (131)
vitest --project contract tests/contract/             Tests  428 passed (428)
vitest --project unit tests/unit/config.test.ts ...   Tests  18 passed (18)
vitest (web) workflow-editor.test.tsx workflow-editor-model.test.ts   Tests  31 passed (31)
pnpm contracts:compat                                 contracts:compat  OK — no breaking change against v1.1.4
pnpm i18n:check                                       i18n:check  OK
```

## المخاطر والرجوع
- المرحلة تُحسب من الخطوات؛ تشغيل قديم بخطوات ناقصة يُعرض بأقرب مرحلة (لا يُخزَّن شيء).
- الرجوع: إرجاع الدمج؛ لا ترحيل.

## التسليم والخطوة التالية
مدموج في `night/2026-09-28` (PR #209) بانتظار CI ومراجعة المالك. لاحقًا: تحرير القواعد
والمشغّلات والوجهات من الجوال، وإدراج أسرار المشغّلات في صفحة «الأسرار».
