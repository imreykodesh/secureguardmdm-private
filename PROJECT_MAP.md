# מפת פרויקט A Bloq / SecureGuardMDM

מסמך זה מרכז את מבנה הפרויקט והזרימות הטכניות שאומתו במהלך העבודה על הקוד ועל המכשיר המחובר. אזורים שלא נותחו במלואם מסומנים במפורש.

## 1. תמונה כללית

```text
A Bloq
├── ניהול מכשיר והרשאות Device Owner
├── הפעלת מדיניות הגנה
├── חסימת אפליקציות
├── הגדרות ושמירה מקומית
├── משימות אתחול
├── מנגנון VPN / Netfree
└── Mini Store
    ├── הצגת אפליקציות מותקנות
    ├── קטלוג עדכונים חתום
    ├── גילוי עדכונים דרך Google Play כאשר מוגדר endpoint מורשה
    ├── הורדת BASE + SPLIT APK
    └── אימות והתקנה
```

זהו פרויקט Android יחיד, הכתוב בעיקר ב-Kotlin ומשלב:

- Jetpack Compose ומסכי Android רגילים.
- Hilt להזרקת תלויות.
- Room למסד נתונים.
- Preferences לשמירת הגדרות מקומיות.
- Android DevicePolicyManager לניהול המכשיר.
- Kotlin Coroutines.
- OkHttp ו-Ktor לתקשורת.
- `gplayapi` לתקשורת הלא-רשמית מול Google Play.
- Android Keystore להצפנת Google Play session.
- Firestack עבור מנגנון ה-VPN הפנימי.

## 2. הגדרות build מרכזיות

קובץ: `app/build.gradle.kts`

```text
applicationId: com.secureguard.mdm
namespace:     com.secureguard.mdm
minSdk:        23
targetSdk:     34
compileSdk:    34
versionCode:   7
versionName:   0.6
Java/Kotlin:   JVM 17
```

זהות הגרסה נשמרת ב-`app/build.gradle.kts` תחת `shippingVersionCode`/`shippingVersionName`, והיא ברירת המחדל בכל build. לצורך בדיקת זרימת העדכון העצמי בלבד ניתן לעקוף אותה ב-opt-in מפורש, באותו דפוס של `enableReleaseShrinking`:

```powershell
.\gradlew.bat :app:assembleDebug "-PoverrideVersionCode=3" "-PoverrideVersionName=0.4.6"
```

כך נבנה APK "גשר" עם קוד עדכני אך גרסה נמוכה, שמאפשר לאמת שהאפליקציה המותקנת אכן מזהה, מורידה ומתקינה גרסה חדשה יותר. ה-override חייב להיות `versionCode` חיובי ו-`versionName` לא ריק. שינוי גרסה משתקף רק לאחר מחיקת `app/build/intermediates/merged_manifest` ו-`merged_manifests`, משום ש-AGP מחזיק את המניפסט הראשי ב-cache; `-Pandroid.injected.version.code` אינו משפיע בתצורה הזו. אין להשתמש ב-override לפרסום.

תלויות מרכזיות:

```kotlin
implementation("com.auroraoss:gplayapi:3.6.4")
implementation("androidx.work:work-runtime:2.9.1")
implementation("com.github.celzero:firestack:61894b7fdba9405be49c593927f51470c0979797@aar")
```

ה-build משלב גם Compose, Hilt, Room, Ktor, OkHttp, Gson, Material ו-Bouncy Castle. `WorkManager` משמש לתורי בדיקת עדכונים עמידים לאחר שינוי package; הגרסה מקובעת ל-`2.9.1` כדי להישאר תואמת ל-`compileSdk 34`.

Release נשאר ללא minification כברירת מחדל. לבדיקת candidate בלבד ניתן להפעיל `-PenableReleaseShrinking=true`, שמדליק יחד R8 ו-resource shrinking ומשתמש ב-`proguard-rules.pro` לשימור מודלי Gson הרפלקטיביים. במדידה מקומית מ-2026-08-17 ה-APK ירד מ-126,327,988 bytes ל-110,793,683 bytes (חיסכון 15,534,305 bytes, כ-12.3%); ברירת המחדל לא שונתה משום שה-release keystore ההיסטורי חסר ולא ניתן לבצע smoke test חתום על Device Owner. ארבעת ה-ABI ו-`assets/nophone.apk` נשמרו.

Google Play ב-Mini Store כבוי כברירת מחדל. הפעלה דורשת זוג ערכים בזמן build: `BuildConfig.MINI_STORE_PLAY_DISPENSER_URL` ו-`BuildConfig.MINI_STORE_PLAY_CLIENT_TOKEN`. הם מתקבלים בהתאמה מ-Gradle properties בשם `miniStorePlayDispenserUrl`/`miniStorePlayClientToken` או מ-environment variables בשם `MINI_STORE_PLAY_DISPENSER_URL`/`MINI_STORE_PLAY_CLIENT_TOKEN`. ה-build דורש שהשניים יוגדרו יחד, שה-token יכיל לפחות 32 תווים ללא whitespace, ושה-endpoint יהיה HTTPS; hostname מנורמל ו-`auroraoss.com` ותת-הדומיינים שלו חסומים, כולל צורות עם trailing dot. ערך ה-client token אינו נכתב למקור, אך הוא מוטמע ב-APK ולכן הוא בקרת גישה בסיסית ולא secret בעל חסינות גבוהה בפני reverse engineering.

## 3. מבנה מקור ברמה גבוהה

```text
app/src/main/
├── AndroidManifest.xml
├── assets/
│   └── nophone.apk
├── java/com/secureguard/mdm/
│   ├── appblocker/
│   ├── boot/
│   ├── data/
│   ├── di/
│   ├── features/
│   └── ministore/
└── res/
    ├── values/
    ├── values-en/
    ├── raw/
    └── xml/
```

## 4. Device Owner ו-MDM

```text
Android System
      │
      ▼
SecureGuardDeviceAdminReceiver
      │
      ▼
DevicePolicyManager
      │
      ├── מדיניות הגנה
      ├── הגנת אפליקציות מערכת
      ├── מניעת הסרת רכיבים חיוניים
      ├── התקנת עדכונים
      └── הגדרות Kiosk ואפליקציות מוגנות
```

מצב שאומת במכשיר הבדיקה:

```text
Device:       Samsung SM_A145P
Serial:       R8YW50PKLHY
Device Owner: com.secureguard.mdm/.SecureGuardDeviceAdminReceiver
```

ה-Mini Store מתייחס לחבילות הבאות כמוגנות מהסרה:

- A Bloq עצמה.
- אפליקציות Device Admin פעילות.
- אפליקציית הבית הנוכחית.
- החייגן הראשי.
- אפליקציות Kiosk שהוגדרו ב-Preferences.
- אפליקציות מערכת.

## 5. מנגנון תכונות ההגנה

```text
features/
├── api/
│   └── ProtectionFeature.kt
└── impl/
    ├── BlockAddUserFeature.kt
    ├── BlockAdjustVolumeFeature.kt
    └── עשרות מימושי מדיניות נוספים
```

המבנה מבוסס על ממשק משותף ומימושים עצמאיים:

```text
ProtectionFeature
      ├── Feature implementation A
      ├── Feature implementation B
      └── Feature implementation ...
```

בתיקיית `features/impl` נמצאו עשרות מימושי Kotlin. כל מימוש אחראי למדיניות הגנה ממוקדת. המיפוי הפנימי המלא של כל המדיניות טרם בוצע.

## 6. חסימת אפליקציות

```text
appblocker/
├── AppInfo.kt
├── AppBlockerState.kt          # כולל AppStatusFilter ואירועי סיסמה/הסרה
├── AppBlockerViewModel.kt
└── ui/
    └── AppSelectionScreen.kt   # מסך אחד: חסימה, השבתה, שחרור והסרה
```

זרימת המידע:

```text
AppSelectionScreen  (לשונית "חסימה והסרה")
                  │
                  ▼
         AppBlockerViewModel
                  │
                  ├── PasswordManager + MiniStoreAccessGate (שער כניסה)
                  ├── טעינת אפליקציות מותקנות
                  ├── סינון לפי מצב: הכול / חסומות / מושבתות
                  ├── חסימה, השבתה, שחרור חסימה, ביטול השבתה
                  └── MiniStorePackageOperator.uninstall (הסרה)
                  │
                  ▼
       Room / Preferences / MDM policy
```

- **מסך אחד לכל הפעולות.** `BlockedAppsScreen` בוטל ותוכנו מוזג לתוך `AppSelectionScreen`; במקום שני מסכים עם רשימות דומות יש רשימה אחת עם שבבי סינון (`AppStatusFilter`), כדי שלא יהיה ספק לאיזו רשימה השינוי נכנס. הרשימות של חסומות/מושבתות נשענות על ה-master lists הנפרדות, כך שגם אפליקציה שהוסרה מהמכשיר או אפליקציית מערכת עדיין ניתנת לשחרור.
- **סיסמה בכניסה.** הכניסה ללשונית תמיד דורשת את סיסמת הניהול, גם כאשר `mini_store_require_password` כבוי, כי כאן נקבעת מדיניות. חלון הרשאה פעיל של Mini Store (`MiniStoreAccessGate`, 5 דקות) נחשב כאישור ולא מבקש סיסמה פעמיים. כאשר לא הוגדרה סיסמה כלל, המסך אינו נחסם.
- **הסרה עברה לכאן.** ההסרה הוצאה מכרטיסי העדכון ב-Mini Store ומופיעה בכרטיס האפליקציה במסך זה, כדי שלחיצה הרסנית לא תשב ליד לחיצת תחזוקה. המימוש מפנה ל-`MiniStorePackageOperator.uninstall`, כך שאותן בדיקות ממשיכות לחול: לא אפליקציית מערכת, לא חבילה שהמכשיר תלוי בה, ולא A Bloq עצמה.
- **שלוש פעולות ראשיות בכרטיס.** הסרה, השבתה וחסימה, ברוחב שווה. הכפתור מציג את הפעולה ההפוכה כאשר האפליקציה כבר חסומה או מושבתת (`שחרר חסימה` / `בטל השבתה`), במקום תיבת סימון שמשמעותה משתנה לפי הסינון הפעיל. אין אייקונים בתוך הכפתורים, כי תווית עברית ארוכה בשליש כרטיס נדחסת לעמודת אותיות.
- **מראה זהה לעדכונים.** הכרטיס באותה פריסה כמו כרטיס העדכון (אייקון 48dp, שם מודגש, תג "מערכת", שם חבילה), כך שהמעבר בין הלשוניות אינו נראה כמעבר בין שתי אפליקציות.
- **טעינת הרשימה.** מעבר אחד על `PackageManager` לכל שלוש הרשימות. קודם לכן כל רשימה נטענה בנפרד: שלוש קריאות `queryIntentActivities` ופענוח חוזר של תווית ואייקון לכל אפליקציה חסומה או מושבתת. כעת התוויות והאייקונים מפוענחים במקביל ב-chunks, הרשימה הראשית מתפרסמת לפני חישוב הרשימות הנגזרות, והרשימות החסומות/מושבתות נבנות מאותו snapshot; `PackageManager` נשאל שוב רק עבור אפליקציה שאינה מותקנת וקיימת ב-cache בלבד. זמן הטעינה נרשם ללוג `AppBlockerViewModel: loaded N apps in Xms`.
- **הודעת "לא ניתן להסיר".** מוצגת כאן בלבד, ליד כפתור ההסרה שהיא מסבירה. היא הוסרה מכרטיסי לשונית העדכונים, שבהם אין כפתור הסרה.

רכיבי Room הרלוונטיים:

```text
data/db/
├── AppDatabase.kt
├── BlockedAppCache.kt
└── BlockedAppCacheDao.kt
```

## 7. משימות אתחול

```text
boot/
├── BootCompletedReceiver.kt
├── api/
│   └── BootTask.kt
├── registry/
│   └── BootTaskRegistry.kt
└── impl/
    ├── NetfreeWatchdogBootTask.kt
    └── ShowToastOnBootTask.kt
```

זרימת האתחול:

```text
BOOT_COMPLETED
      │
      ▼
BootCompletedReceiver
      │
      ▼
BootTaskRegistry
      │
      ├── NetfreeWatchdogBootTask
      └── משימות רשומות נוספות
```

המבנה מאפשר להוסיף משימות אתחול בלי לרכז את כל הלוגיקה בתוך ה-BroadcastReceiver.

## 8. שכבת הנתונים וההגדרות

```text
data/
├── db/
│   ├── AppDatabase.kt
│   ├── BlockedAppCache.kt
│   └── BlockedAppCacheDao.kt
├── local/
│   └── PreferencesManager.kt
├── model/
│   └── NetfreeUser.kt
└── repository/
    ├── SettingsRepository.kt
    └── SettingsRepositoryImpl.kt
```

