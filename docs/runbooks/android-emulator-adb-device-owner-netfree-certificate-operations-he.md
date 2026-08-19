# מדריך תפעולי: Android Emulator, ADB, Device Owner ותעודות NetFree

סטטוס: Runbook מעשי ל-Windows, לבני אדם ולסוכני AI
פרויקט: SecureGuardMDM / A Bloq
מערכת הפעלה מארחת: Windows + PowerShell
יעד שנבדק: Android 14 / API 34 / Google APIs / x86_64

## 1. מטרת המסמך

מסמך זה מרכז את הפעולות שנדרשו כדי להריץ ולבדוק את A Bloq על Android Emulator, להגדיר את האפליקציה כ-Device Owner ולהתקין ישירות את תעודות ה-CA של NetFree.

המסמך מיועד בעיקר לסוכן AI שממשיך את העבודה, אך הפקודות וההסברים מתאימים גם להפעלה ידנית.

## 2. כללי ברזל לסוכן AI

1. יש לעבוד **רק** בתיקיית הפרויקט הפנימית:

   ```text
   C:\projects\SecureGuardMDM\SecureGuardMDM
   ```

   זו התיקייה שמכילה `gradlew.bat`, את `settings.gradle.kts`, את `build.gradle.kts` ואת תיקיית `app`.

2. אין לפתוח את תיקיית האב הבאה כפרויקט:

   ```text
   C:\projects\SecureGuardMDM
   ```

3. Kotlin היא שפת התכנות. Flutter הוא framework עם אינטגרציית Run/Debug שונה. פרויקט Android Native ב-Kotlin אינו מופעל בהכרח מכפתור `Run and Debug` של Kiro/VS Code.

4. אם Kiro מציג חלון `Select debugger` עם אפשרויות כמו IntelliJ Debugger, CMake, Node.js או Python, **אין לבחור אף אחת מהן** לצורך הפעלת האפליקציה. יש לסגור את החלון עם `Esc`. הפעלה מתבצעת באמצעות Gradle + ADB או מתוך Android Studio.

5. `adb devices` הוא מקור האמת למצב החיבור. סימון ירוק ב-Device Manager או חלון אמולטור שמוצג על המסך אינם הוכחה לכך שהמכשיר מחובר ל-ADB.

6. אין להמתין שעה לאמולטור. אתחול רגיל אמור להסתיים בתוך דקות בודדות. יש לבצע בדיקות תחומות בזמן ולבדוק את `sys.boot_completed`.

7. `Wipe Data` מוחק את כל נתוני ה-AVD, לרבות Device Owner, אפליקציות ותעודות. מפעילים אותו רק על אמולטור בדיקה שאין בו מידע נחוץ.

8. אין להסיר את A Bloq ממכשיר אמיתי כאשר היא Device Owner. במכשיר אמיתי פעולה שגויה עלולה לחייב איפוס מלא.

9. התקנת Root CA משנה את מודל האמון של המכשיר ומאפשרת לגורם המנפיק לאמת/לבדוק TLS. יש להתקין רק תעודות שאומתו כמגיעות ממקור רשמי.

10. אין לשנות קוד, build, Git או signing כדי לפתור תקלה של אמולטור לפני שמוכח שהבעיה נמצאת בפרויקט.

## 3. ערכים קבועים בפרויקט זה

```powershell
$ProjectRoot = "C:\projects\SecureGuardMDM\SecureGuardMDM"
$SdkRoot = "C:\Users\Haim-Y\AppData\Local\Android\Sdk"
$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$Emulator = Join-Path $SdkRoot "emulator\emulator.exe"
$Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\Abloq-debug.apk"
$Package = "com.secureguard.mdm"
$MainActivity = "com.secureguard.mdm/.MainActivity"
$AdminReceiver = "com.secureguard.mdm/.SecureGuardDeviceAdminReceiver"
$AvdName = "Pixel_6"
```

פרטי build נוכחיים:

- `compileSdk = 34`
- `targetSdk = 34`
- `minSdk = 23`
- Java/Kotlin bytecode target: 17
- שם APK של debug: `Abloq-debug.apk`

## 4. יצירת AVD מתאים

ב-Android Studio:

1. פתח `Tools` → `Device Manager`.
2. לחץ `Create device`.
3. בחר Pixel 6 או Pixel 7.
4. בחר image עם:
   - Android 14;
   - API 34;
   - Google APIs;
   - x86_64;
   - ללא Google Play, אם המטרה היא Device Owner נקי.
5. מומלץ להגדיר 4GB RAM אם למחשב יש מספיק זיכרון.
6. אל תוסיף חשבון Google.

לבדיקת רשימת ה-AVD-ים:

```powershell
& $Emulator -list-avds
```

במחשב שנבדק שם ה-AVD הוא:

```text
Pixel_6
```

## 5. הפעלת האמולטור

### 5.1 מתוך Android Studio

ב-`Device Manager`, לחץ פעם אחת על ▶ בשורת `Pixel 6`.

אם `Running Devices` ריק, אין להסיק שהאמולטור אינו פועל. ייתכן שהוא הופעל בחלון חיצוני. בדוק תמיד באמצעות ADB.

### 5.2 ישירות מ-PowerShell

כדי לא לחסום את ה-Terminal, הפעל כתהליך נפרד:

```powershell
Start-Process -FilePath $Emulator -ArgumentList @(
    "-avd", $AvdName,
    "-no-snapshot-load"
)
```

סוכן AI צריך להשתמש בכלי לניהול background process אם הוא זמין, ולא להריץ את `emulator.exe` כפקודה סינכרונית שממתינה לנצח.

### 5.3 בדיקת ADB

```powershell
& $Adb devices -l
```

פלט תקין לדוגמה:

```text
emulator-5554    device product:sdk_gphone64_x86_64
```

כאשר מחוברים כמה מכשירים, יש להצמיד serial לכל פקודה:

```powershell
$Serial = "emulator-5554"
& $Adb -s $Serial shell getprop ro.build.version.release
```

## 6. המתנה תחומה לסיום האתחול

אין להשתמש ב-`adb wait-for-device` לבדו ללא timeout, כי המכשיר יכול להיתקע לאחר שחיבור ADB כבר קיים.

```powershell
$deadline = (Get-Date).AddMinutes(5)

do {
    $devices = & $Adb devices
    if ($devices -match 'emulator-\d+\s+device') {
        $bootCompleted = (& $Adb shell getprop sys.boot_completed 2>$null).Trim()
        if ($bootCompleted -eq "1") {
            Write-Host "Android boot completed"
            break
        }
    }

    Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

if ($bootCompleted -ne "1") {
    throw "Android did not complete boot within five minutes"
}
```

בדיקות קצרות שימושיות:

```powershell
& $Adb shell getprop sys.boot_completed
& $Adb shell getprop init.svc.bootanim
& $Adb shell service list | Select-String "device_policy"
```

מצב תקין לאחר boot:

```text
sys.boot_completed = 1
init.svc.bootanim = stopped
device_policy מופיע ברשימת השירותים
```

השגיאה הבאה בדרך כלל אומרת שהמערכת עדיין לא סיימה לעלות:

```text
cmd: Can't find service: device_policy
```

## 7. טיפול באמולטור שנתקע

### 7.1 סדר הפעולות

1. המתן לכל היותר כחמש דקות ובדוק `sys.boot_completed`.
2. ב-Device Manager בחר `Cold Boot Now`.
3. אם עדיין תקוע, ורק אם אין מידע שצריך לשמור, בחר `Wipe Data`.
4. אם גם AVD נקי תקוע, מחק אותו וצור AVD חדש.
5. בדוק שהאצת החומרה זמינה:

   ```powershell
   & $Emulator -accel-check
   ```

6. בדוק את התקדמות ה-boot:

   ```powershell
   & $Adb logcat -b events -d -v time |
       Select-String "boot_progress|am_crash|am_anr|lowmem"
   ```

### 7.2 תקיעת SystemUI

אם Android סיים boot אך המסך נשאר על הלוגו, בדוק:

```powershell
& $Adb shell dumpsys window |
    Select-String "mCurrentFocus|mFocusedApp"
```

אם מופיע ANR של `com.android.systemui`, באמולטור בדיקה בלבד ניתן לאלץ את SystemUI לקרוס ולהיטען מחדש:

```powershell
& $Adb shell am crash com.android.systemui
```

אין לבצע פעולה זו במכשיר ייצור ללא צורך ברור.

### 7.3 מעבר לגרפיקת Software

דרך Android Studio עדיף לערוך את ה-AVD ולבחור Graphics מסוג Software/Compatibility.

קובץ ההגדרה של ה-AVD שנבדק נמצא ב:

```text
C:\Users\Haim-Y\.android\avd\Pixel_6.avd\config.ini
```

ההגדרה ששימשה לפתרון תצוגה הייתה:

```ini
hw.gpu.mode=software
```

יש לעצור את האמולטור לפני עריכת `config.ini`. שינוי זה הפיך ואינו מוחק נתונים.

### 7.4 Device Manager מציג מכשיר פעיל אך ADB ריק

בדוק אם תהליך האמולטור באמת קיים:

```powershell
Get-CimInstance Win32_Process |
    Where-Object { $_.Name -match 'qemu|emulator|adb' } |
    Select-Object Name, ProcessId, CommandLine
```

הפעל מחדש את שרת ADB:

```powershell
& $Adb kill-server
Start-Sleep -Seconds 2
& $Adb start-server
& $Adb devices -l
```

אם אין תהליך `qemu-system-x86_64`, האמולטור אינו רץ בפועל ויש להפעילו מחדש.

## 8. בנייה, התקנה והפעלת A Bloq

יש להריץ מתוך תיקיית הפרויקט הפנימית:

```powershell
Set-Location $ProjectRoot
```

בנייה:

```powershell
.\gradlew.bat :app:assembleDebug
```

אפשר גם לבנות ולהתקין ישירות על מכשיר מחובר:

```powershell
.\gradlew.bat :app:installDebug
```

התקנה באמצעות ADB:

```powershell
& $Adb install -r $Apk
```

אימות שהחבילה מותקנת:

```powershell
& $Adb shell pm path $Package
```

הפעלת האפליקציה:

```powershell
& $Adb shell am start -n $MainActivity
```

או באמצעות launcher intent:

```powershell
& $Adb shell monkey -p $Package -c android.intent.category.LAUNCHER 1
```

### 8.1 חתימה ועדכון מעל התקנה קיימת

`Abloq-debug.apk` חתום במפתח Android Debug. אם במכשיר מותקנת גרסת release החתומה במפתח אחר, `install -r` לא יכול לעדכן אותה.

