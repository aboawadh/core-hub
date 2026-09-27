# «برق» يعمل على مزوّدي الدخول عبر Hermes
المسؤول: twuijri · الفرع: feat/direct-signed-in-providers · الحالة: in-progress

## المشكلة والهدف
شخص مزوّده الوحيد Nous Portal مسجَّل الدخول عبر Hermes يحصل من الوكيل المضمَّن `direct` («برق») على:
«No model provider is configured for this profile, so the agent has nothing to run on … Nous Portal is signed in through
Hermes; only the Hermes agent can use it». السبب: القرار §55 جعل رمز الدخول ملكًا لـ Hermes وحده، فكان مسار `direct`
يرفض كل مزوّد `auth.kind = oauth` بالاسم.

قرار المالك (٢٠٢٦-٠٩-٢٧): «المفروض برق يستخدم اي موديل بـ API أو بدخول». الهدف: أي نموذج في المنتقي يعمل مع `direct`،
مفتاحًا كان مزوّده أو دخولًا عبر Hermes (اشتراك ChatGPT/Codex `openai-codex`، Nous Portal `nous`، xAI `xai-oauth`، MiniMax
`minimax-oauth`)، دون أن يحفظ المركز الرمز.

## القرار والموافقات
موافقة المالك على الهدف: ٢٠٢٦-٠٩-٢٧. التفاصيل أدناه **مقترحة — للمالك أن يؤكدها** (DECISIONS §118).

**التصميم (مكتوب قبل التنفيذ):**
- **الرمز يُستعار عند كل دور ولا يُحفظ.** في بداية كل دور لمزوّد دخول، يشغّل المركز Python الخاص بـ Hermes (قائمة وسائط، لا
  نص برنامج مركّب) بـ `HERMES_HOME` البروفايل الذي سُجِّل الدخول فيه — الجذر لمزوّد مشترك أو لمزوّد البروفايل الافتراضي،
  و`profiles/<slug>` لمزوّد بروفايل آخر — كما تفعل قائمة النماذج الحية (§83) و`image_api.py` (§84). البرنامج يسأل محلِّل
  Hermes نفسه (`hermes_cli.auth.resolve_*_runtime_credentials`، الذي يجدّد الرمز إن قارب الانتهاء) ويطبع سطر JSON واحدًا
  فيه ما يحتاجه المركز فقط: بروتوكول السلك، العنوان الأساسي، اسم النموذج على السلك، الترويسات (ومنها `Authorization`)،
  وحقل التفكير. يبقى الناتج في ذاكرة ذلك الدور فقط ثم يُترك؛ لا يُكتب في قاعدة بيانات ولا ملف ولا سجل ولا رسالة خطأ
  (تُستبدل أي ظهور للرمز في نص خطأ المزوّد بـ `[redacted]`، ولا يُمرَّر stderr ولا stdout عند الفشل).
- **401 مرة واحدة:** إن ردّ المزوّد 401 قبل أن يصل أي نص، يُسأل Hermes مرة ثانية بتجديد إجباري (`force_refresh`)
  ويُعاد الطلب مرة واحدة فقط.
- **السلك لكل مزوّد، كما يستدعيه Hermes v2026.9.14 (MIT، قرأناه ووصفناه بكلماتنا):**
  - `openai-codex` → واجهة Codex الخلفية `POST {base}/responses` (`https://chatgpt.com/backend-api/codex`)، بثّ SSE،
    `store: false`، `instructions` (توجيه النظام أو جملة محايدة)، ترويسات هوية Hermes (`agent.codex_headers`) ومعرّف
    الحساب `ChatGPT-Account-ID` من الرمز، `reasoning: {effort, summary: auto}` بالجهد مقصوصًا إلى مفردات النموذج بدالة
    Hermes نفسها، ولا `max_output_tokens` (الخلفية لا تقبله). اسم النموذج يُجرَّد من لاحقة `-900k` التي يخترعها Hermes.
  - `xai-oauth` → Responses API على `https://api.x.ai/v1/responses`، بثّ، `store: false`، والجهد يُرسل فقط لنموذج Grok يقبله
    (`grok_supports_reasoning_effort`) مقصوصًا إلى مفرداته.
  - `nous` → OpenAI-compatible `chat/completions` (بروتوكول Hermes الافتراضي لـ Nous `nous_api_mode`)، بـ Bearer، بمحوّل
    `openai` نفسه الذي يخدم مزوّدي المفاتيح. إن قال Hermes إن نموذجًا (`anthropic/*` مع `nous.anthropic_wire: native`) يسير
    على Messages، يُستعمل محوّل Anthropic بـ Bearer.
  - `minimax-oauth` → Anthropic Messages (`{base}/v1/messages`) بـ `Authorization: Bearer` لا `x-api-key`، كما يفعل Hermes.
