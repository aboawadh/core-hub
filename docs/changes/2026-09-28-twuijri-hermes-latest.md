# نقل كور هب إلى أحدث هرمز (v2026.9.24) وإصلاح ما انكسر، واختباره على الحد الأدنى والأحدث معًا
المسؤول: twuijri · الفرع: batch/2026-09-28b · الحالة: review

## المشكلة والهدف
طلب المالك (2026-09-28): نقل كور هب إلى أحدث إصدار من هرمز وإصلاح كل ما ينكسر ليُصدر 1.1.5 عليه،
وألا نتأخر عنه بعد اليوم. الصورة كانت على v2026.9.14 (0.21.3)؛ أحدث إصدار مستقر عند التنفيذ
**v2026.9.24 (0.21.5)** (`gh release list -R NousResearch/hermes-agent`؛ لا إصدار أحدث، وفرع main
عندهم يتقدم بلا وسم). تطبيق سطح المكتب يثبّت أحدث هرمز بمثبّته الرسمي، فمستخدموه على 9.24 دون
اختبار. في CI كان يُشغَّل اختبار هرمز حقيقي واحد فقط (MCP OAuth) على إصدار واحد.

## القرار والموافقات
مقترح — للمالك أن يؤكد (DECISIONS §132). الحد الأدنى يبقى 0.21.3 (يمر عليه كل اختبار حقيقي).
- `HERMES_REF=v2026.9.24` في `packages/server/Dockerfile`، ومعه `HERMES_TESTED` و`HERMES_FLOOR` في
  ملف واحد `catalog/hermes-versions.ts` (يقرؤه `scripts/hermes-watch.mjs` وتتحقق منه اختباراته).
- ما انكسر على 0.21.4+ وأُصلح بطريقة تعمل على الإصدارين:
  1. **مفاتيح المزوّد المشترك في البروفايلات المسمّاة**: هرمز الجديد لا يقرأ مفاتيح بروفايل مسمّى
     إلا من `.env` البروفايل (نطاق الأسرار في `agent/secret_scope.py` لا يرجع لبيئة العملية). صار
     المركز يكتب في `.env` كل بروفايل كل مفتاح يستخدمه (الخاص والمشترك)، وقيمة فارغة لما لا يحق له.
  2. **Webhooks البروفايلات المسمّاة مع بوابة واحدة لكل جهاز** (§129): هرمز يخدمها من ملف الجذر
     (`profile: <name>` و`/p/<name>/webhooks/<route>`). المركز ينسخ مسارات كل بروفايل مسمّى إلى ملف
     الجذر (نسخ معلَّمة، لا يمس مسارات الجذر)، ويشغّل مستمع الجذر، ويوجّه التسليم إليه. ملف
     البروفايل يبقى السجل، فمسارات مجلد قديم تعمل بعد الترقية.
  3. **سبب رفض هرمز**: يتجاهل سطر تحذيره `[hermes] WARNING` (تحذير PID 1 يطبعه 9.24).
  4. **اختبارات هرمز الحقيقية** صارت تعرف الشكلين (بوابة لكل بروفايل / بوابة واحدة)، وأُصلح
     تجهيزان: بروفايل Journey بلا ملف هوية (9.24 لا يعدّه بروفايلًا)، ومزوّد اختبار الذاكرة كان
     باسم `corehub-…` يحذفه المركز عند إنشاء البروفايل (كان يفشل على الإصدارين).
- CI: وظيفة `hermes-real` صارت مصفوفة (floor, pinned) تبني الصورة بكل إصدار وتشغّل **كل**
  `*.real.test.ts` (ومعها نسخة مصدر وvenv لاختباري kanban وcron)، ومطلوبة عبر gate.
- هرمز الشخص نفسه (إضافة المالك): المركز يعرف أحدث إصدار مستقر لهرمز من GitHub (كل ست ساعات
  وعند «التحقق من التحديثات»، لهرمز يحدّثه مُحدِّثه فقط، لا لهرمز الصورة، ولا تحديث تلقائي).
  البطاقة تقول: يوجد تحديث وهل اختُبر كور هب عليه، وإن كان هرمز أحدث من المختبر «قد لا يكون
  مدعومًا بعد». زر التحديث يسأل أولًا في صفحة الإعدادات أيضًا.
- مراقب الإصدارات اليومي وسطر الإصدار بُنيا بجانب هذا (سجل `2026-09-28-twuijri-hermes-watch.md`).
- معروف وغير قابل للإصلاح من المركز (مسجّل في §132): على 0.21.4+ بروفايل مسمّى **له قائمة سماح**
  يتجاهل الغرباء بدل إرسال رمز اقتران (هرمز يقرر من إعدادات البروفايل الافتراضي). وهرمز 9.24
  يحاول تثبيت boto3 عند بدء البوابة (يُحفظ في `/data/hermes-packages` إن وُجدت شبكة).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
إضافي فقط: `AgentInstall.tested_version` (اختياري)، ووصف `latest_version` و`newer_than_tested`
يشمل هرمز الشخص. `pnpm contracts:compat` → لا كسر مقابل v1.1.4.

