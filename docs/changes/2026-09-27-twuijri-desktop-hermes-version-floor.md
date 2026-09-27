# أقدم نسخة من هرمز يعمل معها المركز، وتحديث هرمز الشخص من بطاقته
المسؤول: twuijri · الفرع: fix/desktop-hermes-version-floor · الحالة: review

## المشكلة والهدف
متابعة لـ `docs/changes/2026-09-27-twuijri-desktop-local-existing-hermes.md` (PR #189، دُمج). سؤال المالك: «ماذا لو
كان هرمز مثبّتًا من قبل؟» — هرمز صديقه أقدم من كور هب، وقد يكون أقدم أو أحدث من النسخة التي تثبّتها الصورة
(v2026.9.14). المطلوب: ما النسخ التي يدعمها الوضع المحلي، وما يحدث مع نسخة أقدم، ورسالة واضحة غير مانعة على
بطاقة هرمز، وزر «تحديث هرمز» بضغطة واحدة (بأداة هرمز نفسها وبموافقة الشخص) حين تكون النسخة أقدم من الحد الأدنى،
مع تسجيل الحد الأدنى وسببه، وكل ذلك إضافة لا تكسر شيئًا.

### ما وُجد
- إصدارات هرمز: الوسم `v2026.9.14` = `0.21.3` (نسخة الصورة)، `v2026.9.21` = `0.21.4`، `v2026.9.24` = `0.21.5`؛
  ومثبّت هرمز الرسمي يثبّت فرع `main` لا وسمًا (ما جرّبناه: `0.21.5+3413.g4175585`، وفيه مدير الحزم `pm/` الذي
  عولج في PR #189).
- مقارنة استنساخين سطحيين (`v2026.8.13` و`v2026.9.14`) بما يستدعيه المركز من هرمز: `profile create --no-alias
  --clone-from`، `plugins … --no-enable`/`--no-allow-tool-override`، `serve`، `API_SERVER_KEY`، وطرق بوابة TUI
  `llm.oneshot` و`session.steer` و`session.compress` و`command.dispatch` — كلها موجودة في الاثنين. فلا كسر معروف
  قبل 0.21.3، لكنه غير مُثبت بالاختبار.
- `hermes update --yes` موجود في الاثنين: تحديث بلا أسئلة (يقبل ترحيل الإعدادات).

## القرار والموافقات
مقترح — للمالك أن يؤكد (DECISIONS §119):
- **الحد الأدنى `0.21.3`** (v2026.9.14): النسخة التي تثبّتها الصورة وتجري عليها كل اختبارات هرمز الحقيقية
  (`*.real.test.ts`)، ومنها قُرئت أوامر هرمز التي يستعملها المركز. حدّ محافظ لا كسر معروف.
- **الأقدم يعمل، والبطاقة تقول ذلك**: `AgentInstall.minimum_version` و`below_minimum` (اختياريان). لا شيء يُمنع.
- **التحديث من البطاقة** للمالك والمدير فقط، وبعد نافذة تأكيد تقول إنه يحدّث هرمز للحاسوب كله: `agents.upgrade`
  يشغّل `hermes update --yes` ببيئة الشخص (لا بـ`HERMES_HOME` المركز)، ثم يعيد تشغيل هرمز الذي يشغّله المركز ويفحصه
  من جديد. متاح فقط حين يكون هرمز للشخص (`AgentInstall.self_update`): بيته غير بيت المركز؛ لا في الصورة أبدًا.
  لا يحدث تلقائيًا.
- **الأحدث** لا يُعلَّم (ما يثبّته المثبّت اليوم أحدث وجُرّب حقيقيًا في PR #189).
- **بايثون هرمز أينما كان** (طلب المنسّق في نفس المهمة): المركز كان يطلب `python` بجانب برنامج `hermes` فقط، فتُرفض
  قوائم النماذج الحية وقائمة مزوّد مسجَّل الدخول وموافقات الكتابات المعلّقة في الوضع المحلي. الآن
  (`hermes-python.ts`) بالترتيب: `python` بجانب البرنامج (الصورة)؛ بجانب ما يشير إليه الرابط؛ بايثون الـvenv الذي
  يشغّله مشغّل المثبّت القديم (بشرط `pyvenv.cfg`)؛ وإلا ما يقوله هرمز نفسه لـ`hermes --print-runtime-command`
  (واجهته الموثّقة لمن يحمل المشغّل) مع تشغيل برنامجنا بدل وحدة هرمز بعد `hermes_bootstrap` الذي يختار الحزم.
  يُبحث عنه في الخلفية عند بدء المركز وبعد إعادة التشغيل (`runtime.pythonCommand()`)، ولا يُوقف شيئًا. شرط
  `mode === 'managed'` باقٍ: الوضع المحلي مع هرمز الشخص **هو** managed أصلًا (المركز يشغّل البوابة)؛ ويبقى الرفض فقط
  حين تجيب بوابة الشخص نفسه على 8642 (external)، لأن البيت حينها ليس بيت المركز. بوابة TUI للمحادثة لم تُغيَّر
  (تغيير مسار المحادثات الموجودة خارج النطاق).
- لا تغيير كاسر: ثلاثة حقول اختيارية جديدة في الاستجابة؛ `agents.upgrade` بنفس معناه لكل وكيل آخر، ووكيل لم
  يثبّته المركز وبلا `self_update` يُرفض كما كان.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
`AgentInstall`: `minimum_version`، `below_minimum`، `self_update` (اختيارية، غائبة في المراكز الأقدم). وصف
`agents.upgrade` أُضيف إليه حالة `self_update`. DECISIONS §119.

## الملفات والتأثير
- `packages/contracts/openapi.yaml`، `docs/contracts/DECISIONS.md` (§119).
- `packages/server/src/modules/agents/catalog/{types,hermes}.ts`: `minimumVersion` لهرمز.
- `packages/server/src/modules/agents/serialize.ts`: الحقول الجديدة و`belowMinimum()`.
- `packages/server/src/modules/agents/service.ts`: `HermesUpdater` ومهمة `hermesSelfUpdate` داخل `upgrade`.
- `packages/server/src/modules/agents/hermes-runtime.ts`: `personalInstall()` و`selfUpdate()`.
- `packages/server/src/modules/agents/index.ts`: التوصيل، و`hermesPython` عبر `runtime.pythonCommand()`.
- `packages/server/src/modules/agents/hermes-python.ts` (جديد)، `hermes-pending-writes.ts` (المشغّل يقبل أمرًا
  كاملًا)، `packages/server/src/modules/models/index.ts` (قائمة المزوّد المسجَّل عبر `runtime.pythonCommand()`).
- `packages/web/src/agents/AgentManagerScreen.tsx`، `packages/web/src/i18n/{ar,en}.json`: التنبيه والزر والتأكيد.
- `docs/STATUS.md`.
- اختبارات: `hermes-python.test.ts` (جديد)، `hermes-self-update.routes.test.ts` (جديد)، `hermes-runtime.test.ts`، `packages/web/tests/channels-pairing.test.tsx`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ mj-run pnpm --filter @corehub/server exec vitest run hermes-self-update.routes.test.ts hermes-shared-install.test.ts \
    adapters/hermes-version.test.ts hermes-runtime.test.ts adapters/hermes.test.ts agents.test.ts update-policy.test.ts
 Test Files  7 passed (7)
      Tests  101 passed (101)
$ mj-run pnpm --filter @corehub/web exec vitest run tests/channels-pairing.test.tsx
      Tests  20 passed (20)
$ mj-run pnpm contract:test          → Test Files 19 passed (19), Tests 405 passed (405)
$ mj-run pnpm contracts:check-clients → check-clients  OK — 996 client file(s) scanned, 254 contract path(s) known.
$ mj-run pnpm contracts:lint         → contracts:lint  OK (التحذير الوحيد موجود في main قبل التغيير)
$ mj-run pnpm i18n:check             → i18n:check  OK
$ mj-run pnpm typecheck              → exit 0
$ eslint . && prettier --check .     → exit 0
```
الاختبارات الجديدة تفشل على الكود القديم (أُعيد الكود مؤقتًا بـ`git stash`):
```
 × compares the release, ignoring build metadata, and never flags an unknown version
 × is said on the card, and updated by its own updater when asked, then restarted and probed again
 × is only said, never updated, where the Hermes is not the person’s own (the image)
 × says nothing for a Hermes at or past the minimum
 × says so without blocking, and updates it only after the person agrees        (الويب)
 × only says so where the hub may not update that Hermes                          (الويب)
```
تحقق حقيقي من بايثون هرمز على هرمز مثبّت بالمثبّت الرسمي (`0.21.5+3579.ge46d4c0`) ببيت منفصل للمركز (رابط
`installs` مشترك و`HERMES_RUNTIME_DIR`)، ببرنامج يستورد حزم هرمز:
```
shared: already python command: $R/hh/.hermes/tools/python-3.14.7+20260901-linux-x64/bin/python3 [ '-I', '-c' ]
exit 0 in 580 ms: 0.21.5 2.24.0 $R/hubhome ['arg1']
   (hermes_cli.__version__، openai.__version__، get_hermes_home()، sys.argv[1:])
```
لم يُشغَّل `hermes update` حقيقي على هرمز أقدم (يحتاج تثبيتًا قديمًا وشبكة)؛ مُختبَر بسكربت `hermes` مزيف يسجّل
المعاملات والبيئة (`update --yes HERMES_HOME=unset`).

### CI
على `abdb9852` (PR #192) كل الفحوص نجحت: CI (36321108892: lint/typecheck/contracts/client tests/build، اختبارات
الخادم الثلاث، دخان الويب وسطح المكتب، صورة Docker، الترحيلات)، iOS (36321109000)، Android (36321109013)، قائمة
متجر iOS (36321108882)، سجل التغيير (36321108888).

## المخاطر والرجوع
- `hermes update` يغيّر هرمز الشخص للحاسوب كله؛ لذلك لا يحدث إلا بضغطة مالك/مدير بعد تأكيد صريح.
- الحد الأدنى محافظ؛ إن ثبت أن نسخة أقدم تعمل يُخفَّض في `catalog/hermes.ts` وحده.
- الرجوع: إرجاع الـPR؛ العملاء القديمة تتجاهل الحقول الجديدة.

## التسليم والخطوة التالية
PR واحد إلى `main` بالإنجليزية؛ لا دمج ولا صورة ولا إصدار.
