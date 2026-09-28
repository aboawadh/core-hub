# كل هب يستعمل مرحّل الإشعارات تلقائيًا (عنوان workers.dev)
المسؤول: twuijri · الفرع: feat/default-push-relay · الحالة: review

## المشكلة والهدف
المرحّل منشور ويعمل، لكن `DEFAULT_RELAY_URL` في `packages/server/src/modules/devices/relay.ts` كان فارغًا، فلا يصل إشعار
لجوال عبر المرحّل إلا إن ضبط صاحب الهب `COREHUB_PUSH_RELAY_URL`. الهدف: كل هب يتحدّث يرسل للجوالات بلا أي إعداد.

## القرار والموافقات
المالك (2026-09-28): العنوان المبني هو العنوان المجاني `https://corehub-push-relay.twuijri.workers.dev` لا دومينه، حتى لا ينقطع
الإشعار لو تغيّر الدومين، ولا يظهر الدومين في المستودع. #206 شغّل workers.dev، ونشر 2026-09-28 من main نجح، و`/v1/health` عليه
يجيب `{"ok":true,"apns":true,"fcm":true,"registration":true}`.
المتغيّران `COREHUB_PUSH_RELAY_URL` و`COREHUB_PUSH_RELAY=off` ما زالا يغلبان العنوان المبني، ومفاتيح APNs/FCM المحلية تغلب المرحّل.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
- `packages/server/src/modules/devices/relay.ts` — `DEFAULT_RELAY_URL`.
- `packages/server/tests/unit/helpers.ts` — الاختبارات لا تتصل بالمرحّل الحقيقي: `COREHUB_PUSH_RELAY=off` ما لم يعطِ الاختبار عنوانًا أو يشغّله.
- `packages/server/tests/unit/devices-push-relay.test.ts` — حالة «بلا عنوان» صارت «العنوان المبني، غير مسجّل، بلا أي اتصال».
- `packages/server/tests/contract/devices.contract.test.ts` — الحالة في الاختبار `off` (مطفأ في بيئة الاختبار).
- `packages/push-relay/wrangler.toml` — `preview_urls = false` (نشر اليوم نبّه أنها تُفعَّل مع workers.dev).
- `docs/STATUS.md`.
أثره: الهب الذي بلا مفاتيح APNs/FCM يسجّل نفسه عند المرحّل أول ما يحتاج ويرسل عبره. لا تغيير على من ضبط مفاتيحه أو المتغيّرين.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ npx vitest run tests/unit/devices-push-relay.test.ts tests/unit/devices-relay.test.ts tests/contract/devices.contract.test.ts tests/unit/config.test.ts
 Test Files  4 passed (4)
      Tests  36 passed (36)
$ curl https://corehub-push-relay.twuijri.workers.dev/v1/health
{"ok":true,"apns":true,"fcm":true,"registration":true}
```
eslint وprettier على الملفات: نظيفة. باقي حزمة الخادم على CI.

أول CI لـ#209 بعد الدمج: رحلة الويب `zzzzzz-browser-push` سقطت لأن مركز رحلات Playwright (`packages/web/e2e/hub.ts`)
صار يستعمل المرحّل المبني فظهر مرسل FCM «جاهز» بدل «غير مضبوط». أُضيف `COREHUB_PUSH_RELAY: 'off'` لذلك المركز كما في
مساعد اختبارات الخادم — الرحلات لا تتصل بالمرحّل الحقيقي:
```
$ PLAYWRIGHT_CHANNEL=chrome npx playwright test --workers=1 e2e/zzzzzz-browser-push.spec.ts
  ✓  1 [chromium] › e2e/zzzzzz-browser-push.spec.ts:31:1 › a browser turns notifications on, and a test notice is pushed to it (1.8s)
  1 passed (11.0s)
```

## المخاطر والرجوع
الهبات بعد التحديث تتصل بالمرحّل (تسجيل ورموز الأجهزة فقط، والإشعار الخاص يخفي النص). إن تعطّل المرحّل تبقى حالة المرسل
`unreachable` كما هي مصممة. الرجوع: revert، أو `COREHUB_PUSH_RELAY=off` لهب بعينه.

## التسليم والخطوة التالية
ينتظر دمج المالك، ثم يصل مع الإصدار التالي. هب المالك الحالي: `COREHUB_PUSH_RELAY_URL` إلى أن يتحدّث.
