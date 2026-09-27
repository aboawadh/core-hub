# خطوة «أرسل رسالة» في سير العمل: تيليجرام ومحادثة كور هب
المسؤول: twuijri · الفرع: feat/workflow-send-message (مدموج في night/2026-09-28، PR #209) · الحالة: review

## المشكلة والهدف
طلب المالك (2026-09-28) خطوة في سير العمل ترسل رسالة إلى تيليجرام و/أو محادثة في كور هب (يختار
الشخص إحداهما أو كلتيهما): تيليجرام مباشرة عبر Bot API برمز `TELEGRAM_BOT_TOKEN` من `.env`
الخاص ببروفايل Hermes (بموافقة المالك)، ومعرّف محادثة (مثل مجموعة ‎-100…)، وقالب رسالة، وتقسيم
النص العربي الطويل تحت 4096 وحدة UTF-16 على حدود الأسطر والفقرات، ومخرَج
`{message_ids, message_id, delivered_to, status}`، وسبب فشل واضح دون ادعاء نجاح، وعدم تكرار
الإرسال عند الإعادة، وزر «أرسل رسالة تجريبية»، وإعادة إنشاء المحادثة المحذوفة بالعنوان نفسه
وتوجيه الخطوة إليها مع إشعار، وفشل الخطوة وإشعار إذا فشلت كل الوجهات، ومنصة قابلة للتوسعة
(واتساب لاحقًا).

## القرار والموافقات
DECISIONS §124 (مقترح — بانتظار تأكيد المالك):
- **بلا نوع عقدة جديد**: تحققت من العملاء المولَّدة — `WorkflowNode.kind` في Kotlin وSwift
  تعداد مغلق بلا قيمة احتياطية، فقيمة جديدة كانت ستُفشل تحميل قائمة سير العمل كلها في تطبيقات
  الجوال القديمة. لذلك الخطوة عقدة `notify` مع حقل اختياري جديد `send: {targets}`؛ التطبيق
  القديم يراها إشعارًا بالكلمات نفسها، والمركز يُبقي `send` إذا حفظ تطبيق قديم العقدة بدونه.
- `platform` نص عادي (`telegram`، `core_hub`) ليُضاف واتساب لاحقًا دون قيمة تعداد جديدة.
- تيليجرام: `sendMessage`، تقسيم على الفقرات ثم الأسطر ثم الكلمات، وقصّ إجباري لا يكسر زوجًا
  بديلًا؛ الرمز لا يظهر في سجل أو سبب؛ سبب الرفض هو `description` من تيليجرام نفسه.
- محادثة كور هب: تُنشر الرسالة بصفتها رسالة وكيل المحادثة ويُعلن `message.created`؛ إن حُذفت
  المحادثة تُنشأ جديدة بالعنوان والوكيل نفسيهما وتُوجَّه العقدة إليها (دون رفع إصدار الرسم)
  ويُكتب إشعار في صندوق صاحب التشغيل.
- عدم التكرار: جدول `workflow_sent_parts` (ترحيل 0034) بمفتاح التشغيل الأصلي (الإعادة من خطوة
  تُحسب على تشغيلها الأصلي) + العقدة + الوجهة + الجزء.
- `sent` لا يُقال إلا بمعرّف من المنصة؛ فشل كل الوجهات يُفشل الخطوة؛ أي فشل يُكتب في الصندوق.
- `schedules.testWorkflowSend` يرسل الآن ولا يُذكَر (الضغط مرتين يرسل مرتين).
- متغيّر اختياري `COREHUB_TELEGRAM_API_BASE` (افتراضي `https://api.telegram.org`) لهب الاختبار.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافات فقط (`contracts:compat` ناجح مقابل v1.1.4): عملية `schedules.testWorkflowSend`
(`POST /workflows/send-test`)، مخططات `WorkflowSend` و`WorkflowSendTarget` و`WorkflowSendTest`
و`WorkflowSendResult`، وحقل اختياري `WorkflowNode.send`. ترحيل 0034 إضافي.

## الملفات والتأثير
- الخادم: `modules/schedules/send.ts` (التقسيم، Bot API، الفحص)، `workflow-engine.ts`
  (`deliverSend` والخطوة)، `service.ts` (الأجزاء المرسلة، التشغيل الأصلي، إعادة التوجيه، الفحص،
  إبقاء `send`)، `index.ts` (مسار الاختبار)، `schema.ts`، ترحيل 0034،
  `modules/sessions/{service,index}.ts` (`postWorkflowMessage`)، `modules/agents/index.ts`
  (تصدير `telegramToken`)، `modules/index.ts` (منفذ الرسائل).
- الويب: `SendForm.tsx`، زر «أرسل رسالة» في اللوحة، تسمية الخطوة على اللوحة، `model.ts`،
  `queries.ts`، `i18n/{ar,en}.json`.
- iOS/Android: عرض الوجهات للقراءة وإبقاؤها عند الحفظ، ونصوص عربي/إنجليزي. تحريرها من الجوال
  لاحقًا.
- e2e: `hub.ts` (تيليجرام وهمي داخل الهب، و`COREHUB_PUSH_RELAY=off` لهب الرحلات — كانت رحلة
  إشعارات المتصفح تفشل على الفرع الليلي لأن الهب صار يستعمل المرحّل الحقيقي افتراضيًا)،
  رحلة 34.
- المستندات: DECISIONS §124، STATUS (العدد 368)، DEPLOY (المتغيّر الاختياري).

## الفحوص (الأوامر ونواتجها الفعلية)
بعد دمج `origin/night/2026-09-28`:
```
pnpm typecheck                     typecheck 0
pnpm lint                          All matched files use Prettier code style!
pnpm contracts:lint                contracts:lint  OK
pnpm contracts:compat              contracts:compat  OK — no breaking change against v1.1.4
pnpm contracts:check-clients       check-clients  OK — 1069 client file(s) scanned, 266 contract path(s) known.
pnpm i18n:check                    i18n:check  OK
pnpm nav:check                     nav:check  OK — 41 destinations, ...
node scripts/migrations-guard.mjs  migrations:guard  OK — no breaking change against v1.1.4
vitest --project unit src/modules/schedules/ tests/unit/status.test.ts
      Tests  125 passed | 3 skipped (128)
vitest --project contract tests/contract/
      Tests  426 passed (426)
vitest (web) workflow-editor.test.tsx workflow-editor-model.test.ts auth-client.test.ts
      Tests  35 passed (35)
PLAYWRIGHT_CHANNEL=chrome playwright test (browser-push, workflow-editor, workflow-triggers, workflow-send) --workers=1
  ✓  a browser turns notifications on, and a test notice is pushed to it
  ✓  32. a two-step workflow drawn on the canvas runs, and its run is read on the canvas
  ✓  32b. a new workflow is checked before it has a name, then named, saved and run by hand
  ✓  34. a Send message step sends to Telegram and posts in a conversation
  ✓  33. a ClickUp trigger starts the workflow; a test event, a filtered event and a repeat are logged
  5 passed (32.0s)
```
iOS وAndroid في CI فقط (لا Swift ولا Java هنا).

## المخاطر والرجوع
- إعادة التوجيه تكتب في رسم سير العمل دون رفع الإصدار: التغيير الوحيد هو معرّف المحادثة.
- لم يُجرَّب على بوت تيليجرام حقيقي؛ الاختبارات على Bot API وهمي بالشكل الموثّق.
- الرجوع: إرجاع الدمج؛ الترحيل إضافي.

## التسليم والخطوة التالية
مدموج في `night/2026-09-28` (PR #209) بانتظار CI ومراجعة المالك. التالي: قطعة التجميع
(المرحلة، تنبيه الفشل، اختبار الخطوة، الدليل).
