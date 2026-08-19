package com.secureguard.mdm.ui.theme

import androidx.compose.ui.graphics.Color

// ערכת צבעים המבוססת על גוונים של טורקיז (cyan/teal) כמיתוג הראשי של האפליקציה.
// השמות תיאוריים לפי תפקיד, ולא לפי גוון, כדי שהחלפת גוון עתידית לא תדרוש שינוי שמות.

// גוון מוביל
val TurquoisePrimary = Color(0xFF00838F)          // טורקיז כהה, קונטרסט תקין מול טקסט לבן
val TurquoiseOnPrimary = Color(0xFFFFFFFF)
val TurquoisePrimaryContainer = Color(0xFFB2EBF2) // טורקיז בהיר לכרטיסים ובאנרים
val TurquoiseOnPrimaryContainer = Color(0xFF00363D)
val TurquoiseInversePrimary = Color(0xFF4DD0E1)

// גוון משני
val TurquoiseSecondary = Color(0xFF00ACC1)
val TurquoiseOnSecondary = Color(0xFFFFFFFF)
val TurquoiseSecondaryContainer = Color(0xFFCCF0F4)
val TurquoiseOnSecondaryContainer = Color(0xFF04353B)

// גוון שלישי, נוטה לירוק-טורקיז להבחנה בין כרטיסים סמוכים
val TurquoiseTertiary = Color(0xFF00695C)
val TurquoiseOnTertiary = Color(0xFFFFFFFF)
val TurquoiseTertiaryContainer = Color(0xFFB9EDE4)
val TurquoiseOnTertiaryContainer = Color(0xFF00201B)

// רקעים ומשטחים
val BackgroundLight = Color(0xFFF6FDFD)
val SurfaceLight = Color(0xFFFFFFFF)
val SurfaceVariantLight = Color(0xFFDBEDEF)
val SurfaceContainerLowest = Color(0xFFFFFFFF)
val SurfaceContainerLow = Color(0xFFF1FAFB)
val SurfaceContainer = Color(0xFFEBF6F7)
val SurfaceContainerHigh = Color(0xFFE3F1F3)
val SurfaceContainerHighest = Color(0xFFDCEDEF)
val InverseSurfaceDark = Color(0xFF17383B)
val InverseOnSurfaceLight = Color(0xFFEAF7F8)

// טקסט וקווי מסגרת
val TextPrimary = Color(0xFF10292B)
val TextSecondary = Color(0xFF3E4C4F)
val OutlineColor = Color(0xFF6E8083)
val OutlineVariantColor = Color(0xFFBFD4D6)

// צבעי שגיאה נשארים אדומים, כי אדום כאן הוא סמנטי ולא מיתוגי
val ErrorColor = Color(0xFFB3261E)
val OnErrorColor = Color(0xFFFFFFFF)
val ErrorContainerColor = Color(0xFFF9DEDC)
val OnErrorContainerColor = Color(0xFF410E0B)

// גוונים עדינים למסך הקיוסק, שאינו משתמש ב-MaterialTheme לרקעים שלו
val KioskBackground = Color(0xFFE6F4F5)
val KioskCard = Color(0xFFD3E9EB)
val KioskBottomBar = Color(0xFFC7E2E5)
val KioskText = Color(0xFF10292B)
