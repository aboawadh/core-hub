# فحص سير عمل جديد قبل تسميته
المسؤول: twuijri · الفرع: fix/workflow-editor-new-check · الحالة: review

## المشكلة والهدف
فتح سير عمل **جديد** في محرّر الويب يُظهر «تعذّر فحص الرسم: The request did not match the
expected shape» مع أن الخطوات مرسومة. السبب (تأكّد بالتجربة): المحرّر يبدأ من `emptyDraft()`
باسم فارغ، و`toWrite()` يرسل `name: ''` إلى `schedules.validateWorkflow`، وجسمه في العقد كان
`WorkflowWrite` حيث `name` له `minLength: 1`، فيرفضه Ajv بـ`400 validation_failed` قبل أن يبدأ
الفحص، مع أن الفحص (`definitionOf`) لا يقرأ الاسم أصلًا. الخطأ الفعلي من الخادم القديم:
`{"code":"validation_failed","details":{"fields":[{"source":"body","path":"name","message":"must NOT have fewer than 1 characters"}]}}`.
ومعه: سير العمل المحفوظ كان يُفحص رسمه الفارغ قبل أن يصل، و`describeError` يُسقط
`details.fields` فلا يعرف المستخدم أيّ حقل رُفض. رحلة e2e رقم 32 كانت تكتب الاسم أولًا فتخفي
الخطأ. وتطبيقا iPhone وAndroid يرسلان الاسم الفارغ نفسه في فحصهما.

الهدف: فحص الرسم يعمل بلا اسم على كل العملاء، دون أي كسر.

## القرار والموافقات
- جسم `validateWorkflow` صار مخطّطًا خاصًّا `WorkflowCheck` = حقول `WorkflowWrite` نفسها مع اسم
  حرّ (فارغ أو غائب). هذا توسيع لما يُقبل فقط؛ `createWorkflow`/`updateWorkflow` باقيان على
  `WorkflowWrite` ويشترطان الاسم (`400` للفارغ و`409 name_required` للفراغات). DECISIONS §121.
- الويب: لا يُفحص سير عمل محفوظ قبل تحميله؛ «سمِّ سير العمل قبل الحفظ» تلميح تحت حقل الاسم
  بدل رسالة عامة؛ و`details.fields` من أي `validation_failed` (فحص أو حفظ) يوضع عند حقله:
  `name` تحت حقل الاسم، و`nodes.<i>.<field>` / `edges.<i>` على الخطوة أو الوصلة (كمشكلة
  `field_invalid`)، وما لا مكان له يبقى في الرسالة العامة مع مساره.
- iPhone وAndroid: فحصهما يرسل `WorkflowCheck` **بلا اسم إطلاقًا**، فيعمل التطبيق الجديد مع
  مركز أقدم أيضًا (الاسم كان اختياريًا في `WorkflowWrite`). والتطبيقات المنشورة سابقًا تعمل مع
  المركز الجديد لأنه صار يقبل الاسم الفارغ في الفحص.
- لا كسر: `pnpm contracts:compat` ناجح مقابل v1.1.4.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
- مخطّط جديد `WorkflowCheck` في `openapi.yaml`، و`schedules.validateWorkflow` يأخذه جسمًا بدل
  `WorkflowWrite`. إضافة فقط (قبول أوسع للإدخال). العملاء المولَّدون: TS محليًّا، وSwift/Kotlin
  في CI (لا Java محليًّا). في Swift صار الوسيط `workflowCheck:` بدل `workflowWrite:`، وفي Kotlin
  نوع الوسيط `WorkflowCheck` — وعُدّلت مواضع الاستدعاء في التطبيقين.

## الملفات والتأثير
- `packages/contracts/openapi.yaml` — `WorkflowCheck` وجسم `validateWorkflow`.
- `packages/server/tests/contract/workflow-editor.contract.test.ts` — فحص باسم فارغ/فراغات يعيد
  نتيجة الرسم، وإنشاء باسم فارغ ما زال `400` (الحقل `name`) وبفراغات `409`.
- `packages/web/src/auth/client.ts` — `fieldErrorsOf()` يقرأ `details.fields` (إضافة؛
  `describeError` كما هو).
- `packages/web/src/schedules/workflows/model.ts` — `placeRefusedFields()`.
- `packages/web/src/schedules/workflows/WorkflowEditor.tsx` — انتظار التحميل، التلميح، الحقول
  المرفوضة في أماكنها، ومسح خطأ الحفظ عند أي تعديل.
