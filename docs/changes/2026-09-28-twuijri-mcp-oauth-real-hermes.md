# إصلاح ربط ClickUp عبر OAuth مع هرمز الحقيقي، وإضافة خادم بعنوانه وتسجيل الدخول
المسؤول: twuijri · الفرع: fix/mcp-oauth-real-hermes (يُدمج في batch/2026-09-28، PR #215) · الحالة: review

## المشكلة والهدف
بلاغ مختبر المالك على صورة التست (v1.1.4-preview.25، هرمز v2026.9.14): أضاف ClickUp
`{"url": "https://mcp.clickup.com/mcp", "connect_timeout": 600, "skip_preflight": true}`، ضغط «ربط عبر OAuth»،
أكمل موافقة ClickUp، فعادت الصفحة «وصل تسجيل الدخول»، لكن الخادم بقي «غير متصل» والاختبار أعاد
`401 Unauthorized` مع «هذا الخادم يحتاج إلى تسجيل دخول».

طلب المالك بعد ذلك: «إضافة خادم» تعرض طريقتين — JSON كما هو، و«تسجيل دخول (OAuth)» بحقلي العنوان والاسم فقط، يكتب
المركز الكتلة ويبدأ تسجيل الدخول فورًا ثم يصبح «متصل» ويُختبر تلقائيًا.

## القرار والموافقات
**السبب الجذري (مُعاد إنتاجه بهرمز الحقيقي، لا بالمحاكي):** ClickUp يعلن في بياناته
`authorization_response_iss_parameter_supported: true` ويرسل `iss` مع العودة (RFC 9207). مسار العودة في لوحة هرمز
v2026.9.14 (`GET /api/mcp/oauth/callback/{server}`) يأخذ `code` و`state` و`error` فقط ويُسقط `iss`، فيرفض
MCP SDK 2.0 تسجيل الدخول بعد وصول الرمز: «Authorization response missing iss parameter advertised by the
authorization server». وصفحة المركز كانت تقول «وصل» بمجرد قبول هرمز للرمز، قبل التبادل. هرمز v2026.9.21 (0.21.4)
يمرّر `iss`، لكن نقل الصورة إليه أفشل اختبارات هرمز الحقيقية الأخرى (مزوّد مشترك في بروفايل مسمّى، بوابتان متجاورتان،
أمر TUI) وهي تنجح على v2026.9.14 — ليس نطاق هذا الإصلاح.

**الإصلاح (DECISIONS §122 معدّل، مقترح — للمالك أن يؤكد):**
- تسجيل الدخول صار بأمر هرمز نفسه `hermes mcp login <server>` (مسار CLI)، ومستمعه على `127.0.0.1:<port>/callback`
  يحتفظ بـ`iss` في كل إصدار. يكتب المركز `auth: oauth` و`oauth.redirect_uri` (عنوان عودته العام كما قبل) و
  `oauth.redirect_port` (منفذ حر على مضيفه)، ويشغّل الأمر في بيت البروفايل مع `SSH_CLIENT` (كي لا يفتح هرمز متصفحًا على
  جهاز المركز)، ويعطي الشخص الرابط الذي يطبعه هرمز، ثم يسلّم استعلام المزوّد كما هو إلى المستمع. هرمز يتبادل الرمز
  ويحفظ الرموز في `mcp-tokens/` بيت البروفايل ويقول «Authenticated — N tool(s)». الصورة تبقى على v2026.9.14.
- **كيف يُحدَّد عنوان العودة:** كما في #207 — `hub_url` من العميل (الويب يرسل `window.location.origin` فيشمل النفق
  والوكيل)، وإلا `protocol://host` للطلب؛ وما كتبه الشخص بنفسه يُترك. هرمز يسجّل عميلًا جديدًا (DCR) بهذا العنوان في كل
  تسجيل دخول.
- صفحة العودة لا تقول «متصل» إلا بعد انتهاء هرمز بنجاح **ووجود ملف الرمز** في بيت البروفايل الصحيح؛ تنتظر حتى 45 ثانية،
  وإلا «ما زال يكتمل»، أو الفشل بكلمات هرمز، أو «رُفض». لا «وصل» أبدًا بمجرد وصول الرمز.