- **السلوك كمزوّد المفتاح:** البثّ والتفكير والإلغاء (إشارة الإلغاء تغلق المقبس، والدور ينتهي «موقوفًا») والاستهلاك والتكلفة
  (من أسعار صف النموذج) وسلسلة البدائل (§54) تمرّ في الحلقة نفسها. لا أدوات على مسار `direct` أصلًا (backlog §2.16)، فلا شيء
  يتغيّر هناك.
- **الرفض الواضح:** مركز لا يدير Hermes، أو Python الخاص بـ Hermes غير موجود فيه → `agent_unavailable` بجملة تقول لماذا وماذا
  يفعل الشخص (وكيل Hermes، أو مزوّد بمفتاح)؛ وهو قابل للتخطي إلى البديل التالي في السلسلة. مزوّد لم يُكمل الدخول →
  `provider_not_configured` («سجّل الدخول من النماذج»). رفض Hermes إعطاء الرمز (خرج من الحساب) → `provider_unauthorized`
  بكلمات Hermes.
- **المنتقيات:** كانت تعرض نماذج مزوّدي الدخول أصلًا (الرفض كان وقت التشغيل)، فلا تغيير في الويب ولا العقد.

مرفوض: أن يحفظ المركز الرمز أو يخزّنه مؤقتًا بين الأدوار (يخالف ADR 0010 و§55)؛ أن يجري المركز التجديد بنفسه (منطق Hermes
لكل مزوّد ليس لنا أن ننسخه)؛ تمرير الرمز القديم في وسائط العملية (يظهر في `ps`)؛ أن يشغّل Python الطلب كله (البثّ والإلغاء
والاستهلاك أصعب، والسابقة §83/§84 تأخذ الرمز للحظة الاستخدام).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. لا عملية ولا حقل ولا رمز خطأ جديد؛ الرموز المستعملة موجودة. `SignInRuntime` داخلي في الخادم وأضيفت له دالة اختيارية.

## الملفات والتأثير
- `packages/server/src/modules/models/signed-in-chat.ts` (جديد): برنامج Hermes الذي يعطي الاعتماد، وقارئ ناتجه، ودورة الدور
  (اختيار السلك، إعادة واحدة بعد 401، تنقية الرمز من الأخطاء).
- `packages/server/src/modules/models/adapters/responses.ts` (جديد): بثّ OpenAI Responses (`/responses`) للدور المباشر.
- `packages/server/src/modules/models/sign-in.ts`: `SignInRuntime.credential` اختيارية، تنفّذها `hermesSignInRuntime`
  بـ Python الخاص بـ Hermes وبيت البروفايل.
- `packages/server/src/modules/models/service.ts`: `chatOnce` لم يعد يرفض مزوّد الدخول؛ يستعير الاعتماد في بيت البروفايل
  الصحيح ويمرّ بالحلقة نفسها. خيار `profileSlug` لمعرفة slug بروفايل المزوّد.
- `packages/server/src/modules/models/index.ts`: توصيل `profileSlug`.
- `packages/server/src/modules/agents/adapters/direct.ts`: تعليق الرأس فقط.
- اختبارات: `signed-in-chat.test.ts` (جديد).
- وثائق: `docs/contracts/DECISIONS.md` §118، `docs/STATUS.md`، `docs/domain/models.md`، `docs/domain/agents.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
(تُملأ بعد التشغيل.)

## المخاطر والرجوع
- كل دور لمزوّد دخول يشغّل Python الخاص بـ Hermes مرة (نحو ثانية أو أقل مع استيراد `hermes_cli`) قبل الطلب.
- أسلاك Codex وxAI مكتوبة من قراءة Hermes ومن سابقة `image_api.py` التي تعمل في الإنتاج، ومختبرة ضد خادم مزيّف بشكل الأحداث
  الحقيقي؛ لم تُجرَّب ضد حساب حقيقي في هذا الفرع.
- التجديد الإجباري لـ Nous لا يمرّر الرمز القديم (Hermes يفعل ذلك داخل عمليته ليتجنّب إبطال رمز شقيق)؛ الأثر المحتمل تدوير
  رمز يستعمله Hermes نفسه، فيعيد هو القراءة عند 401 التالي.
- الرجوع: revert للـ PR يعيد الرفض بالاسم كما كان؛ لا ترحيل بيانات ولا تغيير عقد.

## التسليم والخطوة التالية
(يُملأ عند فتح الـ PR.)