הזרימה הכללית:

```text
UI / ViewModel
      │
      ▼
SettingsRepository
      │
      ▼
SettingsRepositoryImpl
      │
      ├── PreferencesManager
      ├── Room
      └── Android system services
```

`PreferencesManager` משמש בין היתר לשמירת קבוצות package names, הגדרות Kiosk והגדרות מקומיות שאינן דורשות טבלת Room מלאה.

## 9. Dependency Injection

קובץ מרכזי: `di/AppModule.kt`

Hilt משמש ליצירת והזרקת רכיבים, ובהם:

```text
AppModule / @Inject
      ├── SettingsRepository
      ├── MiniStoreRepository
      ├── InstalledPackageInventoryProvider
      ├── MiniStoreUpdateCoordinator
      ├── MiniStoreUpdateCheckDao
      ├── PlayUpdateSource
      ├── GPlayHttpClient
      ├── PlayCredentialStore
      └── רכיבי מערכת נוספים
```

## 10. Mini Store — מבט על

```text
Mini Store UI
      │
      ▼
ViewModel
      │
      ▼
MiniStoreRepository
      │
      ├── Signed Catalog
      │     └── מקור עדכונים שבשליטת A Bloq
      │
      ├── PlayUpdateSource
      │     └── Google Play, פעיל לאחר התחברות חשבון על המכשיר
      │
      ├── MiniStorePreferences
      │     ├── blacklist — אפליקציות מוחרגות מהצגה ומעדכון
      │     └── דרישת סיסמה
      │
      └── InstalledPackageInventoryProvider
            └── PackageManager ללא תלות ב-UI
```

### התנהגות מסך Mini Store

- **העדכון של מפתח עצמה מוצג בראש הרשימה כ"מועדף".** `MiniStoreRepository.loadSelfUpdate()` בודק את ערוץ העדכון העצמי דרך `UpdateManager.checkForUpdate()` ומחזיר `MiniStoreLoadResult.selfUpdate` — רשומת `ManagedInstalledApp` נפרדת עם `UpdateSource.SELF_UPDATE` ו-`UpdateLocator.SelfUpdate(info)`. הבדיקה נפתחת ב-`async` בתחילת `loadInstalledApps()` ונאספת בסוף, כדי שה-latency שלה לא ייווסף לקטלוג ול-Play; כשל בה הופך ל-`sourceWarning` ולא לכשל טעינה. הסינון `filterNot { packageName == context.packageName }` במלאי **לא** שונה, וכל המחסומים (`assertUpdateAllowed`, `assertUninstallAllowed`, `protectedPackages()`, ו-`validatePlan` שחוסם `plan.packageName == context.packageName`) נשארו כפי שהם. שלוש החלטות מחייבות:
  - **המסלול המוקשח של החנות אינו מתקין את מפתח.** `MiniStoreViewModel.update()` מסתעף לפי `candidate.source`: `SELF_UPDATE` מגיע ל-`startSelfUpdate()` שאוסף `UpdateManager.downloadAndInstallUpdate(info)` וממפה `DownloadProgress` ל-`UpdateOperationStage` (`apkSize` נותן את ה-total). `MiniStorePackageOperator.resolvePlan` ו-`isTrustedHost` מכילים ענפי הגנה בלבד עבור המקור הזה (`error(...)` ו-`false`).
  - **מפתח אינה חלק מ"עדכן הכול".** הרשומה אינה ב-`allApps`, ולכן אינה ב-`updatableApps`, ב-`updateCount` או ב-`updateAll()`. עדכון עצמי מחליף את התהליך מיד לאחר commit, וכל השאר בתור היה נעלם בלי הסבר. `startSelfUpdate` דורש חנות במצב לא-עסוק.
  - **אותה מדיניות עדכונים כמו במסך הראשי.** הרשומה נבנית רק כאשר `!areAllUpdatesDisabled() || (isSettingsLocked() && allowManualUpdateWhenLocked())`, כדי שהחנות לא תהפוך לדרך לעקוף מכשיר שבו העדכונים הושבתו.
  - ביטול: `activeUpdateJob` הוא אותו handle כמו בעדכון רגיל, ולכן הביטול אפשרי בשלב ההורדה ונדחה מ-`INSTALLING`. לאחר commit מוצלח ייתכן שלא תרוץ הודעת סיום ולא `refresh()` — זה מצב תקין.
  - סימון ויזואלי: `MiniStoreAppCard(preferred = true)` — `tertiaryContainer` ותג כוכב "מועדף". הכרטיס אינו מציג שם מקור (`UpdateSource.SELF_UPDATE -> null` ואז `mini_store_available_version`), ואין בו כפתור החרגה.
- **לשונית העדכונים מציגה עדכונים בלבד.** הרשימה נבנית מ-`updatableApps` (`visibleApps` שיש להן `update != null`) ולא מכל האפליקציות המותקנות. קודם לכן הלשונית הציגה את כל המלאי עם שבב `עדכונים` בין שאר השבבים, כך שהמשתמש היה צריך לחפש בתוך רשימה של מאות אפליקציות את הדבר היחיד שהמסך קיים בשבילו. `MiniStoreCategory` צומצם ל-`ALL/USER/SYSTEM`, ושלושת השבבים הם תלויות בתוך קבוצת העדכונים: הכול, עדכוני אפליקציות משתמש, עדכוני אפליקציות מערכת. שורת השבבים מוצגת רק כאשר `categoryFilterVisible`, כלומר כשקיימים עדכונים בשני הצדדים; אחרת שלושה שבבים היו מובילים לאותה רשימה. גם החיפוש ו-`updateAll()` פועלים על אותה קבוצה. כאשר אין עדכונים מוצג `mini_store_no_updates`. הסתרת אפליקציה שאין לה עדכון נעשית מפאנל הרשימה השחורה, שממשיך להציג את כל המלאי (`allApps`).
- **תור עדכונים.** לחיצה על עדכון בכמה אפליקציות מכניסה את כולן לתור שמעובד סדרתית, כי Android מתקין חבילה אחת בכל session. כפתורי העדכון נשארים פעילים בזמן פעולה; הסרה ושינוי רשימה שחורה חסומים.
- **ביטול.** אפליקציה בתור נשלפת מהתור; פעולה רצה מבוטלת. ההורדה נעצרת בין מקטעי קריאה, כי קריאת רשת חוסמת אינה מגיבה לביטול מעצמה. כפתור `בטל` זמין גם בכרטיס ההתקדמות הקבוע למעלה, לצד שם האפליקציה הפעילה; `בטל הכול` מוצג שם כאשר יותר מאפליקציה אחת ממתינה. **שלב `INSTALLING` אינו ניתן לביטול** — לאחר commit התוצאה בידי Android.
- **התקדמות.** מוצגת על כרטיס האפליקציה עצמה ובכרטיס קבוע מתחת לסרגל, עם אחוזים ומגה־בייטים בזמן הורדה.
- **רשימה שחורה.** ניתן להחריג אפליקציה ישירות מהכרטיס שלה, או מפאנל ניהול שבו המוחרגות מוצגות ראשונות. אפליקציה מוחרגת אינה מוצגת, אינה נבדקת ואינה מתעדכנת. **אין דרישת סיסמה** — הסתרה מרשימת העדכונים הפיכה ואינה משנה מה מותר להפעיל, ולכן היא באותה רמת גישה כשאר החנות.
- **סטטוס בדיקה אמיתי בלבד.** `updateCheckComplete` דורש שכל מקור שיכול להחזיק עדכון אכן ענה עבור החבילה: קטלוג `CHECKED` **וגם** Play `CHECKED` והחבילה אינה ב-`failedPackages`. קודם לכן מצב "אין חשבון Google" נחשב כ"אין מה לבדוק", ולכן הוצג "✅ הבדיקה הושלמה" בזמן שאפליקציות Play לא נבדקו בכלל. `PlaySourceState.DISABLED` הוחלף ב-`SIGNED_OUT`, ומצב זה מוצג כבדיקה שלא הושלמה עם קריאה להתחברות.
- **הסרה אינה במסך זה.** פעולות הסרה עברו ללשונית "חסימה והסרה" (`AppSelectionScreen`), כדי להפריד תחזוקה ממדיניות.
- **שתי לשוניות.** `AppCenterTabs` עם אייקונים וקטוריים (`SystemUpdate`, `Shield`): "עדכונים" ו"חסימה והסרה". הכניסה ללשונית החסימה מוגנת בסיסמה גם כשהחנות עצמה אינה.
- **הרשאת ניהול.** מוחזקת ב-`MiniStoreAccessGate` יחיד עם תוקף של 5 דקות, ולא ב-ViewModel. מעבר בין מסכים אינו מבטל אותה; יציאה מהחזית כן. ההאזנה היא למחזור החיים של התהליך, לא של רשומת הניווט.
- **רענון לאחר חזרה.** מצב החשבון נקרא בכל חזרה לחזית, ורענון מתבצע רק אם החשבון השתנה.
- **חיפוש.** אינו שדה קבוע מעל הרשימה אלא אייקון בפינת כרטיס "האפליקציות שלך", שנפתח לשדה בלחיצה ונסגר כשהוא מתרוקן.
- **נוסח הממשק.** קצר ומבוסס אייקונים. אין להציג מונחים כמו "קטלוג חתום", שם המקור או פרטי אימות למשתמש; מצב המקורות מוצג כסימן קצר.

### מקור Google Play — עובד באמצעות התחברות על המכשיר

מקור Google Play **פועל ואומת end-to-end על מכשיר הבדיקה**: גילוי עדכונים, הורדת BASE ו-SPLIT, אימות והתקנה אטומית כ-Device Owner.

הקבצים המרכזיים:

```text
ministore/play/PlayAccountSession.kt     — יצירת session, חידושו ושמירתו
ministore/play/PlayCredentialStore.kt    — הצפנה/פענוח של ה-session ב-Keystore
ministore/play/PlaySessionAudit.kt       — תיעוד עמיד של כל שינוי מצב ב-session
ministore/play/DeviceGoogleAccounts.kt   — זיהוי חשבון Google שבמכשיר
ministore/ui/PlayLoginScreen.kt          — התחברות ב-WebView בתוך A Bloq
ministore/ui/PlayLoginViewModel.kt       — לכידת האישור וההמרה
```

### שמירת החשבון לאורך זמן ואבחון ניתוקים

ה-session מכיל שני סוגי אישורים: **AAS token** ארוך-טווח, ו-Play tokens נגזרים (`authToken`, `deviceConfigToken`) שתוקפם קצר. כאשר הנגזרים פגו, Google מחזיר verdict של אישורים על בקשה רגילה.

- **חידוש לפני ניתוק.** בכל דחיית אישורים מתבצע קודם `PlayAccountSession.refresh()` שבונה session חדש מה-AAS token השמור (`AuthHelper.Token.AAS`), והפעולה חוזרת פעם אחת. רק דחייה ששרדה את החידוש נספרת מול מפתן ההתנתקות. קודם לכן שתי דחיות רצופות מחקו את כל ה-session, כולל AAS token תקף — זו הסיבה שהחשבון "התנתק אחרי כמה שעות". החידוש חל גם על `resolve()`, כדי שעדכון שכבר אושר לא ייפול בין הגילוי להורדה.
- **אין מחיקה שקטה בקריאה.** `PlayCredentialStore.load()` אינו יוצר מפתח Keystore חדש בקריאה (מפתח חדש ממילא אינו יכול לפענח נתונים קיימים, וכך נמחק קובץ תקין), ואינו מוחק את הקובץ על כל שגיאה. כשל בלתי-הפיך בלבד (`AEADBadTagException`, שגיאת serialization, פורמט פגום) מוחק; כשל זמני שומר את הקובץ לניסיון הבא.
- **תיעוד עמיד.** `PlaySessionAudit` כותב כל מצב ל-`files/mini_store_play_audit.log` עם חותמת זמן UTC, `uptime`, גיל ה-session וסיבה. הקובץ שורד מחיקת session, אתחול תהליך ו-reboot, ואינו מכיל tokens או כתובות דוא"ל (מסונן לפני כתיבה). אירועים: `SIGN_IN_OK`, `SESSION_LOADED`, `SESSION_ABSENT`, `CREDENTIAL_REJECTED`, `REFRESH_ATTEMPT/OK/FAILED/UNAVAILABLE`, `SESSION_INVALIDATED`, `SESSION_UNREADABLE_CLEARED`, `SESSION_LOAD_TRANSIENT_KEPT`, `KEYSTORE_KEY_MISSING_CLEARED`, `USER_SIGN_OUT`.

