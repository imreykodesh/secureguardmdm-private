# תוכנית שירות עדכוני Google Play פרטי עבור A Bloq

## 1. מטרת המסמך

מסמך זה מקבע את הכיוון הטכני לשירות עדכונים ישיר מתוך A Bloq, ללא פתיחת אפליקציית Google Play Store, ללא העלאת APKs לשרת הפרטי וללא שימוש ב-dispenser הציבורי של Aurora.

היעד הוא לעדכן אפליקציות קיימות במכשיר — אפליקציות משתמש ואפליקציות מערכת שמופצות דרך Google Play — מתוך ממשק ה-Mini Store של A Bloq.

המסמך אינו מכיל ואסור שיוכנסו אליו:

- כתובת חשבון Google אמיתית;
- AAS token;
- App Password או סיסמת Google;
- `MINI_STORE_CLIENT_TOKEN`;
- Render API token;
- מפתחות חתימה או סודות אחרים.

## 2. תשובה ישירה לגבי אמינות

השירות המתוכנן מסוגל לספק את הזרימה הבאה ישירות מתוך A Bloq:

1. סריקת האפליקציות המותקנות.
2. בדיקת גרסאות מול Google Play.
3. קבלת metadata של BASE ו-SPLIT APKs.
4. הורדה ישירה משרתי Google אל המכשיר.
5. אימות package name, versionCode, גודל, SHA-256 ו-signer.
6. התקנה אטומית באמצעות `PackageInstaller` בהרשאות Device Owner.

עם זאת, יש להבחין בין שני סוגי אמינות:

- **אמינות תפעולית לפיילוט מבוקר:** אפשרית, בכפוף ל-AAS תקף, חשבון ייעודי, Render זמין ובדיקות smoke במכשיר אמיתי.
- **אמינות רשמית עם SLA של Google:** אינה קיימת, מפני שהגישה ל-Google Play נעשית באמצעות `gplayapi`, API לא רשמי ומהונדס לאחור.

לכן אין להציג את השירות כתחליף רשמי ל-Managed Google Play. Google יכולה לשנות פרוטוקול, לבטל token או להגביל חשבון. במקרה כזה A Bloq חייבת להיכשל בצורה סגורה ולהמשיך לפעול במצב `catalog-only`.

## 3. גבולות המערכת

### 3.1 כלול

- אפליקציות שכבר מותקנות במכשיר.
- אפליקציות משתמש המופצות דרך Google Play.
- אפליקציות מערכת שיש להן עדכון תואם ב-Google Play.
- BASE APK ו-SPLIT APKs.
- גילוי, הורדה, אימות והתקנה מתוך A Bloq.
- עדיפות לקטלוג החתום של A Bloq כאשר אותו package קיים בשני המקורות.

### 3.2 לא כלול

- עדכוני Android OTA.
- עדכוני One UI או firmware של Samsung.
- Samsung Galaxy Store.
- עקיפת בקרת גישה של Google, Aurora או Play Store.
- התחזות ל-`com.android.vending`, ל-`com.google.android.gms`, לחתימת Google או ל-User-Agent מוגבל.
- שימוש ב-App Password במקום AAS token.
- אחסון או proxy של APKs דרך `firestore-proxy-server`.
- הבטחה שכל אפליקציה תימצא ב-Google Play או תהיה זמינה לחשבון/אזור/מכשיר הנתון.

## 4. ארכיטקטורה

```text
┌─────────────────────────────────────────────────────┐
│ A Bloq / Mini Store                                 │
│                                                     │
│ MiniStoreScreen                                     │
│       │                                             │
│       ▼                                             │
│ MiniStoreViewModel                                  │
│       │                                             │
│       ▼                                             │
│ MiniStoreRepository                                 │
│       ├──────────────► Signed Catalog               │
│       │                    │                        │
│       │                    └─ עדיפות ראשונה         │
│       │                                             │
│       └──────────────► PlayUpdateSource             │
│                            │                        │
│                            ├─ private auth bootstrap│
│                            └─ gplayapi              │
│                                                     │
│ MiniStorePackageOperator                            │
│       ├─ download BASE/SPLIT                        │
│       ├─ verify package/version/hash/signer         │
│       └─ PackageInstaller as Device Owner           │
└─────────────────────────────────────────────────────┘
             │                         ▲
             │ authenticated bootstrap │
             ▼                         │
┌─────────────────────────────────────────────────────┐
│ firestore-proxy-server on Render                    │
│ POST /api/mini-store/play-auth                      │
│                                                     │
│ Render environment only:                           │
│ - PLAY_ACCOUNT_EMAIL                               │
│ - PLAY_ACCOUNT_AAS_TOKEN                           │
│ - MINI_STORE_CLIENT_TOKEN                          │
└─────────────────────────────────────────────────────┘
             │
             │ returns email + AAS over HTTPS
             ▼
┌─────────────────────────────────────────────────────┐
│ A Bloq builds encrypted Google Play AuthData        │
│ and then downloads APK artifacts directly          │
│ from Google; APK bytes do not pass through Render.  │
└─────────────────────────────────────────────────────┘
```

## 5. רכיבי השרת

Repository חיצוני:

```text
C:\projects\firestore-proxy-server
```

קבצים מרכזיים:

```text
index.js
miniStorePlayAuth.js
render.yaml
```

Endpoint:

```http
POST https://firestore-proxy-server.onrender.com/api/mini-store/play-auth
Authorization: Bearer <client-token>
Content-Type: application/json
```

הבקשה מכילה מאפייני מכשיר הנדרשים לבניית Play session. אין לשמור או ללוג את גוף הבקשה.

תגובה מוצלחת:

```json
{
  "email": "<server-side-account>",
  "aasToken": "<server-side-aas>"
}
```

השרת מיישם:

- POST בלבד;
- Bearer authentication;
- השוואה קבועת-זמן של client token;
- body limit של 64 KiB;
- schema validation;
- rate limit בזיכרון התהליך;
- `Cache-Control: no-store` ו-`Pragma: no-cache`;
- ללא redirects;
- ללא לוג של Authorization, email, AAS או גוף תגובה;
- `503` כאשר secrets חסרים;
- `401` כאשר client token חסר או שגוי;
- `429` כאשר ה-rate limit נחצה.

## 6. רכיבי Android

קבצים מרכזיים:

```text
app/build.gradle.kts
app/src/main/java/com/secureguard/mdm/ministore/play/GPlayHttpClient.kt
app/src/main/java/com/secureguard/mdm/ministore/play/PlayUpdateSource.kt
app/src/main/java/com/secureguard/mdm/ministore/play/PlayCredentialStore.kt
app/src/main/java/com/secureguard/mdm/ministore/data/MiniStoreRepository.kt
app/src/main/java/com/secureguard/mdm/ministore/install/MiniStorePackageOperator.kt
```

תצורת build:

```text
MINI_STORE_PLAY_DISPENSER_URL
MINI_STORE_PLAY_CLIENT_TOKEN
```

חלופות Gradle property:

```text
miniStorePlayDispenserUrl
miniStorePlayClientToken
```

כללי build:

- URL ו-client token חייבים להיות מוגדרים יחד.
- ה-URL חייב להיות HTTPS ללא credentials, query או fragment.
- `auroraoss.com` ותת-הדומיינים שלו חסומים.
- client token חייב להכיל לפחות 32 תווים ללא whitespace.
- ללא שני הערכים, המקור נשאר כבוי וה-Mini Store פועל במצב `catalog-only`.

## 7. זרימת authentication

```text
PlayUpdateSource.ensureAuth()
      │
      ├─ בודק AuthData בזיכרון
      ├─ טוען AuthData מוצפן מהאחסון
      ├─ מאמת session שמור
      └─ אם session אינו קיים או אינו תקף:
             │
             ▼
         POST מאומת ל-Render
             │
             ▼
         email + AAS token
             │
             ▼
         AuthHelper.Token.AAS
         isAnonymous = false
             │
             ▼
         Google Play AuthData
             │
             ▼
         הצפנה ושמירה מקומית
```

`PLAY_ACCOUNT_AAS_TOKEN` חייב להיות AAS אמיתי ממקור מורשה. App Password אינו AAS ואסור להכניס אותו לשדה זה.

אין לייצר AAS באמצעות כלי שמתחזה לזהות או לחתימת Google. אם אין AAS מורשה, השירות נשאר לא פעיל ואין לעקוף את החסימה.

## 8. שמירת credentials במכשיר

`PlayCredentialStore` שומר רק את `AuthData` שנוצר:

```text
Kotlin Serialization
      → AES/GCM/NoPadding
      → Android Keystore
      → AtomicFile
      → noBackupFilesDir/mini_store_play_auth.bin
```

מאפיינים:

- מפתח AES נשמר ב-Android Keystore.
- IV אקראי לכל הצפנה.
- Additional Authenticated Data קשור ל-URL של השירות.
- שינוי endpoint מבטל session קודם.
- קובץ פגום נמחק.
- הקובץ אינו נכלל בגיבוי Android רגיל.
- email או tokens אינם מוצגים ב-UI ואינם נכתבים ללוג.

## 9. גילוי עדכונים

1. A Bloq קוראת את האפליקציות המותקנות ואת `versionCode` שלהן.
2. חבילות נבדקות מול Google Play בקבוצות של עד 30.
3. כשל batch גורר fallback לבדיקה יחידנית.
4. מועמד נוצר רק כאשר:

```text
Play versionCode > installed versionCode
```

5. כשל בחבילה בודדת אינו מסמן אותה בטעות כמעודכנת.
6. קטלוג חתום גובר על מועמד Google Play לאותו package.

## 10. הורדה והתקנה

A Bloq מורידה את artifacts ישירות מה-URLs ש-Google מחזירה.

בדיקות חובה:

- HTTPS בלבד;
- host מותר;
- בדיוק BASE APK אחד;
- SPLIT APKs נתמכים;
- OBB ו-PATCH אינם נתמכים;
- גודל חיובי ותואם metadata;
- SHA-256 תקין ותואם;
- package name תואם;
- versionCode חדש ותואם;
- signer ממשיך את חתימת ההתקנה הקיימת;
- כל ה-splits חתומים באותה חתימה;
- התקנה אטומית ב-`PackageInstaller.Session`.

אין להחליש בדיקות package/version/hash/signer כדי לגרום לעדכון לעבור.

## 11. אבטחת תקשורת

- auth client אינו עוקב אחרי redirects.
- connect timeout: 90 שניות.
- read timeout: 120 שניות.
- call timeout: 150 שניות.
- גוף תגובת auth מוגבל ל-64 KiB.
- retry יחיד לאחר 3 שניות עבור 502/503/504 או כשל תעבורה זמני.
- אין retry לאחר 401/403 או תגובה גדולה מדי.
- Render free עשוי להיכנס ל-cold start; timeout/retry נועדו להתמודד עם wake-up, לא להסתיר כשל credentials.

## 12. מודל האיום והגבלות MVP

### 12.1 Client token בתוך APK

ה-client token מוזרק בזמן build ואינו נמצא ב-source או ב-Git. עם זאת, ניתן לחלץ אותו מ-APK. לכן הוא בקרת גישה בסיסית בלבד.

לפיילוט חובה:

- להשתמש בחשבון Google ייעודי בלבד;
- ללא email אישי, מסמכים, אנשי קשר, אמצעי תשלום או הרשאות נוספות;
- להכין יכולת rotation של AAS ושל client token;
- להגביל rollout למספר מכשירים קטן עד אימות יציבות.

