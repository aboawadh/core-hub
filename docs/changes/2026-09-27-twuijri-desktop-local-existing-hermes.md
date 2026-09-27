# الوضع المحلي لتطبيق سطح المكتب مع هرمز المثبّت مسبقًا
المسؤول: twuijri · الفرع: fix/desktop-local-existing-hermes · الحالة: review

## المشكلة والهدف
بلاغ من صديق المالك (ماك، كور هب 1.1.3، الوضع المحلي، وهرمز مثبّت عنده مسبقًا بمثبّت هرمز الرسمي في
`~/.local/bin/hermes` وبيته `~/.hermes`):
- بطاقة هرمز في صفحة الوكلاء: «متاح»، وبوابة الرسائل «Default · starting · no channels»، ومربع أحمر
  `Command failed: /Users/<user>/.local/bin/hermes --version` ونصه مقصوص عند حافة البطاقة.
- إرسال رسالة: `The run failed: the Hermes gateway at http://127.0.0.1:8642 did not answer (fetch failed) (agent_unavailable)`.
- وسأل: هل يتعارض حساب كور هب الذي أنشأه عند أول تشغيل مع هرمز الخاص به؟

الهدف: أن يعمل الوضع المحلي مع هرمز الشخص كما هو، دون المساس بإعداداته، وأن تقول البطاقة والمحادثة سبب
العطل بكلام يُفهم.

### الأسباب المؤكَّدة (بإعادة إنتاج حقيقية، لا تخمين)
أُعيد الإنتاج على لينكس بهرمز حقيقي مثبّت بمثبّته الرسمي (`install.sh --non-interactive --skip-browser`،
تثبيت git مثل تثبيت الصديق، v0.21.5 بتاريخ 2026.9.24) في HOME مؤقت، وبالمركز المضمَّن نفسه
(`apps/desktop/dist/hub`) مشغَّلًا كما يشغّله التطبيق (HOME، وPATH يبدأ بمجلد `hermes`، و`DATA_DIR`، و`PORT`)
داخل نطاق شبكة خاص (`unshare -n`) له 127.0.0.1 خاص به، فلم يُلمس هرمز المالك على 8642 ولا `~/.hermes` الخاص به.

1. **البوابة لا تقوم (السبب الرئيسي).** ملاحظة أولى: المركز لا يستخدم `~/.hermes` أصلًا؛ يشغّل برنامج الشخص
   ببيت خاص به `<بيانات التطبيق>/local-hub/hermes` (ADR 0021 القرار 3). لكن مدير حزم هرمز الجديد (`pm/`) يحفظ
   حزم بايثون والأدوات **لكل جذر بيانات** (`<root>/installs/<key>/` و`<root>/tools/`) ويعدّ أي `HERMES_HOME` خارج
   `~/.hermes` جذرًا مستقلًا (`hermes_constants.get_default_hermes_root`، `pm/environments.dependency_home_root`).
   فأول `hermes gateway run` في بيت المركز لا يجد أي حزم ويبني بيئة هرمز ثانية كاملة هناك:
   - بلا إنترنت: حلقة انهيار `ModuleNotFoundError: No module named 'ruamel'` (البطاقة بين error وstarting).
   - بإنترنت: **267 ثانية** على هذا الجهاز و**2.0 GB** (بايثون، Node، ffmpeg، Chromium…) قبل أن يجيب
     `/health` — والبطاقة طوال ذلك «starting» والمحادثة «did not answer (fetch failed)».
   - والأخطر: في آخر هذا البناء **أعاد هرمز كتابة مشغّل `hermes` الخاص بالشخص**
     (`~/.hermes/hermes-agent/.hermes/bin/hermes`) ليستخدم بايثون المنزَّل داخل مجلد كور هب؛ بعد حذف ذلك المجلد
     تعطّل `hermes` عند الشخص (`exec: …/hub-home-online/tools/python-3.14.7…/bin/python3: not found`)، وأصلحه
     تشغيل مثبّت هرمز من جديد.
