---
inclusion: always
---

# זרימת עבודה מחייבת — A Bloq / SecureGuardMDM

מסמך זה מגדיר את זרימת העבודה הקבועה בפרויקט. יש ליישם אותו בכל משימה שמשנה קוד, תצורה, משאבים או תיעוד.

## הקשר ארכיטקטוני קבוע

לפני שינוי בפרויקט, השתמש במפת הפרויקט כמקור ההקשר הראשי:

#[[file:PROJECT_MAP.md]]

סקריפט הגיבוי המאומת של הפרויקט:

#[[file:backup.ps1]]

הפניה לקובץ מספקת הקשר בלבד ואינה מריצה את הסקריפט.

## שפת עבודה וסגנון תשובה

- יש להשיב בעברית, אלא אם המשתמש ביקש במפורש שפה אחרת.
- קוד, שמות מחלקות, API, נתיבים ופקודות יישארו בשפת המקור שלהם.
- תשובות קצרות וישירות. תוצאה קודם, פרטים רק אם נדרשים.
- אין לתאר כל שלב ביניים, אין לפרט כל בדיקה שהורצה ואין לחזור על מה שכבר נאמר.
- בקש החלטה רק כשהפעולה הרסנית או כשהבקשה עצמה דו-משמעית. אין לבקש אישור חוזר על מה שהמשתמש כבר ביקש במפורש.
- כשהמשתמש מבקש לבצע — בצע. אל תחזיר ניתוח במקום ביצוע.

## זרימת עבודה לכל משימה

1. **הבנת המטרה**
   - זהה את התוצאה שהמשתמש ביקש ואת קריטריוני ההצלחה המעשיים.
   - בדוק את `PROJECT_MAP.md` והשתמש בו כדי לאתר את השכבה והקבצים הרלוונטיים.
   - אל תניח שמידע היסטורי עדיין נכון אם הקוד הנוכחי סותר אותו.

2. **בדיקה לפני שינוי**
   - קרא כל קובץ לפני עריכתו.
   - בדוק דפוסים קיימים, תלויות וזרימת נתונים לפני הוספת מימוש חדש.
   - העדף שינוי ממוקד על פני שכפול מנגנון קיים.
   - אל תדרוס עבודה קיימת של המשתמש או שינויים לא קשורים.

3. **מימוש בטוח**
   - בצע שינוי לוגי אחד לכל קובץ ככל האפשר.
   - שמור על הארכיטקטורה הקיימת: UI → ViewModel → Repository/Provider → Android services/data.
   - אל תוסיף dependency חדשה בלי צורך ברור ובדיקת רישיון/מקור/גרסה.
   - אל תכניס secrets, tokens, keystore passwords או נתוני חשבון לקוד, ללוגים או לגיבוי הרגיל.

4. **אימות — בדיקה אחת ממוקדת, לא סוללת בדיקות**
   - הרץ בדיקה אחת שמכסה את מה שהשתנה, ולא יותר. שינוי Kotlin/Android: build רלוונטי. שינוי PowerShell: parser validation.
   - אל תמציא שכבות אימות שהמשתמש לא ביקש ושהסקריפטים של הפרויקט לא דורשים בעצמם. אין לחשב hash, להשוות payloads או להריץ אימות זהות אלא אם הסקריפט עושה זאת או שהמשתמש ביקש.
   - אל תדווח על כל בדיקה בנפרד. דווח על התוצאה בלבד.
   - אם ה-build נכשל, תקן והרץ שוב לפני הגיבוי.

5. **עדכון מפת הפרויקט**
   - עדכן את `PROJECT_MAP.md` באותה משימה כאשר השתנו:
     - ארכיטקטורה או זרימת נתונים;
     - תפקיד של קובץ או רכיב מרכזי;
     - dependency או גרסת dependency משמעותית;
     - endpoint, חוזה API או מקור עדכונים;
     - מנגנון אבטחה, אחסון, הצפנה או התקנה;
     - התנהגות Device Owner, VPN, Mini Store או מדיניות הגנה.
   - אין צורך לעדכן את המפה עבור שינוי קוסמטי או תיקון מקומי שאינו משנה את המבנה המתועד.