- تسجيل دخول جديد لنفس الخادم ينهي السابق؛ إغلاق المركز ينهي ما ينتظر.
- الويب: يتابع التدفق والتبويب في الخلفية، يصير «متصل» تلقائيًا، يشغّل الاختبار وحده ويعرض عدد الأدوات، ويعرض «إعادة
  الربط» بجانب «فصل» عند الاتصال (والجوالان: «إعادة الربط» عند الاتصال).
- «إضافة خادم» ← «تسجيل دخول (OAuth)»: عنوان + اسم يُستنتج من المضيف (`mcp.clickup.com` ← `clickup`، والتالي الحر إن
  أُخذ، وقابل للتعديل)؛ الكتلة الدنيا `url` + `auth: oauth` فقط. **`connect_timeout` و`skip_preflight` غير لازمين**:
  أمر الدخول ينتظر 315 ثانية بنفسه، والفحص المسبق يُتخطى لخوادم OAuth (تحقق بهرمز الحقيقي: البروفايل الثاني في
  الاختبار يستعمل الكتلة الدنيا). `https` فقط، و`http` لهذا الجهاز.
- CI: وظيفة جديدة «MCP OAuth against the real Hermes» تثبّت وسم الصورة كما تثبّته الصورة وتشغّل التسجيل كاملًا مقابل
  خادم OAuth 2.1 + MCP محلي يرسل `iss`، وهي ضمن البوابة المطلوبة.

**مرفوض:** نقل الصورة إلى v2026.9.21 داخل هذا الإصلاح؛ ترقيع كود هرمز في الصورة؛ تعطيل فحص `iss`.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
أوصاف فقط، لا شكل (`contracts:compat` نظيف): `agents.startMcpOAuth` (ما يكتبه المركز، `mcp_oauth_stdio`، إنهاء
السابق)، `agents.getMcpOAuthFlow` (متى `approved`/`expired`)، `agents.cancelMcpOAuthFlow`، `agents.mcpOAuthCallback`
(يمرّر `iss`، ولا نجاح إلا بعد هرمز)، ووصف `McpOAuthFlow.error`. لم يعد `startMcpOAuth` يعيد `hermes_refused`/`503`
(الأمر يُشغَّل مباشرة؛ فشله يظهر `failed` بكلماته) — الردود الموثقة باقية.

## الملفات والتأثير
- الخادم: `modules/agents/mcp-oauth.ts` (تشغيل الأمر وقراءة مخرجاته، الحالة من العملية + ملف الرمز، التسليم إلى
  المستمع، الانتظار، صفحة العودة)، `mcp-oauth-routes.ts`، `mcp.ts` (`prepareOAuthLogin` بدل `setOAuthRedirect`)،
  `index.ts` (`mcpLogin` في السياق مع بديل اختبار)، `testing/fake-mcp-login.ts` (جديد)، `i18n/{ar,en}.json`.
- الاختبارات: `mcp-oauth.test.ts`، `mcp-oauth.routes.test.ts` (يشمل: `iss` يصل كما هو، الفشل بسبب هرمز، الرفض،
  الإلغاء والاستبدال، لا هرمز، عدم التسريب)، `mcp.test.ts`، **`mcp-oauth.real.test.ts` (جديد، هرمز الحقيقي)**،
  `tests/fixtures/fake-oauth-mcp.ts` (خادم OAuth 2.1 + MCP حقيقي: RFC 9728/8414، DCR، PKCE S256، `iss`، bearer).
- الويب: `McpSignInForm.tsx` (جديد)، `AgentMcpScreen.tsx`، `McpOAuthControls.tsx`، `skills.ts`، `i18n/{ar,en}.json`،
  `tests/mcp-oauth.test.tsx`، `e2e/zzzzzzzzzzzzzz-mcp-oauth.spec.ts` (رحلتان)، `e2e/hub.ts`، لقطتان.
