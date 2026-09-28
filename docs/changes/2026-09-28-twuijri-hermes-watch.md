# مراقب إصدارات هرمز: لا نتأخر عن هرمز، ولا نُصدر ما ينكسر
المسؤول: twuijri · الفرع: ci/hermes-watch (مدموج في batch/2026-09-28b، الطلب #218) · الحالة: review

## المشكلة والهدف
طلب المالك (2026-09-28): ألا نتأخر أبدًا عن إصدارات هرمز، وألا نُصدر أبدًا ما ينكسر. الصورة تثبّت
إصدارًا واحدًا من هرمز (`HERMES_REF` في `packages/server/Dockerfile`، ويساويه `HERMES_TESTED` في
`hermes-versions.ts`)، ولم يكن هناك ما يقول لنا إن إصدارًا أحدث صدر، ولا هل يعمل المركز معه، ولا
ما يذكّر بذلك عند إصدار صورة. الهدف: فحص يومي آلي يجرّب الإصدار الأحدث على كل اختبارات هرمز
الحقيقية ويفتح طلب دمج (أخضر) أو بلاغًا (أحمر)، وسطر واضح في كل إصدار صورة.

## القرار والموافقات
- `.github/workflows/hermes-watch.yml` (يومي 04:41 UTC + تشغيل يدوي بخيار `force`): يقرأ التثبيت،
  ويأخذ أحدث إصدار **مستقر** من NousResearch/hermes-agent (لا مسودة ولا ما قبل الإصدار). إن كان
  أحدث: يبني الصورة به ويشغّل كل `*.real.test.ts` عبر سير عمل قابل لإعادة الاستخدام
  `hermes-real-suites.yml` (نفس إعداد وظيفة `hermes-real` في ci.yml، والوسم مُدخل).
  - أخضر: طلب دمج واحد لكل إصدار من `bot/hermes-<tag>` ينقل التثبيتين ومعه سجل تغيير (أمر
    `record` الموجود)، ويشغّل الفحوص المطلوبة عليه. **لا دمج آلي أبدًا.**
  - أحمر (أو لم تُبنَ الصورة): بلاغ واحد «Hermes <tag> is not supported yet» يسرد الاختبارات
    الفاشلة ورابط التشغيل.
  - عدم التكرار: لا يُعاد تجريب إصدار له طلب مفتوح (إلا بـ `force`)، الفرع يُصنع مرة ولا يُعاد
    كتابته، بلاغ واحد فقط يُعدَّل ويُعاد تسميته لأحدث إصدار فاشل ويُغلق حين ينجح الأحدث أو يصبح
    هو التثبيت، طلب أغلقه المالك دون دمج لا يُعاد فتحه، وطلب إصدار أقدم يُغلق حين ينجح أحدث منه.
  - الصلاحيات: `GITHUB_TOKEN` فقط، `permissions: {}` على المستوى الأعلى وكل وظيفة بأقل ما تحتاجه
    (الفحص قراءة فقط؛ الكتابة في وظيفتي الإغلاق والتقرير فقط).
- `release.yml`: خطوة قبل البناء «The Hermes this image carries, and any newer one» تطبع
  `::notice` (الصورة على أحدث هرمز) أو `::warning` (يوجد أحدث: مدعوم وطلبه رقم كذا / غير مدعوم
  بعد وبلاغه رقم كذا / لم يُجرَّب بعد). `continue-on-error` ومهلة دقيقتين: لا تمنع الإصدار ولا
  تؤخره ولا تغيّره؛ أُضيفت صلاحيتا قراءة فقط (`pull-requests: read`, `issues: read`).
- `scripts/hermes-watch.mjs`: أمر جديد `status` ودوال مختبرة (`botBranch`, `issueTitle`,
  `issueRef`, `findBumpPr`, `findWatchIssue`, `hermesStatus`)، وأعلام بلا قيمة (`--annotate`).
  تصحيح نص الطلب الأخضر: سجل التغيير صار على الفرع (لم يعد «يُكتب لاحقًا»).
- مقترح — ينتظر تأكيد المالك: الموعد اليومي 04:41 UTC، وأن خطوة الإصدار تحذير لا بوابة (كما طُلب)،
  وإغلاق فروع `bot/hermes-*` المتجاوزة مع حذفها (كما يفعل بوت خريطة الكود).
- لم يُعدَّل ci.yml (ملك وكيل ترقية هرمز)؛ يمكن لاحقًا أن تستدعي وظيفة `hermes-real` فيه
  `hermes-real-suites.yml` نفسه بدل تكرار الخطوات. قسم DECISIONS §132 يكتبه وكيل الترقية.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `.github/workflows/hermes-watch.yml` (جديد)
- `.github/workflows/hermes-real-suites.yml` (جديد، `workflow_call`)
- `.github/workflows/release.yml` (خطوة معلومات + صلاحيتا قراءة)
- `scripts/hermes-watch.mjs`, `scripts/hermes-watch.test.mjs`
- `docs/RELEASING.md` (قسم «The Hermes watch»)

لا تغيير في الخادم ولا الصورة ولا الواجهات؛ لا كسر لأي تثبيت قائم.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ actionlint -version → 1.7.12 (مع shellcheck 0.10.0، مثبّتان في ~/.cache/corehub-agent/)
$ actionlint .github/workflows/hermes-watch.yml .github/workflows/hermes-real-suites.yml .github/workflows/release.yml
actionlint (with shellcheck 0.10.0): 0 problems
$ pnpm scripts:test
ℹ tests 119
ℹ pass 119
ℹ fail 0
$ node --test scripts/hermes-watch.test.mjs
ℹ tests 17
ℹ pass 17
ℹ fail 0
$ pnpm exec eslint scripts/hermes-watch.mjs scripts/hermes-watch.test.mjs   → بلا أخطاء
$ pnpm exec prettier --check <الملفات المعدلة>
All matched files use Prettier code style!
$ GITHUB_TOKEN=… node scripts/hermes-watch.mjs status --repo twuijri/core-hub --annotate
::notice title=Hermes::This image carries Hermes v2026.9.24 (0.21.5), the newest Hermes release.
(مع تثبيت مؤقت على v2026.9.14 ثم إرجاعه)
::warning title=Hermes::Hermes v2026.9.24 (0.21.5) is out. This image carries Hermes v2026.9.14 (0.21.3); the Hermes watch has not tried v2026.9.24 yet (run "Hermes watch" from the Actions tab).
$ node scripts/hermes-watch.mjs report --results /nonexistent …   → {"passed":false,"failed":["(no test report: …)"]}
$ gh api repos/twuijri/core-hub/actions/permissions/workflow
{"default_workflow_permissions":"read","can_approve_pull_request_reviews":true}
```
لم يُشغَّل سير عمل المراقب على GitHub بعد: لن يوجد على `main` إلا بعد دمج #218، وتشغيله الأول
يدويًا من Actions ← Hermes watch.

## المخاطر والرجوع
- أول تشغيل حقيقي لمسار الأخضر/الأحمر يحدث عند صدور هرمز أحدث من v2026.9.24؛ منطق الصدفة مختبر
  وحدويًا ومراجَع، لكن خطوات `gh` لم تُجرَّب على المستودع (لا إصدار أحدث الآن).
- تشغيل يومي يستهلك دقائق Actions فقط حين يوجد إصدار أحدث بلا طلب مفتوح (بناء صورة + الاختبارات).
- الرجوع: حذف `hermes-watch.yml` و`hermes-real-suites.yml` وخطوة release.yml؛ لا حالة تبقى سوى
  فرع/طلب/بلاغ البوت إن فُتحت.

## التسليم والخطوة التالية
مراجعة المالك ضمن #218. بعد الدمج: تشغيل «Hermes watch» يدويًا مرة للتأكد، ومتابعة أول طلب
`bot/hermes-*` أو بلاغ. لاحقًا (اختياري): جعل وظيفة `hermes-real` في ci.yml تستدعي
`hermes-real-suites.yml`.