6. **ניקוי לפני סיום**
   - מחק רק קבצים זמניים שנוצרו במהלך המשימה.
   - אל תמחק artifacts, APK, נתוני מכשיר או קבצי משתמש שלא נוצרו במפורש כקבצי בדיקה זמניים.
   - העדף קבצים זמניים תחת `app/build/tmp/agent/`, משום שתיקיית build מוחרגת מהגיבוי.

7. **גיבוי — הפעולה האחרונה**
   - לאחר שהשינוי הושלם ואומת בהצלחה, הרץ את `backup.ps1` כפעולת הכתיבה האחרונה במשימה.
   - אל תגבה מצב שבור, build שנכשל או שינוי שטרם אומת.
   - משימת קריאה/הסבר שלא שינתה את ה-workspace אינה דורשת snapshot חדש.

## חוק גיבוי מחייב

לאחר כל משימה מוצלחת ששינתה קובץ בפרויקט, ולאחר כל עדכון נדרש ל-`PROJECT_MAP.md`, הרץ:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\backup.ps1" -Description "<תיאור קצר>" -NonInteractive
```

כללי התיאור:

- תיאור קצר שמזהה את השינוי, רצוי עד 40 תווים.
- אין לכלול secrets, כתובות דוא"ל, tokens או מידע אישי.
- דוגמאות:
  - `"fix_mini_store_session"`
  - `"add_firewall_rule_ui"`
  - `"update_project_map"`
  - `"refactor_boot_tasks"`

הגיבוי נחשב מוצלח רק כאשר הפלט מאשר:

```text
Backup completed and verified successfully
Verified: True
```

אם הגיבוי נכשל:

1. אל תטען שהמשימה הושלמה במלואה.
2. בדוק את הודעת השגיאה ואת תיקיית `.incomplete-*` אם נוצרה.
3. תקן רק את הגורם הרלוונטי.
4. הרץ את הגיבוי מחדש.
5. דווח למשתמש אם לא ניתן להשלים את הגיבוי.

ברירת המחדל היא **ללא** `-IncludeGitHistory`, כדי להימנע מגיבוי כבד של `.git`. השתמש בפרמטר זה רק אם המשתמש ביקש במפורש snapshot הכולל היסטוריית Git.

## סדר סיום מחייב

```text
1. השלמת המימוש
2. diagnostics / tests / build רלוונטי
3. תיקון כל כשל שנמצא
4. עדכון PROJECT_MAP.md אם המבנה השתנה
5. ניקוי קבצים זמניים
6. הרצת backup.ps1 ואימות Verified=True
7. סיכום קצר למשתמש
```

אם פעולה נוספת משנה קובץ אחרי הגיבוי, הגיבוי כבר אינו snapshot סופי ויש להריץ גיבוי נוסף.

## כללי בטיחות ייחודיים לפרויקט

### Device Owner ומכשירים

- אין להסיר את A Bloq ממכשיר שבו היא Device Owner.
- אין לבצע factory reset, wipe data, הסרת owner או ניקוי נתוני אפליקציה ללא אישור מפורש.
- לפני התקנת APK מעל גרסה קיימת, ודא package תואם, `versionCode` מתאים וחתימה תואמת.
- אין להתקין debug מעל release כאשר החתימות שונות.
- יש לשמר את מצב המכשיר וה-Device Owner במהלך smoke tests.

### Debug על מכשיר חי וצפייה בשינויים

המכשיר הקבוע ששימש לבדיקה הוא:

```text
Serial:       R8YW50PKLHY
Model:        Samsung SM_A145P
Package:      com.secureguard.mdm
MainActivity: com.secureguard.mdm/.MainActivity
```

מצב ה-Device Owner משתנה בין תקופות ואינו מובטח. אין להסתמך על התיעוד; קרא אותו מהמכשיר כשהשינוי נוגע להתקנה או ל-DevicePolicyManager.

לפני שימוש בו, ודא שהוא עדיין מחובר ושה-Device Owner נשמר. אל תניח שה-serial קיים אם `adb devices -l` מציג מכשיר אחר:

```powershell
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Serial = "R8YW50PKLHY"