2. **«Command failed» على البطاقة.** فحص النسخة يشغّل `hermes --version` بمهلة 5 ثوانٍ. هرمز يطبع نسخته أولًا ثم
   يفحص التحديثات عبر git والشبكة بشكل متزامن (`hermes_cli/_startup_fast.py` → `check_for_updates`، مهلات 10 ث)؛
   قسناه 2.05 ث باردًا هنا، وعلى ماك بتشغيل بارد وشبكة بطيئة يتجاوز 5 ث فيُقتل، وبايثون يحتفظ بمخرجه في الذاكرة
   المؤقتة عند الأنبوب فتضيع النسخة المطبوعة ويظهر «Command failed». ووجدنا أيضًا أن `parseVersion` تقرأ
   `Hermes Agent v0.21.5+…` على أنها `21.5+…` (حرف `v` ملتصق بالرقم).
3. **رسالة المحادثة** نتيجة (1): المحادثة تذهب إلى خادم API للبوابة على 8642، والبوابة لم تقم.
4. **النص المقصوص**: صندوق الخطأ بلا التفاف لسلسلة بلا مسافات (مسار).

ملاحظة خارج النطاق (لم تُغيَّر): بوابة TUI للمحادثة تُطلب من `python` بجانب برنامج `hermes`، ومثبّت هرمز يضع في
`~/.local/bin` نصًّا قصيرًا لا رابطًا، فالمحادثة في الوضع المحلي تمر عبر خادم API لا بوابة TUI. تغيير ذلك يغيّر مسار
المحادثات الموجودة، فتُرك لمهمة منفصلة.

## القرار والموافقات
- **بيت المركز يبقى بيته، والبيئة المثبّتة تُشارك** (مقترح — للمالك أن يؤكد؛ تعديل على ADR 0021): عند وجود
  `installs/` في جذر هرمز الشخص (`HERMES_HOME` إن ضُبط، وإلا `~/.hermes`، وعلى ويندوز `%LOCALAPPDATA%\hermes`)،
  يُنشأ في بيت المركز رابط `installs` → `<root>/installs` (junction على ويندوز)، وتُعطى عمليات هرمز التي يشغّلها
  المركز `HERMES_RUNTIME_DIR=<root>/tools` (متغيّر هرمز الموثّق لمخزن أدواته). هذا ما تفعله ملفات هرمز الشخصية
  (profiles) نفسها: تشارك تثبيتًا واحدًا وإعداداتها ومفاتيحها منفصلة.
  - هرمز قديم (حزمه في venv بجانب البرنامج) لا يحتاج شيئًا ولا يُفعل له شيء. الصورة (Docker) لا يتغير فيها شيء
    (`HERMES_HOME` هو بيت المركز نفسه).
  - بيئة أكملها هرمز سابقًا داخل بيت المركز (ما قد يكون حدث مع 1.1.3) تُترك كما هي (`own`)؛ وبناء لم يكتمل يُنقل
    جانبًا بإعادة تسمية `installs.unfinished-<وقت>` (لا يُحذف شيء) ثم يُربط.
  - مفتاح إطفاء: `COREHUB_HERMES_SHARED_INSTALL=off`.
  - رُفض: جعل بيت المركز `~/.hermes` نفسه (ADR 0021: بوابتان على بيت واحد، وإعدادات الشخص)، وجعله داخل
    `~/.hermes` (هرمز يعامل الجذر حينها `~/.hermes` فتصير ملفات المركز الشخصية ملفات الشخص، ويتبع `active_profile`
    الخاص به)، وترك هرمز يبني بيئة ثانية مع مشاركة الأدوات فقط (ما زال يحتاج إنترنت وحجمًا، ولا يمنع إعادة نشر
    المشغّل).
- **فحص النسخة**: يُقرأ مخرج `hermes --version` سطرًا سطرًا مع `PYTHONUNBUFFERED=1`، ويتوقف عند أول سطر فيه
  نسخة ويُنهي العملية (لا ينتظر فحص التحديثات ولا الشبكة)، بمهلة 15 ث لا تُستهلك إلا إن لم تأتِ نسخة؛ الخطأ يقول
  ما حدث («لم يطبع نسخة خلال N ث» أو آخر سطر من هرمز) بدل «Command failed». و`parseVersion` تقبل `v` الملتصقة.
