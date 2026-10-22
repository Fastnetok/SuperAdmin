# تفصیلی تکنیکی جائزہ رپورٹ (Detailed Technical Analysis Report)

آپ کی ہدایات کی روشنی میں پرانے پروجیکٹ (`C:\Users\Dell\Desktop\EboneAdminPanel`) اور موجودہ ملٹی کمپنی پروجیکٹ کی متعلقہ فائلوں (`OfficeGeofenceActivity.kt`, `OfficeTimmingsActivity.kt`, `BiometricAttendanceActivity.kt`, `DistanceCalculator.kt`) کا گہرائی سے جائزہ لیا گیا ہے۔ شواہد درج ذیل ہیں:

---

### ۱۔ پرانے اور موجودہ پروجیکٹ کا موازنہ
- **پرانے پروجیکٹ میں:** جیو فینس اور اٹینڈنس کا نظام سنگل-کمپنی بنیاد پر تھا (`geofences`, `GeoFenceActivity.kt`). تمام ڈیٹا ایک ہی فائر بیس ڈیٹا بیس میں بغیر کمپنی آئی ڈی کی تقسیم کے محفوظ ہوتا تھا۔
- **موجودہ پروجیکٹ میں:** یہ پروجیکٹ ملٹی کمپنی (SuperAdmin / EboneAdminPanel) ہے جو کہ `CompanyDataHelper` اور `SessionManager` کے ذریعے مختلف کمپنیوں (جیسے `OKA-001`, `DEP-001`) کو سپورٹ کرتا ہے۔ فائر بیس میں جیو فینس اور سیٹنگز اب کمپنی وائز نوڈز (`attendanceGeofence/{compId}` اور `officeSettings/{compId}`) میں محفوظ ہوتی ہیں۔

---

### ۲۔ آفس اٹینڈنس کی اصل منطق
- **محفوظ کرنے کا طریقہ:** `OfficeGeofenceActivity.kt` میں `saveGeofence()` کے ذریعے فائر بیس کے `attendanceGeofence/{compId}` میں `lat`, `lng`, اور `radius` محفوظ کیے جاتے ہیں۔
- **تصدیق (Validation):** `OfficeGeofenceActivity.kt` کے فنکشن `loadEmployeesWithAttendance()` اور `BiometricAttendanceActivity.kt` میں `DistanceCalculator.distanceBetween(empLat, empLng, geoLat, geoLng)` کے ذریعے ملازم کی لوکیشن کا آفس کے مرکز سے فاصلہ ناپا جاتا ہے۔
- اگر فاصلہ `geoRadius` سے زیادہ ہو (مثلاً آفس لاہور سیٹ ہو اور ملازم اوکاڑہ میں ہو)، تو اٹینڈنس کو مسترد کر کے `❌ Outside Geofence (Rejected)` ظاہر کیا جاتا ہے۔

---

### ۳۔ کمپلین لوکیشن اٹینڈنس کی اصل منطق
- **ریڈیئس کی ترتیبات:** `OfficeTimmingsActivity.kt` میں کمپلین کا ریڈیئس `complaintRadiusMeters` کے نام سے فائر بیس کے `officeSettings/{compId}` میں محفوظ کیا جاتا ہے (ڈیفالٹ 500 میٹر، جسے مجاز صارف `showMeterPicker()` کے ذریعے تبدیل کر سکتا ہے)۔
- **علیحدگی (Separation):** آفس اٹینڈنس کے لیے `attendanceGeofence/{compId}` استعمال ہوتا ہے، جبکہ کمپلین اٹینڈنس کے لیے کمپلین کی اپنی لوکیشن اور `complaintRadiusMeters` استعمال ہونا چاہیے۔ ایک کی تبدیلی سے دوسرا متاثر نہیں ہوتا۔

---

### ۴۔ غلط لوکیشن پر اٹینڈنس مسترد ہونے کا پیغام (`Not at office or not at complain location`)
- جب ملازم نہ تو آفس کے جیو فینس کے اندر ہو اور نہ ہی اسائن کردہ کمپلین لوکیشن کے ریڈیئس کے اندر ہو، تو پرانے نظام کے مطابق درج ذیل پیغام ظاہر ہونا چاہیے:
  > **"Not at office or not at complain location"**

---

### ۵۔ کمپنی وائز علیحدگی (Company-wise Isolation)
- ہر کمپنی (`compId`) کا ڈیٹا فائر بیس میں الگ نوڈز پر محفوظ ہوتا ہے:
  - آفس جیو فینس: `attendanceGeofence/{compId}`
  - آفس سیٹنگز و کمپلین ریڈیئس: `officeSettings/{compId}`
- `CompanyDataHelper.getCurrentCompanyId(context)` اور `SessionManager` کے ذریعے ہر کمپنی کو اس کی اپنی ترتیبات خودکار طور پر دستیاب ہوتی ہیں، جس سے ایک کمپنی کی تبدیلی دوسری کمپنی پر اثر انداز نہیں ہوتی۔ 새로 بننے والی کمپنیوں کو بھی یہی ڈھانچہ स्वतः (Automatically) ملتا ہے۔

---

### ۶۔ اصل خرابی اور متعلقہ فائلیں (Root Cause & Key Files)

| فائل کا نام | فنکشن / طریقہ | مقصد |
| :--- | :--- | :--- |
| `OfficeGeofenceActivity.kt` | `loadEmployeesWithAttendance()` / `saveGeofence()` | آفس کی لوکیشن اور جیو فینس ریڈیئس محفوظ کرنا اور اٹینڈنس کی جانچ کرنا۔ |
| `OfficeTimmingsActivity.kt` | `loadSettings()` / `saveSettings()` / `showMeterPicker()` | کمپلین کا قابلِ تبدیلی ریڈیئس (`complaintRadiusMeters`) سیٹ اور محفوظ کرنا۔ |
| `BiometricAttendanceActivity.kt` | `showTodayAllEmployeesAttendance()` / `loadAttendanceReport()` | روزانہ اور ماہانہ رپورٹس میں جیو فینس کی توثیق۔ |
| `DistanceCalculator.kt` | `distanceBetween()` | دو جی پی ایس پوائنٹس کے درمیان فاصلہ میٹرز میں معلوم کرنا۔ |

**خرابی کی وجہ:** آفس کی لوکیشن بدلنے کے باوجود کچھ رپورٹس یا پرانے چیکس میں کمپلین اور آفس کی توثیق کا ملاپ درست نہیں تھا اور غلط لوکیشن پر مسترد ہونے کا مخصوص پیغام (`Not at office or not at complain location`) مکمل طور پر نافذ نہیں تھا۔

---
*نوٹ: آپ کی سخت ہدایت کے مطابق تاحال کسی بھی سورس فائل میں کوئی تبدیلی نہیں کی گئی۔ اب آپ اس رپورٹ کا جائزہ لے کر تبدیلی کی اجازت دے سکتے ہیں۔*
