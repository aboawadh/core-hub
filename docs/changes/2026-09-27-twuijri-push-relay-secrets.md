# مرحّل الإشعارات: وضع المفاتيح من أسرار المستودع دون طرفية
المسؤول: twuijri · الفرع: ci/push-relay-secrets · الحالة: review

## المشكلة والهدف
المالك وافق (٢٠٢٦-٠٩-٢٧) على نشر المرحّل على `push.i3u.us`. النشر نجح (run 36313148373) والفحص يجيب
`{"ok":true,"apns":false,"fcm":false,"registration":false}`: تنقصه مفاتيح أبل وقوقل ومفتاح المراكز. المفاتيح نفسها موجودة
أصلًا في أسرار المستودع (`APNS_KEY_P8`، `APNS_KEY_ID`، `APNS_TEAM_ID`، `FCM_SERVICE_ACCOUNT_JSON`)، فلا داعي لأن يشغّل
المالك `wrangler secret put` بنفسه أو يتعامل مع ملف مفتاح.

## القرار والموافقات
خيار `set_secrets` في تشغيل «Push relay» اليدوي: ينقل المفاتيح من أسرار المستودع إلى أسرار الـ Worker عبر stdin (لا سطر أوامر
ولا سجل)، ويضع `APNS_BUNDLE_ID=com.twuijri.corehub` و`APNS_ENV=production`، ويصنع `HUB_SECRET_KEY` مرة واحدة فقط (لا يُستبدل
أبدًا لأن تغييره يقطع كل المراكز). `ADMIN_TOKEN` يبقى للمالك.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
`.github/workflows/push-relay.yml` (مدخل وخطوة)، `packages/push-relay/README.md`.

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ python3 -c "import yaml; yaml.safe_load(open('.github/workflows/push-relay.yml'))"
yaml ok
```

## المخاطر والرجوع
لا يتغير شيء إن لم يُختر `set_secrets`. الرجوع بإرجاع الطلب.

## التسليم والخطوة التالية
بعد الدمج: تشغيل «Push relay» يدويًا مع `set_secrets`، ثم التحقق من `/v1/health`، ثم طلب يضع `DEFAULT_RELAY_URL=https://push.i3u.us`.