& $Adb devices -l
& $Adb -s $Serial get-state
& $Adb -s $Serial shell dpm list-owners
& $Adb -s $Serial shell pm path com.secureguard.mdm
```

#### לולאת debug מהירה ומומלצת

Android native אינו מספק Flutter-style Hot Reload. הזרימה שנחשבת כאן "לייב" היא build אינקרמנטלי, התקנת debug מעל אותה חתימה, הפעלה מחדש וצפייה מיידית במכשיר המחובר:

```powershell
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Serial = "R8YW50PKLHY"
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17.0.20"

# אין להריץ clean בכל סבב; Gradle ישתמש ב-incremental build.
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug

# -r שומר נתונים ומעדכן התקנה קיימת; -t מאפשר test/debug APK.
& $Adb -s $Serial install -r -t ".\app\build\outputs\apk\debug\Abloq-debug.apk"

# הפעלה מחדש כדי שהשינוי יוצג מיד.
& $Adb -s $Serial shell am force-stop com.secureguard.mdm
& $Adb -s $Serial shell am start -n com.secureguard.mdm/.MainActivity
```

כללים מחייבים ללולאה:

- אם `install -r -t` מחזיר `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, עצור. אין להסיר את האפליקציה כדי לעקוף חתימה שונה, משום שהיא Device Owner.
- אין להריץ `:app:clean` בכל שינוי; השתמש בו רק כאשר יש ראיה ל-build cache פגום.
- שינויי Manifest, Hilt/DI, resources מורכבים, native libraries או מבנה מחלקות דורשים build והתקנה מחדש; אין להסתמך על Apply Code Changes.
- עבור שינויי method body פשוטים ניתן לנסות Android Studio **Apply Code Changes**, אך אם התוצאה אינה חד-משמעית יש לחזור ללולאת build/install שלעיל.
- לצפייה חזותית השתמש ב-Android Studio **Running Devices / Device Mirroring**. אם `scrcpy` מותקן ניתן להשתמש בו ידנית, אך הוא אינו dependency של הפרויקט.
- לפני ואחרי smoke test אמת שוב `dpm list-owners` כאשר השינוי נוגע להתקנה, provisioning או DevicePolicyManager.

#### צפייה בלוגים

ל-snapshot סופי ומסונן שהסוכן יכול להריץ בלי להשאיר process פתוח:

```powershell
$AppProcessId = (& $Adb -s $Serial shell pidof -s com.secureguard.mdm).Trim()
& $Adb -s $Serial logcat -d --pid=$AppProcessId -v threadtime
```

לצפייה רציפה בזמן עבודה, המשתמש יריץ ידנית ב-Terminal:

```powershell
$AppProcessId = (& $Adb -s $Serial shell pidof -s com.secureguard.mdm).Trim()
& $Adb -s $Serial logcat --pid=$AppProcessId -v color
```

אין להפעיל `logcat` רציף דרך כלי agent שממתין לסיום. הסוכן ישתמש ב-`logcat -d`, בטווח זמן מוגבל או בפלט מסונן, ולא ידפיס tokens, כתובות דוא"ל או נתוני משתמש.

#### בדיקת UI לאחר התקנה

כאשר נדרש לאמת טקסט, ניווט או מצב מסך ללא צילום ידני:

```powershell
& $Adb -s $Serial shell uiautomator dump /sdcard/abloq_ui.xml
& $Adb -s $Serial pull /sdcard/abloq_ui.xml ".\app\build\tmp\agent\abloq_ui.xml"
```

קבצי dump וצילומי בדיקה יישמרו תחת `app/build/tmp/agent/` ולא בשורש הפרויקט.

### חתימה וסודות

ה-release keystore קיים ומחובר ל-build אוטומטית. הוא נמצא **מחוץ** לפרויקט ואינו נכלל ב-`backup.ps1`:

```text
C:\projects\SecureGuardMDM\signing\mafteach-release.jks
C:\projects\SecureGuardMDM\signing\keystore.properties
signer SHA-256: fe079f9df99d5bee1a9610f7cbf3d2d6a2c4823672b8a04ee7f9966c45ba2599
```