- الجوالان: `McpOAuthRow.swift` و`McpOAuthRow.kt` (إعادة الربط عند الاتصال).
- `.github/workflows/ci.yml` (وظيفة `hermes-real` ضمن `gate`)، `docs/contracts/DECISIONS.md` (§122 معدّل).
- لا ترحيلات ولا متغيرات بيئة جديدة؛ `oauth.redirect_port` مفتاح هرمز نفسه في كتلة الخادم.

## الفحوص (الأوامر ونواتجها الفعلية)
إعادة الإنتاج بهرمز الحقيقي v2026.9.14 قبل الإصلاح (مسار لوحة هرمز، خادم يرسل `iss`):
```
DEBUG flow failed Authorization response missing iss parameter advertised by the authorization server
 events … register:http://hub.test/api/v1/mcp-oauth/callback/clickup | authorize   (لا token)
 page <main data-outcome="received">
```
بعد الإصلاح، `mcp-oauth.real.test.ts` (Connect → العودة → تبادل الرمز → الرمز في بيت البروفايل الصحيح → Test ينجح
بثلاث أدوات؛ البروفايل الافتراضي بكتلة المختبر، و`work` بالكتلة الدنيا؛ ولا رمز في أي رد أو سطر سجل):
```
COREHUB_HERMES_BIN=<venv v2026.9.14>/bin/hermes   → Test Files 1 passed (1) · Tests 3 passed (3)
COREHUB_HERMES_BIN=<venv v2026.9.21>/bin/hermes   → Test Files 1 passed (1) · Tests 3 passed (3)
COREHUB_HERMES_IMAGE=<صورة بهرمز 0.21.3/v2026.9.14> → Test Files 1 passed (1) · Tests 3 passed (3)
```
مقارنة هرمز v2026.9.21 بصورة مبنية منه (سبب عدم نقل الصورة): فشل على 9.21 فقط `provider-scopes` (2)،
`gateways` (2)، `hermes-tui-commands` (1)، `hermes-journey` و`hermes-profiles` (مشكلات تجهيز: ملف هوية البروفايل
وتحذير PID 1)؛ `memory.real` يفشل على الإصدارين. ملاحظة: نفاد مساحة /tmp أثناء جزء من التشغيل قد يفسّر أخطاء
`write` في اختبارات واتساب.

محليًا:
```
server: vitest mcp-oauth.test.ts mcp-oauth.routes.test.ts mcp.test.ts → Test Files 3 passed · Tests 42 passed
web: vitest mcp-oauth.test.tsx hub-tools-card.test.tsx i18n.test.ts → Test Files 3 passed · Tests 10 passed
playwright zz-agent-tools + zzzzzzzzzzzzzz-mcp-oauth (--workers=1) → 3 passed (21.7s)
pnpm lint → All matched files use Prettier code style!   pnpm typecheck → exit 0
contracts:lint OK · contracts:compat OK — no breaking change against v1.1.4 · check-clients OK · i18n:check OK · nav:check OK
```
CI: يُضاف بعد تشغيل PR #215.

## المخاطر والرجوع
- لم يُجرَّب مع ClickUp الحقيقي (يحتاج حساب المالك)؛ جُرّب مع خادم يطابق بيانات ClickUp المنشورة (`iss`، DCR، PKCE،
  `resource`).
- المستمع على `127.0.0.1` في مضيف المركز: يعمل حين يشغّل المركز هرمز (الصورة، سطح المكتب)؛ هرمز خارجي غير مدعوم كما قبل.
- أمر الدخول ينتظر 5 دقائق؛ بعدها `expired`.
- الرجوع: revert. المفاتيح المكتوبة (`auth: oauth`، `redirect_uri`، `redirect_port`) خاصة بتسجيل الدخول ولا يضر بقاؤها.

## التسليم والخطوة التالية
- دمج في `batch/2026-09-28` (PR #215) بطلب المالك؛ لا PR منفصل.
- المالك يعيد تجربة ClickUp على صورة التست: «خادم جديد» ← «تسجيل دخول (OAuth)» ← `https://mcp.clickup.com/mcp`.
- متابعة: نقل الصورة إلى هرمز أحدث بعد معالجة الإخفاقات أعلاه؛ «إضافة بتسجيل الدخول» على iOS/Android (الآن: الحالة
  وربط/إعادة ربط فقط).