- **أسباب مفهومة**: سقوط البوابة يُعرض مع آخر سطر كتبه هرمز (`hermes gateway exited (code 1): ModuleNotFoundError: …`)
  ويبقى ظاهرًا أثناء إعادة التشغيل حتى تجيب؛ وبوابة حيّة لم تجب بعد فترة الإحماء (120 ث) تبقى `starting` مع
  «لم تجب /health بعد N ث — آخر سطر: …»؛ ورسالة المحادثة حين لا تُبلغ البوابة يُضاف إليها ما يعرفه المركز
  («Hermes is still starting…; try again in a moment» أو سبب التوقف).
- **البطاقة**: نصوص الأخطاء تلتف (`wrap-anywhere`) بدل أن تخرج عن حافة البطاقة.
- **الشاشة الأولى والتوثيق**: سطر تحت «التشغيل على هذا الحاسوب» يقول إن هرمز الموجود يبقى على إعداداته ومفاتيحه
  ومحادثاته، وإن حساب كور هب لتسجيل الدخول إلى هذا المركز فقط؛ وقسم في `apps/desktop/README.md`.
- **لا تغيير كاسر**: لا عقد ولا ترحيل؛ البيانات الموجودة في `local-hub/hermes` تبقى كما هي (بيئة مكتملة لا تُلمس)؛
  لا متغيّر قديم أُزيل؛ الإضافة الوحيدة متغيّر اختياري للإطفاء.

### جواب سؤال الحساب (لإبلاغ الصديق)
حساب كور هب الذي أُنشئ في أول تشغيل هو **تسجيل دخول المركز فقط** (قاعدة بياناته في `local-hub`)، ولا علاقة له
بهرمز ولا بأي حساب فيه، فلا تعارض. والمركز في الوضع المحلي **لا يكتب في إعدادات `~/.hermes`**: لا `config.yaml` ولا
`.env` ولا الذاكرة ولا الجلسات ولا المهارات؛ كل ذلك في بيته الخاص `local-hub/hermes`. ما يحدث في `~/.hermes` هو ما
يحدث عند تشغيل `hermes` من الطرفية: ملفات «إيجار» قصيرة العمر تحت `installs/<key>/…/.leases`، وسجل هرمز لكل
مراجعة في `installs/<key>/bootstrap/`، وقد يكتب `hermes --version` ذاكرة فحص التحديثات `source-checks/`. (في
1.1.3 كان يمكن أن يُعاد نشر مشغّل `hermes` الخاص به ليشير إلى مجلد كور هب — السبب 1 — وهذا ما يمنعه هذا الإصلاح.)

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء. نصوص أخطاء أوضح في حقول موجودة (`runtime.error`، `gateways[].error`، رسالة `agent_unavailable`).

## الملفات والتأثير
- `packages/server/src/modules/agents/hermes-shared-install.ts` (جديد): جذر هرمز الشخص، والربط، و`HERMES_RUNTIME_DIR`.
- `packages/server/src/modules/agents/hermes-runtime.ts`: الربط قبل أي عملية هرمز (`start()`)، والمتغيّر في بيئة
  البوابة و`cliEnv()`، وسبب السقوط بآخر سطر، ورسالة البوابة المتأخرة، و`gatewayNote()`.
- `packages/server/src/modules/agents/adapters/host.ts`: `readVersion()` (قراءة حتى سطر النسخة)، وإصلاح `parseVersion`.
- `packages/server/src/modules/agents/adapters/hermes.ts`: فحص النسخة الجديد، و`gatewayNote` في رسالة «did not answer».
- `packages/server/src/modules/agents/index.ts`: توصيل `gatewayNote`.
- `packages/web/src/agents/AgentManagerScreen.tsx`: التفاف نصوص الأخطاء.
- `apps/desktop/src/renderer/welcome.ts`، `apps/desktop/src/i18n/{ar,en}.json`: سطر الشاشة الأولى.
- `apps/desktop/README.md`، `docs/adr/0021-desktop-local-mode.md` (تعديل مقترح)، `docs/STATUS.md`.
- اختبارات: `hermes-shared-install.test.ts` (جديد)، `adapters/hermes-version.test.ts` (جديد)،
  `hermes-runtime.test.ts`، `packages/web/tests/channels-pairing.test.tsx`، `apps/desktop/tests/smoke/desktop.spec.ts`.

