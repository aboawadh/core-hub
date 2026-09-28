# المهام المجدولة على نقطة نهاية للمركز: تشغيلها على الاختيار نفسه
المسؤول: aboawadh · الفرع: fix/scheduled-jobs-named-provider · الحالة: review

## المشكلة والهدف
كل مهمة مجدولة (جدولة Hermes، `cron/jobs.json`) في بروفايل نموذجه الافتراضي على نقطة نهاية من نقاط المركز
(كتلة تحت `providers:` مثل `corehub-custom-cli-proxy-api`) تفشل في كل تشغيل بـ
`RuntimeError: No LLM provider configured`، والمحادثة في البروفايل نفسه تعمل. `hermes cron resnap` لا يصلحها.

السبب في Hermes الذي تثبّته الصورة (v2026.9.14، وهو الحد الأدنى في DECISIONS §119): المهمة التي لا تسمّي مزوّدًا
تعمل على لقطة تؤخذ عند إنشائها (`cron/jobs.py` §_compute_provider_model_snapshots). اللقطة تحفظ المزوّد **بعد
الحل**، وكل كتلة تحت `providers:` تُحلّ إلى `custom`، لا إلى اسمها. عند التشغيل `custom` وحده مسار بلا عنوان ولا مفتاح
(OpenRouter بلا مفتاح)، فيرفع `agent/agent_init.py` الخطأ أعلاه. `resnap` يأخذ اللقطة نفسها من جديد. Hermes
v2026.9.24 حذف اللقطة، والترتيب فيه: تثبيت المهمة، ثم `cron.model_provider`، ثم `model.*`.

المركز لا يرسل مزوّدًا ولا نموذجًا إلى `/api/jobs` (`HermesJobWrite`)، والمهام التي ينشئها الوكيل بأداة الجدولة عنده
تُنشأ بالطريقة نفسها؛ فكل مهمة في بروفايل على نقطة نهاية تتأثر.

الهدف: أن تعمل المهام المجدولة على النموذج الذي اختاره المركز، على كل Hermes يعمل معه المركز (v2026.9.14 فما
فوق)، دون تعديل Hermes ودون تثبيت المهام.

## القرار والموافقات
`applyModel` في `propagation.ts` يكتب الاختيار نفسه أيضًا في `cron.model_provider` + `cron.model`، في الكتابة
الواحدة نفسها، وفقط حيث يلزم:
- الاختيار يسمّي كتلة تحت `providers:` والمفتاحان غير موجودين — الحالة التي يخطئ فيها Hermes؛ المزوّد المدمج
  (`anthropic` وغيره) تُحفظ لقطته باسمه ولا يحتاج شيئًا هنا؛
- أو الزوج ما زال يقول الاختيار الذي تستبدله هذه الكتابة — كتبه المركز من قبل، فيتبع الاختيار، حتى إلى مزوّد مدمج
  (المهام التي أُنشئت على الكتلة تبقى لقطتها `custom`).
زوج وضعه شخص بقيمة أخرى (`hermes config set cron.model …`) له ويبقى، وكذلك `cron` ليس خريطة. المهمة المثبتة
بـ `hermes cron edit --provider` تتقدم على الاثنين.

لماذا لا نرفع `HERMES_REF`: المركز يعمل أيضًا مع Hermes الشخص نفسه بأي نسخة من الحد الأدنى (§119)، واختبارات
`*.real.test.ts` تعمل على v2026.9.14؛ أما الزوج فهو ضبط Hermes الموثّق (سجلّه نفسه يقترح `cron.model_provider`)
ومعناه واحد في v2026.9.24 وما بعدها. لماذا لا نثبّت المهمة في `/api/jobs`: التثبيت يوقف اتباع الاختيار، ولا يغطي
المهام التي ينشئها الوكيل.

الموافقات: إصلاح ضمن ADR 0010 (المركز يحمل الاختيار إلى الوكلاء)، مقترح لمراجعة المالك؛ لم يُحجز رقم قرار.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `packages/server/src/modules/models/propagation.ts` — `applyCronModel`، يُستدعى من `applyModel` في كل
  الكتّاب (`writeHermesConfiguration`، `writeHermesRoute`، `writeHermesModel`).
- `packages/server/src/modules/models/propagation.test.ts` — ستة اختبارات: الكتلة، المزوّد المدمج، الاتباع بعد
  التبديل، زوج الشخص، `cron` الفارغ ومفاتيحه الأخرى، والكتابة الثانية بلا تغيير.