אין להסיר גרסה קיימת ממכשיר אמיתי אם היא Device Owner. כדי לבצע update רגיל נדרש אותו keystore פרטי ששימש לחתימת ה-release הקודם, וכן `versionCode` גבוה יותר.

### 8.2 מדריך מלא: Debug ובדיקות חיות על המכשיר הפיזי

סעיף זה מתעד את התהליך שאומת בפועל לאורך פיתוח Mini Store, Google Play, מסך החסימה וההסרה. מטרתו לאפשר סבב קצר ובטוח של:

```text
שינוי קוד → build אינקרמנטלי → התקנה מעל הקיים → הפעלה מחדש
→ צפייה במסך ובלוגים → בדיקת Device Owner → גיבוי סופי
```

Android Native אינו מספק Flutter-style Hot Reload. עבור רוב שינויי Compose/Kotlin, התחליף האמין הוא build אינקרמנטלי והתקנת APK מחדש. אין להריץ `clean` בכל סבב.

#### 8.2.1 משתנים קבועים והכנת Terminal

פתח PowerShell בתיקיית הפרויקט הפנימית והגדר:

```powershell
$ProjectRoot = "C:\projects\SecureGuardMDM\SecureGuardMDM"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Serial = "R8YW50PKLHY"
$Package = "com.secureguard.mdm"
$MainActivity = "com.secureguard.mdm/.MainActivity"
$AdminReceiver = "com.secureguard.mdm/.SecureGuardDeviceAdminReceiver"
$Apk = Join-Path $ProjectRoot "app\build\outputs\apk\debug\Abloq-debug.apk"
$AgentTmp = Join-Path $ProjectRoot "app\build\tmp\agent"

Set-Location $ProjectRoot
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17.0.20"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
```

ה-serial לעיל הוא המכשיר ששימש לבדיקה, אך אין להניח שהוא מחובר. `adb devices -l` הוא מקור האמת בכל סשן.

#### 8.2.2 בדיקות מקדימות לפני build או התקנה

```powershell
& $Adb devices -l
& $Adb -s $Serial get-state
& $Adb -s $Serial shell dpm list-owners
& $Adb -s $Serial shell pm path $Package
java -version
```

תוצאה תקינה במכשיר הקבוע:

```text
Model:        Samsung SM_A145P
Package:      com.secureguard.mdm
Device Owner: com.secureguard.mdm/.SecureGuardDeviceAdminReceiver
Java:         Temurin 17
```

אם `adb devices -l` ריק, אפשר להפעיל מחדש את השרת בלי לשנות את המכשיר:

```powershell
& $Adb start-server
Start-Sleep -Seconds 3
& $Adb devices -l
```

אם מוצג מכשיר אחר, עדכן את `$Serial`. כאשר מחוברים כמה מכשירים, כל פקודת ADB חייבת לכלול `-s $Serial`.

#### 8.2.3 build של Debug

מקור האמת להגדרות build הוא `app/build.gradle.kts`. המצב הנוכחי:

```text
applicationId: com.secureguard.mdm
JVM:           17
Debug APK:     app/build/outputs/apk/debug/Abloq-debug.apk
```

פקודת ה-build המאומתת:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

הצלחה מחייבת:

```text
BUILD SUCCESSFUL
```

וכן קיום הקובץ:

```powershell
if (-not (Test-Path $Apk -PathType Leaf)) {
    throw "Debug APK was not created: $Apk"
}
Get-Item $Apk | Select-Object FullName, Length, LastWriteTime
```

`applicationVariants` ב-Gradle משנה במפורש את שם הפלט ל-`Abloq-debug.apk`; אין לחפש `app-debug.apk`.

##### build אינקרמנטלי לעומת clean

ברירת המחדל היא build אינקרמנטלי. אין להריץ `:app:clean` בכל סבב, משום שהוא מאט מאוד את העבודה ומוחק cache תקין.

אם יש ראיה ממשית ל-cache פגום, לדוגמה:

```text
Could not delete app\build\tmp\kapt3\...
Failed to create MD5 hash ... AppBlockerUiState.java ... does not exist
```

עצור את Gradle, מחק רק את cache ה-KAPT שנכשל ובנה שוב:

```powershell
.\gradlew.bat --stop
Start-Sleep -Seconds 3
Remove-Item -Recurse -Force ".\app\build\tmp\kapt3" -ErrorAction SilentlyContinue
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

אין לבצע `clean`, למחוק את כל `app/build` או למחוק caches כלליים בלי ראיה שהם הגורם. נפילה של Kotlin daemon יכולה לעבור ל-fallback ולהסתיים בכל זאת ב-`BUILD SUCCESSFUL`; יש לקבוע לפי תוצאת הסיום, לא לפי warning בודד באמצע הלוג.

#### 8.2.4 בדיקת תאימות לפני התקנה מעל A Bloq קיימת

`install -r` שומר את הנתונים רק כאשר מתקיימים כל התנאים:

1. שם החבילה זהה: `com.secureguard.mdm`;
2. החתימה תואמת להתקנה הקיימת;
3. `versionCode` אינו downgrade אסור;
4. ה-APK תקין ומתאים למכשיר.

לבדיקת פרטי ה-APK המקומי:

```powershell
$Aapt = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\aapt.exe" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName

& $Aapt dump badging $Apk | Select-Object -First 5
```

לבדיקת signer של ה-APK המקומי:

```powershell
$ApkSigner = Get-ChildItem "$env:LOCALAPPDATA\Android\Sdk\build-tools\*\apksigner.bat" |
    Sort-Object FullName -Descending |
    Select-Object -First 1 -ExpandProperty FullName

& $ApkSigner verify --print-certs $Apk
```

כאשר יש ספק לגבי ההתקנה הקיימת, משוך את ה-BASE APK לתיקיית בדיקה מוחרגת והשווה signer:

```powershell
New-Item -ItemType Directory -Force -Path $AgentTmp | Out-Null
$InstalledPath = (& $Adb -s $Serial shell pm path $Package |
    Select-String '^package:' |
    Select-Object -First 1).Line -replace '^package:', ''

if ([string]::IsNullOrWhiteSpace($InstalledPath)) {
    throw "A Bloq is not installed"
}

$InstalledApk = Join-Path $AgentTmp "installed-abloq-base.apk"
& $Adb -s $Serial pull $InstalledPath $InstalledApk
& $ApkSigner verify --print-certs $InstalledApk
& $ApkSigner verify --print-certs $Apk
```

אין להעתיק keystore, passwords או signing properties לתוך הפרויקט. release keystore אינו נמצא בפרויקט ואינו נכלל בגיבוי הרגיל.

#### 8.2.5 התקנה בטוחה והפעלה מחדש

לאחר build מוצלח ובדיקת התאימות:

```powershell
& $Adb -s $Serial install -r -t $Apk
if ($LASTEXITCODE -ne 0) {
    throw "Debug APK installation failed"
}

& $Adb -s $Serial shell am force-stop $Package
& $Adb -s $Serial shell am start -W -n $MainActivity
```

משמעות הדגלים:

- `-r` — החלפת ההתקנה הקיימת תוך שמירת נתוני האפליקציה;
- `-t` — מתיר APK שמסומן test/debug;
- `am force-stop` ואחריו `am start` — מבטיחים שהקוד החדש נטען ולא נשאר process ישן.

אם מתקבל:

```text
INSTALL_FAILED_UPDATE_INCOMPATIBLE
```

עצור. אין להסיר את A Bloq כדי לעקוף חתימה שונה: היא Device Owner, והסרה או ניקוי נתונים עלולים לחייב איפוס מלא ו-provisioning מחדש.

#### 8.2.6 אימות מיידי לאחר התקנה

```powershell
& $Adb -s $Serial shell pm path $Package
& $Adb -s $Serial shell dpm list-owners
$AppProcessId = (& $Adb -s $Serial shell pidof -s $Package).Trim()
$AppProcessId
```

נדרש לראות:

- נתיב `base.apk` עבור `com.secureguard.mdm`;
- owner יחיד שבו `DeviceOwner` שייך ל-`$AdminReceiver`;
- PID לא ריק לאחר הפעלת `MainActivity`.

כאשר השינוי נוגע ל-Google Play session, אפשר לוודא שקובץ ה-session עדיין קיים בלי לקרוא את תוכנו:

```powershell
& $Adb -s $Serial shell "run-as $Package ls no_backup | grep mini_store_play_auth.bin"
```

נדרש לראות `mini_store_play_auth.bin`. אין להדפיס, למשוך או לשתף את תוכן הקובץ.

#### 8.2.7 צפייה חיה במכשיר

הדרך המומלצת היא Android Studio:

```text
View → Tool Windows → Running Devices
```

או `Device Mirroring`, אם הוא זמין. אם `scrcpy` כבר מותקן במחשב אפשר להפעילו ידנית, אך הוא אינו dependency של הפרויקט.

לשינוי method body פשוט אפשר לנסות Android Studio **Apply Code Changes**. יש לחזור ל-build/install מלא כאשר השינוי כולל:

- Manifest;
- Hilt/DI;
- resources או Compose resources מורכבים;
- native libraries;
- מבנה מחלקות או signatures;
- התנהגות שאינה מתעדכנת באופן חד-משמעי.

#### 8.2.8 logcat: snapshot ממוקד וצפייה רציפה

לפני איסוף לוג ודא שהאפליקציה רצה וש-`$AppProcessId` אינו ריק:

```powershell
$AppProcessId = (& $Adb -s $Serial shell pidof -s $Package).Trim()
if ([string]::IsNullOrWhiteSpace($AppProcessId)) {
    & $Adb -s $Serial shell am start -W -n $MainActivity | Out-Null
    $AppProcessId = (& $Adb -s $Serial shell pidof -s $Package).Trim()
}
```

snapshot סופי שאינו משאיר process פתוח:

```powershell
& $Adb -s $Serial logcat -d --pid=$AppProcessId -v threadtime
```

סינון לתגים ששימשו בפיתוח Mini Store וניווט:

```powershell
& $Adb -s $Serial logcat -d --pid=$AppProcessId -v threadtime |
    Select-String "MiniStoreInstall|MiniStorePlayLogin|MiniStoreAccess|MiniStoreAccounts|MiniStorePlaySource|AppBlockerViewModel|AbloqNav|AndroidRuntime|FATAL"
