# ربط خادم MCP عبر OAuth من الواجهة (ClickUp وأمثاله)
المسؤول: twuijri · الفرع: feat/mcp-oauth-connect · الحالة: review

## المشكلة والهدف
معيار قبول المالك: أن يُربط خادم MCP الخاص بـ ClickUp — أو أي خادم MCP يسجّل الدخول بـ OAuth — من واجهة الويب
وحدها بلا طرفية، ثم ينجح زر «اختبار» ويعرض عدد الأدوات.

قبل هذا التغيير: صفحة MCP تكتب كتلة الخادم في `config.yaml` وتختبرها عبر هرمز، لكن لا طريق لتسجيل الدخول. هرمز
نفسه (v2026.9.14، مصدره MIT) يملك تدفق تسجيل الدخول كاملًا للوحة تحكمه (`POST /api/mcp/servers/{name}/auth`،
`GET/DELETE /api/mcp/oauth/flows/{id}`، `GET /api/mcp/oauth/callback/{server}`) ويحفظ الرموز في
`mcp-tokens/` داخل بيت البروفايل. العائق: عنوان العودة (redirect URI) الذي يسمّيه هرمز هو عنوانه هو
`127.0.0.1:<port>` داخل الحاوية، ولا يصل إليه متصفح الشخص. والاختبار دون رمز يرجع بجملة هرمز
«OAuth authentication required — no token found.».

## القرار والموافقات
DECISIONS §121 (مقترح — للمالك أن يؤكد):
- **هرمز يسجّل الدخول، والمركز ينقل فقط.** `agents.startMcpOAuth` يبدأ تدفق هرمز في البروفايل المختار ويرجع معرّف
  المركز (ULID) مع رابط المزوّد؛ `agents.getMcpOAuthFlow` للمتابعة (`pending` ثم `approved` مع الأدوات، أو
  `failed`/`cancelled`/`expired`)؛ `agents.cancelMcpOAuthFlow` للإيقاف. لا يمر أي رمز عبر المركز ولا يُرجَع ولا
  يُسجَّل.
- **كيف يُحدَّد عنوان العودة:** هرمز لا يقبل عنوان عودة لكل تدفق، لكنه يقرأ أولًا `oauth.redirect_uri` من كتلة
  الخادم. فيكتب المركز قبل البدء `<base>/api/v1/mcp-oauth/callback/<server>`، و`<base>` هو `hub_url` الذي يرسله
  العميل (الويب يرسل `window.location.origin` — أي العنوان الذي وصل به الشخص فعلًا، فيشمل النفق أو الوكيل
  العكسي)، وإلا عنوان الطلب نفسه `protocol://host` (يحترم `X-Forwarded-*` من الوكلاء الموثوقين
  `COREHUB_TRUST_PROXY`؛ تطبيقا الجوال يعتمدان عليه). `redirect_uri` كتبه الشخص بنفسه ولا يشبه مسار المركز يُترك
  ويُستعمل. كل تسجيل دخول من لوحة هرمز يسجّل عميلًا جديدًا (هرمز يحذف القديم أولًا)، فتغيير عنوان المركز يحتاج
  تسجيل دخول جديدًا فقط. كُتب في الملف (لا يوجد بديل في واجهة هرمز).
- **مسار العودة العام** `agents.mcpOAuthCallback` (`security: []`) ينقل الاستعلام كما هو إلى مسار هرمز الذي لا يقبله
  إلا بـ`state` تدفق بدأه، ويرد بصفحة قصيرة بلغة المتصفح بألوان المركز (فاتح/داكن) مع زر إغلاق. طلبه لا يُسجَّل
  (`logLevel: 'warn'` لهذا المسار: سطر Fastify يحمل العنوان، والعنوان يحمل رمز التفويض).
- **الحالة من البيانات الوصفية فقط:** `McpServer.oauth` (اختياري، لخوادم `http`/`sse`) = `connected` / `expired` /
  `not_connected` / `error` من وجود `mcp-tokens/<name>.json` و`expires_at` (أو وقت الملف + `expires_in`) ووجود
  refresh token — دون أي قيمة. `required` = `auth: oauth` في الكتلة.
- **الفصل** `agents.disconnectMcpOAuth` يحذف ملفات الخادم الأربعة في البروفايل (هرمز بلا واجهة لذلك). كل بروفايل
  يُربط وحده (`mcp-tokens` غير مشترك)، والواجهة تقول ذلك.
- **إخفاء أسرار المستوى الثاني:** قيمة سرية داخل `headers` (مثل `Authorization`) أو `oauth.client_secret` تُقرأ
  الآن `[stored]` كقيم `env` (قبلها كانت تعود كما كُتبت — تسريب قائم أُغلق هنا)، وحفظ `[stored]` يبقي المخزّن.
  `auth: oauth` يُقرأ كما هو (نمط تسجيل دخول، لا سر).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافة فقط (`pnpm contracts:compat` نظيف مقابل v1.1.4):