קריאת התיעוד:

```powershell
& $Adb -s $Serial shell "run-as com.secureguard.mdm cat files/mini_store_play_audit.log"
```

סיכום המצב האחרון נשמר גם ב-`shared_prefs/mini_store_play_diagnostics.xml`, וממנו נגזרת הודעה למשתמש בכרטיס המקורות (למשל "🔑 החיבור לחשבון נדחה על ידי Google ב-<תאריך>") במקום בקשת התחברות בלי הסבר.

מה שאומת ומה שלא, כדי שלא ייקרא כהבטחה:

| פריט | מצב |
|---|---|
| מחיקת קובץ ה-session במכשיר בזמן הניתוק | אומת: `mini_store_play_auth.bin` נעדר מ-`no_backup` |
| קיום ה-API לחידוש ב-`gplayapi 3.6.4` | אומת ב-`javap`: `AuthData.getAasToken()`, `AuthHelper$Token.AAS` |
| יצירת יומן האבחון וכתיבה אליו במכשיר | אומת: השורה הראשונה שנרשמה היא `SESSION_ABSENT` |
| הצלחת חידוש מול Google במכשיר | **טרם אומת** — נדרש session פעיל שהטוקנים הנגזרים שלו פגו |
| שהניתוק נבע מ-`invalidate()` לאחר שתי דחיות | **הסבר סביר, לא עובדה** — ההכרעה תגיע מהיומן |

הניתוח המלא, טבלת האירועים ותרחישי האימות נמצאים ב-`PRIVATE_PLAY_UPDATE_PLAN.md` סעיף **23**.

זרימת ההתחברות:

```text
כפתור "התחבר לחשבון Google" ב-Mini Store
      │
      ▼
WebView בתוך A Bloq → accounts.google.com/EmbeddedSetup?Email=<חשבון המכשיר>
      │  אין מעורבות של אפליקציית Play Store
      ▼
לכידת cookie בשם oauth_token דרך CookieManager
      │  ה-cookie הוא HttpOnly ולכן אינו נגיש ל-JavaScript, אך כן לאפליקציה
      ▼
AuthHelper.build(email, oauthToken, Token.AUTH, properties, locale)
      │  gplayapi מבצע check-in ומנפיק AAS ו-session מזהות המכשיר עצמו
      ▼
PlayCredentialStore — AES/GCM ב-Android Keystore, תחת noBackupFilesDir
```

**כתובת החשבון נקבעת לפי מסך ההתחברות, לא לפי חשבון המכשיר.** חשבון המכשיר מ-`AccountManager` הוא **hint ל-URL בלבד** (`?Email=`), כדי לדלג על בחירת חשבון. `PlayLoginViewModel` אינו מזריע יותר את `email` בחשבון המכשיר, ו-`onEmailDiscovered()` דורס כל כתובת קיימת בכתובת שנקראה מהדף. קודם לכן ההפוך היה נכון: ה-state הוזרע בחשבון המכשיר ו-`onEmailDiscovered` סירב לדרוס אותו, ולכן `onTokenCaptured` ביצע את ה-exchange עם כתובת חשבון המכשיר ברגע שה-cookie הופיע. התוצאה הייתה ש-`AuthData.email` — ומכאן גם `playSignedInEmail` בכרטיס המקורות — הציג את חשבון המכשיר גם כשהמשתמש התחבר בפועל בחשבון אחר. מכיוון שאין exchange עד שהדף חושף כתובת, גם ה-race שבו טוקן נשרף עם הכתובת השגויה נסגר. אם לא נמצאה כתובת בדף בתוך 12 שניות, מוצג שלב `ENTER_EMAIL` עם שדה ריק ולא עם חשבון המכשיר. תוויות ה-UI עודכנו בהתאם: כפתור הבאנר הוא `התחבר לחשבון Google` נייטרלי במקום `התחבר עם <חשבון>`, והכיתוב במסך ההתחברות מבהיר שהחשבון שייבחר במסך של Google הוא שיישמר.

session שנשמר לפני השינוי עדיין מכיל את כתובת חשבון המכשיר; היא תמשיך להופיע עד התנתקות והתחברות מחדש (אייקון ה-cloud בסרגל).

עקרונות שנלמדו מהבדיקות ואין לשנותם:

- **הטוקן חייב להיווצר על המכשיר.** AAS שהונפק בכלי חיצוני עם `androidId` שרירותי נדחה ב-`BadAuthentication`, כי הספרייה מבצעת check-in משלה. אין לקבל טוקן ממקור חיצוני או משרת.
- **אין לאמת session בבדיקה מקדימה.** בדיקת תקינות שנכשלת מסיבת רשת מחקה session תקין וגרמה להתנתקות מיד לאחר התחברות מוצלחת.
- **מחיקת session רק על דחייה מפורשת וחוזרת.** נדרשות שתי דחיות רצופות עם `BadAuthentication`, `NeedsBrowser` או `Unauthorized`; שגיאת אימות כללית עלולה לנבוע מהגבלת קצב.
- **cookie האישור בלבד מנוקה בין ניסיונות.** מחיקת כל ה-cookies אילצה אימות דו-שלבי מלא בכל ניסיון.
- **הלכידה היא בסקירה תקופתית של ה-cookie**, ולא באירוע סיום טעינת דף, שאינו נורה בסוף התהליך. הטוקן נכתב כבר במסך ההסכמה ומתעדכן לאחר האישור, ולכן כל ערך חדש מנוסה עד שאחד נקבל.

מה שאומת במכשיר `R8YW50PKLHY`:

```text
התחברות חד-פעמית                → session נשמר
חמש התקנות מחדש של האפליקציה     → session נשמר
אתחול מלא של המכשיר              → session נשמר, Device Owner נשמר
עדכון APK יחיד                   → הצליח
עדכון BASE + 5 SPLIT (כ-38MB)    → הצליח
```

הרחבה לצי מכשירים: כל מכשיר מתחבר לחשבון של המשתמש שלו, ולכן אין חשבון משותף ואין חסימה מצד Google. הטוקן של חשבון המערכת אינו נגיש לאפליקציה צד-שלישי, ולכן ההתחברות בדפדפן הפנימי היא חובה; חשבון המכשיר משמש כ-hint בלבד כדי לדלג על בחירת חשבון והקלדת כתובת.

הקטלוג החתום נשאר מקור מקביל ובעל עדיפות, ואינו דורש חשבון Google.

### גילוי עדכונים ברקע לאחר שינוי package

```text
PACKAGE_ADDED / PACKAGE_REPLACED / PACKAGE_REMOVED
      │
      ▼
MiniStorePackageChangeReceiver
      │ parsing ותזמון בלבד
      ▼
MiniStoreUpdateCheckScheduler
      │ unique WorkManager work לכל package
      │ debounce + network constraint + exponential backoff
      ▼
MiniStoreUpdateCheckWorker
      │ Hilt EntryPoint
      ▼
MiniStoreUpdateCoordinator
      ├── InstalledPackageInventoryProvider
      ├── PlayUpdateSource.discover()
      └── MiniStoreUpdateCheckDao
            └── Room: mini_store_update_check
```

ה-Receiver אינו מבצע רשת ואינו מתקין. הוא מתעלם מ-A Bloq עצמה ומ-`PACKAGE_REMOVED` שהוא חלק מהחלפה. ה-Worker בודק מחדש את מצב החבילה מול `PackageManager`, אינו שומר credentials או URLs זמניים ואינו קורא ל-`resolve()` או ל-`PackageInstaller`. פעולות discovery/resolve מסונכרנות דרך mutex משותף, וסיווג retry נעטף בתוך `PlayDiscoveryException` לפני שחרור הנעילה. רק IO שאינו `ProtocolException` או HTTP `408/429/500/502/503/504` מקבל עד שלושה retries; כשל auth/source קבוע נשמר כמצב ולא יוצר retry אינסופי.

טבלת `mini_store_update_check` נוספה בגרסת Room `4` דרך migration מפורש `3→4`. נשמרים package, גרסה מותקנת, metadata מצומצם של גרסה זמינה, source, status, זמן בדיקה ו-failure code לא רגיש. מעבר package ל-blacklist מוחק את הרשומה מיד; טעינת Mini Store מבצעת reconciliation מול inventory וה-blacklist, וה-coordinator מאמת שוב package/version לפני כל כתיבה. חיבור ה-Flow ל-UI, notification ובדיקה תקופתית עדיין לא מומשו.

`receivers.InstallReceiver` נשאר receiver מפורש לתוצאת `PackageInstaller` בלבד; מסנן `PACKAGE_ADDED`/`PACKAGE_REPLACED` הוסר ממנו והוא `exported=false`.

הקובץ המרכזי הוא:

```text
app/src/main/java/com/secureguard/mdm/ministore/data/MiniStoreRepository.kt
```

### טעינת האפליקציות

`MiniStoreRepository.loadInstalledApps()` מבצע:

1. הורדת קטלוג עדכונים חתום.
2. קריאת האפליקציות המותקנות דרך `PackageManager`.
3. סינון A Bloq ואפליקציות שנמצאות ב-blacklist.
4. בדיקת עדכונים דרך `PlayUpdateSource` רק אם ה-build כולל endpoint מורשה.
5. מיזוג תוצאות הקטלוג ותוצאות Google Play, כאשר הן זמינות.
6. סימון אפליקציות מערכת ואפליקציות מוגנות מהסרה.

כל עוד המכשיר אינו מחובר לחשבון Google, ה-Mini Store פועל במצב `catalog-only`: הוא אינו שולח בקשת auth ואינו מציג כשל Play, ומוצג באנר שמזמין להתחבר. `PlayUpdateSource.isConfigured()` נקבע לפי קיום session מקומי, ולא לפי פרמטרי build. ה-UI שומר סטטוס נפרד לקטלוג (`CHECKED`/`FAILED`) ול-Play (`DISABLED`/`CHECKED`/`FAILED`). `PlayDiscoveryResult` מחזיר גם את קבוצת ה-packages שנכשלו, וכל `ManagedInstalledApp` כולל `updateCheckComplete`; לכן כשל מקור מלא או כשל Play לחבילה בודדת מוצג כ־"לא נבדק" ולא כ־"אין עדכון".

סדר העדיפות:

```text
Signed Catalog candidate
          │
          └── אם אינו קיים
                  ▼
          Google Play candidate
```

## 11. מקור העדכונים החתום

```text
Signed Catalog Server
      │
      ▼
MiniStoreCatalogClient
      │
      ├── הורדת הקטלוג
      ├── אימות החתימה
      └── פענוח רשימת העדכונים
      │
      ▼
MiniStoreRepository
```

מפתח האימות הציבורי נמצא במשאבי האפליקציה:

```text
secureguard_mini_store_public_key.json
```

רשומת עדכון יכולה לכלול:

```text
packageName
versionCode
versionName
minSdk
downloadUrl
apkSha256
apkSize
apkSignerSha256
releaseNotes
```

הקטלוג החתום מאפשר ל-A Bloq לוודא שהשרת אינו מחליף APK ללא התאמה ל-hash ולחתימת המפתח הצפויים.

### אירוח ופריסה של הקטלוג

אין שרת SSH/SFTP עבור `imreykodesh.com`, ואין תצורת `~/.ssh` מקומית עבורו. אין להשתמש ב-`ssh`, `sftp` או `scp` כדי לפרסם את הקטלוג.

הפריסה מחולקת לשני מסלולים:

```text
catalog.json
  C:\projects\site\my-landing-page\downloads\secureguard-mini-store\catalog.json
        │
        └── GitLab → Cloudflare Pages
              └── https://imreykodesh.com/downloads/secureguard-mini-store/catalog.json

APK immutable objects
  publish-apk.ps1 → Wrangler CLI → Cloudflare R2
        └── https://downloads.imreykodesh.com/downloads/secureguard-mini-store/
```

פרטי גישה ותפעול שאומתו:

```text
Site workspace:  C:\projects\site\my-landing-page
Git remote:      https://gitlab.com/imreykodesh-group/imreykodesh-project
R2 bucket:       imreykodesh-downloads
R2 key prefix:   downloads/secureguard-mini-store/
Wrangler auth:   CLOUDFLARE_API_TOKEN (שם משתנה בלבד; הערך אינו מתועד)
Catalog URL:     https://imreykodesh.com/downloads/secureguard-mini-store/catalog.json
APK origin:      https://downloads.imreykodesh.com
```