## الفحوص (الأوامر ونواتجها الفعلية)
### إعادة الإنتاج الحقيقية قبل الإصلاح (main، المركز المضمَّن، هرمز v0.21.5 الحقيقي)
بلا إنترنت (نطاق شبكة خاص) — `hub.log`:
```
{"level":40,…,"hermes":true,"msg":"hermes: completing source-update dependencies..."}
{"level":40,…,"hermes":true,"msg":"ModuleNotFoundError: No module named 'ruamel'"}
{"level":50,…,"code":1,"signal":null,"msg":"hermes: gateway crashed; restarting"}
(ثلاث حلقات في 60 ث؛ /health لم يجب: curl exit 7)
```
بإنترنت، البوابة نفسها ببيت منفصل ومنفذ 18642 (لا 8642):
```
health after 267s
2.0G	…/hub-home-online
hermes: completing source-update dependencies...
Preparing the isolated Hermes runtime…
  → Installing Python dependencies…  … → Building the TUI… → Building the web UI…
→ Installing agent-browser (browser tools; opt out with `hermes pm install --without agent-browser`)...
```
ثم مشغّل الشخص بعد ذلك:
```
$ head -2 ~/.hermes/hermes-agent/.hermes/bin/hermes
#!/bin/sh
exec $R/hub-home-online/tools/python-3.14.7+20260901-linux-x64/bin/python3 -I -c 'import os, re, sys
(بعد حذف بيت المركز) …/.hermes/bin/hermes: 2: exec: $R/hub-home-online/tools/python-3.14.7+20260901-linux-x64/bin/python3: not found
```
زمن `hermes --version` الحقيقي: `2.05 s` أول مرة (فحص تحديثات)، `0.33 s` بعدها.

### بعد الإصلاح (المركز المضمَّن من هذا الفرع، بلا إنترنت، بيت بيانات جديد)
```
gateway /health answered 4s after the hub started
--- GET 127.0.0.1:8642/health:
{"status": "ok", "platform": "hermes-agent", "version": "0.21.5"} (curl exit 0)
--- the Hermes row the card reads:
{ slug: 'hermes', version: '0.21.5+3413.g4175585', last_error: null, executable_path: '$R/hh/.local/bin/hermes' }
--- hub home:
lrwxrwxrwx installs -> $R/hh/.hermes/installs
4.6M	$R/hubdata/hermes
--- person's launchers unchanged:
$R/hh/.hermes/hermes-agent/.hermes/bin/hermes: OK
$R/hh/.local/bin/hermes: OK
--- what changed under the person's ~/.hermes (outside the checkout and bytecode caches):
added   f ~/.hermes/installs/8f4d41295e258a07/environments/3fb7…/.leases/1d71…
added   f ~/.hermes/installs/8f4d41295e258a07/pm-runtime/generations/19af…/.leases/8a4d…
```
سجل المركز: `hermes: this home runs on the installed Hermes's runtime` ثم `gateway healthy` بعد ثانيتين.

