# قاعدة «لا تغيير يكسر ما يعمل» — قرار مكتوب وحارسان في CI
المسؤول: twuijri · الفرع: chore/no-breaking-changes · الحالة: review

## المشكلة والهدف
صار Core Hub يعمل عند أكثر من شخص. قبل ذلك كان المالك المستخدم الوحيد ويتحمّل أن يكسر التحديث
تثبيته. قرار المالك في 2026-09-27: «حط قرار صارم» — لا تغيير يوقف مركزًا أو تطبيقًا أو سكربتًا أو
بيانات موجودة عند الناس. المطلوب: القرار مكتوبًا (ADR)، والقاعدة في وثائق العمل، وحارس في CI يرفض
الكسر في العقد والترحيلات آليًا.

القاعدة كانت مكتوبة سطرًا في `docs/contracts/README.md` («`/api/v1` لا يُغيَّر بما يكسره») لكن لا شيء
يفحصها. مقارنة العقد بالوسوم أثبتت أن كسورًا مرّت فعلًا: عمليات webhooks صارت تطلب `X-Hub-Profile` بين
v1.1.2 وv1.1.3، وحُذفت حقول من presets وrelay وpeers بين v1.1.1 وv1.1.3.

## القرار والموافقات
- **ADR 0027** (مقبول من المالك 2026-09-27): تعريف «الكسر» للعقد (العمليات والمسارات والحقول والقيم
  والأحداث، الحقول التي تصير إلزامية، تضييق ما يُقبل، توسيع ما يُرد، تغيّر المعنى والقيم الافتراضية)،
  وللبيانات (ترحيلات للأمام فقط وغير متلفة، الترقية باستبدال الصورة، أسماء البيئة القديمة تبقى
  مقروءة)، وللتطبيقات (الأحدث مع مركز أقدم يكتشف ويخفي الميزة، والأقدم مع مركز أحدث يعمل)، ولأسماء
  ملفات الإصدارات، ولمخطط كتالوج النماذج المشترك. وما يُفعل إن تعذّر تجنّب الكسر: الجديد بجانب القديم،
  مهلة إيقاف، وموافقة المالك أولًا.
- **مقترح — للمالك أن يؤكد**: مدة مهلة الإيقاف «إصداران و90 يومًا على الأقل».
- **مقترح — للمالك أن يؤكد**: المرجع هو آخر وسم `v*` (بترتيب semver، ومنه الإصدارات التجريبية)،
  لا `main`، لأن الناس يشغّلون الإصدارات؛ ولو قورن بـ`main` لمرّ كسرٌ دُمج مرةً في كل طلب بعده.
- **مقترح — للمالك أن يؤكد**: منفذ الاستثناء ملف واحد `docs/contracts/breaking-approved.json` للعقد
  والترحيلات معًا (لا علامة داخل العقد، لأن المحذوف لا يمكن وسمه). كل مدخل: `id` كما يطبعه الحارس،
  `base` (الوسم الذي يُقاس عليه؛ فالموافقة لإصدار واحد وتُعلَن «قديمة» بعد الوسم التالي)، `decision`
  (`ADR NNNN` موجود أو `DECISIONS §N` موجود)، `approved_by: "twuijri"`، `reason`. الحارس يرفض المدخل
  إن نقص منه شيء أو لم يكن القرار موجودًا أو لم يكن الموافق هو المالك. الملف فارغ اليوم.
- لم يُمسّ كود الـprofiles ولا نقل البروفايلات (يعمل عليه وكيل آخر).

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء في `openapi.yaml` ولا في مخططات الأحداث. أُضيف في الحزمة سكربت الحارس
`packages/contracts/scripts/compat.mjs` واختباره وملفّا fixture تحت `packages/contracts/tests/`.

## الملفات والتأثير
- `docs/adr/0027-compatibility-no-breaking-changes.md` (جديد)، ورابط في `docs/adr/README.md`.
- `packages/contracts/scripts/compat.mjs` (جديد): يقارن `openapi.yaml` (العمليات، المعاملات، الأجسام،
  الردود، webhooks، `security`، `x-roles`، `x-scope`) ومخططات `events/**` بالوسم، مع اتجاه: ما يرسله
  العميل لا يضيق، وما يستقبله لا يَعِد بأقل. يتتبّع `$ref` و`allOf` و`oneOf`/`anyOf` والقيم الفارغة
  (`null`)، ويبلّغ عن المكوّن المشترك مرة واحدة باسمه.