## الملفات والتأثير
- `packages/server/Dockerfile`، `catalog/hermes-versions.ts` (جديد)، `catalog/hermes.ts`، `catalog/types.ts`
- `modules/models/service.ts` (مفاتيح `.env` للبروفايلات)
- `modules/agents/hermes-webhooks.ts`، `webhook-routes.ts`، `hermes-gateways.ts`، `hermes-runtime.ts`، `index.ts`
- `modules/agents/hermes-profiles.ts` (سبب الرفض)
- `modules/agents/update-policy.ts`، `service.ts`، `serialize.ts` (إصدارات GitHub، `tested_version`)
- `packages/contracts/openapi.yaml`
- الويب: `agents/AgentManagerScreen.tsx`، `AgentSettingsScreen.tsx`، `versionNotes.ts`، `i18n/ar.json`، `i18n/en.json`
- الاختبارات: `tests/unit/hermes-real.ts` (جديد)، والاختبارات الحقيقية للبوابات وتيليجرام والقنوات
  والذاكرة وJourney، `hermes-releases.routes.test.ts` (جديد)، `hermes-webhooks.test.ts`،
  اختبارات models، `web/tests/agent-versions.test.tsx`
- `.github/workflows/ci.yml` (مصفوفة `hermes-real`)، `scripts/hermes-watch.mjs` و`.test.mjs`
- `docs/contracts/DECISIONS.md` §132، `docs/STATUS.md`

## الفحوص (الأوامر ونواتجها الفعلية)
صورتان بُنيتا محليًا من هذا الفرع (`docker build --build-arg HERMES_REF=…`): `Hermes Agent v0.21.5
(2026.9.24)` و`Hermes Agent v0.21.3 (2026.9.14)`.

قبل الإصلاح، كل الاختبارات الحقيقية على 9.24:
```
Test Files  7 failed | 22 passed | 2 skipped (31)
Tests  11 failed | 66 passed | 9 skipped (86)
(provider-scopes 2، gateways 2، telegram 2، channels 1، memory 2، journey 1، profiles 1)
```
بعده، كل ملفات `*.real.test.ts` الـ31 (ومعها HERMES_SRC لاختباري kanban وcron):
```
COREHUB_HERMES_IMAGE=core-hub:hermes-latest HERMES_SRC=<src v2026.9.24> vitest run --maxWorkers=2 <31 files>
 Test Files  31 passed (31)
      Tests  85 passed | 2 skipped (87)
COREHUB_HERMES_IMAGE=core-hub:hermes-floor HERMES_SRC=<src v2026.9.14> vitest run --maxWorkers=2 <31 files>
 Test Files  31 passed (31)
      Tests  85 passed | 2 skipped (87)
```
(المتخطّيان: فرع الشكل الآخر في `gateways.real.test.ts` على كل إصدار.)

ترقية مجلد بيانات من صورة 9.14 (مبنية من origin/main، 1.1.4) إلى صورة هذا الفرع (9.24) باستبدال
الصورة فقط، على نفس الـvolume، مع نموذج وهمي يرد بالمفتاح الذي وصله:
```
قبل (القديمة): chat default → pong key=key-shared-111 · chat sales → pong key=key-sales-own · webhook door → 202
بعد (الجديدة):
health: {"ok":true,"server_version":"1.1.4",...}
hermes: available 0.21.5 · gateways: default running [webhook], sales running [webhook] (نفس العملية)
profiles: default,sales
providers sales: litellm/profile/[stored] lmstudio/all/[stored]
history default/sales: الرسائل السابقة موجودة
memory sales: The release word is lantern-4417.
mcp sales: echoer
webhooks sales: listener enabled, deploys
schedules: Brief default/true,Brief sales/true
chat default → pong key=key-shared-111 · chat sales → pong key=key-sales-own
door 202 {"status":"accepted","route":"sales--deploys",...}   ← عبر مستمع الجذر
schedule run now (Brief sales) → succeeded، ومخرج هرمز: pong key=key-shared-111
```
اختبارات الوحدة:
```
vitest src/modules/models + hermes-profiles.test.ts → Test Files 19 passed | 1 skipped · Tests 250 passed | 3 skipped
vitest hermes-webhooks.test.ts hermes-gateways.test.ts hermes-runtime.test.ts → Test Files 3 passed · Tests 59 passed
vitest hermes-releases.routes.test.ts hermes-self-update.routes.test.ts → Test Files 2 passed · Tests 10 passed
web vitest tests/agent-versions.test.tsx → Test Files 1 passed · Tests 9 passed
node --test scripts/hermes-watch.test.mjs → pass 17, fail 0
pnpm lint → All matched files use Prettier code style!   pnpm typecheck → exit 0
contracts:lint OK · contracts:compat OK — no breaking change against v1.1.4 · check-clients OK · i18n:check OK
```
CI: يُشغَّل عند فتح الطلب (#218).

## المخاطر والرجوع
- كتابة المفاتيح المشتركة في `.env` كل بروفايل تكرّر المفتاح على القرص (نفس الـvolume ونفس
  المستخدم، الملف 0600)؛ تغيير المفتاح يصل كل الملفات عند الحفظ.
- نسخ مسارات webhooks إلى ملف الجذر يعني أن صفحة قنوات البروفايل الافتراضي تُظهر المستمع مفعّلًا.
- ما لا تغطيه الاختبارات الحقيقية قد يختلف في 9.24 (نافذة ~1800 طلب دمج عند هرمز).
- الرجوع: `node scripts/hermes-watch.mjs bump --ref v2026.9.14 --version 0.21.3` وبناء الصورة؛
  كل الإصلاحات تعمل على 9.14 كما هي، ولا ترحيل بيانات يلزم التراجع عنه.

## التسليم والخطوة التالية
دُفع إلى `batch/2026-09-28b` (الطلب #218). بعد CI الأخضر: مراجعة المالك ودمجه، ثم إصدار 1.1.5.
متابعة مقترحة: إنشاء بروفايل في أول ثوانٍ لمجلد جديد قد يُرفض مرة («exit 1» قبل أن يوجد بيت هرمز)
ويعمل عند الإعادة — موجود قبل هذا التغيير.