לפני rollout רחב נדרש מנגנון חזק יותר, כגון device-bound authentication או mTLS עם rotation.

### 12.2 API לא רשמי

`gplayapi` אינו API רשמי של Google. סיכונים:

- שינוי פרוטוקול;
- ביטול token;
- חסימת חשבון;
- הבדלי אזור/מכשיר/רישוי;
- זמינות שונה של אפליקציות;
- שינוי במבנה BASE/SPLIT.

### 12.3 Render free

- השירות עשוי להירדם.
- cold start עשוי להוסיף עשרות שניות.
- rate limit נשמר בזיכרון ומתאפס לאחר restart.
- השירות הקיים כולל רכיבים נוספים, ולכן blast radius גדול יותר משירות auth ייעודי.

## 13. התנהגות כשל ו-fallback

```text
אין URL/token ב-build
      → catalog-only

Render לא זמין
      → שגיאת מקור Play
      → הקטלוג החתום ממשיך להיבדק

AAS אינו תקף
      → אין Play session
      → אין הורדה או התקנה מ-Play

Google משנה API
      → כשל סגור
      → אין החלשת אימות

קטלוג חתום מספק עדכון
      → הוא מקבל עדיפות על Play
```

ה-UI חייב להבדיל בין:

- אין עדכון;
- לא נבדק;
- מקור Play כבוי;
- מקור Play נכשל;
- כשל בחבילה בודדת.

## 14. שלבי פריסה

### שלב א — Render environment

להגדיר ידנית ב-Render, ללא הדבקה בצ'אט או בקוד:

```text
PLAY_ACCOUNT_EMAIL
PLAY_ACCOUNT_AAS_TOKEN
MINI_STORE_CLIENT_TOKEN
```

הערות:

- `PLAY_ACCOUNT_AAS_TOKEN` הוא AAS אמיתי, לא App Password.
- `MINI_STORE_CLIENT_TOKEN` חייב להיות זהה לערך שמשמש בזמן build.
- `render.yaml` מכיל רק שמות משתנים עם `sync: false`.

### שלב ב — בדיקת endpoint חי

יש לאמת בלי להציג body או secrets:

- ללא Bearer: `401` כאשר השירות מוגדר;
- Bearer שגוי: `401`;
- Bearer נכון אבל account/AAS חסר: `503`;
- Bearer נכון וכל הערכים קיימים: `200` וסכמה תקינה;
- headers כוללים `Cache-Control: no-store`;
- אין redirect;
- אין email/token בלוגי Render.

### שלב ג — build מחובר

ה-build קורא את URL וה-client token מה-environment המקומי. אין לכתוב את הערכים ל-`gradle.properties`, למסמך, ל-Git או ל-command history.

בדיקות:

```text
Diagnostics נקיים
:app:assembleDebug עובר
package name תואם
versionCode מתאים
חתימת APK תואמת להתקנה הקיימת
```

### שלב ד — התקנה במכשיר

לפני התקנה:

- לוודא serial נכון;
- לוודא שהמכשיר עדיין Device Owner;
- לוודא חתימת debug מול ההתקנה הקיימת;
- לא להסיר את האפליקציה;
- לא לנקות data;
- לא לבצע factory reset.

התקנה היא update מעל ההתקנה הקיימת בלבד.

### שלב ה — smoke test

1. לפתוח A Bloq.
2. להיכנס ל-Mini Store.
3. לוודא שהחשבון או token אינם מוצגים.
4. להפעיל בדיקת עדכונים.
5. לוודא שאין פתיחה של Google Play Store.
6. לוודא שמועמדי Play מופיעים רק כאשר הגרסה גבוהה יותר.
7. לבחור אפליקציית בדיקה לא קריטית.
8. לוודא BASE/SPLIT, hash, package, version ו-signer.
9. לוודא שההתקנה הושלמה וש-Device Owner נשמר.
10. לבצע reboot smoke נפרד רק אם נדרש ובאישור מתאים.

## 15. ניטור ותחזוקה

יש לנטר ללא פרטי חשבון:

- מספר בקשות auth לפי status code;
- שיעור 401/429/503;
- זמן cold start;
- כשלי יצירת Play session;
- כשלי discovery לפי מספר packages, לא לפי email/token;
- כשלי artifact validation;
- שינויי גרסת `gplayapi` רק לאחר build ו-smoke מלאים.

Rotation:

1. להגדיר AAS חדש ב-Render.
2. לשמור את הישן רק עד אימות החדש, מחוץ ל-Git ולגיבוי.
3. לנקות session במכשירי בדיקה בדרך מבוקרת.
4. לבדוק התחברות מחדש.
5. לבטל credential ישן.

## 16. Rollback ו-kill switch

ה-kill switch הפשוט הוא build ללא:

```text
MINI_STORE_PLAY_DISPENSER_URL
MINI_STORE_PLAY_CLIENT_TOKEN
```

התוצאה היא `catalog-only` ללא בקשת auth חיצונית.

בשרת ניתן להשבית את השירות באמצעות הסרת `MINI_STORE_CLIENT_TOKEN` או ה-AAS מ-Render environment; endpoint יחזיר `503` ולא ימסור credentials.

Rollback אינו כולל:

- מחיקת נתוני מכשיר;
- הסרת Device Owner;
- uninstall של A Bloq;
- החלשת בדיקות signer/hash;
- מעבר ל-dispenser הציבורי של Aurora.

## 17. קריטריוני הצלחה

הפיילוט נחשב מוכן רק כאשר:

- שלושת ערכי Render הוגדרו בלי להיכנס לקוד/לוג/גיבוי;
- endpoint חי מחזיר 200 רק עם Bearer תקין;
- A Bloq בונה `AuthData` מ-AAS תקף;
- Mini Store מגלה לפחות תרחיש עדכון אמיתי אחד;
- BASE/SPLIT יורדים ישירות מ-Google;
- כל בדיקות האימות עוברות;
- ההתקנה מצליחה מעל package קיים;
- Google Play Store אינו נפתח;
- email/AAS/client token אינם מוצגים למשתמש;
- Device Owner נשמר;
- build ו-diagnostics עוברים;
- נוצר snapshot סופי עם `Verified: True`.

## 18. מצב נוכחי

> **מצב מעודכן ל-2026-08-16:** הסעיף הזה מתאר את מסלול ה-AAS מהשרת, שנסגר. עדכוני Google Play **עובדים בפועל** באמצעות התחברות בתוך A Bloq (22.3א), ואורך חיי ה-session, האבחון והסטטוס מתועדים בסעיף 23. יש לקרוא את הסעיף הזה כהיסטוריה של הניסיון שנכשל, לא כמצב הנוכחי.

נכון ל-2026-08-14:

- endpoint השרת ממומש, נפרס ל-Render והחזיר `200` בניסיון מחובר עם Bearer תקין וסכמת `email` + `aasToken` תקינה.
- Android client ממומש, נבנה עם תצורה מחוברת והגיע בפועל עד `AuthHelper.build()`.
- timeouts, retry, response limit ואחסון מוצפן ממומשים.
- client token מקומי נשמר ב-Windows user environment לצורך build; ערכו אינו מתועד.
- יצירת Google Play session נכשלה לפני שמירת `AuthData` ולפני תחילת discovery.
- שירות Play אינו פעיל ואינו מאומת end-to-end. הקטלוג החתום נשאר מסלול ה-fallback.

### 18.1 ניסיון authentication המחובר האחרון — נכשל

#### מה אומת

זרימת ה-runtime הגיעה לנקודה הבאה:

```text
A Bloq
  → POST מאומת ל-Render
  → HTTP 200
  → JSON עם email + aasToken לא-ריקים
  → PlayUpdateSource.ensureAuth()
  → AuthHelper.build(Token.AAS, isAnonymous=false)
  → GooglePlayException.AuthException(401)
  → "Could not generate OAuth Token"
```

מכאן נובע שבניסיון זה Render, ה-Bearer, התעבורה, פענוח ה-JSON ובדיקת השדות הלא-ריקים לא היו נקודת הכשל. אין בכך הוכחה שה-credential שהוחזר היה AAS תקף.

לא נקראו ולא תועדו email, token מלא, client token או Render token. ה-metadata הלא-סודי היחיד שנבדק לגבי הערך שהוחזר:

```text
Length: 80
Prefix: oauth2_4/
```

ה-prefix והאורך אינם מוכיחים תקפות. הם מצביעים על OAuth login token קצר-חיים, לא על AAS ארוך-חיים שמסלול `Token.AAS` דורש.

#### נקודת הכשל בקוד A Bloq

`PlayUpdateSource.ensureAuth()` ב-`app/src/main/java/com/secureguard/mdm/ministore/play/PlayUpdateSource.kt`:

1. מקבל את תגובת Render.
2. בודק רק ש-`email` ו-`aasToken` אינם ריקים; אין בדיקת סוג, prefix או אורך.
3. קורא:

```kotlin
AuthHelper.using(httpClient).build(
    dispenserAuth.email,
    dispenserAuth.aasToken,
    AuthHelper.Token.AAS,
    false,
    properties,
    Locale.getDefault(),
)
```

4. החריגה נזרקת בתוך `build()`, ולכן הבדיקות של `authData.authToken` ו-`deviceConfigToken` אינן מגיעות לביצוע.
5. `PlayCredentialStore.save()` אינו מתבצע ולא נוצר session מוצפן.
6. `discover()` נעצר לפני `AppDetailsHelper`; לא מתחילים גילוי, הורדה או התקנה מ-Google Play.

`MiniStoreRepository` לוכד את החריגה, משאיר את `playSourceState` כ-`FAILED` ומעביר את `error.message` ללא שינוי ל-`sourceWarning`. `MiniStoreScreen` מציג לכן במדויק את הודעת הספרייה בכרטיס אזהרת המקור. הקטלוג החתום עדיין יכול להיבדק כ-fallback, אך חבילות שלא נבדקו מול Play אינן מסומנות בטעות כמעודכנות.

#### פירוק `gplayapi:3.6.4` המקומי

ה-AAR המקומי נבדק ב-bytecode ללא חילוץ קבוע וללא קריאת secrets. הממצאים:

- `AuthHelper.Token` מכיל רק `AAS` ו-`AUTH`; אין `OAUTH`.
- `AuthHelper.build(..., Token.AAS, ...)` מכניס את הקלט ל-`AuthData.aasToken` ומשאיר `authToken` ריק בתחילת הזרימה.
- לאחר check-in והעלאת device config, ענף `AAS` קורא `GooglePlayApi.generateToken(..., Service.GOOGLE_PLAY)`.
- `generateToken()` שולח את `AuthData.aasToken` כפרמטר `Token` ל-`https://android.clients.google.com/auth` ומצפה לשדה `Auth` בתגובה.
- אם השדה `Auth` חסר, הספרייה עצמה זורקת בדיוק:

```text
GooglePlayException.AuthException(
    code = 401,
    message = "Could not generate OAuth Token"
)
```