- `scripts/migrations-guard.mjs` (جديد): ترحيل صدر عُدِّل أو حُذف؛ ترحيل جديد يحذف أو يعيد تسمية جدول
  أو عمود صدر، أو يفرغ جدولًا، أو ينسخ جدولًا (`__new_x` في SQLite) ناقصًا عمودًا — بمقارنة أعمدة
  آخر snapshot في الإصدار. نسخة الجدول الكاملة والجداول المؤقتة `__*` وما أُنشئ بعد الإصدار تمر.
- `scripts/compat-base.mjs` (جديد): اختيار الوسم، `git show`، قراءة الموافقات والتحقق منها، الطباعة.
- `docs/contracts/breaking-approved.json` (جديد، فارغ).
- الاختبارات: `packages/contracts/tests/compat.test.ts` مع `tests/fixtures/compat/openapi.yaml`
  و`note.created.schema.json` (30 نوع كسر، 17 إضافة تمر، 7 للأحداث، ومستودع git مؤقت بوسوم
  للموافقات)؛ `scripts/migrations-guard.test.mjs` (يُشغَّل ضمن `pnpm scripts:test` في CI).
- `package.json`: `contracts:compat` و`migrations:guard`.
- `.github/workflows/ci.yml`: ثلاث خطوات في job `checks` (جلب وسوم `v*`، الحارسان) — لا job جديد ولا
  تغيير في أسماء الفحوص المطلوبة.
- القواعد: `AGENTS.md` (Hard rules)، `CONTRIBUTING.md`، `docs/TEAM-RULES.md` (§٧ جديد وسؤال في §٥)،
  `.github/pull_request_template.md` (بند Compatibility)، `docs/contracts/README.md`.
- `docs/harness/validation.md` و`docs/harness/README.md` (قسم «Breaking changes»)، `docs/STATUS.md`
  (قسم Compatibility).

## الفحوص (الأوامر ونواتجها الفعلية)
الحارسان على `main` الحالي (الوسم الأحدث v1.1.3):
```
$ pnpm contracts:compat
contracts:compat  compared packages/contracts/openapi.yaml and 95 event schemas with v1.1.3
contracts:compat  OK — no breaking change against v1.1.3
$ pnpm migrations:guard
migrations:guard  33 migration(s) in v1.1.3, 0 new
migrations:guard  OK — no breaking change against v1.1.3
```
وعلى التاريخ الحقيقي (دليل أنه يلتقط كسورًا فعلية):
```
$ node packages/contracts/scripts/compat.mjs --base v1.1.2
contracts:compat  FAILED: 14 breaking change(s) against v1.1.2 (ADR 0027)
  break     contract operation-profile-required GET /notify/webhooks
  break     contract parameter-required GET /notify/webhooks header.X-Hub-Profile
  … (the seven webhook operations, DECISIONS §115)
$ node packages/contracts/scripts/compat.mjs --base v1.1.1   (مقتطف)
  break     contract property-removed AgentPreset.trust
  break     contract response-type-widened AgentPreset.content
  break     contract enum-value-removed Relay.route="official"
  break     contract property-removed Peer.online
  break     contract response-status-removed POST /peers 202
$ node scripts/migrations-guard.mjs --base v0.1.0-alpha.16
  break     migration drops-table 0022_device_requests.sql device_commands
  break     migration drops-table 0024_drop_performance_snapshots.sql performance_snapshots
```
الاختبارات والفحوص المحلية: تُلصق نواتجها أدناه.

## المخاطر والرجوع
- إيجابيات كاذبة: الحارس محافظ (مثلًا أي `pattern` جديد في طلب، أو طلب صار يرفض الحقول غير المعلنة).
  جُرِّب على كل الفروق بين الوسوم ولم يظهر إلا ما هو كسر فعلي بالتعريف. إن ظهر كسر كاذب يُصلح الحارس
  نفسه، لا يُضاف له استثناء.
- ما لا يراه الحارس (المعنى، أسماء البيئة، أسماء ملفات الإصدار، أوامر الـsocket، الكتالوج) يبقى على
  المراجعة وبند القالب.
- استنساخ بلا وسوم لا يشغّل الحارس: الخطوة في CI تجلبها، ورسالة الخطأ تقول الأمر.
- الرجوع: حذف الخطوات الثلاث من `ci.yml` يوقف الحارسين دون أثر على أي شيء آخر.

## التسليم والخطوة التالية
طلب دمج واحد إلى `main` بالإنجليزية. بعد الدمج: كل طلب دمج يُقارن بآخر إصدار. للمالك: تأكيد مدة مهلة
الإيقاف، ومرجع المقارنة (آخر وسم)، وشكل ملف الموافقات.