- `:app:assembleRelease` נחתם בו בלי פרמטרים נוספים. אין צורך לאמת את החותם בכל build.
- אין להעתיק keystore או passwords לתוך הפרויקט או ל-snapshot רגיל.
- אין ליצור מפתח חדש ואין לחתום במפתח debug; אובדן המפתח או החלפתו מונעים עדכון של התקנת Device Owner קיימת.

### גרסאות ופרסום

```text
מקור האמת: app/build.gradle.kts  →  shippingVersionCode, shippingVersionName
```

- כל פרסום חייב `shippingVersionCode` **גבוה** מהגרסה שפורסמה. `versionName` הוא תצוגתי בלבד ואינו מפעיל עדכון.
- כשהמשתמש מבקש "גרסה חדשה" או "גרסה גבוהה יותר" — העלה את `shippingVersionCode`, לא רק את `versionName`.
- שינוי גרסה נכנס לתוקף רק לאחר מחיקת `app/build/intermediates/merged_manifest` ו-`merged_manifests`.
- פרסום ל-R2 ופריסת האתר הם פעולות ייצור: בצע אותן כשהמשתמש ביקש, בלי לדרוש אישור נוסף על מה שהוא כבר ביקש במפורש.

### Google Play ו-Aurora

- אין להתחזות ל-`com.android.vending`, לחתימת Google או לזהות Aurora כדי לעקוף בקרת גישה.
- אין לשנות User-Agent לזהות מוגבלת רק כדי לעקוף `HTTP 403`.
- ה-dispenser הציבורי של Aurora אינו מקור נתמך עבור A Bloq; פתרון מותר דורש dispenser פרטי/מורשה או מסלול הפצה אחר.
- אין למחוק נתוני Aurora או Google Play Services ללא אישור מפורש.

### Mini Store והתקנה

- שמור על אימות HTTPS, package name, version code, SHA-256 וחתימת APK.
- אל תחליש בדיקות BASE/SPLIT או בדיקות signer כדי לגרום להתקנה לעבור.
- הקטלוג החתום מקבל עדיפות על מקור Google Play כאשר שניהם מספקים עדכון.
- `nophone.apk` הוא runtime input ואסור להסירו כ-build artifact.

### VPN ו-Netfree

- Android מאפשר Always-On VPN יחיד; אין להחליף VPN פעיל בשקט.
- שינוי ב-Firestack, TUN, DNS או routing דורש בדיקה של תעבורה מותרת וחסומה ושל התאוששות לאחר reboot.
- אין לבצע outbound network request שמעביר קוד, secrets או נתוני משתמש לצד שלישי ללא בקשה מפורשת.

## Git — רק לפי בקשה מפורשת

- הפרויקט אינו משתמש ב-Git כחלק אוטומטי מזרימת סיום המשימה.
- אין לבצע `git add`, `commit`, `push`, `pull`, `fetch`, `amend`, `reset`, `clean`, יצירת branch או PR בסיום משימה.
- אין לבצע staging או העלאה ל-remote אחרי גיבוי, build או התקנה.
- פעולת Git תבוצע רק אם המשתמש ביקש אותה במפורש בהודעה הנוכחית, ובהיקף המדויק שביקש.
- גם כאשר המשתמש ביקש Git, אין להשתמש ב-`git add .`; יש להוסיף קבצים ספציפיים בלבד.
- אין למחוק או לשחזר שינויים קיימים שאינם חלק מהמשימה.
- `backup.ps1` הוא מנגנון השמירה הקבוע בסיום משימה; הוא אינו גורר פעולת Git כלשהי.

## קריטריון סיום

משימה ששינתה את הפרויקט נחשבת מוכנה כאשר:

- הבקשה מומשה בפועל;
- הבדיקה הממוקדת עברה;
- `PROJECT_MAP.md` עודכן אם המבנה השתנה;
- נוצר snapshot סופי באמצעות `backup.ps1`;
- הסיכום למשתמש קצר: מה השתנה, והיכן הגיבוי.