- זוהי שגיאה מקומית של `gplayapi` על תגובת Google ללא `Auth`; היא אינה קוד ה-HTTP של Render ואינה מוכיחה לבדה אם הסיבה היא סוג token שגוי, token שפג, חסימת חשבון או שינוי בצד Google.
- `GooglePlayApi.generateAASToken()` הוא מסלול נפרד שקורא `AuthData.oAuthLoginToken`; `AuthHelper.build(Token.AAS)` אינו מפעיל אותו.
- `Token.AUTH` אינו תיקון ל-`oauth2_4/`: הוא מתייחס לקלט כ-Play `Auth` סופי ואינו מבצע OAuth→AAS או AAS→Auth.

מסקנת הניסיון: ה-endpoint החזיר OAuth login token תחת שם השדה `aasToken`, ולכן ה-bootstrap היה חסר שלב. אין לחזור על אותה תצורה ואין להחליף ל-`Token.AUTH` או ל-App Password.

### 18.2 מה Aurora Store עושה בפועל

נבדקה גרסת Aurora Store העדכנית `4.8.4`, שפורסמה ב-2026-07-27. היא משתמשת באותה dependency כמו A Bloq:

```text
com.auroraoss:gplayapi:3.6.4
```

לכן הכשל אינו מוסבר בהבדל גרסה של `gplayapi`. ההבדל הוא ב-bootstrap של credentials.

#### כניסה אישית ב-Aurora

במקור `4.8.4` הזרימה היא:

```text
Web login
  → oauthToken
  → AuthViewModel.buildAuthData()
  → AC2DMTask.getAC2DMResponse(email, oauthToken)
  → POST ל-android.clients.google.com/auth
  → קריאת השדה Token מהתגובה כ-aasToken
  → AuthEvent.GoogleLogin(email, aasToken)
  → AuthHelper.build(Token.AAS)
```

כלומר Aurora אינה שולחת את `oauth2_4/` ישירות ל-`AuthHelper.Token.AAS`; היא ממירה אותו קודם ל-AAS בבקשת AC2DM נפרדת. זהו בדיוק השלב שחסר בניסיון של A Bloq.

היישום של Aurora ב-`AC2DMTask` שולח זהויות `com.google.android.gms` וחתימת caller קבועה. ב-`GoogleAccountTokenProvider` קיימת גם זרימת `AccountManager`/microG עם `overridePackage` ו-`overrideCertificate` של Play Store. מנגנונים אלה אינם API רשמי של Google ומתנגשים עם החלטת הפרויקט שלא להתחזות ל-package, לחתימה או לזהות מוגבלת; לכן אין להעתיק אותם ל-A Bloq בלי review מפורש שמשנה את גבולות האבטחה.

#### כניסה אנונימית ב-Aurora

הזרימה האנונימית שונה:

```text
Aurora public dispenser
  → email + Play Auth סופי
  → AuthHelper.build(Token.AUTH, isAnonymous=true)
```

ה-dispenser אינו מחזיר `oauth2_4/` תחת `AAS` ואינו מחייב את הלקוח לבצע את המרת OAuth→AAS. זה מסביר מדוע כניסה אנונימית עשויה לעבוד כאשר כניסה אישית או יצירת AAS נכשלות. ה-dispenser הציבורי עדיין חסום במפורש ב-A Bloq ואינו מקור נתמך לפרויקט צד שלישי.

#### תיעוד תקלות Aurora

התקלות אינן ייחודיות ל-A Bloq:

- [Aurora Store discussion #91](https://github.com/whyorean/AuroraStore/discussions/91), שנפתחה ב-2026-03-02, מתעדת ב-`4.7.5` בדיוק `Could not generate AAS Token`, כאשר anonymous login המשיך לעבוד. הדיון נסגר כ-`resolved` ב-2026-07-27 ללא הסבר maintainer שמוכיח מה תוקן.
- [Release 4.8.1](https://gitlab.com/AuroraOSS/AuroraStore/-/releases/4.8.1), מ-2026-02-16, מציינת `Fix login issues`, ללא פירוט חוזה token.
- [Release 4.8.4](https://gitlab.com/AuroraOSS/AuroraStore/-/releases/4.8.4), מ-2026-07-27, מוסיפה בין היתר תמיכה במספר חשבונות Google; המקור עדיין כולל את המרת OAuth→AAS הלא-רשמית.
- [Aurora Store issue #1273](https://gitlab.com/AuroraOSS/AuroraStore/-/work_items/1273) מתעדת outage של anonymous token dispenser עם `Session generation failed` ו-`Time out`; התקלה נפתחה ב-2025-01-21 ונסגרה ב-2025-01-27.
- [Aurora Store issue #1105](https://gitlab.com/AuroraOSS/AuroraStore/-/work_items/1105) ו-[issue #793](https://gitlab.com/AuroraOSS/AuroraStore/-/work_items/793) מתעדים כשלים היסטוריים נוספים הן בכניסה אישית והן בכניסה אנונימית.

הדיווחים מוכיחים ששני המסלולים של Aurora תלויים בשירותים לא-רשמיים ונכשלים מעת לעת מסיבות שונות. הם אינם מוכיחים outage פעיל נכון ל-2026-08-14 ואינם הופכים את המנגנון ל-API נתמך של Google.

פרטי המקורות החיצוניים לעיל נוסחו מחדש לצורך עמידה במגבלות רישוי; הקישורים מפנים למקור המלא.

### 18.3 החלטת המשך לאחר הכשל

אין לבצע שוב את אותו smoke עם `oauth2_4/` בשדה `PLAY_ACCOUNT_AAS_TOKEN`. חידוש מסלול Play דורש אחת מהחלטות ה-review הבאות:

1. AAS אמיתי שכבר התקבל ממקור מורשה, ללא App Password וללא התחזות; או
2. שינוי מפורש של גבולות האבטחה ואישור נפרד לבחינת זרימת OAuth→AAS הלא-רשמית של Aurora; או
3. מעבר למסלול רשמי כגון Managed Google Play; או
4. המשך הפעלה ב-`catalog-only` עם artifacts ממקור מורשה.

עד לקבלת החלטה, אין שינוי נדרש ב-`PlayUpdateSource`: השירות נשאר ממומש אך כבוי/לא מאומת, ואין להחליש בדיקות credentials, package, version, hash או signer.

## 19. החלטות שאסור לשנות בלי review מפורש

- אין לפתוח את Google Play Store כחלק מזרימת העדכון.
- אין להשתמש ב-Aurora public dispenser.
- אין להתחזות לזהות, package, חתימה או User-Agent מוגבלים.
- אין להכניס credentials ל-source, Git, מסמכים או backup.
- אין להעביר APKs דרך Render.
- אין להחליש אימות package/version/hash/signer.
- אין להסיר Device Owner או למחוק נתוני מכשיר לצורך בדיקה.
- אין להציג את השירות כ-Managed Google Play רשמי.

## 20. אפשרויות עדכון נוספות — נבדקו ונדחו

> **הכרעה:** שתי החלופות בסעיף זה אינן מסלול מוצר. 20.1 נבדקה end-to-end על מכשיר ונכשלה ביצירת Google Play session; 20.5 נדחתה מטעמי רישוי, תשתית ותמיכת schema. הפירוט המלא והתוצאות המדויקות בסעיף 22. הסעיף נשמר לתיעוד ההחלטה ולשימוש חוזר ברכיבים שנבנו.

האפשרויות בסעיף זה הן **תכנון להמשך ולא יכולת שאומתה במכשיר**. אין להפעיל אותן בייצור לפני build, בדיקות אבטחה ו-smoke test מלא. בפרט, עצם קיומה של אפליקציה ב-Google Play אינו מבטיח שהחשבון, האזור, פרופיל המכשיר או חתימת ההתקנה הקיימת יאפשרו לעדכן אותה.

### 20.1 רישום אוטומטי של אפליקציות מותקנות ועדכון ישיר מ-Google Play

#### מטרה ותשובה מעשית

ניתן לגרום לכך שכל התקנה או החלפה של package במכשיר תפעיל בדיקת עדכון מתוך A Bloq, גם אם ההתקנה המקורית בוצעה ידנית או באמצעות חנות אחרת. במסלול המומלץ **קובצי העדכון אינם נשמרים בשרת A Bloq**:

```text
התקנה/החלפה של package
      → package-change receiver
      → תור עבודה עמיד
      → קריאת packageName/versionCode/signer מהמכשיר
      → בדיקת metadata מול Google Play באמצעות PlayUpdateSource
      → הורדת BASE/SPLIT ישירות מ-Google למכשיר
      → אימות package/version/hash/signer
      → התקנה אטומית כ-Device Owner
      → מחיקת קובצי staging
```

השרת הפרטי נשאר control plane בלבד: bootstrap מאומת ל-Play, רישום צי אופציונלי, מדיניות ותזמון. אין צורך להעביר דרכו APKs ואין לשמור בו URLs זמניים של Google.

#### תנאי התאמה להתקנה שבוצעה מחוץ ל-Play

עדכון מעל התקנה קיימת אפשרי רק כאשר כל התנאים הבאים מתקיימים:

- `packageName` של ההתקנה זהה לזה שב-Google Play;
- חתימת גרסת Play זהה לחתימה הנוכחית או ממשיכה signing lineage תקין;
- `versionCode` ב-Play גבוה מהגרסה המותקנת;
- האפליקציה זמינה לחשבון, לאזור ולפרופיל המכשיר שמשמשים את `gplayapi`;
- לחשבון קיימת entitlement מתאימה כאשר האפליקציה בתשלום;
- ה-delivery מורכב מ-BASE/SPLIT נתמכים ואינו דורש OBB/PATCH שאינם נתמכים כעת;
- Android אינו חוסם את ההחלפה בגלל update ownership או מדיניות מתקין אחרת.

חתימה כלשהי אינה מספיקה: נדרשת **אותה זהות חתימה**. APK מקורי של Play שהותקן ידנית אמור להיות מועמד מתאים; build שנחתם מחדש, גרסת F-Droid, mod או build מאתר המפתח שנחתם במפתח אחר חייבים להיכשל בצורה סגורה. אסור להסיר את האפליקציה או למחוק את נתוניה כדי לעקוף חוסר התאמה.

### 20.2 מה כבר קיים וניתן לשימוש חוזר

אין צורך לבנות מנוע עדכונים חדש. הרכיבים הבאים כבר ממומשים:

- `MiniStoreRepository.loadInstalledApps()` קורא את כל ה-APKs המותקנים ואת `versionCode` שלהם;
- `PlayUpdateSource.discover()` בודק package names בקבוצות של עד 30 ומבצע fallback לבדיקה יחידנית;
- `PlayUpdateSource.resolve()` מקבל BASE ו-SPLIT metadata מ-Google Play;
- `MiniStorePackageOperator` מוריד ישירות מ-hosts מורשים של Google, מאמת גודל ו-SHA-256;
- לפני התקנה נבדקים package name, versionCode, minSdk, signer וחתימות עקביות בין splits;
- ההתקנה מתבצעת ב-`PackageInstaller.Session` ודורשת ש-A Bloq תישאר Device Owner;
- קובצי staging נמחקים לאחר הצלחה או כשל.

נכון למימוש הנוכחי קיימים גם:

- `InstalledPackageInventoryProvider`, שמשותף ל-Worker ול-`MiniStoreRepository` ואינו טוען icons;
- `MiniStorePackageChangeReceiver` ייעודי עם `package:` scheme;
- `WorkManager` בגרסה מקובעת `2.9.1`, עם עבודה ייחודית לכל package, debounce, network constraint ו-backoff;
- סיווג retry צמוד ל-discovery ומוגן מ-race מול `resolve()` באמצעות mutex משותף; רק IO מתאים ו-`408/429/500/502/503/504` מסומנים זמניים;
- `MiniStoreUpdateCoordinator`, שמבצע discovery בלבד ואינו מתקין;
- `MiniStoreUpdateCheckEntity`/DAO וטבלת Room בגרסת מסד `4` עם migration `3→4`;
- שמירת metadata מצומצם בלבד, ללא credentials, download URLs או exception messages;
- מחיקה מיידית בעת blacklist, reconciliation מול inventory בטעינת Mini Store ואימות חוזר של version לפני כתיבת תוצאה.

עדיין חסרים חיבור Flow של תוצאות הרקע ל-`MiniStoreViewModel`, notification מרוכזת ועבודה תקופתית. כל עוד Play אינו מוגדר, ה-Worker שומר `SOURCE_DISABLED` ואינו מבצע התחברות.

> **מצב מעודכן:** מקור Google Play נבדק בפועל ונדחה. ראה סעיף 22. שכבת הגילוי ברקע נשארת בתוקף ואינה תלויה במקור מסוים.

### 20.3 מצב מימוש Android ודרישות המשך

#### א. ספק inventory שאינו תלוי ב-UI

קריאת החבילות חולצה מתוך `MiniStoreRepository` למחלקה:

```text
app/src/main/java/com/secureguard/mdm/ministore/inventory/InstalledPackageInventoryProvider.kt
```

החוזה שמומש:

```kotlin
data class InstalledPackageRecord(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val signerSha256: Set<String>,
    val isSystemApp: Boolean,
)

interface InstalledPackageInventoryProvider {
    fun get(packageName: String): InstalledPackageRecord?
    fun getAll(): List<InstalledPackageRecord>
}
```

ה-provider משתמש ב-`PackageManager.GET_SIGNING_CERTIFICATES`, תומך ב-signing history ב-API 28 ומעלה ואינו מחזיר icon/`Drawable`. כך Worker משתמש בו בלי לטעון מודלי UI כבדים. `MiniStoreRepository` משתמש באותו provider כדי למנוע שני מימושי inventory שונים.

#### ב. Receiver ייעודי לשינויי package

קיים `receivers.InstallReceiver`, אך הוא מצפה ל-`PackageInstaller.EXTRA_STATUS` ול-`package_name` ולכן אינו משמש כטריגר כללי ל-`PACKAGE_ADDED`/`PACKAGE_REPLACED`. נוסף receiver נפרד:

```text
app/src/main/java/com/secureguard/mdm/ministore/background/MiniStorePackageChangeReceiver.kt
```

הרישום שמומש ב-`AndroidManifest.xml`:

```xml
<receiver
    android:name=".ministore.background.MiniStorePackageChangeReceiver"
    android:enabled="true"
    android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.PACKAGE_ADDED" />
        <action android:name="android.intent.action.PACKAGE_REPLACED" />
        <action android:name="android.intent.action.PACKAGE_REMOVED" />
        <data android:scheme="package" />
    </intent-filter>
</receiver>
```

ה-Receiver יבצע רק parsing ותזמון; אסור לבצע בו רשת או Play authentication:

```kotlin
val packageName = intent.data?.schemeSpecificPart ?: return
val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
scheduler.enqueuePackageChanged(packageName, intent.action.orEmpty(), replacing)
```

יש להתעלם מחבילה ריקה, מ-A Bloq עצמה ומ-`PACKAGE_REMOVED` שמתקבל כחלק מהחלפה כאשר אין צורך בעבודה כפולה. כל input מה-broadcast ייבדק מחדש מול `PackageManager`; אין לסמוך על package name חיצוני לצורך הורדה או התקנה.

#### ג. תור עבודה עמיד

מומשה תלות `androidx.work:work-runtime:2.9.1` בגרסה מדויקת התואמת ל-`compileSdk 34`. ה-Worker ניגש ל-coordinator דרך Hilt `EntryPoint`, ולכן לא נוספה תלות Hilt-Work. אין לבצע רשת ארוכה ישירות מתוך `BroadcastReceiver`.

קבצים שמומשו:

```text
app/src/main/java/com/secureguard/mdm/ministore/background/MiniStoreUpdateCheckScheduler.kt
app/src/main/java/com/secureguard/mdm/ministore/background/MiniStoreUpdateCheckWorker.kt
```

כללי התזמון:

- `OneTimeWorkRequest` ייחודי לכל package לאחר `PACKAGE_ADDED`/`PACKAGE_REPLACED`;
- `Constraints` עם `NetworkType.CONNECTED`;
- debounce, למשל unique work בשם `mini_store_check_<hash(packageName)>`, כדי למנוע סערת בדיקות;
- exponential backoff עבור רשת, `429`, `502`, `503` ו-`504` בלבד;
- כשל credentials אינו retry אינסופי אלא מצב `AUTH_REQUIRED`/`SOURCE_FAILED`;
- עבודה תקופתית אחת לבדיקת inventory מלא, בתדירות שמרנית, כגיבוי לאירוע שהוחמץ;
- Worker לעולם אינו מתקין אוטומטית בלי שמדיניות מפורשת מאפשרת זאת. ברירת המחדל היא גילוי והצגת מועמד למשתמש.

זרימת Worker מוצעת:

```kotlin
val installed = inventoryProvider.get(packageName) ?: return Result.success()
val result = playUpdateSource.discover(mapOf(installed.packageName to installed.versionCode))
stateStore.saveChecked(packageName, installed.versionCode, result)
notificationPublisher.publishIfUpdateAvailable(packageName)
```

אין לשמור `UpdatePlan` או URLs של artifacts: URLs שמוחזרים מ-Google זמניים. `resolve()` יופעל מחדש רק כאשר המשתמש מתחיל את ההתקנה.

#### ד. שמירת מצב בדיקה

כדי שתוצאת Worker תישמר לאחר שהתהליך נסגר, נוסף מאגר מצב מקומי. המבנה שמומש:

```text
MiniStoreUpdateCheckEntity
- packageName (primary key)
- installedVersionCode
- availableVersionCode nullable
- availableVersionName nullable
- source
- status: UPDATE_AVAILABLE | NO_UPDATE | CHECK_FAILED | SOURCE_DISABLED
- checkedAtEpochMillis
- failureCode nullable
```

המימוש משתמש ב-Room וב-migration `3→4`. אין שמירה של email, AAS, Play auth token, כתובת download או הודעת חריגה שעלולה להכיל מידע רגיש. חיבור `observeAll()` ל-UI נשאר לשלב הבא.

לפני הצגת מועמד יש לוודא ש-`installedVersionCode` עדיין זהה לערך שעליו בוצעה הבדיקה. שינוי package מבטל את הרשומה ומפעיל בדיקה חדשה.

#### ה. תיאום ו-UI

המחלקה שמומשה:

```text
app/src/main/java/com/secureguard/mdm/ministore/domain/MiniStoreUpdateCoordinator.kt
```

היא מרכזת כעת את הזרימה הבאה במקום לשכפל אותה ב-Worker:

```text
inventory → discover → classify retry → persist result
```

חיבור התוצאה ל-UI ו-`resolve()` יישארו בשכבות הקיימות עד לשלב הבא. `MiniStoreViewModel` יצטרך לצרוך Flow של מצב הבדיקות ולהציג בנפרד:

- עדכון זמין;
- מעודכן;
- לא נמצא ב-Play;
- לא נבדק;
- מקור Play כבוי;
- authentication נכשל;
- חתימת ההתקנה אינה מתאימה, אם הדבר התגלה לאחר הורדה.

Notification אופציונלית תפתח את מסך Mini Store בלבד. אין להתחיל הורדה או התקנה מתוך Receiver, notification action או background Worker ללא מדיניות מפורשת ואימות מחדש של מצב החבילה.

### 20.4 API אופציונלי לרישום צי — ללא אחסון APK

רישום בשרת אינו נדרש כדי לבדוק עדכון במכשיר יחיד. הוא שימושי רק לצי מכשירים, תצוגת ניהול, מדיניות rollout ותזמון. endpoint מוצע:

```http
POST /api/mini-store/inventory
Authorization: Bearer <device-bound-credential>
Content-Type: application/json
Cache-Control: no-store
```

Payload מינימלי:

```json
{
  "schemaVersion": 1,
  "deviceId": "<pseudonymous-id>",
  "packages": [
    {
      "packageName": "example.package",
      "versionCode": 123,
      "signerSha256": ["<sha256>"]
    }
  ]
}
```

תגובה אפשרית:

```json
{
  "accepted": 1,
  "nextCheckAfterSeconds": 21600,
  "policyRevision": 4
}
```

כללי שרת:

- לא לקבל APK, icon, app label, email משתמש או רשימת חשבונות;
- לבצע allowlist של שדות, body limit, rate limit ו-schema validation;
- לבצע upsert לפי `deviceId + packageName` ולשמור `lastSeenAt`;
- לא ללוג Authorization, signer מלא או payload מלא;
- לא להשתמש ב-client token משותף שמוטמע ב-APK כזהות מכשיר לפריסה רחבה;
- לפריסה רחבה להעדיף מפתח מכשיר שנוצר ב-Android Keystore, enrollment של public key וחתימת request עם nonce/timestamp למניעת replay;
- לא להחזיר URLs של Google או Play credentials דרך endpoint זה;
- למחוק רשומות לפי מדיניות retention מוגדרת.

השרת יכול להחזיר מדיניות כגון תדירות בדיקה, packages מוחרגים ו-rollout percentage. בדיקת Play וההורדה נשארות במכשיר. אם בעתיד השרת יבצע discovery בעצמו, זהו שירות חדש עם סיכוני חשבון, device profile ו-API לא רשמי; אין להוסיף אותו אגב endpoint ה-inventory.

### 20.5 חלופה: שרת שמוריד ומארח APKs מ-Google

החלופה אפשרית טכנית אך **אינה מומלצת כברירת מחדל** ואינה חלק מה-MVP:

```text
Inventory → backend Play client → download BASE/SPLIT → object storage
          → signed metadata → A Bloq download → verify → install
```

היא מוסיפה את הבעיות הבאות:

- אין API ציבורי רשמי להורדה והפצה מחדש של APKs שרירותיים מ-Google Play;
- entitlement, אפליקציות בתשלום, אזור ו-device targeting תלויים בחשבון ובפרופיל;
- App Bundles מייצרים split sets שונים לפי ABI, density, locale ו-SDK;
- download URLs קצרים בזמן ואינם קטלוג קבוע;
- נדרש אחסון immutable, deduplication, retention וניקוי artifacts;
- נדרש אישור רישוי להפצה מחדש של כל אפליקציה;
- חשבון השירות עלול להיחסם והפרוטוקול הלא-רשמי עלול להשתנות;
- נדרש קטלוג או manifest חתום גם אם יצירתו אוטומטית, כדי שהשרת או האחסון לא יוכלו להחליף APK בשקט.

אם נבחר מסלול זה בעתיד, יש לבצע threat model ו-review משפטי נפרדים. אין ליישמו באמצעות proxy עיוור או להסתמך על HTTPS בלבד. קטלוג חתום אינו חייב להיות ידני: pipeline יכול לזהות גרסה, לאמת package/version/signer/hash, להעלות artifacts immutable ולחתום revision אוטומטית.

### 20.6 סדר מימוש מומלץ

#### שלב 1 — MVP מקומי ללא API inventory

מצב נוכחי:

1. `InstalledPackageInventoryProvider` נוצר ו-`MiniStoreRepository` משתמש בו — הושלם.
2. `MiniStorePackageChangeReceiver` עם `<data android:scheme="package" />` — הושלם.
3. תור עבודה עמיד עם dependency מקובעת, unique work, debounce ו-backoff — הושלם.
4. `MiniStoreUpdateCoordinator` מרכז את זרימת ה-Worker — הושלם; חיבור ה-ViewModel אליו עדיין חסר.
5. תוצאת בדיקה נשמרת ב-Room — הושלם; הצגתה ב-Mini Store עדיין חסרה.
6. ההתקנה נשארת פעולה יזומה של המשתמש — נשמר במפורש.

שלב זה אינו נחשב מאומת end-to-end. ה-smoke test בוצע ונכשל במקור Play עצמו, לא בשכבת הרקע; ראה סעיף 22. לא נוסף API שרת ולא מאוחסנים APKs.

#### שלב 2 — בדיקה תקופתית והתראות

1. להוסיף periodic work שמרני עם network constraint ו-jitter.
2. להציג notification אחת מרוכזת ולא notification לכל package.
3. להוסיף invalidation לאחר `PACKAGE_REPLACED`/`PACKAGE_REMOVED`.
4. להוסיף kill switch מקומי ומדיניות blacklist קיימת.

#### שלב 3 — API inventory אופציונלי

1. להגדיר schema ו-retention לפני כתיבת endpoint.
2. ליישם device-bound authentication או להגביל את ה-MVP למכשירי בדיקה.
3. לשלוח delta לאחר package event ו-snapshot מלא תקופתי לתיקון פערים.
4. להוסיף dashboard/rollout רק לאחר שהנתונים המקומיים הוכחו כנכונים.

#### שלב 4 — בחינת התקנה אוטומטית

התקנה אוטומטית תישקל רק לאחר שהגילוי והאימות יציבים. היא דורשת policy מפורשת, חלון תחזוקה, battery/network constraints, rollback תפעולי והחרגה של חבילות קריטיות. גם במצב אוטומטי אין לעקוף signer mismatch, אין לבצע downgrade ואין להסיר package כדי להתקין מחדש.

### 20.7 תוכנית בדיקה מעשית

יש להשתמש באפליקציות בדיקה לא קריטיות ולשמור את מצב Device Owner. אין למחוק נתונים או להסיר אפליקציה כדי לגרום לתרחיש לעבור.

| תרחיש | תוצאה צפויה |
|---|---|
| APK מקורי של Play מותקן ידנית, וב-Play קיימת גרסה חדשה עם אותו signer | מועמד מתגלה; BASE/SPLIT יורדים; העדכון מותקן והנתונים נשמרים |
| package זהה אך APK נחתם מחדש | גילוי עשוי להצליח; ההתקנה נעצרת ב-signer validation ללא uninstall |
| package אינו קיים ב-Play | `NOT_FOUND`/לא נתמך, ללא ניסיון הורדה |
| הגרסה המקומית שווה או גבוהה מ-Play | אין מועמד ואין downgrade |
| אפליקציה בתשלום ללא entitlement | כשל סגור ומסווג, ללא ניסיון לעקוף רכישה |
| אפליקציה עם splits לפי ABI/density/locale | BASE וכל ה-splits נכתבים לאותה Session ונבדקים |
| OBB/PATCH נדרש | מסומן לא נתמך; אין התקנה חלקית |
| Render או AAS אינם זמינים | `AUTH_REQUIRED`/`SOURCE_FAILED`; אין סימון שגוי כמעודכן |
| רשת נופלת באמצע הורדה | Session אינה committed; staging נמחק; ניתן retry מבוקר |
| מתקבלים broadcasts כפולים | נוצרת עבודה ייחודית אחת ללא סערת בקשות |
| package מוחלף בזמן שהבדיקה רצה | version נבדק מחדש לפני resolve/install והמועמד הישן נפסל |
| reboot לאחר גילוי | מצב הבדיקה נשמר והעבודה התקופתית חוזרת ללא התקנה אוטומטית |
| A Bloq אינה Device Owner | התקנה נדחית; אין fallback להתקנה לא מאומתת |

בדיקת signer חייבת לכלול לפחות:

- APK שהותקן ישירות מ-Play או עותק זהה שלו;
- APK מאותו package שנחתם במפתח בדיקה אחר;
- אם האפליקציה משתמשת ב-Play App Signing, APK מערוץ חיצוני כדי לבדוק האם הוא נחתם במפתח ההפצה של Play או במפתח אחר.

### 20.8 קריטריוני החלטה אם הגישה נכונה

הגישה תיחשב מתאימה לפיילוט רק כאשר:

- שלוש אפליקציות בדיקה שונות שהותקנו מחוץ לחנות אך חתומות בזהות Play מתעדכנות ללא אובדן נתונים;
- אפליקציה בעלת signer שונה נחסמת לפני commit ואינה מוסרת;
- לפחות אפליקציה אחת עם splits מתעדכנת בהצלחה;
- package events מפעילים עבודה גם כשה-UI סגור;
- broadcast כפול, reboot וכשל רשת אינם יוצרים התקנה כפולה או מצב שקרי;
- אין APKs, Play credentials או download URLs בשרת inventory או בלוגים;
- אין regression בקטלוג החתום, blacklist, kiosk או Device Owner;
- כשל ה-AAS הנוכחי נפתר במסלול מורשה או שהבדיקה מסומנת כחסומה ולא כהצלחה.

גם לאחר הצלחת הפיילוט אין להגדיר את המסלול כוודאי או רשמי: `gplayapi` נשאר API לא רשמי. אם נדרשת התחייבות ארוכת טווח שאינה תלויה בו, החלופות הן Managed Google Play/EMM או מאגר artifacts מורשה שבשליטת הארגון עם metadata חתום.

## 21. מסלול חלופי מומלץ — קטלוג חתום ואוטומטי ללא AAS

### 21.1 החלטת היתכנות

מסלול זה הוא החלופה הישימה כאשר אין AAS ממקור מורשה ואין רצון לעבור לתשתית Android Enterprise מלאה. הוא אינו מתחבר ל-Google Play בשם A Bloq ואינו תלוי ב-`gplayapi`, ב-Render או בחשבון Google במכשיר.

הזרימה היא:

```text
מקור APK מורשה
      │
      ▼
Ingestion/Publisher שבשליטת הארגון
      ├─ אימות מקור והרשאת הפצה
      ├─ אימות package/version/minSdk
      ├─ אימות חתימת APK ו-SHA-256
      ├─ העלאה ל-R2 בשם immutable
      └─ יצירת catalog.json חתום Ed25519
                    │
                    ▼
Cloudflare Pages + R2
                    │
                    ▼
A Bloq Mini Store
      ├─ אימות חתימת הקטלוג ומניעת rollback
      ├─ הורדת APK רלוונטי בלבד
      ├─ אימות package/version/hash/signer
      └─ התקנה אטומית כ-Device Owner
```

המסלול אפשרי בפועל עבור APKs שהארגון רשאי לקבל ולהפיץ, כגון:

- אפליקציות בבעלות הארגון;
- APKs שמגיעים מ-release feed רשמי של הספק ומותרים להפצה ארגונית;
- artifacts שמתקבלים ישירות מהיצרן או ממערכת build שבשליטת הארגון;
- אפליקציות קוד פתוח כאשר הרישיון ומקור ההפצה מאפשרים זאת.

המסלול אינו הופך scraping של Google Play, שימוש ב-Aurora dispenser או הפצה מחדש ללא הרשאה למותרים. אין להוסיף מקור רק משום שניתן טכנית להוריד ממנו APK.

### 21.2 מה כבר קיים ומה עדיין לא מומש

כבר קיים ואומת בקוד:

- `publish-apk.ps1` קורא APK מקומי, מאמת package, `versionCode`, `minSdk`, חתימת APK, SHA-256 וגודל;
- העלאת object immutable ל-Cloudflare R2 תחת package/version/hash;
- אימות `HTTP 200` וגודל לאחר ההעלאה;
- הגדלת `revision` ועדכון `publishedAt`;
- `generate-catalog.js` שמנרמל את המקור, חותם Ed25519 ומוודא שהמפתח הפרטי תואם למפתח הציבורי;
- Android client שמאמת Ed25519, schema, package, version, URL immutable, hash ו-signer;
- anti-rollback מקומי באמצעות revision ו-payload digest ב-`noBackupFilesDir`;
- עדיפות למועמד מהקטלוג החתום על פני מועמד Play.

עדיין לא קיים:

- רכיב שמאתר אוטומטית release חדש אצל ספק מורשה;
- allowlist מתועד של מקורות, packages וחתימות מותרות;
- retry בפרסום ל-R2 ובבדיקת הקטלוג החי;
- cache מקומי של הקטלוג המאומת האחרון ב-A Bloq;
- retry להורדת APK שנקטעה;
- תמיכת schema של הקטלוג החתום ב-BASE + SPLIT; schema v1 תומך ב-APK יחיד בלבד;
- ניטור מתוזמן והתראות על כשלי sync או פרסום.

לכן הסעיפים הבאים הם תוכנית יישום; אין להציגם כמנגנון שכבר הושלם או נבדק end-to-end.

### 21.3 אילו נתונים עולים לשרת

ל-Cloudflare עולים רק חומרי הפצה ציבוריים או ארגוניים:

- APK מאושר להפצה;
- `catalog.json` חתום;
- metadata לא-סודי: package, version, minSdk, גודל, hash, signer ותיאור גרסה.

אין צורך להעלות:

- רשימת אפליקציות מותקנות מהמכשיר;
- מזהה Device Owner, הגדרות MDM או נתוני משתמש;
- email, AAS, OAuth, Play Auth או client token;
- מפתח החתימה הפרטי של הקטלוג;
- release keystore של APK;
- קוד מקור של A Bloq.

בקשת download רגילה חושפת ל-CDN את כתובת ה-IP ואת נתיב ה-object, שמכיל package/version/hash. יש להגדיר שמירת לוגים מינימלית לפי מדיניות הפרטיות, ואין להוסיף query parameters עם מזהה מכשיר או משתמש.

### 21.4 רכיבי האתר ומקורות האמת

Repository האתר:

```text
C:\projects\site\my-landing-page
```

קבצים קיימים:

```text
scripts/secureguard-mini-store/publish-apk.ps1
scripts/secureguard-mini-store/generate-catalog.js
scripts/secureguard-mini-store/catalog-lib.js
secureguard-mini-store/catalog.source.json
downloads/secureguard-mini-store/catalog.json
secureguard-mini-store/public-key.json
secureguard-mini-store/.private/catalog-ed25519.pkcs8.b64
```

יעדי הפרסום:

```text
Catalog:
https://imreykodesh.com/downloads/secureguard-mini-store/catalog.json

APK objects:
https://downloads.imreykodesh.com/downloads/secureguard-mini-store/
```

המפתח הפרטי אינו עולה לאתר, ל-R2, ל-Git או לגיבוי הרגיל. לפני הרחבת השימוש בקטלוג חובה ליצור עבורו גיבוי מוצפן, אופליין ובמיקום נוסף, ולבדוק שחזור בלי להציג את ערכו.

### 21.5 שלב ראשון — פיילוט ידני מבוקר

מטרת שלב זה היא להוכיח את כל הזרימה עם APK יחיד ולא-קריטי ממקור מורשה לפני הוספת אוטומציה.

דרישות מקדימות:

1. APK standalone/universal חתום; אין להשתמש רק ב-BASE של App Bundle שדורש splits.
2. package כבר מותקן במכשיר הבדיקה בגרסה ישנה יותר.
3. חתימת העדכון ממשיכה את החתימה של ההתקנה הקיימת.
4. `CLOUDFLARE_API_TOKEN` מוגדר בסביבה המקומית עם הרשאת R2 הנדרשת, בלי להדפיס או להעביר אותו בפקודה.
5. Wrangler מחובר לחשבון הנכון.
6. מפתח Ed25519 הפרטי קיים במיקום המקומי המתועד או ב-`SECUREGUARD_MINISTORE_PRIVATE_KEY_B64` בסביבה מאובטחת.

פקודת פרסום מתוך פרויקט האתר:

```powershell
$SiteRoot = "C:\projects\site\my-landing-page"
$ApkPath = "C:\path\to\authorized-update.apk"

powershell.exe -NoProfile -ExecutionPolicy Bypass `
  -File "$SiteRoot\scripts\secureguard-mini-store\publish-apk.ps1" `
  -ApkPath $ApkPath `
  -DisplayName "Approved App" `
  -ReleaseNotes "Approved maintenance update"
```

הסקריפט אמור לבצע, בסדר זה:

1. `aapt2 dump badging` וחילוץ package/version/minSdk;
2. `apksigner verify --print-certs` וחילוץ signer SHA-256;
3. חישוב SHA-256 וגודל;
4. יצירת R2 key immutable:

```text
downloads/secureguard-mini-store/<package>/<versionCode>/<sha256>.apk
```

5. העלאה ל-`imreykodesh-downloads`;
6. בדיקת HEAD וגודל מול ה-origin הציבורי;
7. החלפת הרשומה הקודמת לאותו package רק אם `versionCode` גבוה יותר;
8. הגדלת revision;
9. יצירת קטלוג חתום ואימות החתימה שנוצרה.

המתג `-SkipUpload` אינו Dry Run מלא: הוא מדלג על R2 אך עדיין משנה את `catalog.source.json` ומייצר קטלוג. אין להשתמש בו כבדיקת no-write.

לאחר הרצת הסקריפט יש לבדוק ידנית שרק הקבצים הצפויים השתנו:

```text
secureguard-mini-store/catalog.source.json
downloads/secureguard-mini-store/catalog.json
```

הפרסום ל-Cloudflare Pages מתבצע רק לאחר review ופעולת Git מפורשת בפרויקט האתר. אין לבצע commit או push אוטומטי מתוך A Bloq או מתוך משימת תחזוקה שלא ביקשה Git במפורש.

### 21.6 אימות השרת לאחר פרסום

בלי להציג secrets:

```powershell
$CatalogUrl = "https://imreykodesh.com/downloads/secureguard-mini-store/catalog.json"
$response = Invoke-WebRequest -Uri $CatalogUrl -Method Get -MaximumRedirection 0
if ($response.StatusCode -ne 200) { throw "Catalog returned HTTP $($response.StatusCode)" }
if ($response.Headers['Content-Type'] -notmatch 'application/json') { throw "Unexpected catalog content type" }
```

יש לאמת גם:

- `schemaVersion=1`;
- `algorithm=Ed25519`;
- `keyId=sgms-d4887b656ff9d398`;
- revision חדש וגבוה מהקודם;
- object ה-APK מחזיר `HTTP 200` וגודל תואם;
- אין redirect לדומיין אחר;
- הקטלוג החי זהה לקובץ הציבורי שנבדק ופורסם.

אין להסתפק ב-HTTP 200: האימות האמיתי מתבצע גם ב-A Bloq מול המפתח המוטמע ומול anti-rollback.

### 21.7 smoke test במכשיר

על המכשיר הקבוע, ורק לאחר אימות serial ו-Device Owner:

1. לפתוח את Mini Store ולבצע refresh.
2. לוודא שמצב הקטלוג הוא `CHECKED`.
3. לוודא שהמועמד מופיע רק עבור package מותקן ורק כאשר `versionCode` גבוה יותר.
4. לבחור אפליקציה לא-קריטית.
5. לוודא שלבי download, hash verification, package/version/signer verification והתקנה.
6. לוודא שהגרסה המותקנת תואמת לגרסה שבקטלוג.
7. לוודא שהאפליקציה עדיין נפתחת ושהמצב hidden/suspended נשמר אם היה קיים.
8. לוודא ש-A Bloq נשארה Device Owner.
9. לבצע refresh נוסף ולוודא שהעדכון אינו מוצע שוב.

אין להסיר את A Bloq, לנקות data או להסיר Device Owner במקרה כשל.

### 21.8 שלב שני — allowlist של מקורות מורשים

לפני כתיבת downloader אוטומטי יש להוסיף בפרויקט האתר קובץ תצורה לא-סודי, לדוגמה:

```text
secureguard-mini-store/authorized-sources.json
```

לכל package יישמרו לפחות:

```json
{
  "packageName": "org.example.app",
  "displayName": "Example App",
  "sourceType": "vendor-release-feed",
  "releaseMetadataUrl": "https://vendor.example/releases/latest.json",
  "allowedDownloadHosts": ["downloads.vendor.example"],
  "expectedSignerSha256": ["<approved-sha256>"],
  "distributionApprovalReference": "internal-approval-id",
  "channel": "stable",
  "enabled": true
}
```

כללים:

- אין לשמור token או credential בקובץ;
- `releaseMetadataUrl` ו-hosts חייבים להיות HTTPS וב-allowlist קשיח;
- `expectedSignerSha256` נקבע מראש ממקור אמין ואינו נלמד אוטומטית מה-APK החדש;
- שינוי signer דורש review נפרד והוכחת signing lineage;
- `distributionApprovalReference` הוא מזהה פנימי בלבד, ללא מידע אישי;
- package לא מאושר נשאר מחוץ לקטלוג גם אם נמצא עבורו release חדש.

### 21.9 שלב שלישי — רכיב sync אוטומטי

הרכיב המוצע, שטרם קיים:

```text
scripts/secureguard-mini-store/sync-authorized-updates.ps1
```

אחריותו המדויקת:

1. לטעון ולוודא schema של `authorized-sources.json`.
2. לעבור רק על entries עם `enabled=true`.
3. לבקש release metadata מ-host מורשה.
4. לדחות redirect ל-host שאינו מורשה.
5. להשוות version מול `catalog.source.json` בלי לבצע downgrade או reuse של revision.
6. להוריד artifact לתיקייה זמנית מחוץ ל-source או תחת build/temp שאינו מתפרסם.
7. להגביל גודל לפני ובמהלך ההורדה.
8. לחשב SHA-256 ולאמת signer מול allowlist לפני העלאה.
9. לוודא package מדויק, version חדש ו-APK standalone.
10. לקרוא ל-`publish-apk.ps1` עבור artifact שעבר את כל הבדיקות.
11. למחוק קובץ זמני ב-`finally`.
12. להחזיר exit code שונה עבור `no update`, כשל זמני, כשל trust וכשל פרסום.
13. לא ללוג headers, tokens, URLs חתומים זמניים או גוף תגובה רגיש.

אין לאפשר ל-release feed לקבוע בעצמו:

- package אחר;
- signer חדש;
- יעד R2;
- כתובת catalog;
- מפתח חתימה;
- פקודת shell לביצוע.

### 21.10 retry בצד המפרסם

retry מותר רק לכשל זמני:

```text
IOException / timeout
HTTP 408
HTTP 429, תוך כיבוד Retry-After בגבול מוגדר
HTTP 500 / 502 / 503 / 504
```

מדיניות מומלצת:

```text
ניסיון 1: מיידי
ניסיון 2: אחרי 2–4 שניות עם jitter
ניסיון 3: אחרי 8–12 שניות עם jitter
```

אין retry אוטומטי עבור:

- signer לא תואם;
- hash או גודל לא תואמים;
- package שונה;
- version שאינו חדש;
- schema לא תקין;
- redirect ל-host לא מורשה;
- `401` או `403`;
- כשל חתימת הקטלוג.

פרסום revision חדש מתבצע רק לאחר שה-APK קיים ב-R2 ואומת. אם upload הצליח אך יצירת הקטלוג נכשלה, ה-object הבלתי-מקושר יכול להישאר ב-R2; אין לפרסם קטלוג חלקי ואין למחוק object באופן אוטומטי במהלך recovery.

### 21.11 עמידות נדרשת בצד Android

`MiniStoreCatalogClient` הנוכחי מבצע ניסיון download יחיד ואינו שומר last-known-good catalog. כדי שהמנגנון ימשיך לעבוד בזמן נפילות קצרות יש לממש בשלב נפרד:

1. עד שלושה ניסיונות לקטלוג עבור timeout/IO ו-`408/429/500/502/503/504` בלבד.
2. backoff עם jitter וכיבוד `Retry-After` בגבול של עד 60 שניות.
3. ללא retry על חתימה שגויה, schema שגוי, payload גדול או rollback.
4. שמירת envelope מאומת אחרון באמצעות `AtomicFile` תחת `noBackupFilesDir`.
5. טעינת cache רק לאחר אימות Ed25519 מלא, revision ו-payload digest מול רצפת ה-anti-rollback.
6. הצגת מצב נפרד `CACHED`/אזהרה, כדי שלא להציג cache ישן כאילו נבדק מול השרת עכשיו.
7. אין למחוק cache תקין בגלל כשל רשת; יש למחוק רק אם אימות מקומי נכשל.
8. הורדת APK: retry מוגבל מהתחלה לאחר ניקוי הקובץ החלקי; אין retry אוטומטי לאחר hash/signer/package mismatch.
9. לפני retry של התקנה יש לבדוק אם version היעד כבר הותקן. אין ליצור session נוסף כאשר מצב session קודם אינו ידוע.

הקטלוג המאומת האחרון אינו עוקף freshness: יש להציג למשתמש מתי בוצעה הבדיקה החיה האחרונה. חתימה תקינה מוכיחה מקור ושלמות, לא שהמידע חדש.

### 21.12 מגבלת BASE/SPLIT

schema v1 של הקטלוג החתום מתאר APK יחיד. לכן הפיילוט תומך רק ב-standalone/universal APK שניתן להתקין לבדו.

עבור אפליקציה שמופצת רק כ-App Bundle ונדרשים לה splits, אין לפרסם BASE לבדו. הרחבה עתידית דורשת schema v2 מתואם בכל הרכיבים:

```text
catalog-lib.js
publish-apk.ps1 או publisher חדש שמקבל artifact set
MiniStoreCatalogClient.kt ומודלי הקטלוג
UpdateLocator.SignedCatalog
MiniStorePackageOperator.kt
```

רשומת v2 צריכה לכלול מערך artifacts עם role מסוג `BASE` או `SPLIT`, שם, גודל ו-SHA-256 לכל קובץ, בדיוק BASE אחד, signer אחיד ומגבלת כמות. כל הקבצים יותקנו באותה `PackageInstaller.Session`. אין לשנות schema פעיל בלי backward compatibility ו-build/smoke מלאים.

### 21.13 תזמון והרצה

התחלה מומלצת:

- הרצה ידנית של הפיילוט;
- לאחר הצלחה, scheduled task יומי על runner ייעודי ומאובטח;
- רק לאחר מספר מחזורי פרסום תקינים, מעבר ל-CI מתוזמן.

ה-runner צריך:

- Node.js ו-Android build-tools;
- Wrangler בגרסה מקובעת ומאומתת;
- הרשאת R2 מצומצמת ל-bucket/prefix הנדרש;
- גישה למפתח Ed25519 דרך secret store או קובץ מוצפן שנפתח לזמן הריצה;
- workspace נקי ובידוד בין runs;
- לוגים ללא secrets;
- התראה על כשל, ללא פרסום אוטומטי חוזר אינסופי.

Cloudflare Pages build אינו המקום המועדף לשמירת המפתח הפרטי. עדיף לחתום על runner בשליטת הארגון ולהעלות/לפרסם רק את הקטלוג החתום.

### 21.14 rollback ו-kill switch

עצירת sync:

```text
enabled=false ב-authorized-sources.json
או השבתת ה-scheduled task
```

הסרת package מהצעות חדשות:

1. להסיר את הרשומה מ-`catalog.source.json`;
2. להגדיל revision;
3. לחתום ולפרסם קטלוג חדש.

אין להקטין revision ואין למחזר revision עם payload שונה. APK ישן שכבר הותקן אינו מוסר מהמכשיר אוטומטית. object immutable ישן אינו נמחק כחלק מ-rollback רגיל, כדי לא לשבור מכשיר שטען קטלוג תקף קודם ועדיין מוריד אותו.

במקרה של חשד לדליפת מפתח הקטלוג:

- לעצור פרסום;
- לא למחוק את המפתח המוטמע מהאפליקציה הקיימת בלי תוכנית rotation;
- להכין build חדש עם trust root/rotation policy;
- אין לקבל קטלוג לא חתום כפתרון זמני.

### 21.15 קריטריוני הצלחה למסלול הקטלוג האוטומטי

המסלול נחשב מוכן לפיילוט רק כאשר:

- לכל package יש מקור והרשאת הפצה מתועדים;
- signer allowlist נקבע מראש;
- מפתח הקטלוג מגובה בצורה מוצפנת ונבדק שחזורו;
- publisher דוחה package/version/signer/hash לא תואמים;
- APK עולה ל-R2 בשם immutable ומאומת מה-origin הציבורי;
- הקטלוג נחתם, מתפרסם עם revision חדש ומתקבל ב-A Bloq;
- כשל קטלוג חי משתמש רק ב-cache מאומת ומסומן, לאחר שהשיפור ימומש;
- כשל source אחד אינו מפרסם קטלוג חלקי או package לא מאומת;
- APK אמיתי עובר הורדה, אימות והתקנה על מכשיר בדיקה;
- Device Owner נשמר;
- אין email, AAS, OAuth, client token או נתוני מכשיר בשרת;
- build, diagnostics ו-smoke עוברים;
- snapshot סופי של פרויקט Android מאומת עם `Verified: True` לאחר כל שינוי בו.

### 21.16 סדר היישום המומלץ

```text
1. גיבוי מוצפן ונבדק למפתח Ed25519
2. בחירת APK standalone לא-קריטי ממקור מורשה
3. פיילוט ידני עם publish-apk.ps1
4. אימות קטלוג חי ו-smoke מלא במכשיר
5. הוספת retry + last-known-good catalog ל-Android
6. הוספת authorized-sources.json
7. כתיבת sync-authorized-updates.ps1
8. בדיקות כשל: timeout, 429, 503, hash/signer/package mismatch
9. scheduled task על runner ייעודי
10. rollout הדרגתי וניטור
11. schema v2 ל-BASE/SPLIT רק אם קיים צורך מוכח
```

המסלול הזה אינו מבטיח שכל אפליקציה ב-Google Play תהיה זמינה. הוא כן מספק מנגנון עדכונים שניתן לשלוט בו, לבדוק אותו end-to-end ולהפעיל אותו ללא התחזות וללא תלות ב-AAS.

## 22. תוצאות הבדיקה בפועל — מסלול private Play נסגר, והוחלף בהתחברות על המכשיר

סעיף זה מתעד בדיקה שבוצעה end-to-end על המכשיר `R8YW50PKLHY` מול חשבון Google ייעודי. הוא מחליף כל הערכה קודמת לגבי היתכנות המסלול. **המסקנה: מסלול ה-AAS אינו בסיס למוצר ואין להמשיך בו.**

### 22.1 מה כן אומת כתקין

כל השכבות למעט יצירת ה-session עבדו:

| שלב | תוצאה |
|---|---|
| שליפת `oauth2_4` מ-`accounts.google.com/EmbeddedSetup` | הצליח, לאחר שהתברר שה-cookie הוא `HttpOnly` ואינו נגיש מ-`document.cookie` |
| המרת `oauth2_4` ל-`aas_et` מול `android.clients.google.com/auth` | הצליח |
| endpoint פרטי ב-Render | `HTTP 200`, מחזיר `email` ו-`aasToken` תקינים |
| סוג הטוקן שחוזר מהשרת | `aas_et/`, באורך 217 |
| אימות `client token` | תקין, ללא `401` |
| build עם המקור מופעל | `BuildConfig` מכיל שני ערכים לא ריקים |
| התקנה מעל התקנה קיימת | `Success`, ללא אובדן נתונים |
| Device Owner לפני ואחרי | נשמר |

### 22.2 נקודת הכשל המדויקת

בעת רענון ב-Mini Store, `logcat` החזיר:

```text
W/MiniStore: Google Play update check failed: PlayDiscoveryException
Caused by: AuthException(code=403, reason=Error=BadAuthentication)
    at com.aurora.gplayapi.GooglePlayApi.generateToken(GooglePlayApi.kt:158)
    at com.aurora.gplayapi.helpers.AuthHelper.build(AuthHelper.kt:70)
    at PlayUpdateSource.ensureAuth(PlayUpdateSource.kt:190)
```

הכשל אינו בהורדת הטוקן ואינו בשרת. הוא ב-`AuthHelper.build`, כלומר בהמרת ה-AAS ל-Google Play session.

הסיבה הישירה: ה-AAS הונפק תוך שימוש ב-`androidId` שרירותי בכלי ההמרה, בעוד שהאפליקציה על המכשיר פונה ל-Google עם פרופיל מכשיר אחר. Google קושרת את ה-AAS לרישום המכשיר שביקש אותו, ולכן הצירוף נדחה.

### 22.3 למה תיקון ה-androidId לא היה פותר את הבעיה האמיתית

ניתן היה להתאים את ה-`androidId` ולגרום למכשיר בודד לעבוד. זה לא היה משנה את המסקנה, משום שהחסימה היא ארכיטקטונית ולא טכנית:

- AAS קשור לחשבון Google בודד ולרישום מכשיר;
- חשבון אחד שמשרת אלפי מכשירים ייחסם על ידי Google, ולכל הפחות יוגבל בקצב;
- אין דרך להנפיק AAS לכל משתמש בלי שכל משתמש יחבר חשבון Google פרטי ויעבור תהליך ידני של שליפת cookie מהדפדפן;
- התהליך כולו נשען על `gplayapi`, API לא רשמי, ועל endpoint שאינו מתועד;
- שימוש בחשבון משותף לצי מכשירים מנוגד לתנאי השימוש של Google.

לכן גם פיילוט מוצלח על מכשיר אחד לא היה מהווה הוכחת היתכנות לאלפי משתמשים.

### 22.3א עדכון: המסלול נפתר באמצעות התחברות על המכשיר

הסעיפים שלהלן נכתבו כשהמסלול נראה חסום. **הוא נפתר.** הפתרון אינו שרת ואינו טוקן משותף, אלא העברת ההתחברות אל תוך A Bloq:

- `WebView` בתוך האפליקציה טוען את דף ההתחברות של Google. אפליקציית Play Store אינה מעורבת, ולכן ניתן להשביתה.
- A Bloq לוכדת את ה-cookie `oauth_token` דרך `CookieManager`; הוא `HttpOnly` ולכן חסום ל-JavaScript אך נגיש לאפליקציה.
- `gplayapi` מבצע check-in ומנפיק AAS ו-session **מזהות המכשיר עצמו**, וזה מה שפתר את ה-`BadAuthentication`.
- ה-session נשמר מוצפן ואומת ששרד התקנות מחדש ואתחול מלא.
- כל משתמש מתחבר לחשבון שלו, ולכן אין חשבון משותף ואין מחסום קנה מידה.

השרת ב-Render וכלי ההמרה החיצוני **אינם בשימוש יותר** לאימות. מה שנשאר תקף מהסעיפים הבאים הוא הניתוח למה טוקן שהונפק מחוץ למכשיר נדחה.

### 22.4 מסקנה תפעולית

- אין להשקיע יותר במסלול AAS/private Play. הוא נסגר כ"נבדק ונדחה", לא כ"טרם נוסה".
- סעיף 20 כולו, לרבות שתי החלופות שבו, אינו מסלול מוצר. 20.5 נדחה עוד לפני הבדיקה מטעמי רישוי ותשתית; 20.1 נדחה כעת בבדיקה בפועל.
- שכבת הגילוי ברקע שנבנתה בסעיף 20.3 **נשארת שימושית**: היא אינה תלויה במקור מסוים, ותשמש גם את הקטלוג החתום וגם מסלול רשמי בעתיד.
- כל עוד `MINI_STORE_PLAY_DISPENSER_URL` מוגדר ב-build, ה-Mini Store ימשיך להציג כשל מקור Play בכל רענון. לביטול נקי יש לבנות ללא שני הפרמטרים, וה-UI יציג `catalog-only`.

### 22.5 המסלולים שנותרו

1. **קטלוג חתום (סעיף 21)** — עובד היום, מתרחב לאלפי מכשירים, אינו דורש חשבון Google כלל. מוגבל לאפליקציות שהארגון רשאי להפיץ.
2. **Managed Google Play / Android Enterprise** — המסלול הרשמי לצי מכשירים, עם רישום per-device ובלי טוקנים ידניים. דורש הרשמה כ-Enterprise ושינוי תשתית provisioning.

השילוב המומלץ הוא קטלוג חתום לאפליקציות שבשליטת הארגון, ו-Managed Google Play לשאר.

### 22.6 חוב שנותר מהבדיקה

- `screen_off_timeout` במכשיר הבדיקה שונה ל-900000 מ-30000 לצורך הבדיקה. יש להחזירו.
- כלי ההמרה ו-`ui.ps1` נמצאים תחת `app/build/tmp/agent/` ומוחרגים מהגיבוי; אין להסתמך עליהם כרכיבי מוצר.
- ה-AAS שהונפק שוכב ב-`SEC/` בטקסט גלוי, ונמצא גם ב-snapshots 9–12. יש לבטלו ולנקות, בפעולה נפרדת שהמשתמש ביקש לדחות.

## 23. אורך חיי ה-session, אבחון ניתוקים וסטטוס בדיקה אמיתי

סעיף זה מתעד עבודה שבוצעה לאחר שמסלול ההתחברות על המכשיר (22.3א) כבר עבד end-to-end, ושתי תקלות שנצפו בשימוש ממשי: **החשבון התנתק לאחר כמה שעות**, ו-**ה-UI הציג בדיקה שהושלמה בזמן שלא נבדק דבר**.

### 23.1 התסמינים שנצפו

1. לאחר התחברות מוצלחת ועדכונים שעבדו, כעבור שעות A Bloq ביקשה שוב להתחבר לחשבון Google, בלי פעולה של המשתמש.
2. במקביל, כרטיס המקורות הציג `✅ הבדיקה הושלמה` למרות שהחשבון לא היה מחובר ואף אפליקציית Play לא נבדקה.

### 23.2 העדות מהמכשיר — עובדה, לא השערה

בדיקת המכשיר במצב המנותק:

```powershell
& $Adb -s $Serial shell "run-as com.secureguard.mdm ls -l no_backup"
```

הפלט הכיל את `androidx.work.workdb` ואת `mini_store_catalog_floor`, אך **לא** את `mini_store_play_auth.bin`.

מכאן: קובץ ה-session **נמחק**, ולא נשאר במכשיר במצב שאינו ניתן לפענוח. זה מוציא מהתמונה את ההסבר "הטוקן פג ונשאר מאוחסן", ומצמצם את הסיבה לאחת מדרכי המחיקה שבקוד.

### 23.3 שתי דרכי המחיקה שהיו בקוד

| מסלול | מה קרה | האם תועד |
|---|---|---|
| `PlayUpdateSource` — שתי דחיות אישורים רצופות | `accountSession.invalidate()` מחק את כל ה-session, כולל AAS token | `Log.w` בלבד, נעלם מ-`logcat` תוך שעות |
| `PlayCredentialStore.load()` — `runCatching{...}.getOrElse { clear(); null }` | כל שגיאה, גם זמנית, מחקה את הקובץ | לא תועד כלל |

בנוסף, `load()` קרא ל-`getOrCreateKey()`: כאשר מפתח ה-Keystore לא נמצא, נוצר מפתח **חדש**, שאינו יכול לפענח נתונים שנכתבו בקודם. הפענוח נכשל בהכרח, והקובץ נמחק — כלומר תקלת מפתח חד-פעמית הפכה למחיקת חשבון קבועה.

הסיבה הסבירה ביותר לתסמין "אחרי כמה שעות" היא המסלול הראשון: `AuthData` מכיל **AAS token ארוך-טווח** ולצידו Play tokens נגזרים (`authToken`, `deviceConfigToken`) שתוקפם קצר. כאשר הנגזרים פגו, Google מחזיר verdict של אישורים על בקשה רגילה; בודק העדכונים ברקע חוזר ופונה, המונה מגיע ל-2, והכול נמחק אף שה-AAS היה תקף. עם זאת — ראה 23.9 — ההוכחה הסופית תגיע מהיומן החדש ולא מהניתוח.

### 23.4 התיקון העיקרי: חידוש session במקום ניתוק

`PlayAccountSession.refresh(reason)` בונה session חדש מה-AAS token השמור, בלי מעורבות המשתמש. ה-API אומת מול ה-AAR של `gplayapi 3.6.4`:

```text
com.aurora.gplayapi.data.models.AuthData.getAasToken()
com.aurora.gplayapi.helpers.AuthHelper.build(String, String, AuthHelper$Token, boolean, Properties, Locale)
com.aurora.gplayapi.helpers.AuthHelper$Token.AAS
```

הזרימה החדשה בכל דחיית אישורים:

```text
דחיית אישורים מ-Google
        │
        ▼
רישום CREDENTIAL_REJECTED (marker + קוד HTTP)
        │
        ▼
refresh() מה-AAS token השמור
        │
        ├── הצליח → הפעולה חוזרת פעם אחת → המונה מתאפס
        │
        └── נכשל/בלתי אפשרי → registerRejection()
                    │
                    └── רק בדחייה שנייה: invalidate(reason)
```

- החידוש חל גם על `resolve()`, כדי שעדכון שכבר אושר לא ייפול בין הגילוי להורדה.
- דחיית אישורים מתוך batch של `getAppByPackageName` מועברת למעלה במקום להיספר כ"חבילה שנכשלה", אחרת פקיעת טוקן הייתה נראית כמו 343 כשלי חבילות.
- `refresh()` שנכשל **אינו** מוחק את ה-session בעצמו.

### 23.5 תיקון אחסון ה-credentials

`PlayCredentialStore.load()`:

- מחפש מפתח קיים בלבד (`existingKey()`), ואינו יוצר מפתח חדש בקריאה.
- מפתח חסר → הקובץ באמת בלתי-קריא: הוא נמחק, ונרשם `KEYSTORE_KEY_MISSING_CLEARED`.
- כשל בלתי-הפיך (`AEADBadTagException`, שגיאת serialization, פורמט פגום) → מחיקה + `SESSION_UNREADABLE_CLEARED`.
- כל כשל אחר → הקובץ **נשמר** לניסיון הבא + `SESSION_LOAD_TRANSIENT_KEPT`.

### 23.6 תיעוד עמיד: `PlaySessionAudit`

עד כה ניתוק לא השאיר עדות: `logcat` הוא buffer מתגלגל, ושתי דרכי המחיקה לא רשמו סיבה. לכן כל הסבר היה השערה. הרכיב החדש כותב כל שינוי מצב לקובץ שחי מחוץ למחזור החיים של ה-session.

```text
app: files/mini_store_play_audit.log
prefs: shared_prefs/mini_store_play_diagnostics.xml
```

מבנה שורה:

```text
2026-08-16T12:02:15Z | uptime=57983709 | SESSION_ABSENT | no stored session file
```

כאשר קיים session, נוסף גם `sessionAge=4h12m`, כך שגיל ה-session בזמן הכשל הוא נתון ולא זיכרון.

| אירוע | משמעות |
|---|---|
| `SIGN_IN_OK` | התחברות חדשה; מאפס מונים ומצב כשל |
| `SESSION_LOADED` | ה-session פוענח בהצלחה בעליית התהליך |
| `SESSION_ABSENT` | אין קובץ session כלל |
| `CREDENTIAL_REJECTED` | Google דחה אישורים; כולל marker וקוד HTTP |
| `REFRESH_ATTEMPT` / `REFRESH_OK` | חידוש מה-AAS token — ניסיון והצלחה |
| `REFRESH_FAILED` | החידוש נדחה; כולל סוג החריגה |
| `REFRESH_UNAVAILABLE` | אין session או שאין AAS token לחידוש |
| `SESSION_INVALIDATED` | ניתוק בפועל, עם הסיבה ומספר הדחיות |
| `SESSION_UNREADABLE_CLEARED` | הקובץ נמחק כי הפענוח בלתי-הפיך |
| `SESSION_LOAD_TRANSIENT_KEPT` | כשל זמני; הקובץ נשמר |
| `KEYSTORE_KEY_MISSING_CLEARED` | מפתח Keystore חסר |
| `USER_SIGN_OUT` | המשתמש התנתק ביודעין |

היומן אינו מכיל tokens או כתובות דוא"ל: כל טקסט עובר סינון שמחליף כתובת דוא"ל ב-`<account>` ורצף ארוך של תווי טוקן ב-`<redacted>`, ונחתך ל-240 תווים. הקובץ מוגבל ל-32KB עם שמירת השורות האחרונות.

קריאה:

```powershell
& $Adb -s $Serial shell "run-as com.secureguard.mdm cat files/mini_store_play_audit.log"
& $Adb -s $Serial shell "run-as com.secureguard.mdm cat shared_prefs/mini_store_play_diagnostics.xml"
```

### 23.7 סטטוס בדיקה אמיתי במקום כוזב

הסטטוס הכוזב היה באג ולא אי-דיוק בנוסח:

- `PlaySourceState.DISABLED` פירושו בפועל היה "אין חשבון Google", אך `updateCheckComplete` התייחס אליו כ"אין מקור שצריך לענות", ולכן החזיר `true`.
- הכרטיס העליון הציג `mini_store_source_catalog_only` = `✅ הבדיקה הושלמה`.

התיקון:

```kotlin
updateCheckComplete = updateCandidate != null ||
    (catalogSourceState == CatalogSourceState.CHECKED &&
        playSourceState == PlaySourceState.CHECKED &&
        packageName !in playFailedPackages)
```

- `DISABLED` שונה ל-`SIGNED_OUT`, כדי שהמצב יתאר את המציאות.
- במצב `SIGNED_OUT` הכרטיס מציג `⚠️ הבדיקה לא הושלמה — נדרשת התחברות לחשבון Google`.
- מתחת לכך מוצגת הסיבה מהיומן, למשל `🔑 החיבור לחשבון נדחה על ידי Google ב-<תאריך>`, במקום בקשת התחברות בלי הסבר.

### 23.8 מה אומת בפועל

| בדיקה | תוצאה |
|---|---|
| `:app:assembleDebug` | `BUILD SUCCESSFUL` |
| התקנה `install -r -t` מעל ההתקנה הקיימת | `Success` |
| Device Owner לאחר ההתקנה | נשמר |
| קובץ היומן נוצר ונכתב | `files/mini_store_play_audit.log` |
| השורה הראשונה שנרשמה | `SESSION_ABSENT | no stored session file` |
| API החידוש קיים ב-dependency | `getAasToken()` ו-`Token.AAS` אומתו ב-`javap` |

### 23.9 מה עדיין לא הוכח

- **החידוש עצמו לא נבדק מול Google** במכשיר, כי בזמן העבודה לא היה session מאוחסן. הוא ייבדק בפעם הבאה שהטוקנים הנגזרים יפוגו.
- הקביעה שהניתוק נבע מ-`invalidate()` לאחר שתי דחיות היא **ההסבר הסביר**, ולא עובדה מאומתת; העדות המאומתת היחידה היא שהקובץ נמחק.

אימות בפעם הבאה, לפי היומן:

```text
תרחיש תקין:      CREDENTIAL_REJECTED → REFRESH_ATTEMPT → REFRESH_OK  (החשבון נשאר)
תרחיש שדורש טיפול: CREDENTIAL_REJECTED → REFRESH_ATTEMPT → REFRESH_FAILED → SESSION_INVALIDATED
```

אם יופיע `KEYSTORE_KEY_MISSING_CLEARED` או `SESSION_UNREADABLE_CLEARED`, הסיבה אינה Google אלא אחסון המפתח במכשיר, וזה מצריך טיפול שונה לגמרי.

### 23.10 שינויי UI בלשונית העדכונים שנעשו באותה עבודה

- **ההסרה הוצאה מכרטיסי העדכון** ועברה ללשונית "חסימה והסרה", כדי שלחיצה הרסנית לא תשב ליד לחיצת תחזוקה. מסלול ההסרה עצמו נשאר `MiniStorePackageOperator.uninstall` עם אותן בדיקות.
- **הודעת "לא ניתן להסיר" הוסרה מלשונית העדכונים**, כי אין בה כפתור הסרה; היא מוצגת כעת ליד כפתור ההסרה שהיא מסבירה.
- **החרגה לרשימה שחורה אינה דורשת סיסמה.** הסתרת אפליקציה מרשימת העדכונים הפיכה ואינה משנה מה מותר להפעיל.
- **החיפוש עבר לכרטיס "האפליקציות שלך"** כאייקון שנפתח לשדה בלחיצה, במקום שדה קבוע שתפס שורה שלמה מעל הרשימה.