```

לצפייה רציפה, המשתמש מפעיל ידנית ב-Terminal:

```powershell
& $Adb -s $Serial logcat --pid=$AppProcessId -v color
```

סוכן אוטומטי לא ישאיר `logcat` רציף פתוח; הוא ישתמש ב-`logcat -d`, בטווח זמן מוגבל או בסינון. אין להדפיס tokens, כתובות דוא"ל או נתוני משתמש.

כאשר צריך למדוד ביצועים, יש להוסיף לוג עם זמן התחלה/סיום ולבדוק ערך בפועל. לדוגמה, טעינת מסך החסימה אומתה במכשיר עם:

```text
AppBlockerViewModel: loaded 343 apps in 537ms
AppBlockerViewModel: loaded 343 apps in 917ms
```

#### 8.2.9 אימות UI ללא הסתמכות על צפייה ידנית

צור תיקיית בדיקה תחת build, בצע dump ומשוך אותו:

```powershell
New-Item -ItemType Directory -Force -Path $AgentTmp | Out-Null
& $Adb -s $Serial shell uiautomator dump /sdcard/abloq_ui.xml
& $Adb -s $Serial pull /sdcard/abloq_ui.xml (Join-Path $AgentTmp "abloq_ui.xml")
```

לחיפוש טקסט מסוים:

```powershell
Select-String -Path (Join-Path $AgentTmp "abloq_ui.xml") -Pattern "עדכונים|חסימה והסרה|חיפוש"
```

צילום מסך לבדיקה חזותית:

```powershell
& $Adb -s $Serial shell screencap -p /sdcard/abloq_screen.png
& $Adb -s $Serial pull /sdcard/abloq_screen.png (Join-Path $AgentTmp "abloq_screen.png")
```

`uiautomator dump` יכול להחזיר `null root node` בזמן מעבר מסך. במקרה כזה המתן 1–3 שניות ונסה פעם נוספת. קובצי dump וצילומים נשמרים רק תחת `app/build/tmp/agent/`, שמוחרג מהגיבוי.

#### 8.2.10 smoke tests לפי סוג השינוי

בדיקת מינימום לכל שינוי:

```text
[ ] BUILD SUCCESSFUL
[ ] install -r -t החזיר Success
[ ] MainActivity הופעלה והתקבל PID
[ ] אין AndroidRuntime/FATAL בלוג
[ ] dpm list-owners עדיין מציג את A Bloq כ-Device Owner
```

הרחבה לפי תחום:

- **UI/Compose:** פתח את המסך, אמת טקסט/אייקון/מצב באמצעות mirroring, screenshot או UI dump, ובדוק ניווט הלוך וחזור.
- **Mini Store:** רענן, בדוק מקור עדכון, תור, progress וביטול; ודא שאפליקציה מוחרגת אינה מוצגת; אל תבטל בשלב `INSTALLING`.
- **Google Play:** ודא שה-session שרד, התחבר רק אם נדרש, ובדוק שאין מחיקה מוקדמת של session. אין להדפיס credentials.
- **חסימה/השבתה/הסרה:** בדוק על אפליקציית test בלבד, ודא שכפתורי השחרור עובדים, ואל תנסה להסיר אפליקציית מערכת או חבילה מוגנת.
- **DevicePolicyManager/provisioning:** אמת owner לפני ואחרי. אין להריץ `set-device-owner` מחדש במכשיר שכבר provisioned.
- **Boot/VPN/NetFree:** בדוק התאוששות אחרי reboot, תעבורה מותרת וחסומה, ו-owner לאחר האתחול. אין להחליף Always-On VPN פעיל בשקט.
- **Room/migration:** התקן מעל נתונים קיימים, פתח את המסך הצורך את הטבלה ובדוק שאין migration crash; אין להשתמש ב-`pm clear`.

#### 8.2.11 לולאה מהירה להעתקה

לאחר שבדיקות התאימות כבר בוצעו באותו מכשיר ובאותה חתימת debug:

```powershell
Set-Location "C:\projects\SecureGuardMDM\SecureGuardMDM"
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17.0.20"
$Adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$Serial = "R8YW50PKLHY"
$Package = "com.secureguard.mdm"
$Apk = ".\app\build\outputs\apk\debug\Abloq-debug.apk"

.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
if ($LASTEXITCODE -ne 0) { throw "Build failed" }

& $Adb -s $Serial install -r -t $Apk
if ($LASTEXITCODE -ne 0) { throw "Install failed" }

& $Adb -s $Serial shell am force-stop $Package
& $Adb -s $Serial shell am start -W -n "$Package/.MainActivity"
& $Adb -s $Serial shell dpm list-owners
```

#### 8.2.12 סיום משימה וגיבוי

לאחר שהמימוש עבר build ובדיקות חיות, עדכן את `PROJECT_MAP.md` אם השתנו ארכיטקטורה, זרימת נתונים, מנגנון אבטחה, Mini Store, VPN או Device Owner. נקה רק קבצי בדיקה זמניים שיצרת; אין למחוק artifacts, APK או נתוני מכשיר.

פעולת הכתיבה האחרונה היא:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass `
    -File ".\backup.ps1" `
    -Description "<short_description>" `
    -NonInteractive
```

הצלחה מחייבת את שתי השורות:

```text
Backup completed and verified successfully
Verified: True
```

ברירת המחדל אינה כוללת `.git`. אין להשתמש ב-`-IncludeGitHistory` אלא לפי בקשה מפורשת. release keystore וסיסמאות אינם נכללים בגיבוי ודורשים גיבוי מוצפן נפרד.

## 9. הגדרת Device Owner

### 9.1 תנאים

- אמולטור חדש או מאופס;
- ללא חשבון Google או חשבונות משתמש אחרים;
- ה-APK כבר מותקן;
- אין Device Owner אחר.

בדיקות אבחון:

```powershell
& $Adb shell settings get global device_provisioned
& $Adb shell settings get secure user_setup_complete
& $Adb shell dpm list-owners
```

הדגלים `device_provisioned` ו-`user_setup_complete` שימושיים לאבחון, אך נוכחות חשבונות או Device Owner קיים היא החסם החשוב.

### 9.2 הגדרה ואימות

```powershell
& $Adb shell dpm set-device-owner $AdminReceiver
```

פלט הצלחה צפוי:

```text
Success: Device owner set to package com.secureguard.mdm/.SecureGuardDeviceAdminReceiver
Active admin set to component com.secureguard.mdm/.SecureGuardDeviceAdminReceiver
```

אימות:

```powershell
& $Adb shell dpm list-owners
```

פלט צפוי:

```text
User 0: admin=com.secureguard.mdm/.SecureGuardDeviceAdminReceiver,DeviceOwner
```

### 9.3 מסך Modify system settings

לאחר שהאפליקציה הופכת ל-Device Owner, A Bloq פותחת בכוונה את מסך Android:

```text
Modify system settings → A Bloq → Allow modifying system settings
```

יש לאשר את המתג ולחזור לאפליקציה. זה אינו מסך VPN ואינו תקלה.

## 10. שפה: אפליקציה לעומת מערכת Android

שפת A Bloq ושפת Android הן שתי הגדרות שונות.

להגדרת עברית רק ל-A Bloq ב-Android 13 ומעלה:

```powershell
& $Adb shell cmd locale set-app-locales $Package --user 0 --locales he-IL
& $Adb shell cmd locale get-app-locales $Package --user 0
& $Adb shell am force-stop $Package
& $Adb shell am start -n $MainActivity
```

הפקודה אינה משנה את השפה של Settings או של שאר Android.

לשינוי שפת המערכת ידנית ב-Pixel:

```text
Settings → System → Languages → System languages
```

הוסף Hebrew (Israel) והעבר אותה למקום הראשון.

## 11. התקנת תעודות NetFree באמולטור

### 11.1 אזהרת אבטחה

Root CA מאפשרת ל-NetFree לאמת תעבורת HTTPS שעוברת דרך הסינון. יש להשתמש רק בתעודות שהורדו ממקור NetFree רשמי ולבדוק Subject, Issuer ו-fingerprint לפני ההתקנה.

מדריך NetFree שסופק לעבודה זו:

```text
https://netfree.link/wiki/התקנת_תעודה_באנדרואיד
```

ייתכן שהאתר מפנה בין `netfree.link` לבין `wiki.netfree.link` או מחזיר 404 לכלי אוטומטי. אין להוריד תעודה ממקור חלופי לא מאומת רק משום שה-Wiki אינו נגיש לכלי.

### 11.2 הקבצים שנבדקו

הקבצים שנמצאו ב-Downloads:

```text
root_ca_x2_ed25519.crt
root_ca_x2_prime256v1.crt
root_ca_x2_rsa.crt
root_ca_x2_rsa_2037.crt
root_ca_x2_secp384r1.crt
```

בכל חמש התעודות שנבדקו:

- `O=NetFree`
- `OU=netfree.link`
- Subject ו-Issuer זהים, כלומר אלו Root CA self-signed.

fingerprint מסוג SHA-256 שנצפה ב-session זה:

| קובץ | SHA-256 של התעודה |
|---|---|
| `root_ca_x2_ed25519.crt` | `17031B612D4BC0DB25DE1999F167F2E6DBAC181F638606849AA45EA1F13FFC40` |
| `root_ca_x2_prime256v1.crt` | `336A31840C0500FE32FAC5759D30610DD66F5A5D8E606F0EF2EBA3E218C73C06` |
| `root_ca_x2_rsa.crt` | `22D61B27B728865BDD8CF46EBA5677D2A9DE0EF059236B67C1E438ABE6BB33BC` |
| `root_ca_x2_rsa_2037.crt` | `AE1EFD860D72348E8B6A65FA2531359E86D4B26AE87FAD510ADC818E67E9FB71` |
| `root_ca_x2_secp384r1.crt` | `FCE033C1057EE152163251BC1EF9852C3F7AE813AB873BA18DE46B6E3763AFB7` |

אם NetFree מחליפה תעודות בעתיד, אין לפסול אוטומטית fingerprint חדש ואין לאשרו אוטומטית. יש להשוות מול מקור רשמי עדכני.

### 11.3 בדיקה מקומית של התעודות ב-Windows

```powershell
$CertDirectory = Join-Path $env:USERPROFILE "Downloads"

