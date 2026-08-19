# מפתח החתימה של Release — איפה הוא נמצא

קובץ זה הוא מצביע בלבד. הוא אינו מכיל סיסמאות ואינו מכיל את המפתח.

## מקום המפתח

```text
C:\projects\SecureGuardMDM\signing\mafteach-release.jks     ← המפתח עצמו
C:\projects\SecureGuardMDM\signing\keystore.properties      ← נתיב, alias וסיסמאות
C:\projects\SecureGuardMDM\signing\README-KEYSTORE.txt      ← הוראות שחזור
```

התיקייה נמצאת מחוץ לשורש הפרויקט במכוון, תיקייה אחת מעל `SecureGuardMDM\`.

## זהות המפתח

```text
alias:            mafteach-release
אלגוריתם:         RSA 4096, SHA256withRSA, PKCS12
תוקף:             18/08/2026 עד 10/08/2056
signer SHA-256:   fe079f9df99d5bee1a9610f7cbf3d2d6a2c4823672b8a04ee7f9966c45ba2599
```

זו החתימה שנמצאת בפועל ב-`dist\apk\מפתח-0.5.0-release.apk` וב-`מפתח-0.6.0-release.apk`.

## איך לאמת שהמפתח תקין

```powershell
$env:JAVA_HOME = "$env:USERPROFILE\.jdks\temurin-17.0.20"
& "$env:JAVA_HOME\bin\keytool.exe" -list -v `
    -keystore "C:\projects\SecureGuardMDM\signing\mafteach-release.jks" `
    -alias "mafteach-release"
```

השורה `SHA256:` חייבת להתאים ל-signer שלמעלה. אם היא שונה, אין להשתמש במפתח לעדכון
של התקנה קיימת.

## אזהרה חשובה

`backup.ps1` מחריג בכוונה `*.jks` ו-`keystore.properties`, ולכן **המפתח אינו נמצא
באף snapshot של הפרויקט**. נכון לאימות האחרון קיים ממנו עותק אחד בלבד, על הדיסק הזה.

Android מאפשר עדכון רק כאשר החתימה זהה, והאפליקציה מותקנת כ-Device Owner. אובדן
הקובץ הזה מונע לצמיתות עדכון של מכשירים שכבר הותקנו. יש לשמור עותק מוצפן נוסף
במקום נפרד, ואת הסיסמאות במנהל סיסמאות.
