# رابط التسويق في متجر أبل: صفحة التحميل
المسؤول: twuijri · الفرع: chore/ios-marketing-url · الحالة: review

## المشكلة والهدف
المالك (٢٠٢٦-٠٩-٢٧) عند تعبئة TestFlight: رابط التسويق يكون صفحة التحميل https://twuijri.github.io/core-hub/ لا صفحة المستودع.

## القرار والموافقات
قرار المالك: `marketing_url.txt` للعربية والإنجليزية = صفحة التحميل.

## العقد (ما تغيّر في packages/contracts، أو «لا شيء»)
لا شيء.

## الملفات والتأثير
`apps/ios/fastlane/metadata/{en-US,ar-SA}/marketing_url.txt`. يصل مع رفع بيانات المتجر التالي («iOS store listing»).

## الفحوص (الأوامر ونواتجها الفعلية)
```
$ cat apps/ios/fastlane/metadata/en-US/marketing_url.txt
https://twuijri.github.io/core-hub/
```

## المخاطر والرجوع
لا شيء. الرجوع بإرجاع الطلب.

## التسليم والخطوة التالية
طلب إلى `main`.