Get-ChildItem "$CertDirectory\root_ca_x2_*.crt" | ForEach-Object {
    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($_.FullName)

    [pscustomobject]@{
        File       = $_.Name
        Subject    = $certificate.Subject
        Issuer     = $certificate.Issuer
        Thumbprint = $certificate.Thumbprint
        NotAfter   = $certificate.NotAfter
    }
}
```

ל-SHA-256 של מבנה התעודה:

```powershell
Get-ChildItem "$CertDirectory\root_ca_x2_*.crt" | ForEach-Object {
    certutil.exe -dump $_.FullName |
        Select-String "Cert Hash\(sha256\)"
}
```

### 11.4 התקנה ידנית דרך Pixel Android 14

הנתיב ב-Pixel Android 14 הוא בדרך כלל:

```text
Settings
→ Security & privacy
→ More security & privacy
→ Encryption & credentials
→ Install a certificate
→ CA certificate
```

לאחר מכן בחר את הקובץ מתוך `Downloads`.

Android דורש אישור ידני ומציג אזהרה על ניטור תעבורה. לעיתים הוא דורש PIN או נעילת מסך.

### 11.5 התקנה ישירה כתעודת משתמש באמצעות ADB root — אמולטור בלבד

שיטה זו מוסיפה את התעודות ל-**User CA store** בנתיב `cacerts-added`; היא אינה מתקינה אותן כתעודות מערכת ואינה מעניקה להן System Trust. היא חוסכת את ממשק ההתקנה ונבדקה על image מסוג Google APIs API 34.

היא אינה מיועדת למכשיר פיזי רגיל או ל-image מסוג production שבו `adb root` חסום.

דרישות:

- האמולטור מחובר ל-ADB;
- `adb root` מצליח;
- Git for Windows/OpenSSL מותקן בנתיב הבא, או שהנתיב מותאם:

  ```text
  C:\Program Files\Git\usr\bin\openssl.exe
  ```

סקריפט מלא:

```powershell
$SdkRoot = "C:\Users\Haim-Y\AppData\Local\Android\Sdk"
$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$OpenSsl = "C:\Program Files\Git\usr\bin\openssl.exe"
$CertDirectory = Join-Path $env:USERPROFILE "Downloads"
$Certificates = Get-ChildItem "$CertDirectory\root_ca_x2_*.crt"

if ($Certificates.Count -eq 0) {
    throw "No NetFree certificate files were found"
}

& $Adb devices -l
& $Adb root
Start-Sleep -Seconds 3
& $Adb wait-for-device

$identity = & $Adb shell id
if ($identity -notmatch 'uid=0\(root\)') {
    throw "adb root is unavailable; do not continue with direct installation"
}

& $Adb shell (
    "mkdir -p /data/misc/user/0/cacerts-added && " +
    "chown system:system /data/misc/user/0/cacerts-added && " +
    "chmod 755 /data/misc/user/0/cacerts-added && " +
    "chcon u:object_r:system_security_cacerts_file:s0 " +
    "/data/misc/user/0/cacerts-added"
)

if ($LASTEXITCODE -ne 0) {
    throw "Failed to prepare Android user CA directory"
}