- האתר הסטטי מתפרסם ב-Cloudflare Pages בעקבות פרסום מפורש ל-GitLab. אין לבצע commit או push אוטומטי; פרסום Git ייעשה רק לפי בקשה מפורשת של המשתמש.
- APK מועלה ישירות ל-R2 באמצעות `wrangler r2 object put ... --remote` מתוך `scripts/secureguard-mini-store/publish-apk.ps1`.
- `publish-apk.ps1` מאמת package/version/signer/hash, מעלה ל-R2, בודק `HTTP 200` וגודל, מעדכן revision ומפעיל חתימת קטלוג.
- ה-token המקומי הנוכחי מאפשר גישה ל-R2 ול-bucket המפורט לעיל. הוא אינו כולל כרגע הרשאת קריאה לרשימת Cloudflare Pages projects; אין להרחיב הרשאות בלי צורך ואישור.
- ערך `CLOUDFLARE_API_TOKEN`, Account ID וכל credential אחר אינם נשמרים בפרויקט Android, ב-`PROJECT_MAP.md` או בגיבוי הרגיל.
- הקטלוג החי נבדק והחזיר `HTTP 200`, `algorithm=Ed25519`, `schemaVersion=1` ו-key ID התואם למפתח המוטמע.

כלי התפעול נמצאים בפרויקט האתר:

```text
scripts/secureguard-mini-store/generate-key.js
scripts/secureguard-mini-store/generate-catalog.js
scripts/secureguard-mini-store/catalog-lib.js
scripts/secureguard-mini-store/publish-apk.ps1
secureguard-mini-store/catalog.source.json
downloads/secureguard-mini-store/catalog.json
```

### מפתח חתימת הקטלוג

המפתח הפרטי אינו נמצא בפרויקט Android ואסור להעתיק את ערכו לכאן. מקור האמת המקומי הוא:

```text
Private key path:
C:\projects\site\my-landing-page\secureguard-mini-store\.private\catalog-ed25519.pkcs8.b64

Optional environment variable:
SECUREGUARD_MINISTORE_PRIVATE_KEY_B64

Format:
Ed25519 private key, PKCS#8 DER encoded as Base64
```

פרטי הזיהוי הציבוריים:

```text
Key ID:
sgms-d4887b656ff9d398

Public SPKI SHA-256:
d4887b656ff9d398a87696c4f09028eebb9584ff209a7cdfb9bf360a7661f72f

Website public config:
C:\projects\site\my-landing-page\secureguard-mini-store\public-key.json

Android trust root:
app/src/main/res/raw/secureguard_mini_store_public_key.json
```

אימות שבוצע בלי להציג את הסוד:

- הקובץ הפרטי קיים ונטען כמפתח `Ed25519` מסוג PKCS#8.
- המפתח הציבורי שנגזר ממנו זהה ל-`public-key.json` ולמפתח המוטמע ב-A Bloq.
- `keyId` נגזר מ-16 תווי hex הראשונים של SHA-256 על public SPKI.
- החתימה היא על `secureguard-mini-store-catalog/v1\n` ואחריו ה-payload הקנוני.
- `generate-catalog.js` קורא קודם את `SECUREGUARD_MINISTORE_PRIVATE_KEY_B64`, ואם אינו מוגדר קורא את הקובץ הפרטי המקומי.

מצב גיבוי קריטי:

- `.gitignore` של האתר מחריג את `secureguard-mini-store/.private/`, ולכן המפתח אינו נמצא ב-Git.
- `backup.ps1` של אתר אמרי קודש אינו מגבה את `secureguard-mini-store/` או את `.private/`.
- `backup.ps1` של SecureGuardMDM מגבה רק את פרויקט Android ואינו כולל את המפתח החיצוני.
- נכון לבדיקה זו לא נמצא עותק התאוששות מוצפן ונפרד של מפתח חתימת הקטלוג.
- אובדן המפתח ימנע חתימת revision חדש שהאפליקציה הקיימת תקבל. יש ליצור עבורו גיבוי מוצפן, אופליין וביותר ממיקום אחד, אך רק בפעולה נפרדת ובאישור מפורש; אין להכניס את ערך המפתח למסמך או ל-snapshot רגיל.

## 12. מקור Google Play

הקבצים המרכזיים:

```text
ministore/play/
├── PlayUpdateSource.kt
├── GPlayHttpClient.kt
└── PlayCredentialStore.kt
```

### זרימת ההתחברות

```text
PlayUpdateSource.ensureAuth()
      │
      ├── בדיקת AuthData בזיכרון
      ├── טעינת AuthData מוצפן מהאחסון
      ├── AuthHelper.isValid()
      └── אם אינו תקף:
              │
              ▼
      POST מאומת לשרת הפרטי
              │
              ▼
      email + AAS token
              │
              ▼
      AuthHelper.build(Token.AAS, isAnonymous=false)
              │
              ▼
      Google Play AuthData
```

אין endpoint ציבורי קשיח בקוד. ברירת המחדל היא מחרוזות ריקות ומצב `catalog-only`. הפעלת המקור דורשת URL ו-client token יחד, דרך Gradle properties או environment variables:

```text
miniStorePlayDispenserUrl / MINI_STORE_PLAY_DISPENSER_URL
miniStorePlayClientToken  / MINI_STORE_PLAY_CLIENT_TOKEN
```

ה-build דורש HTTPS ללא credentials, query או fragment, וחוסם במפורש את `auroraoss.com`. ה-client token מוזרק רק בזמן build ואינו נשמר בקוד או ב-Git, אך ניתן עקרונית לחלץ אותו מה-APK. כתובת החשבון וה-AAS נשמרים ב-Render ומועברים ל-A Bloq רק בתגובה מאומתת מעל HTTPS; A Bloq ממירה אותם ל-`AuthData`, שומרת את ה-session מוצפן ואינה מציגה או מלוגגת את כתובת החשבון.

הבקשה ל-endpoint המורשה:

```http
POST /api/mini-store/play-auth
Authorization: Bearer <client-token>
User-Agent: com.secureguard.mdm-mini-store/1
Content-Type: application/json
```

התגובה המצופה:

```json
{
  "email": "...",
  "aasToken": "..."
}
```

המימוש השרתִי נמצא ב-`C:\projects\firestore-proxy-server\miniStorePlayAuth.js` ומשולב ב-`index.js`. הוא קורא ערכים רק מ-`PLAY_ACCOUNT_EMAIL`, `PLAY_ACCOUNT_AAS_TOKEN` ו-`MINI_STORE_CLIENT_TOKEN` ב-Render environment, מבצע השוואת Bearer קבועת-זמן, מגביל method/body/schema/rate, מחזיר `Cache-Control: no-store` ואינו מלוגג גוף, email או tokens. השרת אינו מוריד או מעביר APKs; לאחר יצירת session, A Bloq פונה ישירות ל-Google Play.

### אבחון ה-403 ההיסטורי

לפני הסרת התלות הציבורית התקבל:

```text
A Bloq → auroraoss.com/api/auth → HTTP 403 Forbidden
```

הכשל מתרחש לפני:

- קבלת `email` ו-`authToken`.
- יצירת Google Play session.
- פנייה ל-Google Play.
- בדיקת עדכונים.
- הורדת APK.

ההשוואה מול Aurora Store 4.8.3 הוכיחה כי שתי האפליקציות משתמשות באותו:

- endpoint.
- HTTP method.
- `Content-Type`.
- מבנה JSON שטוח של מאפייני מכשיר.
- מבנה תגובה `email` ו-`authToken`.
- `AuthHelper.Token.AUTH` עם `isAnonymous=true`.
- מנגנון redirects.

הבדלים שנמצאו:

```text
Aurora 4.8.3:
User-Agent: com.aurora.store-4.8.3-75
gplayapi:  3.5.9
Profile:   פרופיל native עקבי כברירת מחדל

A Bloq:
User-Agent: com.secureguard.mdm-mini-store/1
gplayapi:  3.6.4
Profile:   פרופיל S25 עם override חלקי של נתוני המכשיר
```

נשלחה גם בקשה מבוקרת עם:

- User-Agent האמיתי של A Bloq.
- פרופיל S25 מלא ועקבי ללא ה-overrides ההיברידיים.

התוצאה נשארה `HTTP 403`. לכן ה-profile ההיברידי, גרסת `gplayapi`, redirects ופורמט ה-JSON אינם הסיבה ל-403.

מסקנה: ה-dispenser הציבורי של Aurora מוגבל ללקוחות Aurora הישירים ואינו מיועד לפרויקטי צד שלישי. התחזות ל-User-Agent של Aurora לא נחשבת פתרון נתמך ולא יושמה.

## 13. Aurora Store שנבדקה במכשיר

```text
Package:     com.aurora.store
Version:     4.8.3
VersionCode: 75
APK layout:  base APK יחיד
```

נמשך ה-APK המותקן ונבדק מול source tag `4.8.3`, commit:

```text
e9be2c8293e02cc362d603df6b12b019fdb849f2
```

בעת הפעלה מחדש ללא מחיקת נתונים:

1. Aurora טענה `AuthData` שמור.
2. ה-session נמצא לא תקף.
3. Aurora ניסתה ליצור session חדש.
4. ההתחברות נכשלה.
5. המסך הציג:

```text
Unable to resolve host "auroraoss.com":
No address associated with hostname
```

לכן Aurora המותקנת אינה מצליחה כרגע לבצע התחברות אנונימית חדשה. פעולה קודמת שלה יכולה הייתה להתבסס על session או תוכן שמור.

חתימת ה-APK שנמשך אינה תואמת ל-fingerprint הרשמי שמפורסם על ידי upstream Aurora, ולכן ייתכן שמדובר ב-build שנחתם מחדש על ידי ערוץ הפצה כגון F-Droid. הדבר אינו מסביר את ה-403: בקשת ה-dispenser אינה שולחת חתימת APK או attestation.

## 14. שמירת Google Play session

קובץ: `PlayCredentialStore.kt`

מיקום הקובץ במכשיר:

```text
noBackupFilesDir/mini_store_play_auth.bin
```

זרימת האחסון:

```text
AuthData
   │
   ▼
Kotlin Serialization
   │
   ▼
AES/GCM/NoPadding
   │
   ▼
Android Keystore
   │
   ▼
AtomicFile
```

מאפייני אבטחה:

- מפתח AES נשמר ב-Android Keystore.
- IV אקראי נוצר לכל הצפנה.
- פורמט האחסון הוא version 2.
- ה-Additional Authenticated Data כולל את URL ה-dispenser המורשה; שינוי endpoint מבטל ומנקה session ישן במקום להעביר credentials בין מקורות.
- הכתיבה אטומית ומסונכרנת לדיסק.
- קובץ פגום או session שלא ניתן לפענח נמחק.
- הקובץ נמצא ב-`noBackupFilesDir` ואינו אמור לעבור בגיבוי רגיל.

מגבלת recovery קיימת:

```text
cachedAuth בזיכרון
      └── מוחזר ללא revalidation
```

A Bloq מאמתת session שמור לאחר הפעלת התהליך, אבל אינה מנקה אוטומטית session שנפסל מאוחר יותר על ידי Google ואינה מבצעת ניסיון התחברות יחיד מחדש לאחר כשל כזה.

## 15. גילוי עדכונים ב-Google Play

```text
PlayUpdateSource.discover()
      │
      ▼
AppDetailsHelper
      │
      ├── בדיקה בקבוצות של עד 30 package names
      ├── fallback לבדיקה יחידנית
      └── השוואת versionCode
```

מועמד לעדכון נוצר רק כאשר:

```text
Play versionCode > installed versionCode
```

אם בדיקת קבוצה נכשלת, הקוד מנסה כל package בנפרד וסופר את מספר הכישלונות.

## 16. יצירת תוכנית הורדה

```text
UpdateCandidate
      │
      ▼
PlayUpdateSource.resolve()
      │
      ▼
PurchaseHelper.purchase()
      │
      ├── BASE APK
      ├── SPLIT APKs
      └── delivery metadata
      │
      ▼
UpdatePlan
```

בדיקות שבוצעו בקוד:

- מקור ההורדה חייב להיות HTTPS.
- גודל הקובץ חייב להיות חיובי.
- SHA-256 חייב להיות בפורמט תקין.
- חייב להיות בדיוק BASE APK אחד.
- SPLIT APKs נתמכים.
- OBB ו-PATCH אינם נתמכים כרגע.
- חתימת האפליקציה המותקנת מחושבת ונשלחת כאשר נדרש.

## 17. התקנת עדכונים

```text
UpdatePlan
      │
      ├── הורדת BASE
      ├── הורדת SPLITs
      ├── אימות גודל
      ├── אימות SHA-256
      ├── אימות package name
      ├── אימות versionCode
      ├── אימות signer
      └── התקנה משותפת
```

מנגנון ההתקנה אינו מניח ש-BASE APK לבדו מספיק. כאשר Google מחזירה splits, הם נכללים באותה התקנת package.

### חלון התקנה מול DISALLOW_INSTALL_APPS

