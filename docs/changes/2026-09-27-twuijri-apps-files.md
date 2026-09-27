# الدفعتان 11 و13: أداة الملفات (ملفات البروفايل) في التطبيقين
المسؤول: twuijri · الفرع: night/apps-files · الحالة: in-progress

## المشكلة والهدف
في قائمة الفجوات كانت «الملفات» (مجلد عمل البروفايل، §65) غائبة عن الجوال: الآيفون يعرض «قريبًا» والأندرويد يفتح صفحة الويب.
الهدف: أداة الملفات كما في الويب، من مكوّنات كل تطبيق وبتخطيط مضغوط وأرقام لاتينية — التصفح والمعاينة والتنزيل والمشاركة والإرفاق
بمحادثة (الدفعة 11)، ثم الرفع والمجلد الجديد وإعادة التسمية والنقل والنسخ والحذف وتحرير النص مع فحص التعارض (الدفعة 13).

## القرار والموافقات
- **إعادة استخدام فاتح ملفات المحادثة** (#179، الدفعة 6): أضفت نوعًا ثالثًا من ملفات المركز «ملف البروفايل»
  (`HubFile.Profile` / `.profileFile`) يجلبه المُنزِّل نفسه عبر `knowledge.downloadWorkspaceFile` ويشغّل الصوت والفيديو بتذكرة
  `knowledge.createWorkspaceFileStream`؛ فالصور تُرسم والمستندات تفتح في عارض الهاتف والوسائط تُبثّ، بالتقدّم والإلغاء نفسيهما.
  لم أبنِ عارضًا جديدًا.
- **ما يفعله النقر**: المجلد يُفتح، الملف النصي (`editable`) يفتح في المحرر المشترك `TextEditorSheet` (Markdown بمعاينة، غيره
  بخط ثابت)، وغيره يفتح كملف المحادثة، والرابط إلى الخارج يقول إنه لا يُفتح. بقية الإجراءات في «⋯» (وفي iOS بالضغطة المطوّلة أيضًا)،
  والحذف بالسحب. هذا اختيار للجوال (الويب يعاين النص أولًا) — مقترح، للمالك أن يؤكد.
- **البحث والترتيب** محليّان في المجلد المعروض (الاسم، الأحدث، الأكبر؛ المجلدات أولًا)؛ الويب لا يملك ترتيبًا ولا بحثًا والعقد لا
  يملكهما، وطلبتهما المهمة. تغيير البحث أو الترتيب يعيد ترتيب ما قُرئ دون طلب جديد، والسحب للتحديث يقرأ المجلد من جديد.
- **الإرفاق بمحادثة**: «إرفاق بمحادثة جديدة» فقط. يُجلب الملف إلى الهاتف ثم يُسلَّم للمحادثة الجديدة كما تُسلَّم ملفات مشاركة
  تطبيق آخر (`sharedFiles` في الأندرويد، `pendingFiles` في الآيفون) فيُرفع من صينية المحادثة. لم أستعمل
  `knowledge.attachWorkspaceFile` لأن شاشات المحادثة لا تقبل مرفقًا جاهزًا دون تعديل ملفات المحادثة (خارج نطاق هذه الصفحة)؛
  الثمن أن البايتات تذهب وتعود (حتى حدّ الرفع). «المحادثات الأخيرة» في الويب لم تُبنَ لنفس السبب — مقترح، للمالك أن يؤكد.
- **التنزيل والمشاركة**: الأندرويد «حفظ في الهاتف» (مجلد التنزيلات، أندرويد 10+) و«مشاركة»؛ الآيفون «مشاركة» (ورقة المشاركة
  فيها «حفظ في الملفات»). مجلد كامل (أو المجلد المعروض) يُشارك مضغوطًا عبر `knowledge.downloadWorkspaceFolder`.
- **الرفع**: ملفات أو صور وفيديو (الأصل كما هو، مدير ملفات)، واحدًا بعد واحد بشريط تقدّم (الأندرويد يعدّ جسم الطلب بمعترض، الآيفون
  يتابع `Progress` لطلب العميل المولَّد)، ويُرفض قبل الإرسال ما تجاوز `limits.max_upload_bytes`، ويُسأل الشخص عند `409 exists`
  «استبدال أو تخطٍّ». **لا رفع قابل للاستئناف**: العقد يملك لهذه الملفات `knowledge.uploadWorkspaceFile` واحدًا متعدد الأجزاء بحدّ
  المركز، والتدفق القابل للاستئناف (`sessions.startUpload`…) خاص بمرفقات المحادثة ولا ينقل إلى مجلد البروفايل؛ لم أخترع عملية.
- **الرفض بسطر واحد**: 403 (ليس مالكًا ولا مشرفًا)، الاسم موجود، كبير جدًا، ليس نصًا، خارج ملفات البروفايل، المجلد الأعلى، مجلد
  داخل نفسه، لم يعد موجودًا — بكلمات الصفحة، وغير ذلك بكلمات المركز.
- **إصلاح في المحرر المشترك** (`TextEditorSheet.kt` / `DocumentEditor.swift`): فحص «تغيّر في مكان آخر» كان ينتظر `code == "changed"`،
  والمركز يرسل `code: conflict` مع `details.reason = changed` (ملفات البروفايل وملفات إعداد الوكيل)، فلم يكن زر «إعادة التحميل»
  يظهر أبدًا. صار يقبل السبب أيضًا؛ اختبارا الطقم يثبتان ذلك (يفشلان على الكود القديم).
- **أيقونات Lucide جديدة** للتطبيقين عبر `scripts/icons/lucide-mobile.mjs`: upload, folder-plus, file-plus, paperclip, arrow-up-down,
  folder-input.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- Android: `ui/screens/settings/FilesPage.kt` (الصفحة، حُذف `native = false`)، `ui/screens/settings/FilesKit.kt` (`FilesRules`,
  `FilesApis`, `FilesOps`)، `chat/HubFiles.kt` (نوع `HubFile.Profile`)، `ui/components/TextEditorSheet.kt` (فحص التعارض)،
  `res/values*/strings_files.xml` (مفاتيح `files_page_*` لأن `files_*` مستعملة في `strings.xml`)، أيقونات `res/drawable/lucide_*`
  و`ui/kit/Lucide.kt`.
- iOS: `Settings/Pages/FilesPage.swift` (الصفحة، حُذف `native: false`)، `Settings/Pages/FilesViews.swift` (الرأس والصف)،
  `Settings/Pages/FilesRules.swift` (`FilesRules`, `FilesOps`)، `Chat/HubFiles.swift` (`.profileFile`)،
  `Components/DocumentEditor.swift`، `i18n/files.{en,ar}.json`، `Generated/Lucide.swift` وأصول الأيقونات.
- اختبارات: Android `parity/FilesPageTest.kt` (13: القواعد، والطلبات أمام خادم مُبرمج: القراءة بالبروفايل، المجلد والنقل والنسخ
  والحذف، الحفظ بالـetag و409، الرفع بالتقدّم والاستبدال، المضغوط، وجلب ملف البروفايل بالمُنزِّل المشترك)، `shots/FilesPageShots.kt`
  (فاتح بالإنجليزية وداكن بالعربية، المجلد الأعلى ثم مجلد بعد نقرة)، و`ui/PageKitTest.kt` (سطران)؛ iOS
  `CoreHubTests/FilesRulesTests.swift` (7) و`PageKitTests.swift` (سطران).
- `docs/STATUS.md` وفهرس الليلة.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ gradlew :app:testDebugUnitTest --tests '*FilesPageTest' --tests '*FilesPageShots' --tests '*PageKitTest' --tests '*HubFilesTest' \
    --tests '*StringsParityTest' --tests '*NavigationParityTest' :app:lintDebug      (mj-run, JDK 17)
BUILD SUCCESSFUL in 1m 23s
<testsuite name="hub.core.android.parity.FilesPageTest" tests="13" skipped="0" failures="0" errors="0"
<testsuite name="hub.core.android.shots.FilesPageShots" tests="2" skipped="0" failures="0" errors="0"
<testsuite name="hub.core.android.ui.PageKitTest" tests="5" skipped="0" failures="0" errors="0"
<testsuite name="hub.core.android.chat.HubFilesTest" tests="11" skipped="0" failures="0" errors="0"
<testsuite name="hub.core.android.ui.StringsParityTest" tests="4" skipped="0" failures="0" errors="0"
<testsuite name="hub.core.android.nav.NavigationParityTest" tests="10" skipped="0" failures="0" errors="0"
lint: لا مشكلة في strings_files.xml ولا في ملفات الصفحة (نبّه lint إلى أربعة مفاتيح غير مستعملة: حُذفت ثلاثة وصار الرابع اسم المسار لقارئ الشاشة)

$ pnpm i18n:check              → i18n:check  ios: 1922 keys, ar/en in parity … android: Arabic resources use Latin digits … OK
$ pnpm contracts:check-clients → check-clients  OK — 963 client file(s) scanned, 254 contract path(s) known.
$ pnpm nav:check               → nav:check  OK — 39 destinations …
$ pnpm lint                    → All matched files use Prettier code style!
$ pnpm typecheck               → EXIT 0
```
صور الأندرويد: `apps/android/app/build/shots/files-page/android-files{,-folder}-{light-en,dark-ar}.png` (راجعتُها: الأزرار والمسار
والحدود والصفوف؛ اسم الملف العربي صار يُقرأ باتجاهه بعد أن ظهر أولًا «md.خطة الإطلاق» فأصلحته).

iOS لا يُبنى على لينكس:
```
$ gh workflow run ios.yml --ref night/apps-files   (run 36288362508، الالتزام 7de59365)
** BUILD SUCCEEDED **
Test Case '-[CoreHubTests.FilesRulesTests testAFileOpensThroughTheChatsOpenerAndAChangedFileIsFetchedAgain]' passed
Test Case '-[CoreHubTests.FilesRulesTests testAPromptsWordsBecomeTheOneStepThePageTakes]' passed
Test Case '-[CoreHubTests.FilesRulesTests testARefusalIsNamedInOneLineAndANameAlreadyThereAsksToReplace]' passed
Test Case '-[CoreHubTests.FilesRulesTests testEveryRefusalHasItsLineInBothLanguagesAndASaidLineStaysAsItIs]' passed
Test Case '-[CoreHubTests.FilesRulesTests testFoldersComeFirstThenTheChosenOrderAndASearchNarrowsByName]' passed
Test Case '-[CoreHubTests.FilesRulesTests testPathsJoinSplitAndTrailTheWayTheHubWritesThem]' passed
Test Case '-[CoreHubTests.FilesRulesTests testSizesAndTimesReadInLatinDigits]' passed
Test Case '-[CoreHubTests.PageKitTests testASaveRefusedAsChangedElsewhereIsToldApart]' passed
Executed 284 tests, with 0 failures (0 unexpected)
** TEST SUCCEEDED **
```

## المخاطر والرجوع
- لم تُجرَّب على هاتفي المالك؛ صفحة iOS لا صور لها (اختبارات القواعد فقط في CI).
- الإرفاق بمحادثة جديدة ينزّل الملف ثم يرفعه مرفقًا؛ الملف فوق حدّ مرفقات المحادثة (50 MB) يرفضه الصندوق هناك.
- إصلاح فحص التعارض في المحرر المشترك يغيّر سلوك كل صفحة تستعمله: يظهر «إعادة التحميل» حيث كان يظهر نص المركز فقط.
- الرجوع: `git revert` لالتزامات هذا الفرع في فرع الليلة؛ لا عقد ولا بيانات.

## التسليم والخطوة التالية
يُدمج في `night/2026-09-27-apps` (#181). للمالك: تأكيد «النقر على نص يفتح المحرر»، و«الإرفاق بمحادثة جديدة فقط». إن أراد الإرفاق
بمحادثة قائمة أو رفعًا قابلًا للاستئناف: الأول تعديل صغير في شاشتي المحادثة، والثاني عملية جديدة في العقد.