- عمليات: `agents.startMcpOAuth` (`POST /agents/{agent_id}/mcp-servers/{server_name}/oauth`، جسم اختياري
  `McpOAuthStart {hub_url?}`)، `agents.disconnectMcpOAuth` (`DELETE` على المسار نفسه)، `agents.getMcpOAuthFlow`
  و`agents.cancelMcpOAuthFlow` (`GET`/`DELETE …/oauth/{flow_id}`)، `agents.mcpOAuthCallback`
  (`GET /mcp-oauth/callback/{server_name}`، عام، `text/html`).
- مخططات: `McpOAuthState`، `McpOAuthStart`، `McpOAuthFlow`؛ حقل اختياري `McpServer.oauth`؛ معامل `McpOAuthFlowId`.

## الملفات والتأثير
- `packages/contracts/openapi.yaml` — ما سبق.
- `packages/server/src/modules/agents/mcp-oauth.ts` (جديد) — الحالة من الملف، الحذف، بناء عنوان العودة، ترجمة تدفق
  هرمز، سجل التدفقات في الذاكرة (15 دقيقة كهرمز)، نقل العودة وصفحتها.
- `packages/server/src/modules/agents/mcp-oauth-routes.ts` (جديد) — المسارات الخمسة.
- `packages/server/src/modules/agents/mcp.ts` — `oauth` في الشكل الداخلي، إخفاء المستوى الثاني، `setOAuthRedirect`
  (يعدّل عقدة واحدة ويحفظ التعليقات).
- `packages/server/src/modules/agents/index.ts` — `toMcpServer` يضيف `oauth`، وتسجيل المسارات.
- `packages/server/src/lib/route.ts` — خيار `logLevel` اختياري لمسار واحد.
- `packages/server/src/i18n/{ar,en}.json` — نصوص صفحة العودة.
- الويب: `agents/McpOAuthControls.tsx` (جديد: الشارة، ربط/إعادة ربط/فصل، فتح التبويب عند النقر نفسه ثم توجيهه، المتابعة
  كل 1.5 ث، «اختبر الآن»)، `AgentMcpScreen.tsx` (الشارة والأزرار، و«ربط عبر OAuth» تحت اختبار فشل لعدم وجود رمز)،
  `McpTestResultView.tsx` (خانة إجراء اختيارية)، `skills.ts` (الخطافات و`needsOAuth`)، `i18n/{ar,en}.json`.
- iOS: `Screens/Agent/McpOAuthRow.swift` (جديد) وربطه في `AgentMcpPage.swift`، `i18n/mcp_oauth.{ar,en}.json`،
  `CoreHubTests/McpOAuthRulesTests.swift`. Android: `ui/screens/agent/McpOAuthRow.kt` (جديد)، `AgentsTwoOps.kt`،
  `AgentMcpPage.kt`، `res/values{,-ar}/strings_mcp_oauth.xml`، `test/.../parity/McpOAuthTest.kt`. في الجوالين: الشارة،
  «ربط/إعادة ربط» يفتح المتصفح ويتابع كل ثانيتين، ورسالة النتيجة. **ليس فيهما بعد:** زر الفصل، و«ربط» تحت اختبار فاشل.
- الاختبارات: `mcp-oauth.test.ts`، `mcp-oauth.routes.test.ts` (هرمز مُمثَّل: بدء، متابعة، نقل العودة، إلغاء، فصل،
  حالة من ملفات، وبروفايل آخر ⇒ 404، واختبار أن **لا قيمة رمز** — access/refresh/client_secret/رمز التفويض — تظهر في
  أي رد أو أي سطر سجل)، إضافات `mcp.test.ts`، `web/tests/mcp-oauth.test.tsx`، رحلة Playwright
  `e2e/zzzzzzzzzzzzzz-mcp-oauth.spec.ts` مع مسارات هرمز المُمثَّلة في `e2e/hub.ts` («المزوّد» فيها يعيد المتصفح فورًا
  إلى عنوان العودة الذي كتبه المركز) ولقطتها `e2e/shots/agent-mcp-oauth-ar-light.png`.
- `docs/contracts/DECISIONS.md` §121، `docs/STATUS.md` (356 عملية، سطر الوكلاء).
- لا ترحيلات، لا متغيرات بيئة جديدة، لا تغيير على بيانات قائمة.