### اختبارات وفحوص محلية (ما لمسه التغيير فقط؛ الباقي على CI)
```
$ mj-run pnpm --filter @corehub/server exec vitest run src/modules/agents/hermes-shared-install.test.ts \
    src/modules/agents/adapters/hermes-version.test.ts src/modules/agents/hermes-runtime.test.ts \
    src/modules/agents/adapters/hermes.test.ts src/modules/agents/agents.test.ts
 Test Files  5 passed (5)
      Tests  71 passed (71)
$ mj-run pnpm --filter @corehub/web exec vitest run tests/channels-pairing.test.tsx
 Test Files  1 passed (1)
      Tests  18 passed (18)
$ mj-run pnpm --filter @corehub/desktop exec vitest run tests/unit/shared.test.ts tests/unit/hermes.test.ts tests/unit/local-hub.test.ts
 Test Files  3 passed (3)
      Tests  51 passed (51)
$ mj-run pnpm typecheck            → exit 0
$ mj-run pnpm i18n:check           → i18n:check  OK
$ mj-run pnpm change-record:check  → change-record  OK — 1 record(s) valid
$ eslint . && prettier --check .   → exit 0 (مجلد إعادة الإنتاج المؤقت .tmp-repro مستثنى؛ غير مُلتزَم)
```
الاختبارات الجديدة تفشل على الكود القديم (أُعيد الكود القديم مؤقتًا بـ`git stash` مع إبقاء الاختبارات):
```
 × starts the gateway on the installed runtime instead of letting Hermes build a second one
 × says why the gateway stopped in Hermes's own words, and keeps saying it while it restarts
 × tells a gateway that is alive but silent after the warm-up apart from one that is starting
 × reads Hermes's own line, `v` and build suffix included
 × shows the version as soon as it is printed, without an error, and does not wait 5011ms
 × reads a Python program line by line (its pipe output is not held back) 5011ms
 × says plainly when no version comes in time, instead of "Command failed" 5009ms
 × says what the hub knows about the gateway it runs, not only `fetch failed`
 × wraps a path or command with no spaces instead of running past the edge   (الويب)
```
اختبار الدخان لسطح المكتب (`tests/smoke/desktop.spec.ts`، سطر الشاشة الأولى) يعمل على CI فقط (Electron + Xvfb).

### CI
على `2c755f8f` (PR #189) كل الفحوص نجحت:
```
CI (36316890402) success: Lint/typecheck/contracts/client tests/build, Server unit tests 1/3 2/3 3/3,
  Web smoke journeys (Playwright), Desktop app smoke (Electron under Xvfb), Docker image /health,
  db:generate + db:migrate (SQLite and PostgreSQL), graphify-out guard
Desktop installers (36316890337) success: macos-latest, ubuntu-latest, windows-latest
Change record (36316890430) success
```

## المخاطر والرجوع
- الربط يعتمد على تخطيط هرمز الداخلي (`installs/`): إن تغيّر، يصير الرابط بلا أثر ويرجع هرمز لسلوكه (بيئة خاصة).
- سجل هرمز لكل مراجعة (`bootstrap/default.json`) مشترك بالاسم بين `default` عند الشخص و`default` في بيت المركز؛
  أثره المعروف أن ترحيل إعدادات هرمز لمراجعة جديدة قد يُجرى لأحد البيتين فقط حتى المراجعة التالية (إعدادات بيت المركز
  يكتبها المركز نفسه). مذكور للمالك.
- تثبيت هرمز كسول (lazy install) من المركز يضيف إلى بيئة الشخص المشتركة كما لو شغّله الشخص؛ المسارات المسجّلة
  محلولة إلى مجلد الشخص الحقيقي (`pm/lock.py`: `environment.resolve()`) فلا تشير إلى مجلد كور هب.
- الرجوع: `COREHUB_HERMES_SHARED_INSTALL=off`، أو إرجاع هذا الـPR؛ حذف الرابط `local-hub/hermes/installs` لا يمس
  شيئًا عند الشخص.

## التسليم والخطوة التالية
- PR واحد إلى `main` بالإنجليزية؛ لا دمج ولا صورة ولا إصدار.
- ما يُقال للصديق الآن على 1.1.3: انظر تقرير المهمة (إعادة تشغيل مثبّت هرمز إن كان مشغّله يشير إلى مجلد كور هب،
  ثم ترك البوابة تكمل أول تشغيل بإنترنت).
- مهمة لاحقة مقترحة: بوابة TUI لهرمز المثبّت بالمثبّت الرسمي (`hermes --print-runtime-command --module
  tui_gateway.entry`) مع الحفاظ على المحادثات الموجودة.