אומת על SM_A145P (Android 14): ההגבלה `DISALLOW_INSTALL_APPS` נאכפת ב-`PackageInstallerService.createSessionInternal` ואינה מעניקה פטור ל-Device Owner ואף לא ל-`adb install` של shell. כאשר "חסימת התקנת אפליקציות" דלוקה, כל session נכשל עם `SecurityException: User restriction prevents installing`, כולל session של A Bloq עצמה.

לכן כל מסלולי ההתקנה עוברים דרך `utils/InstallRestrictionGuard.kt`:

```text
InstallRestrictionGuard
      ├── קורא את מצב BlockInstallAppsFeature
      ├── clearUserRestriction(DISALLOW_INSTALL_APPS)   ← רק אם ההגבלה דלוקה
      ├── הרצת ה-session
      └── addUserRestriction(DISALLOW_INSTALL_APPS)     ← ב-finally
```

- הכיוון הפוך ל-`withTemporaryUninstallRestriction` שבאותו קובץ אופרטור, ואותה תבנית שחזור ב-`finally`.
- החלון נפתח סביב ה-session בלבד, לא סביב הורדה. ב-Mini Store הוא נמצא בתוך `operationMutex`, כך ששני חלונות אינם יכולים לחפוף.
- מצב ה-feature ב-Preferences אינו משתנה, כך שהמתג ממשיך להציג "דלוק" גם בתוך החלון.
- שאר ההגנות נשארות פעילות לכל אורך החלון: חסימת מקורות לא מזוהים, הסתרת Play, חסימת אפשרויות פיתוח.
- ארבעה מסלולים משתמשים בו: עדכון Mini Store, התקנת `nophone.apk`, עדכון עצמי של A Bloq, והתקנה ידנית מהמסך הראשי.
- מצב קצה פתוח: אם התהליך נהרג בתוך החלון, ההגבלה נשארת מנוקה עד להחלה מחדש של ה-feature. אין כרגע החלה מחדש בעליית האפליקציה או ב-boot.

### התקנה ידנית מהמסך הראשי

`DashboardViewModel.installPackage` הוא עדכון בלבד: הקובץ מועתק ל-cache פרטי, כל הבדיקות וה-session עובדים על אותו עותק (כדי שלא ייפתח פער בין אימות ל-session), נדרש שהחבילה כבר מותקנת, נדרש `versionCode` גבוה יותר, וה-session ננעל ב-`setAppPackageName`. חבילה שאינה מותקנת מפעילה את דיאלוג "האפליקציה אינה מותקנת".

## 18. בדיקת Google Account במכשיר

נבדק המסלול הנתמך דרך Android `AccountManager`:

```text
A Bloq
  → בחירת חשבון com.google
  → בקשת oauth2:https://www.googleapis.com/auth/googleplay
  → AuthenticatorException
```

החשבון נבחר בהצלחה בלי לחשוף אותו לאפליקציה לפני האישור, אך Google Play Services סירבה לתת Play token ל-package ולחתימה האמיתיים של A Bloq.

מסקנה:

```text
Stock Google Play Services
      └── אינו מאפשר ל-A Bloq לקבל Google Play token
```

שימוש ב-`overridePackage=com.android.vending` ובתעודת Google יהיה התחזות ל-Play Store ואינו מסלול נתמך. probe האימות הזמני ששימש לבדיקה הוסר לאחר מכן.

## 19. רשת ואבטחת TLS

קובץ מרכזי:

```text
app/src/main/res/xml/network_security_config.xml
```

מצב שאומת:

- Cleartext HTTP חסום.
- HTTPS נדרש.
- קיימת אמונה ב-system CAs.
- קיימת אמונה גם ב-user-installed CAs.
- OkHttp עוקב אחרי redirects רגילים ו-SSL redirects עבור תעבורת `gplayapi`.
- `getAuth()` ו-`postAuth()` משתמשים ב-client נפרד שבו redirects רגילים ו-SSL redirects כבויים, כדי ש-endpoint מורשה לא יוכל להעביר credentials או body ליעד אחר.
- בקשת השרת הפרטי משתמשת ב-`Authorization: Bearer`, ב-connect timeout של 90 שניות, read timeout של 120 שניות ו-call timeout של 150 שניות כדי לאפשר cold start של Render.
- גוף תגובת auth מוגבל ל-64 KiB לפני materialization בזיכרון.
- מתבצע retry יחיד לאחר 3 שניות עבור `HTTP 502`, `503`, `504` או `IOException` זמני; תגובה גדולה מדי מסווגת ככשל protocol ואינה נשלחת מחדש, ואין retry לאחר דחיית credentials.
- `retryOnConnectionFailure` מופעל.

`GPlayHttpClient` משמש גם לבקשת ה-dispenser וגם כמימוש `IHttpClient` עבור `gplayapi`.

## 20. VPN / Netfree

רכיבים מרכזיים:

```text
FirewallScreen / FirewallViewModel
        │  בחירת אפליקציה + MONITOR_ONLY ללכידה
        ▼
BlockerVpnService
        │  per-app VPN
        ▼
FirestackEngineAdapter
        ├── DNS query → domain מנורמל + BLOCKED/MONITORED/ALLOWED
        └── flow → DNS/TLS-SNI אם זמין, אחרת IP_ONLY
        ▼
ConnectionHistoryRecorder → Room → סל הלכידה / היסטוריה

NetfreeWatchdogBootTask.kt
NetfreeUser.kt
third_party/firestack/
```

מצב הלכידה רושם כל שאילתת DNS מנורמלת של האפליקציה שנבחרה, גם כשהשאילתה מותרת; החלטת החסימה וה-DNS transport נשארות בלתי תלויות ברישום. הסל מציג רק אירועים עם domain לאחר זמן תחילת הלכידה. תעבורה שבה Firestack אינו מספק DNS או TLS-SNI נשמרת כ-`IP_ONLY` ואינה מוצגת כאתר, כדי לא להציג IP משותף כיעד בטוח לחסימה. DNS מוצפן, cache ו-QUIC עדיין עשויים להגביל זיהוי hostname; המנגנון אינו לוכד path מלא של URL ב-HTTPS.

ה-watchdog מופעל בזמן אתחול ונועד לשמור על פעילות רכיבי Netfree.

## 21. מצב מקורות העדכון

```text
                    ┌─────────────────────┐
                    │ Mini Store          │
                    └──────────┬──────────┘
                               │
             ┌─────────────────┴─────────────────┐
             ▼                                   ▼
┌────────────────────────┐          ┌────────────────────────────┐
│ Signed Catalog         │          │ Google Play / gplayapi     │
│ בשליטת A Bloq          │          │ לא רשמי                    │
├────────────────────────┤          ├────────────────────────────┤
│ זמין ומאומת            │          │ ברירת מחדל: כבוי           │
│ הקטלוג החי כרגע ריק    │          │ Public Aurora: חסום ב-build│
│ דורש מקור APK          │          │ Private dispenser: נתמך    │
│ חתימה + hash           │          │ נדרש endpoint מורשה        │
└────────────────────────┘          └────────────────────────────┘
```

## 22. דרכי המשך נתמכות

### 22.1 שירות Play פרטי

מסמך התכנון, הפריסה, האבטחה, האמינות וה-rollback המלא נמצא ב-`PRIVATE_PLAY_UPDATE_PLAN.md`. אין להכניס למסמך זה או למפת הפרויקט ערכי email, AAS, App Password או client token.

```text
A Bloq → firestore-proxy-server → חשבון Google ייעודי → Google Play
```

השירות ממומש מקומית ב-`firestore-proxy-server` בנתיב `POST /api/mini-store/play-auth`. ערכי `PLAY_ACCOUNT_EMAIL`, `PLAY_ACCOUNT_AAS_TOKEN` ו-`MINI_STORE_CLIENT_TOKEN` אינם נמצאים בקוד או ב-`render.yaml`; הקובץ מצהיר רק על שמותיהם עם `sync: false`. פריסה ל-Render והפעלת המקור באפליקציה דורשות שהערכים יוגדרו ב-Render environment ושאותו client token יוזרק ל-build של A Bloq.

השרת מחזיר ללקוח המאומת:

```json
{
  "email": "...",
  "aasToken": "..."
}
```

A Bloq משתמשת במנגנון הקיים:

```text
Private Play auth endpoint
      → AuthHelper.Token.AAS
      → Google Play AuthData
      → AppDetailsHelper
      → PurchaseHelper
      → BASE + SPLIT
      → אימות והתקנה
```

יתרונות:

- אין צורך להעלות כל עדכון APK ידנית.
- APKs אינם עוברים דרך השרת הפרטי; A Bloq מורידה אותם ישירות מ-Google.
- מנגנון הגילוי וההתקנה הקיים נשמר.
- אין תלות ב-dispenser הציבורי של Aurora.

דרישות וסיכונים:

- שירות Render פעיל ו-HTTPS.
- חשבון Google ייעודי ו-AAS token ארוך-חיים.
- אחסון email/AAS/client token ב-Render environment בלבד; אין להכניס ערכים ל-Git, למסמכים, ללוגים או לגיבוי.
- ה-client token המוטמע ב-APK ניתן לחילוץ ולכן מספק חסימה בסיסית בלבד; הוא אינו תחליף ל-device-bound authentication או mTLS.
- rate limit נוכחי נשמר בזיכרון התהליך ומתאפס בכל restart.
- Google עלולה להגביל את החשבון או לשנות את ה-API.
- זהו עדיין API לא רשמי ומהונדס לאחור.

ה-client מיישם:

- URL ו-client token כזוג חובה דרך Gradle property או environment בלבד.
- נרמול hostname באמצעות IDN, הסרת trailing dots, HTTPS ללא credentials/query/fragment וחסימת `auroraoss.com` ותת-הדומיינים שלו.
- Bearer authentication ללא redirects.
- timeout ארוך, גוף תגובת auth מוגבל ל-64 KiB ו-retry יחיד לכשלי תעבורה זמניים או ל-502/503/504 עבור cold start.
- ללא URL/token, אין בקשת Play וה-UI מציג מצב catalog-only.
- session מוצפן קשור ל-URL המוגדר ונמחק כאשר ה-scope משתנה.
- ה-UI מציג סטטוס מקור ושגיאת recovery בלי להציג email או tokens.

שיפורים עתידיים אפשריים:

- device-bound authentication במקום Bearer משותף.
- שימוש בפרופיל מכשיר עקבי.
- ניקוי session וניסיון re-auth יחיד לאחר כשל אימות מאוחר.
- backoff עם jitter עבור `HTTP 429`.

### 22.2 Managed Google Play / Android Enterprise

```text
A Bloq / EMM Backend
      → Android Enterprise
      → Managed Google Play
```

זהו המסלול הרשמי והיציב יותר, אך הוא דורש תשתית Android Enterprise/EMM ואינו שינוי מקומי קטן באפליקציה.

### 22.3 קטלוג חתום ואוטומטי

```text
Backend שבשליטת הארגון
      ├── עוקב אחרי מקורות מורשים
      ├── מזהה גרסאות חדשות
      ├── מאמת APK
      ├── מפרסם קטלוג חתום
      └── A Bloq מורידה ומתקינה
```

אין חובה להעלות כל עדכון ידנית אם תהליך השרת אוטומטי, אך חייב להיות מקור מורשה ואמין לקובצי ה-APK.

## 23. קבצים חשובים

קובצי זרימת העבודה וההתאוששות:

```text
PROJECT_MAP.md
backup.ps1
.kiro/steering/project-map.md
```

- `PROJECT_MAP.md` מרכז את הארכיטקטורה והמצב הטכני שאומת.
- `backup.ps1` יוצר snapshot ממוספר ומאומת SHA-256 של ה-working tree.
- `.kiro/steering/project-map.md` נטען אוטומטית ומחייב אימות, עדכון מפה וגיבוי סופי לאחר משימה מוצלחת ששינתה קבצים. הוא כולל גם את כללי הבטיחות הקבועים למכשיר חי וקובע שאין לבצע staging, commit או push אלא לפי בקשה מפורשת של המשתמש.
- `docs/runbooks/android-emulator-adb-device-owner-netfree-certificate-operations-he.md`, סעיף **8.2**, הוא מדריך ה-Debug המלא והמאומת: הכנת PowerShell ו-Java 17, בדיקות package/signer/Device Owner, `assembleDebug`, התקנה עם `adb install -r -t`, הפעלה מחדש, mirroring, logcat ממוקד, מדידת ביצועים, UI dump, screenshots, smoke tests, אבחון cache פגום וגיבוי סופי.

קובצי יישום מרכזיים:

```text
app/build.gradle.kts
app/src/main/AndroidManifest.xml

app/src/main/java/com/secureguard/mdm/
├── appblocker/
│   ├── AppBlockerState.kt
│   ├── AppBlockerViewModel.kt
│   └── ui/
├── boot/
│   ├── BootCompletedReceiver.kt
│   ├── api/BootTask.kt
│   ├── registry/BootTaskRegistry.kt
│   └── impl/NetfreeWatchdogBootTask.kt
├── data/
│   ├── db/AppDatabase.kt
│   ├── local/PreferencesManager.kt
│   └── repository/SettingsRepositoryImpl.kt
├── di/AppModule.kt
├── features/
│   ├── api/ProtectionFeature.kt
│   └── impl/
└── ministore/
    ├── data/MiniStoreRepository.kt
    └── play/
        ├── PlayUpdateSource.kt
        ├── GPlayHttpClient.kt
        └── PlayCredentialStore.kt

app/src/main/res/
├── values/strings.xml
├── values-en/strings.xml
├── xml/network_security_config.xml
└── raw/secureguard_mini_store_public_key.json
```

## 24. סיכום ארכיטקטוני

```text
┌──────────────────────────────────────────────┐
│ UI: Compose / Activities                    │
├──────────────────────────────────────────────┤
│ ViewModels                                  │
├──────────────────────────────────────────────┤
│ Repositories / Providers / Feature Registry │
├──────────────────────────────────────────────┤
│ Room / Preferences / Encrypted Auth Store   │
├──────────────────────────────────────────────┤
│ Android DevicePolicy / PackageManager       │
├──────────────────────────────────────────────┤
│ Network: Ktor / OkHttp / gplayapi / VPN     │
├──────────────────────────────────────────────┤
│ Android OS + Google Play + Update Servers   │
└──────────────────────────────────────────────┘
```

הפרויקט בנוי באופן מודולרי יחסית: מדיניות ההגנה, משימות האתחול, שכבת הנתונים ומקורות העדכון מופרדים. החסם המרכזי במסלול Google Play אינו מנגנון ההתקנה אלא השגת הרשאת Google Play אמינה ומותרת. מנגנון גילוי העדכונים, יצירת תוכנית BASE+SPLIT, אימות הקבצים וההתקנה כבר קיים.

## 25. מיתוג חיצוני וערכת צבעים

השם המוצג למשתמש הוא **מפתח** (עברית) / **Mafteach** (אנגלית). זהו מיתוג חיצוני בלבד: ה-package, ה-namespace, שמות המחלקות, ה-Device Admin receiver, שמות ה-styles (`Theme.SecureGuard*`) **לא שונו**, כדי לא לשבור Device Owner קיים או חתימה. שמות קובצי ה-APK כן שונו ל-`מפתח-<versionName>-<buildType>.apk` (לדוגמה `מפתח-0.6.0-debug.apk`); שם קובץ ה-host אינו חלק מזהות Android, מהחתימה או מ-Device Owner. הגרסה הנוכחית היא `versionCode=4`,‏ `versionName=0.6.0`. ערוץ העדכון העצמי הישן מ-GitHub הוחלף בערוץ ייעודי מהפאנל של אמרי קודש.

### ערוץ עדכון עצמי של מפתח

ערוץ זה נפרד לחלוטין מ-Mini Store ואינו משתמש בקטלוג שלו:

```text
DashboardScreen
  → DashboardViewModel
  → UpdateManager
  → GET https://imreykodesh.com/.netlify/functions/get-mafteach-update?channel=<stable|prebuild>
  → הורדת APK immutable מ-downloads.imreykodesh.com
  → אימות והתקנה עצמית דרך PackageInstaller
```

- `UpdateManager` ממפה את ההגדרה הקיימת `STABLE`/`PREBUILD` ל-API החדש. בדיקה אוטומטית מתבצעת בטעינת המסך הראשי כאשר היא מותרת בהגדרות, ובדיקה ידנית נשארת בדיאלוג "אודות". `DashboardViewModel` אוכף את מדיניות העדכונים גם באירועי בדיקה/הורדה עצמם, ודיאלוג בתהליך `DOWNLOADING` אינו נסגר בלחיצה מחוץ לחלון או Back.
- היעדר רשת מוחזר כ-`UpdateResult.Failure` ולא כ-`NoUpdate`, כך שהמשתמש אינו מקבל הודעה מטעה שהגרסה עדכנית.
- חוזה metadata הוא `schemaVersion=1` וכולל `channel`,‏ `packageName`,‏ `versionCode`,‏ `versionName`,‏ `releaseNotes`,‏ `publishedAt`,‏ `downloadUrl`,‏ `apkSha256` ו-`apkSize`. מקור האמת הוא `downloads/mafteach/metadata/<channel>/current.json` ב-Cloudflare R2; ה-endpoint הציבורי נשאר ללא שינוי ומבצע validation לפני החזרת החוזה.
- החלטת זמינות נשענת רק על `versionCode` מספרי. `versionName` הוא תצוגתי בלבד.
- לפני התקנה נבדקים HTTPS ו-host/port מורשים, והנתיב חייב להתאים בדיוק ל-`/downloads/mafteach/<channel>/<versionCode>/<uploadId-UUID>/<sha256>.apk`. גם redirect מורשה חייב לשמור את אותו protocol/host/port/path/query/ref/userInfo של ה-URL שאומת; לאחר ההורדה נבדקים גודל מדויק, SHA-256, package name זהה ל-`com.secureguard.mdm`,‏ versionCode זהה, minSdk נתמך והמשכיות signer מול האפליקציה המותקנת.
- ה-session נעול באמצעות `setAppPackageName` ו-`setSize`; ב-Android 12+ נדרש `USER_ACTION_NOT_REQUIRED`. ההתקנה מתבצעת רק כאשר האפליקציה עדיין Device Owner ובתוך `InstallRestrictionGuard`.
- `InstallReceiver` מקבל operation וגרסה צפויה, ומשווה לאחר `STATUS_SUCCESS` את הגרסה שהותקנה בפועל. `Completed` ב-Flow מציין שה-session המאומת נמסר ל-Android; תוצאת ההתקנה הסופית מגיעה מה-receiver משום שעדכון עצמי עשוי להחליף את התהליך מיד לאחר commit.
- אין dependency חדשה ואין שינוי ב-Manifest. ה-release keystore הקבוע נשמר מחוץ לפרויקט ומחובר ל-build דרך `C:\projects\SecureGuardMDM\signing\keystore.properties`; כל APK עתידי חייב להיחתם באותו מפתח ולהעלות `versionCode`, אחרת Android או בדיקת signer ידחו אותו.
- נכון ל-2026-08-17 הקוד עבר `:app:assembleDebug` ו-`:app:assembleRelease`; `aapt2 dump badging` אימת בשני ה-APK את `com.secureguard.mdm`,‏ `versionCode=4`,‏ `versionName=0.6.0`. Debug נבנה בגודל 136,204,652 bytes וחתום debug; Release ברירת המחדל בגודל 126,327,988 bytes ואינו חתום כי ה-keystore ההיסטורי אינו בפרויקט.
- הערוץ הופעל במבנה R2-only ב-2026-08-18: `stable/current.json` ב-Cloudflare R2 מצביע ל-APK release החתום הקבוע של `0.6.0`, בגודל `126,434,718` bytes וב-SHA-256 `8f1bf52de93702dffb79b4950b2bb93703caa53e6ebbf6be741670a0cf8dbdfc`. ה-endpoint הציבורי מחזיר `200` עם אותו חוזה ואילו `prebuild` מחזיר `204`. Firestore אינו משמש עוד לפרסום או לקריאת metadata של עדכוני מפתח.
- ההפעלה ההיסטורית מ-2026-08-17 השתמשה ב-APK debug של `0.6.0` תחת אותו versionCode. גרסה `0.4.6` שהיתה מותקנת דיווחה "אתה מעודכן" משום שהיא בדקה `version.txt` ב-GitHub ולא הכירה את הערוץ החדש.
- אימות מקצה לקצה על המכשיר בוצע באמצעות APK גשר (`overrideVersionCode=3`) בעל אותה חתימה כמו ההתקנה הקיימת, שהותקן עם `install -r -t`. האפליקציה רשמה `Self-update available: 3 -> 4 (stable)`, הציגה את דיאלוג העדכון, הורידה מ-R2, אימתה והתקינה בעצמה. לאחר מכן `dumpsys package` הראה `versionCode=4`/`versionName=0.6.0`, `InstallReceiver` דיווח תוצאה, ו-`dpm list-owners` נשאר `DeviceOwner,Affiliated`.
- הגרסה שמותקנת כרגע חתומה בחתימת debug (`04a3b2bb…`), משום שה-release keystore ההיסטורי (`7cff9705…`) חסר. כל עדכון עתידי חייב לשמור על אותה חתימה שכבר מותקנת ולהעלות `versionCode`, אחרת Android או בדיקת ה-signer ידחו אותו.

```text
external brand   → מפתח / Mafteach   (app_name, טקסטים למשתמש, ערוץ התראות)
internal identity → com.secureguard.mdm, SecureGuardDeviceAdminReceiver, Theme.SecureGuard
```

מקומות שבהם השם מופיע למשתמש:

```text
app/src/main/res/values/strings.xml       — app_name = מפתח, וכל הטקסטים בעברית
app/src/main/res/values-en/strings.xml    — app_name = Mafteach
MainService.kt                            — שם ערוץ ההתראות
HomeLauncherSelectionScreen.kt            — טקסטים מוטבעים בעברית
AppInfoDialog.kt                          — נושא מייל הפנייה
```

### ערכת הצבעים

הגוון המוביל הוא טורקיז. אדום נשאר **סמנטי בלבד** (`error`), ולא צבע מיתוג.

```text
primary              #00838F
primaryContainer     #B2EBF2
secondary            #00ACC1
tertiary             #00695C
tertiaryContainer    #B9EDE4
background           #F6FDFD
surfaceVariant       #DBEDEF
error                #B3261E  (ללא שינוי, סמנטי)
```

מקורות האמת:

```text
ui/theme/Color.kt          — כל ערכי ה-hex, בשמות לפי תפקיד ולא לפי גוון
ui/theme/Theme.kt          — lightColorScheme מוגדר במלואו
res/values/colors.xml      — md_theme_* עבור Theme.SecureGuard.Kiosk (Views/XML)
res/values/themes.xml      — statusBarColor = @color/brand_turquoise
```

- `LightColorScheme` מגדיר כעת **את כל** תפקידי הצבע. קודם לכן הוגדרו רק `primary/secondary/tertiary/background/surface/error`, ולכן `primaryContainer`, `tertiaryContainer`, `secondaryContainer` ו-`surfaceVariant` נלקחו מברירת המחדל הסגלגלה של Material 3 — זו הסיבה שכרטיסי Mini Store נראו סגולים למרות שהמיתוג היה אדום.
- שמות ה-legacy ב-`colors.xml` (`purple_500`, `purple_700`) נשמרו כדי לא לשבור הפניות קיימות, אך ערכיהם טורקיז.
- מסך הקיוסק אינו צורך את `MaterialTheme` לרקעים שלו; הצבעים שלו מוטבעים ב-`KioskScreen.kt`, `KioskActivity.kt` ו-`KioskViewModel.kt`, ובנוסף ברירת המחדל של `KEY_KIOSK_PRIMARY_COLOR` ב-`SettingsRepositoryImpl` היא `0xFF00838F`. ברירת מחדל חלה רק על מכשיר שלא שמר צבע מותאם.
- אייקון ההשקה עוצב מחדש כמגן זכוכית עם מפתח בגוון שמפניה, על רקע טורקיז כהה שכבתי. מקור העיצוב נשמר ב-`app/src/main/icon/mafteach_launcher.svg`; הוא מומר ל-`VectorDrawable` עבור adaptive icons, ל-`monochrome` עבור Android 13+ ול-PNG בכל צפיפויות ה-legacy. החל מ-`versionCode 7`, ה-Manifest מפנה לשמות cache-busting חדשים: `@mipmap/mafteach_launcher_v2` ו-`@mipmap/mafteach_launcher_round_v2`. כך Android/Samsung Launcher אינו ממשיך להציג resource ישן ששמר במטמון. ה-foreground, background וה-monochrome סונכרנו בפועל לעיצוב ה-SVG החדש.

### אימות שבוצע

```text
:app:assembleDebug + :app:assembleRelease → BUILD SUCCESSFUL
מפתח-0.6.0-debug.apk                    → 136,204,652 bytes, versionCode 4, חתימת debug
מפתח-0.6.0-release.apk                  → 126,327,988 bytes, versionCode 4, unsigned
-PenableReleaseShrinking=true candidate → 110,793,683 bytes; build/identity בלבד
העלאה ל-R2                              → 136,204,652 bytes, SHA-256 תואם לאחר הורדה חזרה
GET get-mafteach-update?channel=stable   → 200, versionCode 4, versionName 0.6.0
GET get-mafteach-update?channel=prebuild → 204
עדכון עצמי במכשיר                        → 3 → 4 הושלם, Device Owner נשמר
```