## الفحوص (الأوامر ونواتجها الفعلية)
اختبار عدم التسريب فشل فعلًا حين عُطّل `logLevel` (رمز التفويض ظهر في سطر الطلب)، ونجح بعد إعادته.
```
$ pnpm typecheck                     → exit 0
$ pnpm contracts:check-clients
check-clients  OK — 1057 client file(s) scanned, 257 contract path(s) known.
$ pnpm nav:check
nav:check  OK — 39 destinations, 2 pre-auth screens (login, setup), 44 terms, ar/en complete, routes for web, ios, android, desktop
$ pnpm contracts:lint
contracts:lint  OK   (تحذير واحد قائم في main، سطر ليس من هذا التغيير)
$ pnpm contracts:compat
contracts:compat  OK — no breaking change against v1.1.4
$ pnpm i18n:check
i18n:check  ios: 2615 keys, ar/en in parity
i18n:check  OK
$ pnpm lint
All matched files use Prettier code style!
$ vitest --project unit mcp-oauth.test.ts mcp-oauth.routes.test.ts mcp.test.ts agent-tools.routes.test.ts hub-tools/ tests/unit/status.test.ts
 Test Files  8 passed | 2 skipped (10)
      Tests  79 passed | 3 skipped (82)
$ vitest --project contract tests/contract/contract.test.ts -t "oauth|OAuth|mcp"
 Test Files  1 passed (1)
      Tests  12 passed | 345 skipped (357)
$ (web) vitest tests/mcp-oauth.test.tsx tests/hub-tools-card.test.tsx tests/i18n.test.ts
 Test Files  3 passed (3)
      Tests  9 passed (9)
$ PLAYWRIGHT_CHANNEL=chrome playwright test e2e/zzzzzzzzzzzzzz-mcp-oauth.spec.ts e2e/zz-agent-tools.spec.ts --workers=1
  ✓  1 … 23. the agent tools ask Hermes: an MCP test, a skill pack imported, WhatsApp linked by QR (8.3s)
  ✓  2 … MCP OAuth: a remote server is signed in from the web, tested, and disconnected (1.5s)
  2 passed (19.1s)
```
كود iOS وAndroid لم يُبنَ محليًا (لا Java ولا Xcode هنا؛ العملاء الأصليون يُولَّدون في CI) — تحقق منه CI.
نتيجة CI على PR #207 (الالتزام `f9845ac3`):
```
pass | Android build, unit tests, lint | 10m14s
pass | Build and test on the iOS simulator | 7m1s
pass | Generate the Swift client (CoreHubClient) | 37s
pass | Lint, typecheck, contracts, client tests, build | 6m53s
pass | Server unit tests (shard 1/3, 2/3, 3/3)
pass | Web smoke journeys (Playwright against the real hub) | 9m33s
pass | Desktop app smoke (Electron under Xvfb against the real hub) | 1m12s
pass | Docker image builds and answers /health | 2m40s
pass | db:generate + db:migrate (SQLite and PostgreSQL) | 1m15s
pass | PR adds or updates a change record · PR leaves graphify-out/ to the code-map bot
```

## المخاطر والرجوع
- **لم يُجرَّب مع مزوّد حقيقي** (ClickUp أو غيره): كل ما سبق مقابل هرمز مُمثَّل مبني على قراءة مصدره. مزوّد يرفض
  عنوان عودة `http` غير محلي (مركز على IP داخلي بلا https) سيرفض التسجيل — الحل الوصول إلى المركز بعنوان https (نفق
  أو وكيل). مزوّد لا يسمح بالتسجيل الديناميكي يظهر خطأ هرمز كما هو.
- تطبيق سطح المكتب يمنع فتح النوافذ من الصفحة (`setWindowOpenHandler` يرفض `about:blank`)، فلا يُفتح التبويب
  تلقائيًا هناك: يظهر رابط «افتح صفحة تسجيل الدخول» فيفتح المتصفح الخارجي، والعودة تمر بالعنوان المحلي للتطبيق
  (يعمل ما دام التطبيق مفتوحًا).
- الفصل يحذف الملفات فقط؛ هرمز الذي فتح الاتصال فعلًا يبقى عليه حتى إعادة تشغيل بوابته (مذكور في الواجهة).
- إخفاء `headers`/`oauth` تغيير سلوك على رد قائم لكنه مطابق لوصف العقد («secret-looking values read as
  `[stored]`»)، والحفظ يعيد القيمة المخزنة؛ لا عميل يفقد شيئًا.
- الرجوع: revert للـPR؛ ما كُتب في `config.yaml` (`oauth.redirect_uri`) مفتاح يقرؤه هرمز فقط لتسجيل الدخول ولا يضر
  بقاؤه.

## التسليم والخطوة التالية
- PR إلى `main` بالإنجليزية؛ المالك يراجع ويدمج.
- المالك يجرّب ClickUp فعليًا: إضافة خادم بعنوان MCP الذي تنشره ClickUp (`{"url": "<العنوان>"}`) ثم «ربط عبر OAuth» ثم «اختبار».
- متابعة للجوالين: زر الفصل، و«ربط» تحت اختبار فاشل.