- `packages/web/src/i18n/{ar,en}.json` — `check_fields`، `name_refused`، `fields_refused`،
  `issues.field_invalid`.
- `packages/web/tests/{auth-client,workflow-editor-model,workflow-editor}.test.*` — اختبارات
  وحدة للتحويل والواجهة.
- `packages/web/e2e/zzzzzz-workflow-editor.spec.ts` — رحلة 32b.
- `apps/ios/CoreHub/Screens/WorkflowEditRules.swift`، `WorkflowEditor.swift`،
  `apps/ios/CoreHubTests/WorkflowEditorTests.swift` — `check()` بلا اسم.
- `apps/android/.../ui/screens/WorkflowEditor.kt`، `.../parity/SelfSufficientTest.kt` —
  `toCheck()` بلا اسم.
- `docs/contracts/DECISIONS.md` §121، `docs/STATUS.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
اختبار العقد الجديد فشل على العقد القديم ونجح بعد التغيير:
```
# قبل (openapi.yaml من origin/main)
× validateWorkflow checks a drawing nobody has named yet; saving still needs a name
AssertionError: schedules.validateWorkflow: {"error":"The request did not match the expected shape.","code":"validation_failed","details":{"fields":[{"source":"body","path":"name","message":"must NOT have fewer than 1 characters"}]}}: expected 400 to be 200
# بعد
npx vitest run --project contract tests/contract/workflow-editor.contract.test.ts
 Test Files  1 passed (1)
      Tests  3 passed (3)
```
اختبارات الويب (الثلاثة الجديدة في الواجهة فشلت على الشيفرة القديمة: `3 failed | 7 passed`):
```
npx vitest run tests/workflow-editor.test.tsx tests/workflow-editor-model.test.ts tests/auth-client.test.ts --maxWorkers=2
 Test Files  3 passed (3)
      Tests  29 passed (29)
```
Playwright (بعد `pnpm build`):
```
PLAYWRIGHT_CHANNEL=chrome pnpm exec playwright test e2e/zzzzzz-workflow-editor.spec.ts --workers=1
  ✓  1 [chromium] › 32. a two-step workflow drawn on the canvas runs, and its run is read on the canvas (3.5s)
  ✓  2 [chromium] › 32b. a new workflow is checked before it has a name, then named, saved and run by hand (5.2s)
  2 passed (16.8s)
# رحلة 32b على الشيفرة القديمة:
    Expected: 200
    Received: 400
    > 195 |   expect(check.status()).toBe(200);
```
بقية الفحوص:
```
pnpm lint                     All matched files use Prettier code style!
pnpm typecheck                exit 0
pnpm contracts:lint           contracts:lint  OK
pnpm contracts:compat         contracts:compat  OK — no breaking change against v1.1.4
pnpm contracts:check-clients  check-clients  OK — 1050 client file(s) scanned, 254 contract path(s) known.
pnpm i18n:check               i18n:check  OK
pnpm nav:check                nav:check  OK — 39 destinations, ...
pnpm --filter @corehub/contracts test   Tests  118 passed (118)
```
iOS وAndroid: لا Swift ولا Java على هذا الجهاز، فبناؤهما واختباراتهما في CI فقط. نتيجة CI على
الـPR #205 (الالتزام a5e6e74، كل الفحوص):
```
Generate the Swift client (CoreHubClient)                   pass  38s
Build and test on the iOS simulator                         pass  8m37s
Android build, unit tests, lint                             pass  9m44s
Lint, typecheck, contracts, client tests, build             pass  5m27s
Server unit tests (shard 1/3, 2/3, 3/3)                     pass
Web smoke journeys (Playwright against the real hub)        pass  9m16s
Desktop app smoke (Electron under Xvfb against the real hub) pass  1m25s
Docker image builds and answers /health                     pass  3m8s
db:generate + db:migrate (SQLite and PostgreSQL)            pass  1m9s
PR adds or updates a change record                          pass
```

## المخاطر والرجوع
- المخاطر: توليد Swift/Kotlin يسمّي الوسيط والنوع كما توقّعنا (`workflowCheck:` /
  `WorkflowCheck`) — يتحقّق منه CI. أي عميل خارجي كان يرسل اسمًا فارغًا للفحص صار يحصل على
  نتيجة بدل `400`، وهذا المقصود.
- الرجوع: إرجاع هذا الـPR؛ لا بيانات ولا ترحيل.

## التسليم والخطوة التالية
PR إلى `main` بانتظار مراجعة المالك ونتيجة CI (ويب، خادم، iOS، Android). لا دمج.