ה-candidate הממוזער אינו מיועד להתקנה עד שיהיה זמין מפתח החתימה ההיסטורי ויעבור smoke test חתום.


### scripts/release-mafteach.ps1 - בנייה ופרסום לערוץ העדכון בפקודה אחת

הסקריפט שמריצים מתיקיית הפרויקט כדי לשחרר גרסה. ברירת המחדל היא build מסוג `release`, והפרסום משתמש ב-Cloudflare R2 בלבד:

```powershell
powershell -File ".\scripts\release-mafteach.ps1" -ReleaseNotes "מה חדש" -DryRun
powershell -File ".\scripts\release-mafteach.ps1" -ReleaseNotes "מה חדש"
powershell -File ".\scripts\release-mafteach.ps1" -SkipBuild -ApkPath ".\dist\apk\מפתח-0.6.0-release.apk" -DryRun
```

סדר הפעולות, עם עצירה בכל כשל:

```text
בדיקת JAVA_HOME, gradlew, aapt2 ו-apksigner
  -> :app:assembleRelease ללא clean, אלא אם הועבר SkipBuild
  -> aapt2 dump badging: package, versionCode, versionName
  -> apksigner verify: ה-APK חתום והחתימה תקינה
  -> העברה ל-publish-mafteach-update.ps1 בפרויקט האתר
  -> APK + manifest immutable + current.json ב-R2
  -> אימות endpoint ציבורי ו-rollback של pointer במקרה כשל
```

- אין תלות ב-Firebase או ב-Firestore במסלול העדכון. נדרש רק Wrangler מאומת ל-Cloudflare.
- `apksigner` הוא דרישת preflight, וכל APK - debug או release - נעצר אם אינו חתום או אם אימות החתימה נכשל.
- `-ApkPath` בוחר artifact מפורש. ללא הפרמטר, הסקריפט בוחר את ה-APK בעל `versionCode` הגבוה ביותר מתיקיית `app/build/outputs/apk/<buildType>` ולא לפי זמן שינוי.
- `-SkipBuild` אינו מדלג על אימות identity וחתימה.
- `-AllowSameVersionReplacement` מאפשר החלפה מפורשת של pointer באותו versionCode רק למיגרציה של artifact שלא הופץ; ברירת המחדל דורשת עלייה.
- `-SitePath` מצביע כברירת מחדל ל-`C:\projects\site\my-landing-page`, שם נמצא סקריפט הפרסום ל-R2.
- כלי Android כותבים הודעות מידע ל-stderr, ולכן כל קריאה לכלי חיצוני עוברת דרך `Invoke-Native` ונבדקת לפי exit code בלבד.
- הסקריפט אינו מתחבר למכשיר, אינו מתקין דבר, אינו נוגע ב-Device Owner ואינו מסיר אפליקציות. רציפות signer מול התקנה קיימת נאכפת בזמן העדכון על ידי `UpdateManager` ו-Android; סקריפט הפרסום מאמת שה-artifact עצמו חתום ותקין.

### scripts/install-mafteach.ps1 — התקנה חכמה והגדרת Device Owner

סקריפט שמזהה את מצב המכשיר לפני כל פעולה ובוחר מסלול אחד בלבד. ברירת המחדל אינה הרסנית, והוא נשאר מקור האמת גם עבור אשף ה-HTML החזותי.

```text
זיהוי: adb, מכשיר (ללא serial קבוע), APK עדכני, package, versionCode, signer SHA-256,
       Device Owner, מספר חשבונות במכשיר
```

| מצב | מסלול |
|---|---|
| לא מותקן, אין owner, אין חשבונות | `install` + `dpm set-device-owner` |
| מותקן, אותו signer, versionCode גבוה | `install -r`; Device Owner קיים נשמר, ואם אינו מוגדר הסקריפט עשוי להשלים provisioning רק במצב בטוח |
| מותקן, signer שונה, **הוא** Device Owner | עצירה עם exit 2 והוראות שחרור מתוך האפליקציה |
| מותקן, signer שונה, אינו Device Owner | הסרה + התקנה + Device Owner, רק עם `-AllowUninstall` |
| Device Owner של package אחר | עצירה |
| לא ניתן להשוות signer | עצירה עם exit 3 |

- פרמטרים: `-ApkPath`,‏ `-Serial`,‏ `-AllowUninstall`,‏ `-Force`,‏ `-CheckOnly`,‏ `-PlanOutputPath`.
- `-PlanOutputPath` כותב באופן אטומי JSON ב-`schemaVersion=1` ובו המסלול, APK, snapshot של המכשיר, סיבות חסימה ו-`CanExecuteSafely`. הוא מיועד לצריכה על ידי ממשק ולא מחליף את בדיקות הבטיחות לפני ביצוע; כל הרצה בפועל קוראת מחדש את מצב המכשיר.
- `ConvertFrom-DpmOwnersOutput` מפרש רק פורמטים מפורשים של `DeviceOwner`/`Device Owner`/`ComponentInfo`; התוצאה היא `Found`,‏ `None` או `Unknown`. `None` מתקבל רק מפלט מפורש כמו `0 owners:`. exit code כושל, פלט ריק, Profile Owner בלבד או פורמט לא מוכר מחזירים `Unknown` ועוצרים ללא שינוי; `-Force` אינו עוקף מצב בעלות לא ודאי.
- מצב הבעלות נקרא מחדש מיד לפני הסרה אפשרית ושוב לאחר התקנה ולפני `set-device-owner`, כדי למנוע פעולה לפי snapshot שהתיישן.
- ה-APK נבחר לפי הקובץ העדכני ביותר מבין `app/build/outputs/apk/release`,‏ `app/release` ו-`app/build/outputs/apk/debug`, כדי שלא ייבחר artifact ישן.
- השוואת signer מתבצעת על ידי `adb pull` של ה-APK המותקן ל-`app/build/tmp/agent` והרצת `apksigner`; הקובץ הזמני נמחק תמיד.
- הסרה דורשת `-AllowUninstall` ואישור בהקלדת `YES`, אלא אם הועבר `-Force`.
- בסיום מאמת `pm path`,‏ `versionCode` בפועל ו-`dpm list-owners`, ונכשל אם אינם תואמים.
- **אין דרך להסיר Device Owner דרך ADB.** השחרור נעשה מתוך האפליקציה עצמה: `SettingsViewModel.initiateRemoval()` קורא `clearDeviceOwnerApp` ואז מפעיל הסרה. לאחר מכן נדרשת הסרת חשבונות Google כדי ש-`set-device-owner` יצליח. אין צורך באיפוס יצרן.
- `$ErrorActionPreference = "Continue"` מכוון: adb ו-apksigner כותבים הודעות ל-stderr, והסקריפט בודק exit codes במפורש.

### חתימת release — keystore קבוע מחוץ לפרויקט

ה-keystore ההיסטורי לא נמצא באף מקום במחשב (נסרקו `C:\projects`,‏ `.android`,‏ `.gradle`,‏ Documents,‏ Desktop,‏ Downloads,‏ OneDrive וכל הגיבויים). האפליקציה מעולם לא הופצה, ולכן ב-2026-08-18 נוצר keystore חדש וקבוע ל-release.

```text
C:\projects\SecureGuardMDM\signing\mafteach-release.jks     ← המפתח
C:\projects\SecureGuardMDM\signing\keystore.properties      ← נתיב, alias וסיסמאות
C:\projects\SecureGuardMDM\signing\README-KEYSTORE.txt      ← הוראות שחזור
```

```text
alias:       mafteach-release
algorithm:   RSA 4096, SHA256withRSA, PKCS12
validity:    10950 ימים (~30 שנה)
SHA-256:     fe079f9df99d5bee1a9610f7cbf3d2d6a2c4823672b8a04ee7f9966c45ba2599
```

- התיקייה נמצאת **מחוץ** לשורש הפרויקט במכוון. `backup.ps1` אינו מגבה `*.jks` ואינו מגבה `keystore.properties`, ולכן שמירה בתוך הפרויקט הייתה יוצרת ביטחון מדומה. נדרש גיבוי מוצפן נפרד של התיקייה כולה.
- `SIGNING-KEY-LOCATION.md` בשורש הפרויקט הוא מצביע ללא סודות: מקום המפתח, alias, טווח תוקף, ה-signer הצפוי ופקודת אימות. הוא נוצר כדי שהמפתח יימצא מיד גם ללא קריאת כל המפה.
- אימות read-only מ-2026-08-18: שלושת הקבצים קיימים, `keystore.properties` מכיל `storeFile`,‏ `storePassword`,‏ `keyAlias` ו-`keyPassword`, הנתיב שבתוכו נפתר לקובץ אמיתי, ו-`keytool -list -v` פתח את ה-alias `mafteach-release` בהצלחה. ה-fingerprint שהתקבל הוא `fe079f9df99d5bee1a9610f7cbf3d2d6a2c4823672b8a04ee7f9966c45ba2599`, תוקף `18/08/2026`–`10/08/2056`, והוא **זהה** לחותם שנקרא בפועל מ-`מפתח-0.5.0-release.apk`. סריקת `C:\projects` מצאה עותק אחד בלבד של ה-keystore, ולכן אין כרגע יתירות.
- סדר החיפוש של התצורה ב-`app/build.gradle.kts`: `-PsigningPropertiesPath` → `MAFTEACH_SIGNING_PROPERTIES` → ברירת המחדל שלמעלה. אם הקובץ חסר, `assembleRelease` מפיק APK **לא חתום** במקום להיכשל, כדי ש-checkout ללא מפתח יוכל להתקמפל. אין נפילה חזרה למפתח debug, כדי לא לשבור רציפות signer.
- ב-`keystore.properties` הנתיב חייב להיות עם `/` ולא `\`, משום ש-`Properties.load()` מפרש `\` כתו בריחה. הכשל שהתקבל היה `Keystore was not found at C:projectsSecureGuardMDM...`.
- ב-PKCS12 סיסמת המפתח זהה לסיסמת ה-keystore. `keytool` קיבל `-keypass` נפרד אך שמר את הרשומה תחת סיסמת ה-store, וה-build נכשל ב-`Given final block not properly padded`. לכן `keyPassword` ו-`storePassword` חייבים להיות זהים.
- ב-`build.gradle.kts` נדרש `import java.util.Properties`; `java.util.Properties()` המלא אינו נפתר משום ש-`java` נתפס כ-extension של Gradle.
- **המפתח החדש אינו תואם לחתימת ה-debug** (`04a3b2bb…`) שבה נחתמו ההתקנה הקודמת וה-APK שפורסם ב-2026-08-17. לכן אין מסלול עדכון מגרסת debug מותקנת לגרסת release חדשה; נדרשת שחרור והסרה מתוך האפליקציה, הסרת חשבונות Google והתקנה נקייה.

### artifacts חתומים (עודכן 2026-08-19)

```text
app/build/outputs/apk/release/מפתח-0.6-release.apk   versionCode 7   126,557,426 bytes   ← פורסם ב-stable
dist/apk/מפתח-0.6.0-release.apk                      versionCode 4   126,434,728 bytes
dist/apk/מפתח-0.5.0-release.apk                      versionCode 3   126,434,729 bytes
```

**גרסה פעילה בערוץ stable:** `versionCode 7`,‏ `versionName 0.6`, פורסמה ל-Cloudflare R2 ב-2026-08-19. ה-APK חתום ב-signer הקבוע `fe079f9d…45ba2599` וכולל את שמות משאבי האייקון החדשים שמרעננים את מטמון ה-launcher. כתובת ההורדה: `https://downloads.imreykodesh.com/downloads/mafteach/stable/7/32a0e6ca-7453-4e65-a188-29a5b8154d05/68de7275dad29ce628e6bdb72056b0227e6cf1ac5ae40a20fca39441dbcfadcc.apk`.

`Mafteach-Installer.cmd` המעודכן (`127,525` bytes) נמצא באתר וממשיך לשמש להתקנה. artifacts היסטוריים של versionCode 3/4 נשמרים לצורכי תיעוד ובדיקות בלבד.

### אשף HTML חזותי להתקנת מפתח

להפצה למשתמש קיים artifact בודד, `dist/Mafteach-Installer.cmd`, ולפיתוח בתוך הפרויקט קיימת גם הפעלה ישירה:

```text
dist/Mafteach-Installer.cmd            ← קובץ אחד להפצה (self-extracting)
  → פירוק base64 ל-%TEMP%\mafteach-installer-<guid>
  → powershell.exe -STA
  → Start-MafteachInstaller.ps1 -Port 0 -InstallerScriptPath -HtmlFilePath -WorkRoot
  → http://127.0.0.1:<ephemeral-port>/  (index.html)
  → install-mafteach.ps1
  → מחיקת תיקיית הסשן ב-finally

scripts/Start-Mafteach-Installer.cmd   ← הפעלה מתוך עץ הפרויקט
```

- `scripts/build-mafteach-installer-bundle.ps1` בונה את הקובץ הבודד. הוא מטמיע ב-base64 את `install-mafteach.ps1`,‏ `Start-MafteachInstaller.ps1` ו-`index.html`, ולכן **אין מנוע התקנה שני**: ה-artifact נושא את אותו סקריפט בטיחות שנמצא ב-repo. יש להריץ את הבונה מחדש לאחר כל שינוי בשלושת המקורות.
- ה-bundle הוא polyglot batch/PowerShell: שלב ה-batch מסתיים ב-`exit /b`. ה-bootstrap תחום במרקרים `REM #MAFTEACH_BOOTSTRAP_BEGIN#` ו-`REM #MAFTEACH_BOOTSTRAP_END#`, וה-split כולל את התחילית `REM`; כך ה-scriptblock אינו מכיל את שורות ה-payload או `REM` יתום. בלי מרקר הסיום, PowerShell המשיך לפרש את שורות ה-base64 לאחר סגירת האשף וזרק `base64 is not recognized`.
- ה-bundle נשמר ASCII בלבד וללא BOM, והבונה נכשל אם נמצא תו לא-ASCII. כך נמנעת בעיית הקריאה של `cmd.exe` ושל Windows PowerShell 5.1; העברית מגיעה מ-`index.html` המפורק כ-UTF-8.
- `Start-MafteachInstaller.ps1` מקבל `-InstallerScriptPath`,‏ `-HtmlFilePath` ו-`-WorkRoot`, כדי לפעול גם מחוץ לעץ הפרויקט. ללא פרמטרים אלה הוא נופל חזרה לנתיבי הפרויקט.
- **רכישת כלי Android אוטומטית (2026-08-18):** למשתמש קצה אין Android SDK, ולכן `Get-PortableAndroidTool` קיים גם ב-`Start-MafteachInstaller.ps1` וגם ב-`install-mafteach.ps1`. אם `adb.exe` או `aapt2.exe` אינם נמצאים בנתיבים הרגילים, הכלי מורד פעם אחת מהמאגר הרשמי של Google — `platform-tools-latest-windows.zip` ו-`build-tools_r34-windows.zip` — ונשמר תחת `%LOCALAPPDATA%\Mafteach\android-tools\<tool>\`. כל עץ החילוץ נשמר, משום ש-`adb` תלוי ב-DLL שכנות. אין הפצה מחדש של הבינארי בתוך הפרויקט או בתוך ה-bundle, ואין התקנה במחשב המשתמש מלבד הקאש הזה.
- ה-host מכין את `adb` לפני פתיחת הדפדפן, כדי שההורדה החד-פעמית תוצג בקונסולה ולא תתקע את הבקשה הראשונה מהאשף. כשל הכנה אינו מונע פתיחת האשף. `-ValidateOnly` מריץ `Resolve-Adb -NoDownload` ולכן אינו מוריד דבר.
- `apksigner.bat` דורש Java, שאינו קיים אצל רוב המשתמשים. `Get-ApkSignerDigestFromArchive` מחשב את אותו ערך ללא Java: הוא קורא את בלוק חתימת v1 מ-`META-INF/*.RSA`, מפענח `SignedCms`, ומחשב SHA-256 על DER של תעודת החותם. `Get-ApkSignerDigest` מעדיף `apksigner` כשקיים ונופל למימוש הזה; `Get-InstalledSignerDigest` אינו דורש עוד `apksigner`. שני הצדדים בהשוואה מחושבים באותה פונקציה. APK חתום ב-v2/v3 בלבד יחזיר `$null`, שמתורגם ל-`UNKNOWN_SIGNER` ולא להתאמה. אומת מול `מפתח-0.5.0-release.apk`: הערך שהתקבל זהה ל-signer המתועד `fe079f9d…45ba2599`.
- `Select-ApkFile` מריץ את `OpenFileDialog` ב-runspace ייעודי עם `ApartmentState = STA`, ומעביר לו חלון אב שקוף עם `TopMost`. בלי חלון אב הדיאלוג נפתח מאחורי הדפדפן ונראה כאילו הלחיצה לא עשתה דבר, ובלי apartment מובטח הוא עלול לא להיפתח כלל.
- `.hta` נבחן ונדחה: הוא אכן קובץ בודד, אך מרונדר במנוע IE ושובר את ה-CSS המודרני של הממשק, ולעתים חסום על ידי אנטי-וירוס ומדיניות ארגונית.

- `index.html` הוא ממשק RTL עצמאי ללא CDN או dependency חיצונית. הוא מחליף חמישה מסכים: הכנה, בחירת APK, חיבור ובחירת מכשיר, הצגת תוכנית בטיחות, והתקנה/אימות עם פלט טכני מתקפל.
- דפדפן רגיל אינו יכול להריץ `adb`; לכן `Start-MafteachInstaller.ps1` הוא host מקומי מינימלי שמגיש את ה-HTML ומתרגם פעולות UI ל-API. בחירת APK נעשית ב-`OpenFileDialog` מקומי, כדי לא להסתמך על נתיב מוסתר של `<input type=file>`.
- ה-host משתמש ב-`TcpListener` על `IPAddress.Loopback` בלבד ובפורט ephemeral, ואינו דורש URL ACL או הרשאות מנהל. בכל הפעלה נוצר token אקראי בן 32 תווים שמוזרק ל-HTML ונדרש בכותרת `X-Mafteach-Token` בכל API שמשנה או קורא מצב מקומי. התגובות כוללות `no-store`,‏ `nosniff`,‏ CSP,‏ `frame-ancestors 'none'` ו-`form-action 'none'`.
- חיבורי speculative/idle של הדפדפן אינם יכולים להפיל את ה-host: `Read-HttpLine` מחזיר `null` על `IOException` או `ObjectDisposedException`, timeout הקריאה הוא 5 שניות, ולולאת השרת מבודדת כל socket ב-`try/catch` וממשיכה לחיבור הבא. התיקון מונע את הקריסה ההיסטורית `ReadByte ... Unable to read data from the transport connection`.
- ה-endpoints הם `devices`,‏ `pick-apk`,‏ `inspect`,‏ `install` ו-`shutdown`. גוף בקשה מוגבל ל-1 MiB; ה-host נסגר דרך ה-UI או לאחר שעתיים ללא פעילות.
- `inspect` מריץ `install-mafteach.ps1 -CheckOnly -PlanOutputPath`; `install` מריץ מחדש את אותו סקריפט ללא `CheckOnly`, ולכן signer,‏ versionCode,‏ Device Owner וחשבונות נקראים שוב סמוך לפעולה.
- ה-GUI מאפשר ביצוע רק עבור `FRESH_INSTALL` או `UPDATE_IN_PLACE` כאשר `CanExecuteSafely=true`. הוא אינו מעביר ואינו חושף `-AllowUninstall` או `-Force`, ואינו מסיר אפליקציה, חשבון או Device Owner. מסלול חתימה שונה מוצג כחסימה עם הוראות בלבד.
- קובץ ה-host נשמר כ-ASCII כדי להיות תקין ב-Windows PowerShell 5.1 גם ללא BOM; העברית נמצאת ב-HTML שמוצהר UTF-8 ובפלט סקריפט ההתקנה הקיים.

אימות מ-2026-08-17 בוצע ללא מכשיר: PowerShell parser validation ושמונה fixtures עברו עבור פלט חד-שורי, `ComponentInfo`, בלוק רב-שורי `Device Owner`, שילוב Device+Profile, Profile בלבד, `0 owners:`, exit code כושל ופלט לא מוכר. הרשומה ההיסטורית שהציגה `DeviceOwner: none` נפסלה משום שה-parser הישן איחד "אין owner" עם כשל/פלט לא מוכר.

אימות אשף מ-2026-08-18: parser של `install-mafteach.ps1` ושל ה-host עבר ב-Windows PowerShell 5.1; `-ValidateOnly`, חוזה ומבנה HTML, תחביר JavaScript וחוזה CMD עברו. smoke test מקומי אימת טעינת HTML, הזרקת token, `devices` API ו-`shutdown` שסגר את הפורט. באותו שלב לא היה מכשיר מחובר, ולכן `-CheckOnly` אומת כעצירה בטוחה עם exit 1 ללא שינוי.

אימות ה-bundle הבודד מ-2026-08-18: `dist/Mafteach-Installer.cmd` נבנה בגודל ~101 KB, אומת כ-ASCII ללא BOM, עם סימן bootstrap יחיד ו-parser תקין לבלוק ה-PowerShell. שלושת ה-payloads פורקו והושוו ב-SHA-256 מול המקור והיו זהים. הרצה בפועל של הקובץ הבודד הגישה את הממשק בעברית, הזריקה token בן 32 תווים, דחתה token שגוי, והחזירה `devices` API תקין. בדיקה מקצה לקצה מול `R8YW50PKLHY` (SM_A145P) החזירה `FRESH_INSTALL` עם `CanExecuteSafely=false` בגלל חשבון אחד במכשיר, ו-`aapt2` אימת `com.secureguard.mdm`,‏ `versionCode=4`,‏ `versionName=0.6.0`. תיקיית הסשן ב-`%TEMP%` נמחקה והפורט נסגר.

עדכון UI ויציבות מ-2026-08-18: הממשק צומצם למידות קומפקטיות (בסיס `14px`, רוחב מסגרת `980px`, סרגל צד `208px`). אין גופן חיצוני ואין CDN, כדי לשמור על CSP `default-src 'self'`. בורר הקבצים אומת כפעיל: `EnumWindows` זיהה חלון גלוי בשם `Select the Mafteach APK file` עם `topmost=True`. האיור הגנרי במסך הפתיחה והסימן `⌁` בכותרת הוחלפו באייקון הרשמי ממקור `app/src/main/icon/mafteach_launcher.svg`: האיור מוטמע פעם אחת כ-`symbol` וקטורי ונעשה בו `use` בשני המקומות, ללא PNG נוסף וללא בקשת רשת. ב-2026-08-19 תוכן ה-`symbol` סונכרן מחדש לעיצוב המעודכן באותו קובץ מקור, כולל glow, rim, sheen ופרטי המפתח. פריטי `.check-list` עוטפים את תוכנם ב-`span`, ו-token לטיני כגון `versionCode` מבודד בכיווניות ומוצג בשורה עצמאית כדי למנוע יצירת grid item חופף ב-RTL.

ה-bundle המעודכן מ-2026-08-19 הוא בגודל `127,525` bytes וב-SHA-256 `9DBFC2A3D12ED984007DEC54E3ED670EF576330A13A780AA01D9DE6CED47EF04`. אומתו PowerShell parser,‏ `Start-MafteachInstaller.ps1 -ValidateOnly -NoBrowser`,‏ ASCII ללא BOM, שני מופעי `#mafteach-app-icon`, ושלושת ה-payloads ב-bundle מול המקורות ב-SHA-256. בדיקות ההורדה הקרה והקאש של כלי Android שבוצעו ב-2026-08-18 נשארות תקפות למנגנון ולא הורצו מחדש בשינוי החזותי.

תיקון טיפוגרפיה: הניסיון הראשון הגדיר `Segoe UI Variable Text`/`Segoe UI Variable Display` עם משקלים `650`,‏ `750`,‏ `800` ו-`900`. משפחות אלה אינן מכסות עברית במלואה, ולכן ריצות טקסט נפלו לגופן חלופי והדפדפן סינתז עבורן bold — מה שיצר משקל לא אחיד בתוך אותה מילה. המצב הנוכחי: משפחה אחת `"Segoe UI", Tahoma, Arial, sans-serif` לכל הממשק, `font-synthesis: none`, ומשקלים `400`,‏ `600` ו-`700` בלבד שקיימים בפועל במשפחה. אין להחזיר משקלים ביניים או משפחת `Variable`/`Semibold` נפרדת בממשק הזה.

**מצב מכשיר הבדיקה השתנה מול התיעוד ההיסטורי:** נכון ל-2026-08-18 `dpm list-owners` על `R8YW50PKLHY` מחזיר `no owners`, ו-`pm path com.secureguard.mdm` ריק, כלומר האפליקציה אינה מותקנת ואין Device Owner. הבדיקה הייתה read-only ולא ביצעה התקנה, הסרה או `set-device-owner`.