- الأثر: البروفايل الذي نموذجه على نقطة نهاية يُكتب فيه المفتاحان في أول نشر بعد التحديث (إعادة تدوير واحدة
  للبوابة)، ثم لا شيء. المهام المعطلة أصلًا تعمل من تشغيلها التالي دون تعديلها. غيره لا يتغير.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ pnpm --filter @corehub/server exec vitest run src/modules/models/propagation.test.ts
 Test Files  1 passed (1)
      Tests  36 passed (36)

$ pnpm --filter @corehub/server typecheck
(exit 0)

$ pnpm lint
$ eslint . && prettier --check .
Checking formatting...
All matched files use Prettier code style!

$ pnpm exec vitest run --project unit --maxWorkers=4   (linux, node v24.21.0, LANG=C.UTF-8)
 FAIL   unit  src/modules/agents/hub-tools/hub-tools.routes.test.ts > the hub's own tools: the MCP endpoint > acts only while a run is live, as its owner, through the REST routes
 Test Files  1 failed | 196 passed | 31 skipped (228)
      Tests  1 failed | 1986 passed | 86 skipped (2073)

$ pnpm exec vitest run --project unit src/modules/agents/hub-tools/hub-tools.routes.test.ts   (x3 branch, x2 main)
      Tests  10 passed (10)

$ pnpm contract:test   (linux)
 Test Files  20 passed (20)
      Tests  428 passed (428)
```

- `hub-tools.routes.test.ts` ينجح وحده ثلاث مرات على الفرع ومرتين على `main`: تذبذب توقيت تحت حمل المجموعة كاملة، لا علاقة له بالتغيير.
- على macOS تفشل 12 حالة في 6 ملفات (الطرفية، أشجار عمل المهام، `working-dir`، `files`، أرشيف البروفايل ونقله: tar النظام و`/private/tmp` وPTY) وحالة عقد واحدة (الطرفية)، وتفشل هي نفسها على `main` (037024b) دون هذا التغيير. وفي Docker بلا `LANG=C.UTF-8` وبعمّال كثيرين يفشل اختبار tar (اسم ملف عربي) وتظهر أخطاء «Worker forks emitted error»، على الفرع و`main` بالتساوي.

إعادة الخلل على Hermes الصورة نفسه (v0.21.3 / v2026.9.14)، ببيانات مختلقة في حاوية مؤقتة بلا شبكة (`HERMES_HOME` فارغ، كتلة
`providers:` عنوانها `example.invalid`، مهمة تُنشأ بـ `cron.jobs.create_job` ثم تُحل كما يحلها المجدول):

```
created: provider=None provider_snapshot='custom' model_snapshot='example-model'
as shipped            : provider='custom' requested_provider='custom' base_url='https://openrouter.ai/api/v1' key=MISSING model='example-model'
after resnap: provider_snapshot='custom'
after resnap          : provider='custom' requested_provider='custom' base_url='https://openrouter.ai/api/v1' key=MISSING model='example-model'
with cron.* (this PR) : provider='custom' requested_provider='corehub-custom-example' base_url='http://proxy.example.invalid:8317/v1' key=set model='example-model'
```

```
$ pnpm change-record:check
$ node scripts/check-change-record.mjs
change-record  OK — 1 record(s) valid
```

## المخاطر والرجوع
- مفتاحان إضافيان في `config.yaml` فقط حيث يلزم، والملف يُكتب بالطريقة نفسها (رحلة ذهاب وإياب، تبقى التعليقات
  والمفاتيح الأخرى). لا ترحيل ولا تغيير في العقد.
- إن غيّر شخص `model.*` يدويًا خارج المركز، يتوقف المركز عن كتابة الزوج (لم يعد يتبع الاختيار) ويبقى الزوج على آخر
  اختيار صالح؛ يعيده `hermes config set` أو حذف المفتاحين.
- الرجوع: إرجاع الطلب. المفتاحان المكتوبان يبقيان صالحين (يسميان الاختيار نفسه)، ويُحذفان يدويًا إن أراد أحد.

## التسليم والخطوة التالية
بعد دمج المالك: تحقق على مركز حقيقي من مهمة مجدولة على نقطة نهاية (أول تشغيل ينجح دون تعديلها). حين يُرفع
`HERMES_REF` إلى v2026.9.24 أو أحدث يبقى الزوج صحيحًا ولا يلزم حذفه.