foreach ($certificate in $Certificates) {
    $subjectHash = (& $OpenSsl x509 `
        -in $certificate.FullName `
        -subject_hash_old `
        -noout).Trim()

    if ($subjectHash -notmatch '^[0-9a-fA-F]{8}$') {
        throw "Invalid Android subject hash for $($certificate.Name): $subjectHash"
    }

    $target = "/data/misc/user/0/cacerts-added/$subjectHash.0"
    & $Adb push $certificate.FullName $target

    if ($LASTEXITCODE -ne 0) {
        throw "Failed to push $($certificate.Name)"
    }
}

& $Adb shell (
    "chown system:system /data/misc/user/0/cacerts-added/* && " +
    "chmod 644 /data/misc/user/0/cacerts-added/* && " +
    "chcon u:object_r:system_security_cacerts_file:s0 " +
    "/data/misc/user/0/cacerts-added/* && " +
    "ls -lZ /data/misc/user/0/cacerts-added"
)

if ($LASTEXITCODE -ne 0) {
    throw "Failed to finalize Android user CA permissions"
}

& $Adb reboot
```

ה-alias-ים שחושבו עבור חמש התעודות שנבדקו:

| קובץ | Android alias |
|---|---|
| `root_ca_x2_ed25519.crt` | `e2ba495d.0` |
| `root_ca_x2_prime256v1.crt` | `8241407d.0` |
| `root_ca_x2_rsa.crt` | `0d721af3.0` |
| `root_ca_x2_rsa_2037.crt` | `0368fee2.0` |
| `root_ca_x2_secp384r1.crt` | `1b31ea34.0` |

### 11.6 אימות לאחר reboot

המתן ל-boot מלא, החזר את adbd למצב root וקרא את המאגר:

```powershell
& $Adb root
Start-Sleep -Seconds 3
& $Adb wait-for-device

& $Adb shell getprop sys.boot_completed
& $Adb shell "ls -lZ /data/misc/user/0/cacerts-added"
& $Adb shell "sha256sum /data/misc/user/0/cacerts-added/*"
& $Adb shell dpm list-owners
```

לכל קובץ נדרש:

- owner: `system system`;
- mode: `-rw-r--r--` (`0644`);
- SELinux context: `u:object_r:system_security_cacerts_file:s0`.

יש לוודא גם שה-A Bloq עדיין Device Owner לאחר האתחול.

### 11.7 מגבלת אמון בתעודות משתמש — Android 7 ומעלה

> **אזהרה מהותית:** קיום התעודה ב-`cacerts-added`, הצלחת `DevicePolicyManager.installCaCert()` או תשובת `true` מ-`hasCaCertInstalled()` מוכיחים רק שהתעודה נמצאת ב-**User CA store**. הם אינם הופכים אותה לתעודת מערכת ואינם מוכיחים ש-Google Play,‏ Cronet או אפליקציה אחרת יסמכו עליה.

החל מ-Android 7 / API 24, אפליקציות שמכוונות לגרסאות Android מודרניות אינן סומכות כברירת מחדל על תעודות CA שהמשתמש או מנהל המכשיר הוסיפו. אפליקציה יכולה לבחור במפורש לסמוך עליהן באמצעות Network Security Configuration, אך אפליקציות שמשתמשות במאגר פרטי, ב-certificate pinning או במדיניות שאינה כוללת User CAs עדיין ידחו TLS.

מקורות רשמיים:

- [Android Network security configuration](https://developer.android.com/privacy-and-security/security-config) — הגדרת מקורות האמון של האפליקציה וההתנהגות כלפי User CAs.
- [Android DevicePolicyManager](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#installCaCert(android.content.ComponentName,%20byte%5B%5D)) — `installCaCert()` מתועד כהתקנת התעודה **as a user CA**.

לכן, אם דפדפן או אפליקציה אחת עובדים ואפליקציה אחרת אינה עובדת, אין להסיק שההתקנה נכשלה. יש לבדוק את מדיניות ה-TLS של האפליקציה. בפרט, התקנת אותן תעודות שוב לא תשנה את מדיניות האמון של Google Play.

המידע מן המקורות המקוונים נוסח מחדש לצורך עמידה במגבלות רישוי; יש לעיין בקישורים לנוסח הרשמי.

### 11.8 הסרת תעודות NetFree מהאמולטור

רק אם רוצים לבטל את האמון, ובאמולטור זה בלבד:

```powershell
& $Adb root
Start-Sleep -Seconds 3
& $Adb wait-for-device

& $Adb shell "rm -f \
/data/misc/user/0/cacerts-added/e2ba495d.0 \
/data/misc/user/0/cacerts-added/8241407d.0 \
/data/misc/user/0/cacerts-added/0d721af3.0 \
/data/misc/user/0/cacerts-added/0368fee2.0 \
/data/misc/user/0/cacerts-added/1b31ea34.0"

& $Adb reboot
```

לפני מחיקה יש לוודא שה-alias-ים עדיין שייכים לתעודות NetFree ולא הוחלפו.

### 11.9 התקנת תעודת משתמש/מנהל במכשיר פיזי באמצעות Device Owner

> **מגבלה שאסור להחמיץ:** ההליך בסעיף זה מתקין User/Admin CA בלבד. Device Owner אינו יכול להפוך תעודה זו ל-System CA באמצעות `installCaCert()`. הצלחת ההליך אינה אמורה לגרום ל-Google Play או לאפליקציות אחרות שאינן סומכות על User CAs לקבל את תעודת NetFree.

#### 11.9.1 ההבדל לעומת אמולטור ומה ה-API מבטיח

במכשיר production פיזי, כגון Samsung, ‏`adb root` חסום ול-shell אין הרשאה לקרוא או לכתוב ישירות ב:

```text
/data/misc/user/0/cacerts-added
```

לכן **אין** לנסות להעתיק לשם קבצים, לשנות owner/SELinux או לחקות את שיטת האמולטור. כאשר A Bloq היא Device Owner, ה-API הנתמך להתקנה שקטה של **תעודת משתמש המנוהלת בידי מנהל המכשיר** הוא:

```kotlin
DevicePolicyManager.installCaCert(admin, certificateDerBytes)
```

ולבדיקת נוכחותה באותו מאגר:

```kotlin
DevicePolicyManager.hasCaCertInstalled(admin, certificateDerBytes)
```

שמות המתודות אינם משנים את סוג מאגר האמון: התיעוד הרשמי מגדיר את פעולת `installCaCert()` כהתקנה “as a user CA”. ‏`hasCaCertInstalled()` מאמת נוכחות בלבד, לא System Trust ולא הצלחת TLS באפליקציה מסוימת.

בגרסה שנבדקה אין עדיין מסך מובנה להתקנת CA. האפשרות **„חסימת התקנת אישורי אבטחה”** רק מפעילה את `DISALLOW_CONFIG_CREDENTIALS`; היא אינה מתקינה תעודה. ההליך הבא משתמש ברכיב זמני ב-`src/debug`, מתקין באמצעות Device Owner, מאמת, מחזיר את ה-APK המקורי ומסיר את הרכיב הזמני.

#### 11.9.2 אזהרת אבטחה ואישור

התקנת Root CA משנה את מודל האמון של המכשיר ועלולה לאפשר ל-NetFree לאמת ולסנן HTTPS שעובר דרכה. לפני ביצוע שינוי יש:

1. לאמת שהקבצים הגיעו ממקור NetFree רשמי;
2. להציג למפעיל את שמות חמש התעודות ואת ה-fingerprint-ים;
3. לקבל אישור מפורש להתקנת Root CA כ-User/Admin CA במכשיר;
4. להכין מסלול rollback באמצעות `uninstallCaCert()`;
5. לא להסיר את A Bloq, לא לבצע `pm clear` ולא להסיר Device Owner.

#### 11.9.3 ערכי המכשיר שנבדק

```powershell
$ProjectRoot = "C:\projects\SecureGuardMDM\SecureGuardMDM"
$SdkRoot = "C:\Users\Haim-Y\AppData\Local\Android\Sdk"
$Adb = Join-Path $SdkRoot "platform-tools\adb.exe"
$Serial = "R8YW50PKLHY"
$Package = "com.secureguard.mdm"
$CertDirectory = Join-Path $env:USERPROFILE "Downloads"
```

המכשיר שנבדק:

```text
Samsung SM-A145P
Android 15 / API 35
serial: R8YW50PKLHY
```

אמת תמיד מחדש ולא באמצעות הנחה:

```powershell
& $Adb devices -l
& $Adb -s $Serial shell getprop ro.product.model
& $Adb -s $Serial shell getprop ro.build.version.sdk
& $Adb -s $Serial shell getprop ro.debuggable
& $Adb -s $Serial shell id
& $Adb -s $Serial shell dpm list-owners
& $Adb -s $Serial shell run-as $Package id
```

במכשיר production צפוי:

```text
ro.debuggable=0
adb shell id → uid=2000(shell)
ls /data/misc/user/0/cacerts-added → Permission denied
```

`run-as` חייב להצליח עבור ה-build הזמני. אם החבילה אינה debuggable, אין להמשיך במסלול זה בלי APK חתום באותו מפתח ו-entry point נתמך.

#### 11.9.4 גיבוי ה-APK המקורי ואימות חתימה

עדכון במקום שומר Device Owner ונתוני אפליקציה **רק** כאשר `applicationId` והחתימה זהים. גבה קודם את ה-APK שמותקן בפועל:

```powershell
$OriginalApk = Join-Path $ProjectRoot `
    "app\build\tmp\connected-phone-com.secureguard.mdm-base.apk"

$RemoteApk = ((& $Adb -s $Serial shell pm path $Package |
    Select-Object -First 1) -replace '^package:', '').Trim()

& $Adb -s $Serial pull $RemoteApk $OriginalApk
Get-FileHash -Algorithm SHA256 $OriginalApk
```

אתר את `apksigner` ובדוק את המפתח של ה-APK המקורי ושל ה-build המקומי:

```powershell
$BuildTools = Get-ChildItem "$SdkRoot\build-tools" -Directory |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1
$ApkSigner = Join-Path $BuildTools.FullName "apksigner.bat"
$LocalApk = Join-Path $ProjectRoot `
    "app\build\outputs\apk\debug\Abloq-debug.apk"

& $ApkSigner verify --print-certs $OriginalApk |
    Select-String "certificate DN|certificate SHA-256"
& $ApkSigner verify --print-certs $LocalApk |
    Select-String "certificate DN|certificate SHA-256"
```

בפעולה שנבדקה שני ה-APK-ים היו חתומים כך:

```text
certificate DN: C=US, O=Android, CN=Android Debug
certificate SHA-256:
04a3b2bb8c9731d9e3e54b4c8637da843745c3ad6d53d45f1829698a2d911479
```

SHA-256 של ה-APK המקורי שנשמר:

```text
08A05914F1F5A3F8C8A065FB2BD78C3C255A12668A19F52EC7B0AD41B2292734
```

אם חתימות ה-APK שונות — **עוצרים**. אין להסיר את האפליקציה כדי לעקוף `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, משום שהיא Device Owner.

#### 11.9.5 המרת התעודות ל-DER ואימות fingerprint

`installCaCert()` מקבל certificate bytes מקודדים. בפעולה שנבדקה ה-DER הוטמע זמנית כמחרוזת Base64 בתוך רכיב ה-debug; שמירת אותם bytes כמשאבי `res/raw` שקולה, קצרה יותר לתיעוד ומונעת העתקת מחרוזות Base64 ארוכות. המר את קובצי PEM/CRT ל-DER באמצעות `X509Certificate2.RawData`, והוסף אותם זמנית ל-`src/debug/res/raw`:

```powershell
$RawDirectory = Join-Path $ProjectRoot "app\src\debug\res\raw"
New-Item -ItemType Directory -Force -Path $RawDirectory | Out-Null

$CertificateNames = @(
    "root_ca_x2_ed25519",
    "root_ca_x2_prime256v1",
    "root_ca_x2_rsa",
    "root_ca_x2_rsa_2037",
    "root_ca_x2_secp384r1"
)

foreach ($name in $CertificateNames) {
    $source = Join-Path $CertDirectory "$name.crt"
    if (-not (Test-Path $source)) {
        throw "Missing certificate: $source"
    }

    $certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($source)
    if ($certificate.Subject -ne $certificate.Issuer) {
        throw "$name is not self-signed"
    }

    $hasher = [System.Security.Cryptography.SHA256]::Create()
    try {
        $fingerprint = -join ($hasher.ComputeHash($certificate.RawData) |
            ForEach-Object { $_.ToString("X2") })
    } finally {
        $hasher.Dispose()
    }

    Write-Host "$name = $fingerprint"
    [System.IO.File]::WriteAllBytes(
        (Join-Path $RawDirectory "$name.der"),
        $certificate.RawData
    )
}
```

יש להשוות את הפלט לטבלה בסעיף 11.2. אין לבנות APK אם אפילו fingerprint אחד שונה בלי אימות חדש מול NetFree.

#### 11.9.6 רכיב Device Owner זמני

צור זמנית:

```text
app/src/debug/java/com/secureguard/mdm/debug/NetfreeCaInstallerActivity.kt
```

מימוש מינימלי:

```kotlin
package com.secureguard.mdm.debug

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import com.secureguard.mdm.R
import com.secureguard.mdm.SecureGuardDeviceAdminReceiver
import java.security.MessageDigest

class NetfreeCaInstallerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val operation = intent.getStringExtra("operation") ?: "verify"
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, SecureGuardDeviceAdminReceiver::class.java)
        require(dpm.isDeviceOwnerApp(packageName)) { "Device Owner required" }

        val results = CERTIFICATES.map { certificate ->
            val der = resources.openRawResource(certificate.resourceId).use { it.readBytes() }
            val fingerprint = MessageDigest.getInstance("SHA-256")
                .digest(der)
                .joinToString("") { byte -> "%02X".format(byte) }
            val before = dpm.hasCaCertInstalled(admin, der)
            val apiResult = when (operation) {
                "install" -> before || dpm.installCaCert(admin, der)
                "remove" -> {
                    if (before) dpm.uninstallCaCert(admin, der)
                    true
                }
                "verify" -> true
                else -> error("Unsupported operation: $operation")
            }
            val after = dpm.hasCaCertInstalled(admin, der)
            val expectedAfter = operation != "remove"
            val ok = apiResult && after == expectedAfter
            "${certificate.name}|sha256=$fingerprint|before=$before|" +
                "api=$apiResult|after=$after|ok=$ok"
        }

        val allSuccessful = results.all { it.endsWith("ok=true") }
        val summary = buildString {
            appendLine("operation=$operation")
            results.forEach(::appendLine)
            append("allSuccessful=$allSuccessful")
        }
        openFileOutput(RESULT_FILE, MODE_PRIVATE)
            .bufferedWriter().use { it.write(summary) }
        Log.i(TAG, summary.replace('\n', ';'))
        finish()
    }

    private data class CertificateDefinition(
        val name: String,
        val resourceId: Int,
    )

    companion object {
        const val RESULT_FILE = "netfree-ca-install-result.txt"
        private const val TAG = "NetfreeCaInstaller"

        private val CERTIFICATES = listOf(
            CertificateDefinition("root_ca_x2_ed25519", R.raw.root_ca_x2_ed25519),
            CertificateDefinition("root_ca_x2_prime256v1", R.raw.root_ca_x2_prime256v1),
            CertificateDefinition("root_ca_x2_rsa", R.raw.root_ca_x2_rsa),
            CertificateDefinition("root_ca_x2_rsa_2037", R.raw.root_ca_x2_rsa_2037),
            CertificateDefinition("root_ca_x2_secp384r1", R.raw.root_ca_x2_secp384r1),
        )
    }
}
```

צור זמנית גם:

```text
app/src/debug/AndroidManifest.xml
```

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="com.secureguard.mdm.debug.NetfreeCaInstallerActivity"
            android:excludeFromRecents="true"
            android:exported="true"
            android:noHistory="true"
            android:theme="@style/Theme.SecureGuard" />
    </application>
</manifest>
```

`exported=true` שימש זמנית משום שב-Samsung Android 15 הפעלה של Activity לא-מיוצאת דרך `run-as ... am start` נכשלה עם:

```text
SecurityException: package=com.android.shell does not belong to uid=10264
```

הרכיב קיים רק ב-`src/debug`, מתקין/מסיר רק את חמש התעודות הקבועות, ובודק שהחבילה היא Device Owner. למרות זאת, יש להשאירו מותקן לזמן הקצר ביותר ולהחזיר מיד את ה-APK המקורי.

#### 11.9.7 בנייה עם Java 17

במחשב שנבדק `JAVA_HOME` הצביע ל-Java 21, ו-Android Gradle נכשל ב-`jlink` עם:

```text
PluginException: ModuleTarget is malformed: platformString missing delimiter: android
```

ה-build הצליח עם Temurin 17:

```powershell
Set-Location $ProjectRoot
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17.0.20"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

java -version
.\gradlew.bat --no-daemon --console=plain :app:assembleDebug
```

נדרש לראות `BUILD SUCCESSFUL`. לאחר הבנייה אמת שוב את חתימת ה-APK הזמני מול ה-APK המקורי לפני התקנה.

#### 11.9.8 התקנה, הפעלה ואימות חמש התעודות

התקן **מעל** החבילה הקיימת, בלי uninstall ובלי `pm clear`:

```powershell
$HelperApk = Join-Path $ProjectRoot `
    "app\build\outputs\apk\debug\Abloq-debug.apk"

& $Adb -s $Serial install -r $HelperApk
if ($LASTEXITCODE -ne 0) {
    throw "Temporary helper update failed"
}
```

הפעל את ההתקנה:

```powershell
& $Adb -s $Serial logcat -c

& $Adb -s $Serial shell am start -W `
    -n com.secureguard.mdm/.debug.NetfreeCaInstallerActivity `
    --es operation install

Start-Sleep -Seconds 3

$InstallResult = (& $Adb -s $Serial exec-out run-as $Package `
    cat files/netfree-ca-install-result.txt | Out-String)

$InstallResult
```

נדרש לראות חמש שורות שבהן:

```text
api=true|after=true|ok=true
```

ובסוף:

```text
allSuccessful=true
```

בצע pass נוסף שאינו מתקין אלא בודק את מצב Android:

```powershell
& $Adb -s $Serial shell am start -W `
    -n com.secureguard.mdm/.debug.NetfreeCaInstallerActivity `
    --es operation verify

Start-Sleep -Seconds 2

$VerifyResult = (& $Adb -s $Serial exec-out run-as $Package `
    cat files/netfree-ca-install-result.txt | Out-String)

$VerifyResult

if ($VerifyResult -notmatch 'allSuccessful=true' -or
    ([regex]::Matches($VerifyResult, 'after=true\|ok=true')).Count -ne 5) {
    throw "Not all five NetFree certificates are installed"
}
```

`hasCaCertInstalled()` הוא האימות הקובע לכך שהתעודה קיימת ב-User CA store המנוהל על ידי Device Owner. הוא **אינו** מאמת System Trust ואינו מבטיח שאפליקציה כלשהי תקבל את שרשרת ה-TLS. אין להסתפק בכך שה-Activity נפתחה או ש-`installCaCert()` לא זרק חריגה.

#### 11.9.9 החזרת ה-APK המקורי וניקוי

לאחר ששתי בדיקות התעודות עברו, החזר את ה-APK המקורי שגיבית:

```powershell
& $Adb -s $Serial install -r $OriginalApk
if ($LASTEXITCODE -ne 0) {
    throw "Original APK restore failed"
}

Start-Sleep -Seconds 5

$RemoteApk = ((& $Adb -s $Serial shell pm path $Package |
    Select-Object -First 1) -replace '^package:', '').Trim()
& $Adb -s $Serial shell sha256sum $RemoteApk
```

בפעולה שנבדקה חזר hash ה-APK המקורי:

```text
08a05914f1f5a3f8c8a065fb2bd78c3c255a12668a19f52ec7b0ad41b2292734
```

עדכון APK אינו מסיר תעודות שהותקנו על ידי Device Owner; הן נשמרות בשירות מדיניות המכשיר. מחק לאחר מכן מה-workspace את כל הקבצים הזמניים:

```text
app/src/debug/AndroidManifest.xml
app/src/debug/java/com/secureguard/mdm/debug/NetfreeCaInstallerActivity.kt
app/src/debug/res/raw/root_ca_x2_ed25519.der
app/src/debug/res/raw/root_ca_x2_prime256v1.der
app/src/debug/res/raw/root_ca_x2_rsa.der
app/src/debug/res/raw/root_ca_x2_rsa_2037.der
app/src/debug/res/raw/root_ca_x2_secp384r1.der
```

בנה שוב `assembleDebug` ובדוק שהשם `NetfreeCaInstaller` אינו מופיע ב-source, ב-merged manifest או ב-APK. מחק גם את קובץ התוצאה הזמני מהמכשיר:

```powershell
& $Adb -s $Serial shell run-as $Package `
    rm -f files/netfree-ca-install-result.txt
```

#### 11.9.10 שחזור Device Owner, VPN וחומת האש לאחר החלפת APK

החלפת APK מפעילה מחדש את תהליך האפליקציה. ב-Samsung שנבדק Always-On נשמר, אך Android הפעיל את `BlockerVpnService` עם action כללי `android.net.VpnService`, שהגרסה הנוכחית אינה בונה ממנו ממשק. לכן `tun0` נעלם זמנית עד שנשלח `ACTION_START`:

```powershell
& $Adb -s $Serial shell run-as $Package `
    am start-foreground-service --user 0 `
    -a com.secureguard.mdm.firewall.ACTION_START `
    -n com.secureguard.mdm/.services.BlockerVpnService

Start-Sleep -Seconds 5
```

אימות מלא:

```powershell
& $Adb -s $Serial shell dpm list-owners

& $Adb -s $Serial shell dumpsys device_policy |
    Select-String "mAlwaysOnVpnPackage=com.secureguard.mdm"

& $Adb -s $Serial shell dumpsys vpn_management
& $Adb -s $Serial shell ip addr show tun0

& $Adb -s $Serial shell run-as $Package `
    sha256sum databases/secure_guard_database

& $Adb -s $Serial shell run-as $Package `
    am start-foreground-service --user 0 `
    -a com.secureguard.mdm.firewall.ACTION_RELOAD_RULES `
    -n com.secureguard.mdm/.services.BlockerVpnService

Start-Sleep -Seconds 2

& $Adb -s $Serial logcat -d -v time |
    Select-String "Firewall VPN active|Reloaded 14 firewall rules"
```

בפעולה שנבדקה אומת:

- A Bloq נשארה Device Owner;
- `mAlwaysOnVpnPackage=com.secureguard.mdm`;
- `tun0` חזר למצב `UP`;
- Waze UID `10258` ו-Pango UID `10270` נותבו דרך A Bloq;
- מסד חומת האש נשאר עם SHA-256 `149bddfcd0a3721cd56675007acf2bd0bbf3b5a6f2cdb2f679e0f150825b5991`;
- המנוע דיווח `Reloaded 14 firewall rules.`.

#### 11.9.11 הסרת התעודות מהמכשיר הפיזי

Rollback צריך להשתמש באותם DER bytes. בנה שוב את רכיב העזר הזמני והפעל:

```powershell
& $Adb -s $Serial shell am start -W `
    -n com.secureguard.mdm/.debug.NetfreeCaInstallerActivity `
    --es operation remove
```

נדרש לראות עבור כל חמש התעודות:

```text
after=false|ok=true
```

לאחר מכן החזר שוב את ה-APK המקורי, הסר את קובצי `src/debug` הזמניים, הפעל מחדש את ה-VPN ואמת Device Owner וחומת אש. אין למחוק קבצים ישירות מ-`/data/misc/user/0/cacerts-added` במכשיר production.

#### 11.9.12 תוצאות ההתקנה שנבדקה

ב-Samsung שנבדק כל אחת מחמש התעודות החזירה בשלב ההתקנה ובשלב האימות:

```text
before=false/true
api=true
after=true
ok=true
```

והסיכום החזיר:

```text
allSuccessful=true
```

לא בוצעו uninstall,‏ `pm clear`, הסרת admin או שינוי Device Owner. ה-APK המקורי הוחזר, רכיב ההתקנה הזמני הוסר, והתעודות נשארו מותקנות כ-User CAs המנוהלות על ידי Device Owner.

#### 11.9.13 תוצאת Google Play שנבדקה — ההתקנה הצליחה אך האמון נדחה

לאחר התקנת כל חמש התעודות ואימות `after=true` עבור כולן, אומת שהרשת עצמה תקינה:

- Wi-Fi היה במצב `VALIDATED`;
- DNS ו-`ping` אל `accounts.google.com` ואל `testnewcert.internal.netfree.link` הצליחו;
- Google Play ו-Google Play Services היו מותקנים ומופעלים.

למרות זאת, Google Play (`Finsky` / `cr_X509Util`) דחה את שרשרת ה-TLS עם השגיאות:

```text
net::ERR_CERT_AUTHORITY_INVALID
ErrorCode=11, InternalErrorCode=-202
Trust anchor for certification path not found.
javax.net.ssl.SSLHandshakeException
java.security.cert.CertPathValidatorException
```

זוהי התוצאה הצפויה כאשר האפליקציה אינה כוללת User CAs במקורות האמון שלה. אין להתקין שוב את אותן חמש תעודות: הפעולה כבר הצליחה, וחזרה עליה לא תהפוך אותן ל-System CAs. כמו כן, Device Owner אינו עוקף את מדיניות האמון של Google Play/Cronet.

#### 11.9.14 המגבלה ב-Android 15 והחלופות הבטוחות ללא root

במכשיר Samsung Android 15 production ללא root אין מסלול ADB או Device Owner נתמך שמוסיף CA פרטי למאגר תעודות המערכת. לפי תיעוד NetFree שסופק לעבודה זו, אין פתרון מלא ללא root עבור Android 7 ומעלה; ב-Android 14 ומעלה שינוי אמון המערכת מתואר כמסלול Magisk/root. אין לבצע זאת אוטומטית: פתיחת bootloader עלולה למחוק את המכשיר, להשפיע על Samsung Knox ועל בדיקות integrity, ולסכן Device Owner ונתונים.

החלופות הבטוחות הן:

1. לקבל את ה-APK המדויק ממקור אמין ולהתקין ישירות, בלי תלות ב-Google Play:

   ```powershell
   & $Adb -s $Serial install -r "C:\path\to\trusted-app.apk"
   ```

   יש לאמת package, חתימה ו-hash לפני ההתקנה. Sideload עוקף רק את החנות; אם האפליקציה עצמה אינה סומכת על User CA, חיבורי הרשת שלה עדיין עלולים להיכשל.

2. לבדוק זמנית את האפליקציה דרך mobile data או רשת שאינה עוברת דרך NetFree, ולאחר הבדיקה לשחזר את הרשת המקורית. אין לשנות תוך כדי כך את Device Owner, ה-Always-On VPN או כללי חומת האש.

3. להשתמש בהפצה/פתרון רשמי תואם של NetFree או WiFree, אם קיימת תמיכה בסוג המכשיר ובאפליקציה הנדרשת.

4. עבור אפליקציה שבשליטת הפרויקט בלבד, להגדיר במפורש ובזהירות `network_security_config` שסומך על User CAs. אין לשנות, לחתום מחדש או לעקוף הגנות של Google Play, Google Play Services או אפליקציה צד שלישי.

לביצוע התקנה ישירה נדרשים שם האפליקציה, שם החבילה או נתיב ה-APK. ללא אחד מאלה אין לבצע שינוי ספקולטיבי במכשיר.

## 12. Smoke test לחומת האש הפנימית

לאחר שהאמולטור, Device Owner והתעודות תקינים:

1. הפעל את A Bloq.
2. אשר `Modify system settings`, אם נדרש.
3. השלם יצירת סיסמת ניהול ראשונית.
4. עבור להגדרות חומת האש.
5. בחר דפדפן שאינו חולק UID עם אפליקציות אחרות.
6. שמור את הבחירה.
7. הפעל תחילה `MONITOR_ONLY`.
8. אשר את הרשאת ה-VPN של Android, אם היא מופיעה.
9. גלוש ל-`example.com`.
10. פתח "יעדים אחרונים" וודא שה-domain/IP, הפורט, הפרוטוקול וההחלטה נרשמו.
11. לחץ "חסום" על היעד.
12. ודא שנוצר כלל `DOMAIN_EXACT` או `IP_EXACT` מתאים.
13. ודא שהמצב השתנה מ-`MONITOR_ONLY` ל-`BLOCKLIST`.
14. נסה לגלוש שוב וודא שהיעד נחסם.
15. בדוק גם חסימת UDP/443 עבור QUIC ופורט 853 עבור DoT, לפי ההגדרות הרצויות.

אפליקציות עם shared UID צריכות להיות מסומנות כלא נתמכות ולא להיכנס ל-VPN.

## 12.1 חסימת Google Play ו-Apple App Store עבור Waze ו-Pango

### 12.1.1 האם האפשרות קיימת בתוך A Bloq?

**כן. זו הדרך המומלצת והבטוחה.** אין צורך ללכוד תעבורה כדי להוסיף דומיינים ידועים מראש.

A Bloq כוללת בממשק:

- בחירת אפליקציות שייכנסו ל-VPN הפנימי;
- מצב `MONITOR_ONLY`,‏ `BLOCKLIST` או `ALLOWLIST` לכל אפליקציה;
- הוספת כללים מסוג `DOMAIN_EXACT`,‏ `DOMAIN_SUFFIX`,‏ `IP_EXACT`,‏ `CIDR`,‏ `PORT`,‏ `IP_PORT` ו-`DOMAIN_PORT`;
- פעולת `BLOCK` או `ALLOW` ופרוטוקול `ANY`,‏ `TCP` או `UDP`;
- כפתור **„יעדים אחרונים”** שמאפשר ליצור חסימה מהירה מתעבורה שנצפתה, אך הוא אופציונלי ואינו נדרש במקרה זה.

הנתיב המדויק באפליקציה בעברית:

```text
A Bloq
→ הגדרות
→ ניהול אפליקציות
→ סינון רשת לפי אפליקציה
→ אפליקציות וכללים
```

אם הכניסה להגדרות נעולה, יש להזין את סיסמת הניהול של A Bloq. לסוכן AI או ל-ADB יש גם קיצור דרך למסך, שאינו משנה מדיניות בעצמו:

```powershell
& $Adb -s $Serial shell am start -W `
    -n com.secureguard.mdm/.MainActivity `
    --es start_destination firewall_overview
```

### 12.1.2 הגדרה מלאה דרך ממשק האפליקציה

יש לבצע את הפעולות בסדר הבא. בחירת האפליקציות חייבת להישמר לפני הפעלת ה-VPN.

1. פתח **„סינון רשת לפי אפליקציה”**.
2. חפש `Waze` או `com.waze` וסמן את תיבת הבחירה שלה.
3. בשורת Waze פתח **„מצב”** ובחר `BLOCKLIST`. בממשק הוא מתואר כ-**„חסום רק לפי כללי BLOCK”**.
4. חפש `Pango` או `com.unicell.pangoandroid`, סמן אותה ובחר גם עבורה `BLOCKLIST`.
5. אין צורך להפעיל **„חסום QUIC (UDP/443)”** או **„חסום DNS מוצפן (פורט 853)”** עבור חסימת דומייני החנויות בלבד.
6. לחץ על הכפתור הצף **„שמור והפעל”**. פעולה זו שומרת את בחירת האפליקציות. אם חומת האש הראשית עדיין כבויה, תופיע הודעה שיש להפעיל אותה בהגדרות.
7. לחץ על `+` / **„הוסף כלל”**. הכפתור זמין לאחר שבחירת האפליקציות נשמרה.
8. עבור כל אחד משבעת הדומיינים שבסעיף הבא, צור כלל אחד עבור Waze וכלל אחד עבור Pango — בסך הכול 14 כללים.
9. בכל כלל בחר:

   ```text
   אפליקציה: com.waze או com.unicell.pangoandroid
   סוג: DOMAIN_SUFFIX
   פעולה: BLOCK
   פרוטוקול: ANY
   domain / IP / CIDR: הדומיין בלבד, ללא https:// וללא נתיב
   ```

10. לחץ **„שמור כלל”** לאחר כל כלל.
11. חזור למסך **„ניהול הגדרות”**, גלול לקטגוריה **„VPN וחומת אש”** והפעל **„חומת אש פנימית לפי אפליקציה”**.
12. אם Android מציג בקשת חיבור VPN, אשר אותה פעם אחת.
13. לחץ על הכפתור הצף **„שמור שינויים”**. לא מספיק רק להזיז את המתג: השמירה היא שמפעילה את קריאת ה-Device Owner, מגדירה את A Bloq כ-Always-On VPN ומתחילה את השירות.

אפשר לפתוח ישירות את מסך ניהול ההגדרות באמצעות:

```powershell
& $Adb -s $Serial shell am start -W `
    -n com.secureguard.mdm/.MainActivity `
    --es start_destination settings
```

ה-Intent הזה רק פותח את המסך. הוא **אינו** מפעיל את ה-VPN ואינו עוקף את הצורך ללחוץ **„שמור שינויים”**.

### 12.1.3 קבוצת הדומיינים המינימלית

יש ליצור את הרשימה הבאה עבור כל אחת משתי האפליקציות:

| דומיין | מטרה |
|---|---|
| `play.google.com` | דפי Google Play וקישורי חנות עיקריים |
| `market.android.com` | קישורי Play ישנים |
| `play.app.goo.gl` | קישורים מקוצרים/מופנים אל Google Play |
| `apps.apple.com` | דפי Apple App Store |
| `itunes.apple.com` | קישורי App Store ישנים ושירותי קישור של Apple |
| `appstore.com` | קישורי App Store קצרים |
| `appsto.re` | קישורי App Store קצרים ישנים |

`DOMAIN_SUFFIX` חוסם גם את הדומיין עצמו וגם subdomains שלו. `ANY` נבחר בכוונה כדי שהכלל יחול גם כאשר זיהוי הדומיין מגיע ממסלול DNS ולא רק מחיבור TCP מסוים.

אין לחסום באופן גורף את `google.com`,‏ `googleapis.com` או `apple.com`: אלו דומיינים רחבים שעלולים לשבור ניווט, מפות, התחברות ושירותים רגילים של Waze/Pango.

### 12.1.4 מה החסימה עושה ומה היא אינה עושה

ה-VPN מוגדר באמצעות `addAllowedApplication`, ולכן רק ה-UID-ים של האפליקציות שנבחרו עוברים בממשק ה-VPN. במקרה שנבדק:

```text
com.waze                    UID 10258
com.unicell.pangoandroid    UID 10270
```

כאשר Waze או Pango מבצעות בעצמן בקשת DNS/רשת לדומיין חסום, מנוע חומת האש מחזיר החלטת `BLOCK`.

עם זאת, אם Waze או Pango שולחות רק Android `Intent` שפותח את אפליקציית Google Play, התעבורה שלאחר פתיחת החנות שייכת ל-`com.android.vending`, לא ל-Waze/Pango. כללים המוגבלים ל-Waze/Pango אינם אמורים לחסום את Google Play עבור כל המכשיר. כדי לחסום גם את פתיחת החנות נדרשת מדיניות נפרדת עבור `com.android.vending` או חסימת Intent/אפליקציה — שינוי רחב יותר שיש לבצע רק בכוונה מפורשת.

### 12.1.5 אימות שהמדיניות פעילה

הגדר תחילה את המכשיר הפיזי במפורש, במיוחד כאשר מחובר גם אמולטור:

```powershell
$Serial = "R8YW50PKLHY"   # דוגמה מהמכשיר שנבדק; יש לאמת עם adb devices
& $Adb devices -l
```

אימות Device Owner ו-Always-On:

```powershell
& $Adb -s $Serial shell dumpsys device_policy |
    Select-String "Device Owner|admin=|mAlwaysOnVpnPackage"
```

הפלט חייב לכלול:

```text
admin=ComponentInfo{com.secureguard.mdm/...SecureGuardDeviceAdminReceiver}
mAlwaysOnVpnPackage=com.secureguard.mdm
```

אימות VPN פעיל וה-UID-ים המנותבים:

```powershell
& $Adb -s $Serial shell dumpsys vpn_management
& $Adb -s $Serial shell ip addr show tun0
```

נדרש לראות:

- `Active package name: com.secureguard.mdm`;
- `sessionId=A Bloq`;
- את UID `10258` ו-`10270` במכשיר שנבדק, או את ה-UID-ים העדכניים שהתקבלו מ-`cmd package`;
- ממשק `tun0` במצב `UP`.

בדיקת UID-ים במכשיר הנוכחי:

```powershell
& $Adb -s $Serial shell cmd package list packages -U com.waze
& $Adb -s $Serial shell cmd package list packages -U com.unicell.pangoandroid
```

ב-build debuggable ניתן לבקש מהמנוע לקרוא מחדש את מסד הנתונים, בלי לבנות מחדש את הממשק:

```powershell
& $Adb -s $Serial logcat -c

& $Adb -s $Serial shell run-as com.secureguard.mdm `
    am start-foreground-service --user 0 `
    -a com.secureguard.mdm.firewall.ACTION_RELOAD_RULES `
    -n com.secureguard.mdm/.services.BlockerVpnService

Start-Sleep -Seconds 2

& $Adb -s $Serial logcat -d -v time |
    Select-String "InternalFirewallVpn"
```

עבור הרשימה שבמסמך נדרש לראות:

```text
Reloaded 14 firewall rules.
```

בעת בניית הממשק נדרש לראות:

```text
Firewall VPN active for 2 selected package(s).
```

### 12.1.6 הזרקה ישירה למסד Room — מסלול חירום בלבד

הדרך דרך ממשק A Bloq עדיפה. הזרקה ישירה מוצדקת רק כאשר צריך להגדיר רשימה ידועה מראש באופן אוטומטי ואין entry point נתמך אחר.

**אזהרות:**

1. אין לערוך מסד Room חי. כתיבה ישירה בזמן שהתהליך מחזיק WAL/SHM עלולה להשחית או לאבד נתונים.
2. אין למחוק, לבצע `pm clear` או להסיר את A Bloq ממכשיר שבו היא Device Owner.
3. `am force-stop` עשוי להידחות עבור חבילת Device Owner. אם אי אפשר להוכיח שאין writer פעיל למסד — יש לעצור ולא להזריק.
4. יש לגבות את קובצי `secure_guard_database`,‏ `-wal`,‏ `-shm` ואת `secure_guard_prefs.xml` לפני כל שינוי.
5. יש לעבוד על עותק במחשב, לבצע checkpoint, לבדוק `PRAGMA integrity_check`, ורק אז להחליף את המסד במכשיר.

הטבלאות הרלוונטיות:

```text
firewall_app_policy
firewall_rule
```

הייצוג שנבדק:

```text
firewall_app_policy:
  package_name = com.waze / com.unicell.pangoandroid
  policy_mode  = BLOCKLIST
  enabled      = 1
  block_quic   = 0
  block_dot    = 0

firewall_rule:
  rule_type = DOMAIN_SUFFIX
  action    = BLOCK
  protocol  = ANY
  priority  = 100
  enabled   = 1
  source    = MANUAL
  port_start / port_end = NULL
```

תבנית SQL לעותק העבודה — היא משמרת כללים אחרים ומחליפה רק כללים זהים עבור שבעת הדומיינים ושתי החבילות:

```sql
BEGIN IMMEDIATE;

INSERT INTO firewall_app_policy (
    package_name, policy_mode, block_quic, block_dot, enabled, created_at, updated_at
)
VALUES
    ('com.waze', 'BLOCKLIST', 0, 0, 1,
     CAST(strftime('%s','now') AS INTEGER) * 1000,
     CAST(strftime('%s','now') AS INTEGER) * 1000),
    ('com.unicell.pangoandroid', 'BLOCKLIST', 0, 0, 1,
     CAST(strftime('%s','now') AS INTEGER) * 1000,
     CAST(strftime('%s','now') AS INTEGER) * 1000)
ON CONFLICT(package_name) DO UPDATE SET
    policy_mode='BLOCKLIST', block_quic=0, block_dot=0, enabled=1,
    updated_at=excluded.updated_at;

DELETE FROM firewall_rule
WHERE package_name IN ('com.waze', 'com.unicell.pangoandroid')
  AND rule_type='DOMAIN_SUFFIX'
  AND value IN (
      'play.google.com', 'market.android.com', 'play.app.goo.gl',
      'apps.apple.com', 'itunes.apple.com', 'appstore.com', 'appsto.re'
  );

WITH
packages(package_name) AS (
    VALUES ('com.waze'), ('com.unicell.pangoandroid')
),
domains(value) AS (
    VALUES
      ('play.google.com'), ('market.android.com'), ('play.app.goo.gl'),
      ('apps.apple.com'), ('itunes.apple.com'), ('appstore.com'), ('appsto.re')
)
INSERT INTO firewall_rule (
    package_name, rule_type, action, value, protocol,
    port_start, port_end, priority, enabled, source, created_at, updated_at
)
SELECT
    package_name, 'DOMAIN_SUFFIX', 'BLOCK', value, 'ANY',
    NULL, NULL, 100, 1, 'MANUAL',
    CAST(strftime('%s','now') AS INTEGER) * 1000,
    CAST(strftime('%s','now') AS INTEGER) * 1000
FROM packages CROSS JOIN domains;

COMMIT;
PRAGMA wal_checkpoint(TRUNCATE);
PRAGMA integrity_check;
```

`PRAGMA integrity_check` חייב להחזיר `ok`, ושאילתות האימות חייבות להחזיר שתי מדיניות ו-14 כללים:

```sql
SELECT package_name, policy_mode, enabled, block_quic, block_dot
FROM firewall_app_policy
WHERE package_name IN ('com.waze', 'com.unicell.pangoandroid')
ORDER BY package_name;

SELECT package_name, rule_type, action, value, protocol,
       port_start, port_end, priority, enabled, source
FROM firewall_rule
WHERE package_name IN ('com.waze', 'com.unicell.pangoandroid')
ORDER BY package_name, value;
```

לאחר החלפת המסד אין להסתפק ב-`ACTION_START`. הפעלה ישירה של `BlockerVpnService` אינה מבצעת את קריאת ה-Device Owner להגדרת Always-On ועלולה להסתיים ב:

```text
VPN establish() returned null; firewall is not active.
```

המסלול הנכון לאחר ההזרקה הוא:

1. לפתוח את מסך `settings` באמצעות ה-Intent שלעיל;
2. להפעיל **„חומת אש פנימית לפי אפליקציה”**;
3. לאשר את חלון ה-VPN של Android אם הוא מופיע;
4. ללחוץ **„שמור שינויים”**;
5. לאמת `mAlwaysOnVpnPackage=com.secureguard.mdm`,‏ `tun0`, שני UID-ים והודעת טעינת 14 הכללים.

### 12.1.7 גיבוי ושחזור מההזרקה שנבדקה

ב-session של המכשיר הפיזי נשמר גיבוי host בלתי משתנה בנתיב:

```text
C:\projects\SecureGuardMDM\SecureGuardMDM\app\build\tmp\phone-firewall-backup-20260812-132939
```

עותקי rollback נשמרו גם בתוך sandbox האפליקציה:

```text
databases/secure_guard_database.pre-store-rules
databases/secure_guard_database-wal.pre-store-rules
databases/secure_guard_database-shm.pre-store-rules
shared_prefs/secure_guard_prefs.xml.pre-store-rules
```

SHA-256 של המסד שהוזרק ואומת במחשב ובמכשיר:

```text
149bddfcd0a3721cd56675007acf2bd0bbf3b5a6f2cdb2f679e0f150825b5991
```

לפני rollback יש לכבות את **„חומת אש פנימית לפי אפליקציה”** דרך A Bloq וללחוץ **„שמור שינויים”**. לאחר מכן יש לוודא שהשירות אינו מחזיק את המסד, לשחזר יחד את המסד/WAL/SHM וההעדפות מאותה נקודת גיבוי, ולפתוח מחדש את האפליקציה. אין לערבב main database מגיבוי אחד עם WAL/SHM מגיבוי אחר.

המצב שאומת בפועל לאחר ההזרקה:

- Device Owner נשמר;
- `block_internet_vpn=true`;
- `mAlwaysOnVpnPackage=com.secureguard.mdm`;
- `BlockerVpnService` פעל כ-Foreground Service;
- `tun0` היה `UP`;
- מסד הנתונים החי החזיר `INTEGRITY=ok`;
- נמצאו שתי מדיניות `BLOCKLIST` פעילות ו-14 כללי `DOMAIN_SUFFIX/BLOCK/ANY`;
- המנוע דיווח `Reloaded 14 firewall rules.`.

## 13. פקודות אבחון שימושיות

מצב מכשיר:

```powershell
& $Adb devices -l
& $Adb shell getprop ro.build.version.release
& $Adb shell getprop ro.build.version.sdk
& $Adb shell getprop sys.boot_completed
```

חלון/Activity פעילים:

```powershell
& $Adb shell dumpsys window |
    Select-String "mCurrentFocus|mFocusedApp"

& $Adb shell dumpsys activity activities |
    Select-String "topResumedActivity|mResumedActivity"
```

מצב Device Owner:

```powershell
& $Adb shell dpm list-owners
```

מצב התקנת A Bloq:

```powershell
& $Adb shell pm path com.secureguard.mdm
& $Adb shell cmd package resolve-activity `
    --brief `
    -a android.intent.action.MAIN `
    -c android.intent.category.LAUNCHER `
    com.secureguard.mdm
```

לוגים מרכזיים:

```powershell
& $Adb logcat -d -v time '*:E' | Select-Object -Last 200

& $Adb logcat -b events -d -v time |
    Select-String "boot_progress|am_crash|am_anr"
```

עצירת אמולטור בצורה נקייה:

```powershell
& $Adb -s emulator-5554 emu kill
```

## 14. תקלות נפוצות והמשמעות שלהן

| סימפטום | משמעות | פעולה מומלצת |
|---|---|---|
| Kiro מציג `Select debugger` | הופעל Run and Debug כללי | לחץ `Esc`; אין לבחור debugger לצורך Android Emulator |
| `adb devices` ריק | אין transport של אמולטור/מכשיר | בדוק תהליך qemu, הפעל מחדש ADB והפעל AVD |
| `emulator-5554 offline` | האמולטור טרם התחבר או נתקע | המתן זמן קצר, הפעל ADB מחדש או Cold Boot |
| `Can't find service: device_policy` | Android לא סיים לעלות | בדוק `sys.boot_completed` ואל תריץ עדיין `dpm` |
| מסך Google נשאר זמן רב | boot תקוע או SystemUI ANR | בדוק logcat, Cold Boot, ורק אז Wipe Data |
| `dpm set-device-owner` נכשל | חשבון קיים, provisioning מתקדם או owner קיים | השתמש ב-AVD חדש ללא חשבונות |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | חתימת APK שונה מהגרסה המותקנת | אין לעדכן debug מעל release עם keystore אחר |
| `adb root` נכשל | image מסוג production/Google Play או מכשיר פיזי | השתמש בהתקנה ידנית של CA או ב-image מתאים |
| תעודה קיימת אך אפליקציה דוחה TLS | האפליקציה לא סומכת על User CA או משתמשת ב-pinning | בדוק את מדיניות ה-TLS של האפליקציה |
| A Bloq בעברית אך Settings באנגלית | שונתה שפת האפליקציה בלבד | שנה System language בנפרד |
| `Running Devices` ריק | אמולטור חיצוני או mirroring לא פעיל | הסתמך על `adb devices`; הפעל דרך Device Manager אם נדרש UI |

## 15. מצב שנבדק בפועל ב-session זה

הפעולות הבאות הושלמו ואומתו:

- AVD מסוג Pixel 6, Android 14 / API 34 / Google APIs / x86_64.
- `adb devices` הציג `emulator-5554` במצב `device`.
- `Abloq-debug.apk` הותקן.
- `com.secureguard.mdm/.SecureGuardDeviceAdminReceiver` הוגדר כ-Device Owner.
- A Bloq הופעלה דרך `com.secureguard.mdm/.MainActivity`.
- שפת A Bloq הוגדרה ל-`he-IL` בלי לשנות את שפת המערכת.
- חמש תעודות NetFree Root CA X2 הותקנו ישירות ב-User CA store.
- לאחר reboot כל חמש התעודות נשארו עם owner, mode ו-SELinux context תקינים.
- לאחר reboot `sys.boot_completed=1`.
- לאחר reboot A Bloq נשארה Device Owner.

## 16. Checklist קצר לסוכן AI שממשיך מכאן

1. אשר שה-workspace הוא התיקייה הפנימית.
2. אל תלחץ ואל תמליץ על Kiro Run and Debug.
3. הגדר את משתני PowerShell מסעיף 3.
4. הרץ `adb devices -l`.
5. אם אין device, בדוק תהליך emulator/qemu לפני כל פעולה אחרת.
6. המתן ל-`sys.boot_completed=1` עם timeout.
7. אמת `dpm list-owners` לפני שינוי Device Owner.
8. אמת `pm path com.secureguard.mdm` לפני התקנה מחדש.
9. אל תבצע Wipe Data אם יש state שצריך לשמור.
10. לפני התקנת CA, אמת Subject, Issuer ו-SHA-256.
11. אחרי התקנת CA, בצע reboot ואמת את הקבצים ואת Device Owner.
12. רק לאחר שכל שכבת התשתית תקינה, המשך ל-smoke test של חומת האש.
