# תכנון: Firewall/VPN פנימי לפי אפליקציה

סטטוס: טיוטת תכנון לפני מימוש  
תאריך: 2026-08-11  
ענף יעד: `feature/internal-vpn-firewall`  
Repository פרטי: `imreykodesh/secureguardmdm-private`  
נקודת בסיס: `a12ab30f643139181b13006f8961eb1329244b97`

## 1. מטרת המסמך

מסמך זה מגדיר את התכנון להחלפת התלות ב-NetGuard במנוע Firewall/VPN פנימי, הארוז כחלק מאותו APK של SecureGuardMDM/A Bloq. המנוע יאפשר לבחור אפליקציות לפי package name, להחיל עליהן כללי domain/IP, ולתעד באופן מקומי את היעדים שאליהם הן ניסו להתחבר.

המסמך הוא מסמך תכנון בלבד. הוא אינו משנה עדיין את קוד היישום, אינו מוסיף dependency ואינו קובע סופית ספריית packet forwarding לפני ביצוע אב-טיפוס טכני ממוקד.

## 2. מצב בסיס מאומת

ה-build הקיים עבר בהצלחה באמצעות:

```text
:app:assembleDebug
BUILD SUCCESSFUL
```

מאפייני הפרויקט הנוכחיים:

- Android application ב-Kotlin ו-Jetpack Compose.
- `compileSdk = 34`, `targetSdk = 34`, `minSdk = 22`.
- שימוש ב-Hilt, Room ו-SharedPreferences.
- האפליקציה מיועדת לפעול כ-Device Owner.
- `BlockerVpnService` כבר מוצהר כ-`VpnService` ב-Manifest.
- `BlockerVpnService` הנוכחי מקים TUN אך אינו קורא, מנתח או מעביר packets ולכן אינו מנוע Firewall מלא.
- `netguard.apk` ו-`nophone.apk` ארוזים כיום תחת `app/src/main/assets`.
- מקור NetGuard אינו קיים בריפו.
- NetFree מנוטר באמצעות `NetfreeMonitorService`, אך השירות מתחרה כיום עם מסלולי VPN אחרים על Always-On VPN יחיד.

## 3. מטרות פונקציונליות

### 3.1 מטרות חובה

1. בחירת אפליקציות מותקנות לפי package name.
2. ניתוב רק האפליקציות שנבחרו דרך ה-VPN הפנימי.
3. השארת אפליקציות שלא נבחרו מחוץ ל-VPN, לרבות דפדפן חיצוני שלא נבחר.
4. כללי חסימה ואישור לכל אפליקציה עבור:
   - domain מדויק;
   - domain וכל תתי-הדומיינים שלו;
   - כתובת IPv4 או IPv6;
   - טווח CIDR;
   - פרוטוקול ופורט, כאשר מנוע ה-packet forwarding מספק את המידע.
5. חסימת DNS עבור domains אסורים.
6. בדיקת TLS ClientHello/SNI כאשר המידע גלוי וללא פענוח TLS.
7. אפשרות לחסום QUIC/HTTP3 באמצעות חסימת UDP/443 באפליקציות המסוננות, כדי לעודד fallback ל-TCP/TLS.
8. היסטוריית יעדים מקומית לכל אפליקציה:
   - domain כאשר הוא ידוע;
   - IP;
   - פורט;
   - TCP/UDP;
   - זמן ראשון ואחרון;
   - מספר ניסיונות;
   - האם החיבור אושר או נחסם;
   - מקור הזיהוי: DNS, SNI או IP בלבד.
9. פעולת "חסום" או "אפשר" מהירה מתוך היסטוריית היעדים.
10. שמירת כל המדיניות לאחר reboot והפעלה מחודשת של השירות.
11. הסרה מלאה של NetGuard מה-build ומהמסלול הפעיל.
12. כל העיבוד והלוגים נשארים מקומיים במכשיר.

### 3.2 מטרות משניות

- חיפוש וסינון ברשימת האפליקציות והיעדים.
- הצגת סטטוס VPN ברור: כבוי, מתחבר, פעיל, שגיאה או ללא הרשאה.
- ייבוא/ייצוא rules יישקל בגרסה עתידית, אך אינו חלק מהשלב הראשון.
- ניקוי היסטוריה ידני ומדיניות retention אוטומטית.
- סטטיסטיקה בסיסית: מספר חיבורים מותרים/חסומים לכל אפליקציה.

## 4. דברים שאינם אפשריים או אינם חלק מהגרסה הראשונה

### 4.1 חסימת WebView בלבד באפליקציית צד שלישי

Android אינו מספק ל-Device Owner או ל-`VpnService` סימון שמבדיל בין traffic שנוצר על ידי WebView לבין traffic שנוצר על ידי OkHttp, SDK פרסום, telemetry או קוד אחר באותו UID. לכן המדיניות תחול על כל תעבורת הרשת של package שנבחר.

חסימת WebView מדויקת תתאפשר רק כאשר אפליקציית היעד משתפת פעולה באמצעות אחד מאלה:

- קוד WebView שבשליטתנו;
- `WebViewClient` ו-`shouldInterceptRequest`;
- Managed Configurations שהאפליקציה קוראת ומיישמת;
- API ייעודי של אפליקציית היעד.

### 4.2 URL מלא ב-HTTPS

ללא TLS interception אין גישה אמינה ל-path, query, headers, cookies או גוף הבקשה. לדוגמה, אפשר לנסות לזהות `example.com`, אך לא להבחין באופן אמין בין:

```text
https://example.com/allowed
https://example.com/blocked/path
```

הגרסה הראשונה לא תתקין CA, לא תבצע MITM ולא תפענח TLS.

### 4.3 זיהוי domain מובטח בכל מצב

Domain עשוי להיות לא ידוע כאשר:

- האפליקציה משתמשת ב-IP קשיח;
- היא משתמשת ב-DoH/DoT עצמאי;
- DNS נשמר ב-cache לפני הפעלת ה-VPN;
- TLS משתמש ב-ECH ומסתיר את SNI;
- QUIC אינו מפוענח ברמת metadata;
- היעד משותף למספר domains דרך CDN.

במקרים אלה יוצג IP בלבד והחלטת rule תתבסס על IP/CIDR או על metadata אחר שזמין.

## 5. החלטת ארכיטקטורה מרכזית

### 5.1 נדרש VPN מלא ולא TUN מסוג sink

כדי לתעד את כל היעדים ולאכוף SNI/פורט/QUIC, המנוע חייב לנתב את כל התעבורה של האפליקציות שנבחרו דרך TUN, לקרוא packets ולהעביר חיבורים מותרים אל הרשת הפיזית. הקמת TUN בלבד תגרום לאיבוד התעבורה.

ה-flow התקין יהיה:

```text
Selected app
    -> Android routing
    -> BlockerVpnService TUN
    -> packet/flow engine
    -> RuleEvaluator
       -> BLOCK: drop/reject + history event
       -> ALLOW: protected upstream socket
    -> physical network
```

Packets חוזרים יעברו בכיוון ההפוך אל TUN ומשם לאפליקציה.

### 5.2 Per-App VPN

לפני `Builder.establish()` יופעל:

```kotlin
builder.addAllowedApplication(packageName)
```

עבור כל package שנבחר. כאשר קיימת allowed list, packages שלא נוספו משתמשים ברשת הרגילה ואינם אמורים להיות מושפעים מה-VPN.

ה-builder יכלול לפחות:

- כתובת IPv4 פנימית;
- כתובת IPv6 פנימית;
- route עבור `0.0.0.0/0` ו-`::/0`;
- DNS פנימי של המנוע;
- allowed applications;
- MTU שנבחר לאחר בדיקה;
- session name ברור.

כל שינוי ברשימת packages מחייב הקמה מחדש של ממשק ה-VPN.

### 5.3 מנוע forwarding מוטמע

מימוש TCP/IP userspace stack מלא מאפס אינו מומלץ. הוא דורש TCP state machine, retransmission, congestion behavior, IPv4/IPv6 fragmentation, UDP, ICMP, DNS, socket protection, NAT/flow tracking וטיפול ב-network changes.

לכן התכנון דורש adapter מעל מנוע forwarding מוטמע בתוך אותו APK. אין אפליקציה נוספת ואין APK צד שלישי שמותקן במכשיר.

מועמדים שנבדקו ברמת feasibility:

1. **Firestack** — מועמד מוביל ל-spike. הוא מתואר כ-userspace firewall/network monitor, מופץ ב-Maven Central וברישיון MPL-2.0. יש לאמת API, ABI, גודל AAR, תאימות Android 7–14 ויכולת callbacks לפני בחירה סופית. מקור: [Firestack](https://github.com/celzero/firestack), [Maven Central](https://central.sonatype.com/artifact/com.celzero/firestack/overview).
2. **NetBare** — אינו מומלץ כבסיס חדש משום שהפרויקט הוכרז כעבר/לא פעיל. מקור: [NetBare repository](https://github.com/MegatronKing/NetBare-Android), [status issue](https://github.com/MegatronKing/NetBare-Android/issues/87).
3. **PCAPdroid engine** — מוכיח שהדרישות ניתנות למימוש מקומי, אך אינו מוצע כרגע כ-dependency ישיר בלי בדיקת ארכיטקטורה ורישיון מלאה. מקור: [PCAPdroid](https://github.com/emanuele-f/PCAPdroid).
4. **tun2socks בלבד** — אינו מספיק לבדו; הוא דורש SOCKS server/direct proxy מתאים ואינו מספק בהכרח את hooks הדרושים להחלטות per-flow. דוגמה: [sockstun](https://github.com/heiher/sockstun).
5. **מנוע מותאם אישית** — fallback אחרון בלבד, בשל סיכון אבטחה, יציבות וזמן פיתוח גבוהים.

### 5.4 Spike חובה לפני הטמעת dependency

לפני שינוי רחב יבוצע spike בענף העבודה:

1. הוספת dependency בגרסה מדויקת, ללא `latest` או טווח פתוח.
2. יצירת VPN עבור package בדיקה אחד.
3. הוכחת TCP ו-UDP forwarding.
4. הוכחת IPv4 ו-IPv6.
5. הוכחת DNS callback או יכולת parsing לפני forwarding.
6. הוכחת callback עבור destination IP/port/protocol.
7. הוכחת שיוך UID/package או דרך אמינה להשיגו.
8. בדיקת restart, network switch ו-revoke.
9. מדידת גודל APK, CPU, RAM וסוללה.
10. בדיקת רישיון והודעות attribution.

אם המועמד אינו עומד בתנאים, לא בונים עליו את שכבת הנתונים והממשק כאילו הוא מתאים; בוחרים engine אחר או מורידים scope באופן מפורש.

## 6. שיוך חיבור לאפליקציה

### 6.1 Android 10 ומעלה

ב-API 29+ ניתן להשתמש ב-`ConnectivityManager.getConnectionOwnerUid()` עבור TCP ו-UDP כאשר האפליקציה היא ה-VPN הפעיל. ה-UID ימופה ל-package באמצעות `PackageManager`.

מנוע שמחזיר UID ישירות עדיף, אך התוצאה תאומת מול Android API כאשר אפשר.

### 6.2 Android 5.1–9

ב-API 22–28 אין API ציבורי מקביל ואמין באותה רמה. קריאת `/proc/net` אינה בסיס יציב לגרסאות Android מודרניות.

לפיכך יש לקבוע אחת משתי מדיניות לפני release:

- **המלצה:** תמיכה מלאה בכללים שונים והיסטוריה per-package רק ב-API 29+, וב-API 22–28 הצגת מגבלה ברורה ומדיניות משותפת לכל packages שנבחרו.
- חלופה: העלאת `minSdk` של תכונת ה-Firewall בלבד ל-29, בלי לשנות את minSdk של שאר האפליקציה.

אין להציג ב-UI שיוך package ודאי אם בפועל המנוע יודע רק שה-packet הגיע מאחת האפליקציות ב-allowed list.

### 6.3 Shared UID ותהליכים מבודדים

- UID משותף יכול למפות ליותר מ-package אחד.
- isolated process עשוי לקבל UID זמני.
- Chrome Custom Tab שייך בדרך כלל ל-package של ספק הדפדפן ולא לאפליקציה שפתחה אותו.

במצבים לא חד-משמעיים הלוג יסומן כ-`UNKNOWN` או `SHARED_UID`; אין להמציא package יחיד.

## 7. מנוע כללים

### 7.1 סוגי policy לאפליקציה

לכל package יהיה `policyMode`:

- `BLOCKLIST`: הכול מותר למעט rules שחוסמים.
- `ALLOWLIST`: הכול חסום למעט rules שמאפשרים.
- `MONITOR_ONLY`: תיעוד בלבד, ללא חסימה.
- `DISABLED`: package אינו נכלל ב-VPN.

ברירת המחדל בעת בחירת אפליקציה תהיה `MONITOR_ONLY`, כדי לא לשבור אותה לפני שהמנהל ראה את יעדיה. המנהל יוכל לעבור ל-`BLOCKLIST` או `ALLOWLIST`.

### 7.2 סוגי rules

```text
DOMAIN_EXACT
DOMAIN_SUFFIX
IP_EXACT
CIDR
PORT
IP_PORT
DOMAIN_PORT
```

כל rule יכיל:

```text
id
packageName
action: ALLOW | BLOCK
type
value
protocol: ANY | TCP | UDP
portStart / portEnd
enabled
priority
source: MANUAL | RECENT_DESTINATION | IMPORT
createdAt
updatedAt
```

### 7.3 נרמול domains

לפני שמירה והשוואה:

- lowercase;
- הסרת נקודה סופית;
- המרת IDN ל-ASCII באמצעות `IDN.toASCII`;
- דחיית scheme, path ו-query בשדה domain;
- validation של label lengths;
- הפרדה ברורה בין exact לבין suffix.

`example.com` מסוג `DOMAIN_SUFFIX` יתאים ל-`example.com` ול-`api.example.com`, אך לא ל-`notexample.com`.

### 7.4 נרמול IP/CIDR

- parsing בינארי, לא השוואת מחרוזות;
- IPv4 ו-IPv6;
- prefix חוקי בלבד;
- אחסון canonical representation;
- אין reverse DNS אוטומטי כבסיס להחלטת אבטחה.

### 7.5 סדר הכרעה

סדר מוצע:

1. packages ורכיבי מערכת שחייבים bypass פנימי לצורך פעולת ה-VPN.
2. rule מפורש ברמת package עם priority הגבוה ביותר.
3. exact domain/IP לפני suffix/CIDR רחב.
4. ב-`ALLOWLIST`: חסימה כברירת מחדל.
5. ב-`BLOCKLIST`: אישור כברירת מחדל.
6. ב-`MONITOR_ONLY`: אישור ותיעוד.

אם קיימים ALLOW ו-BLOCK באותה specificity ובאותה priority, BLOCK ינצח. ה-UI ימנע ככל האפשר conflicts ויציג אותם.

## 8. DNS filtering

### 8.1 תהליך

1. המנוע מקבל DNS query מאפליקציה מסוננת.
2. parser מפיק transaction ID, qname, qtype ו-flags.
3. RuleEvaluator בודק policy של package.
4. אם domain חסום:
   - מוחזרת תשובת blocked עקבית, מומלץ `NXDOMAIN` או `REFUSED` לפי הגדרה;
   - נרשם אירוע חסום;
   - אין פנייה ל-upstream.
5. אם מותר:
   - השאילתה מועברת דרך protected socket ל-resolver שהוגדר;
   - התשובה מוחזרת לאפליקציה;
   - A/AAAA/CNAME נשמרים ב-cache עם TTL לצורך correlation.

### 8.2 DNS cache

`DomainIpCache` יחזיק:

```text
packageName
domain
ip
recordType
expiresAt
sourceDnsServer
```

ה-cache אינו מקור אמת קבוע. entries נמחקים לפי TTL ולא משמשים לחסימת IP לנצח.

### 8.3 DoT ו-DoH

- Strict mode יחסום TCP/UDP 853 עבור packages מסוננים, אלא אם הוגדר allow מפורש.
- DoH משתמש ב-HTTPS ולכן אינו ניתן לזיהוי מלא בלי רשימת endpoints, SNI גלוי או TLS interception.
- ניתן להוסיף רשימת resolvers ידועים, אך היא אינה הגנה מוחלטת.
- Private DNS של המכשיר ואפליקציות עם resolver עצמאי דורשים בדיקות נפרדות.

ב-UI יוצג שהגנת domain היא חזקה אך אינה הבטחה מוחלטת מול DNS מוצפן או IP קשיח.

## 9. TLS/SNI filtering

- parser יקרא רק את תחילת TLS ClientHello.
- הוא יחלץ SNI כאשר הוא גלוי.
- הוא לא ישמור payload ולא יפענח את session.
- אם SNI חסום, החיבור יופסק לפני השלמת TLS.
- אם ECH מסתיר SNI, ההחלטה תיפול ל-IP/domain שנלמד מ-DNS או לברירת המחדל של policy.
- parser חייב לטפל ב-ClientHello מפוצל על מספר TCP segments; אין להניח שכל המידע נמצא ב-packet אחד.
- יש להגדיר timeout וגודל buffer מקסימלי כדי למנוע memory abuse.

## 10. QUIC ו-HTTP/3

ברירת המחדל המומלצת עבור package מסונן במצב enforcement:

```text
blockUdp443 = true
```

התנהגות:

- UDP/443 נחסם ונרשם כ-`QUIC_BLOCKED`.
- דפדפנים ואפליקציות רבים יעברו ל-TCP/TLS, ואז ניתן יהיה לבדוק DNS/SNI.
- אפליקציות שאינן מבצעות fallback עלולות להפסיק לעבוד.
- לכל package יהיה toggle נפרד כדי לאפשר QUIC במקרה הצורך.
- parsing מלא של QUIC Initial אינו יעד לגרסה הראשונה.

## 11. היסטוריית יעדים

### 11.1 מטרת ההיסטוריה

היסטוריית היעדים מיועדת לעזור למנהל להבין במהירות לאן אפליקציה מתחברת וליצור rule בלי להקליד domain או IP ידנית.

### 11.2 שדות

```text
id
packageName
uid
domain (nullable)
destinationIp
destinationPort
protocol
firstSeenAt
lastSeenAt
connectionCount
lastDecision: ALLOWED | BLOCKED | MONITORED
decisionReason
metadataSource: DNS | TLS_SNI | IP_ONLY | CACHE
networkType: WIFI | CELLULAR | ETHERNET | OTHER
```

אין לשמור:

- URL path;
- query;
- HTTP headers;
- cookies;
- bodies;
- תוכן TLS;
- DNS payload מעבר למידע הנדרש.

### 11.3 Aggregation

במקום שורה לכל packet, תישמר שורה aggregate לפי:

```text
packageName + domain/IP + port + protocol + decision
```

`connectionCount` ו-`lastSeenAt` יעודכנו. הדבר מצמצם I/O וגודל DB.

### 11.4 Retention

ברירת מחדל:

- שמירה ל-7 ימים;
- עד 10,000 records;
- purge יומי או בעת חציית הסף;
- כפתור "נקה היסטוריה";
- אפשרות עתידית לשנות retention.

היסטוריה היא מידע רגיש. היא לא תיכלל ב-backup, לא תישלח לשרת ולא תופיע בלוג טקסט רגיל.

### 11.5 פעולות מהירות

מתוך destination row:

- "חסום domain" אם domain ידוע;
- "אפשר domain";
- "חסום IP";
- "אפשר IP";
- "העתק";
- "הצג פרטים";

לפני יצירת IP rule שמקורו ב-CDN תוצג אזהרה שה-IP עלול להיות משותף או להשתנות.

## 12. מודל נתונים Room

גרסת DB תעלה מ-1 ל-2 עם migration מפורשת. אין להשתמש ב-`fallbackToDestructiveMigration` משום שהמכשיר מנוהל והמדיניות חייבת להישמר.

### 12.1 `FirewallAppPolicyEntity`

```text
package_name TEXT PRIMARY KEY
policy_mode TEXT NOT NULL
block_quic INTEGER NOT NULL
block_dot INTEGER NOT NULL
enabled INTEGER NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

### 12.2 `FirewallRuleEntity`

```text
id INTEGER PRIMARY KEY AUTOINCREMENT
package_name TEXT NOT NULL
rule_type TEXT NOT NULL
action TEXT NOT NULL
value TEXT NOT NULL
protocol TEXT NOT NULL
port_start INTEGER
port_end INTEGER
priority INTEGER NOT NULL
enabled INTEGER NOT NULL
source TEXT NOT NULL
created_at INTEGER NOT NULL
updated_at INTEGER NOT NULL
```

אינדקסים:

- `package_name`;
- `(package_name, enabled)`;
- unique logical index למניעת rule כפול לאחר normalization.

### 12.3 `ConnectionHistoryEntity`

```text
id INTEGER PRIMARY KEY AUTOINCREMENT
package_name TEXT
uid INTEGER
normalized_destination TEXT NOT NULL
domain TEXT
destination_ip TEXT NOT NULL
destination_port INTEGER NOT NULL
protocol TEXT NOT NULL
first_seen_at INTEGER NOT NULL
last_seen_at INTEGER NOT NULL
connection_count INTEGER NOT NULL
last_decision TEXT NOT NULL
decision_reason TEXT
metadata_source TEXT NOT NULL
network_type TEXT NOT NULL
```

אינדקסים:

- `last_seen_at`;
- `package_name`;
- `(package_name, normalized_destination)`;
- unique aggregate key מתאים ל-upsert.

### 12.4 repositories

```text
FirewallPolicyRepository
FirewallRuleRepository
ConnectionHistoryRepository
```

כל repositories יחשפו `Flow` ל-UI ו-suspend APIs לשירות. שירות ה-VPN לא יבצע query ל-Room עבור כל packet; הוא יטען snapshot immutable בזיכרון ויעדכן אותו כאשר rules משתנים.

## 13. רכיבי תוכנה מוצעים

```text
firewall/
  engine/
    FirewallEngine.kt
    FirewallEngineAdapter.kt
    FlowMetadata.kt
    RuleEvaluator.kt
    RuleSnapshot.kt
    ConnectionTracker.kt
  dns/
    DnsMessageParser.kt
    DnsPolicyHandler.kt
    DomainIpCache.kt
  tls/
    TlsClientHelloParser.kt
    TlsFlowBuffer.kt
  model/
    FirewallAppPolicy.kt
    FirewallRule.kt
    FirewallDecision.kt
    ConnectionEvent.kt
  data/
    FirewallAppPolicyEntity.kt
    FirewallRuleEntity.kt
    ConnectionHistoryEntity.kt
    FirewallDao.kt
    FirewallRepositoryImpl.kt
  coordinator/
    VpnCoordinator.kt
    VpnDesiredState.kt
    VpnRuntimeState.kt
  ui/
    FirewallOverviewScreen.kt
    FirewallAppsScreen.kt
    FirewallAppDetailsScreen.kt
    FirewallRulesScreen.kt
    ConnectionHistoryScreen.kt
    FirewallViewModel.kt
    FirewallUiState.kt
```

## 14. אחריות `BlockerVpnService`

`BlockerVpnService` יהיה רכיב lifecycle בלבד ולא יכיל את כל הלוגיקה בקובץ אחד.

אחריותו:

1. מעבר ל-foreground מיד.
2. קריאת config snapshot.
3. בדיקת VPN permission.
4. בניית `VpnService.Builder` עם packages שנבחרו.
5. הקמת TUN.
6. הפעלת `FirewallEngineAdapter` עם file descriptor.
7. טיפול ב-actions:
   - `ACTION_START`;
   - `ACTION_STOP`;
   - `ACTION_RELOAD_RULES`;
   - `ACTION_REBUILD_INTERFACE`;
   - `ACTION_NETWORK_CHANGED`.
8. טיפול ב-Always-On start כאשר `intent` או `action` הם null.
9. `onRevoke()` וסגירה מסודרת.
10. restart עם backoff במקרה של כשל.
11. פרסום `VpnRuntimeState` ל-UI.

כל upstream socket של המנוע חייב להיות מחוץ ל-VPN באמצעות `protect()` או מנגנון שקול של engine, אחרת תיווצר לולאת routing.

## 15. `VpnCoordinator` ומכונת מצבים

מצבים:

```text
DISABLED
WAITING_FOR_PERMISSION
STARTING
RUNNING_MONITOR
RUNNING_ENFORCING
REBUILDING
BLOCKED_BY_OTHER_VPN
ERROR
```

ה-coordinator יהיה מקור אמת יחיד עבור:

- package selection;
- הפעלה/כיבוי;
- Always-On;
- NetFree gate;
- rebuild לאחר שינוי packages;
- reload לאחר שינוי rules;
- שגיאות והודעות UI.

אין לאפשר ל-`BlockInternetVpnFeature`, `NetfreeMonitorService` ורכיב אחר להחליף independently את Always-On package.

## 16. Always-On, lockdown וכשל

### 16.1 ברירת מחדל

Per-app Firewall יוגדר כ-Always-On ללא lockdown גלובלי. כך השירות יעלה לאחר boot, אך packages שלא נבחרו לא אמורים להיחסם אם השירות נופל.

משמעות: ברירת המחדל היא fail-open בזמן crash קצר. זה נבחר כדי לעמוד בדרישה שאפליקציות לא מסוננות והדפדפן החיצוני לא ייפגעו.

### 16.2 Fail-closed אופציונלי

Fail-closed רק לאפליקציות שנבחרו מורכב יותר מ-lockdown רגיל, משום ש-lockdown עלול להשפיע על traffic שאינו משתמש ב-VPN. אם יתווסף:

- תיבדק גרסת `setAlwaysOnVpnPackage` עם `lockdownAllowlist`;
- ה-allowlist תכיל את כל packages שלא מסוננים;
- הרשימה תעודכן בעת package install/remove;
- תיבדק התנהגות OEM בפועל.

Fail-closed אינו מופעל כברירת מחדל בגרסה הראשונה בלי בדיקת מכשיר מלאה.

## 17. שילוב NetFree

NetFree אינו יפעיל VPN שני. `NetfreeMonitorService` יהפוך ל-provider של מצב רשת בלבד:

```text
UNKNOWN
CHECKING
APPROVED
REJECTED
```

הוא יעביר state ל-`VpnCoordinator`, והמנוע היחיד יחליט אם לאפשר traffic.

מצבים אפשריים:

1. **Per-app Firewall בלבד:** רק packages שנבחרו נכנסים ל-VPN; דפדפן חיצוני לא נבחר נשאר חופשי.
2. **NetFree-only במפורש:** כדי לשמר את המשמעות הישנה, policy זו עשויה להשפיע על כל המכשיר. ה-UI חייב להזהיר שזה שונה מ-Per-app Firewall.
3. **שילוב:** packages נבחרים מקבלים rules, ובנוסף כל traffic שלהם נחסם כשהרשת אינה מאושרת.

המלצה לגרסה הראשונה: לשמור את NetFree כמצב נפרד ומפורש, mutually exclusive עם Per-app Firewall, עד שיושלמו בדיקות state machine. אין להשאיר את ההתנגשות הנוכחית שבה מספר features משנים Always-On independently.

## 18. הסרת NetGuard

### 18.1 הסרה מה-build

יש להסיר:

- `app/src/main/assets/netguard.apk`;
- `InstallAndProtectNetGuardFeature.kt`;
- `ForceNetGuardVpnFeature.kt`;
- entries מתוך `FeatureRegistry`;
- imports ולוגיקה מיוחדת ב-`SettingsViewModel`;
- strings של התקנה/הגנה/קונפליקט;
- דיאלוג השדרוג של NetGuard ב-`MainActivity`;
- references בשם NetGuard באייקונים או notifications.

יש ליצור icon כללי בשם `ic_firewall_shield` ולהחליף references. רק לאחר שאין references ניתן להסיר `ic_netguard_shield.xml`.

### 18.2 migration למכשירים קיימים

למרות הסרת NetGuard מהקוד, מכשיר קיים עלול להכיל אותו כשהוא מוגן מהסרה או מוגדר Always-On. נדרש migration חד-פעמי:

1. אם Always-On package הוא `eu.faircode.netguard`, להסיר את ההגדרה לפני הפעלת המנוע החדש.
2. לנסות `setUninstallBlocked(..., false)`.
3. לא להתקין ולא להפעיל NetGuard.
4. להציג למנהל אפשרות להסיר אותו; אין להסיר אפליקציה בשקט ללא החלטה מפורשת.
5. לשמור flag שה-migration בוצע.

לאחר תקופת migration אפשר להסיר גם קוד זה.

### 18.3 `nophone.apk`

`nophone.apk` אינו קשור ל-Firewall. הוא משמש את פיצ'ר חסימת השיחות הנכנסות ולכן לא יוסר במסגרת שינוי זה. provenance והרישוי שלו הם משימה נפרדת.

## 19. תכנון ממשק משתמש

### 19.1 כניסה מהגדרות

בקטגוריית "ניהול אפליקציות" או "VPN וחומת אש" יתווסף פריט:

```text
סינון רשת לפי אפליקציה
```

הוא ינווט אל `Routes.FIREWALL_OVERVIEW`.

### 19.2 מסך Overview

יציג:

- toggle ראשי להפעלת Firewall;
- סטטוס VPN;
- מספר אפליקציות שנבחרו;
- מספר rules פעילים;
- מספר חיבורים שנחסמו ב-24 השעות האחרונות;
- כפתורים:
  - בחירת אפליקציות;
  - ניהול rules;
  - יעדים אחרונים;
  - הגדרות מתקדמות.

אם VPN אחר פעיל, יוצג conflict ברור ולא תבוצע החלפה שקטה.

### 19.3 מסך בחירת אפליקציות

- שימוש בתשתית גילוי החבילות הקיימת, לאחר חילוצה ל-repository משותף.
- icon, שם ו-package name.
- חיפוש.
- סינון user/system/launcher.
- checkbox לבחירה.
- SecureGuardMDM עצמה לא תיבחר.
- packages קריטיים יקבלו אזהרה.
- הדפדפן הכללי לא יסומן אוטומטית.

שמירה תגרום ל-rebuild מבוקר של ה-VPN.

### 19.4 מסך פרטי אפליקציה

- שם/package/icon;
- policy mode;
- חסימת QUIC;
- חסימת DoT;
- rules של האפליקציה;
- יעדים אחרונים של אותה אפליקציה;
- counters;
- כפתור השבתת סינון לאפליקציה.

### 19.5 מסך יעדים אחרונים

- tabs: הכול / אושר / נחסם;
- filter לפי אפליקציה;
- search domain/IP;
- sort לפי זמן, count או אפליקציה;
- badge של DNS/SNI/IP;
- פעולת block/allow מהירה;
- refresh;
- clear history עם confirmation.

### 19.6 הרשאת VPN

ה-UI ישמור `pendingFeatureId` או פעולה ממתינה מפורשת, ולא boolean כללי. כך חזרה מ-`VpnService.prepare()` לא תפעיל feature שגוי כפי שעלול לקרות בקוד הקיים.

## 20. שינויים בקבצים קיימים

### קבצים מרכזיים

- `services/BlockerVpnService.kt` — lifecycle ו-engine adapter.
- `features/impl/BlockInternetVpnFeature.kt` — שינוי semantics או החלפה ב-Internal Firewall feature.
- `services/NetfreeMonitorService.kt` — דיווח state במקום שינוי Always-On עצמאי.
- `features/impl/NetfreeOnlyModeFeature.kt` — תיאום עם coordinator.
- `boot/impl/NetfreeWatchdogBootTask.kt` — הפעלה דרך coordinator.
- `boot/registry/BootTaskRegistry.kt` — task מאוחד אם יידרש.
- `data/db/AppDatabase.kt` — entities, DAOs וגרסה 2.
- `di/AppModule.kt` — providers ל-repositories ול-engine.
- `data/local/PreferencesManager.kt` — flags כלליים בלבד.
- `ui/navigation/AppNavigation.kt` — routes חדשים.
- `settingsfeatures/impl/AppManagementSettings.kt` — כניסה למסך Firewall.
- `settingsfeatures/registry/SettingsRegistry.kt` — רישום הפריט.
- `ui/screens/settings/SettingsViewModel.kt` — הסרת NetGuard ותיקון pending VPN action.
- `MainActivity.kt` — הסרת דיאלוג NetGuard והוספת migration חד-פעמי במקום המתאים.
- `AndroidManifest.xml` — metadata ורכיבים לפי engine; לא להוסיף VPN service שני.
- `app/build.gradle.kts` — dependency pinned ואריזת native libraries אם נדרש.
- `proguard-rules.pro` — rules של engine/JNI אם נדרש.
- `res/values/strings.xml` ו-`values-en/strings.xml` — UI מלא בעברית ובאנגלית.

## 21. Concurrency וביצועים

- packet processing לא ירוץ ב-main thread.
- rule snapshot יהיה immutable ויוחלף atomically.
- Room writes של history ייאספו ב-buffer וייכתבו batch, לא write לכל packet.
- queue תהיה bounded; במקרה overload יישמר counter של dropped telemetry events בלי לחסום network forwarding.
- DNS cache ו-flow table יקבלו upper bounds ו-timeouts.
- אין לבצע package manager lookup לכל packet; UID mapping יישמר ב-cache עם invalidation בעת package changes.
- service scope ישתמש ב-`SupervisorJob` ויכבה באופן מסודר.
- native crashes יתועדו כ-runtime state ולא יגרמו ללולאת restart מהירה.

יעדי ביצועים ראשוניים:

- latency נוספת נמוכה ככל האפשר; יעד מדידה ראשוני מתחת ל-20ms בחיבור מקומי רגיל, לא כהבטחה.
- memory יציב תחת load של אלפי flows.
- ללא ANR.
- ללא wake lock קבוע אם engine אינו דורש זאת.

## 22. פרטיות ואבטחה

1. אין שליחת history או rules מחוץ למכשיר.
2. אין TLS interception בגרסה הראשונה.
3. אין שמירת payload.
4. DB history לא ייכלל ב-Android backup.
5. מסכי history/rules יהיו מאחורי מנגנון הגנת ההגדרות הקיים.
6. input rules יעבור validation ונרמול.
7. engine dependency יוצמד לגרסה וייבדק מול CVEs ורישיון.
8. native binaries ייכללו רק עבור ABI נדרשים וייבדקו reproducibility ככל שניתן.
9. notification תציין שה-Firewall פעיל.
10. שגיאה לא תסומן כמצב מוגן; UI יציג fail-open/error במפורש.

## 23. טיפול בשגיאות

מצבים שיש להציג:

- VPN permission נדחתה;
- package הוסר לאחר שנבחר;
- VPN אחר קיבל ownership;
- `establish()` החזיר null;
- engine לא התחיל;
- DNS upstream אינו זמין;
- Room אינו זמין;
- rules לא חוקיים;
- network התחלף;
- Always-On configuration נכשלה;
- native library חסרה ל-ABI.

כל שגיאה תכיל הודעה למנהל ו-log טכני ללא מידע רגיש. אין לטעון שהחסימה פעילה אם engine אינו forwarding ומבצע enforcement בפועל.

## 24. תוכנית ביצוע

### שלב 0 — Engine spike

- בחירת engine candidate.
- dependency pinned.
- VPN עבור package יחיד.
- TCP/UDP/IPv4/IPv6.
- callback metadata.
- החלטת go/no-go מתועדת.

### שלב 1 — Data foundation

- Room entities ו-migration 1→2.
- DAOs ו-repositories.
- normalization ו-RuleEvaluator.
- snapshot בזיכרון.

### שלב 2 — VPN lifecycle

- coordinator.
- service start/reload/rebuild/revoke.
- per-app builder.
- network changes.
- status reporting.

### שלב 3 — Enforcement ו-observability

- DNS parser/proxy.
- SNI parser.
- IP/CIDR/port rules.
- QUIC/DoT policy.
- UID/package attribution.
- connection history aggregation.

### שלב 4 — UI

- Overview.
- app selection.
- app details.
- rules editor.
- recent destinations.
- VPN consent/error flows.

### שלב 5 — NetGuard removal ו-NetFree integration

- asset ו-features.
- migration למכשירים קיימים.
- coordinator יחיד ל-Always-On.
- NetFree state provider.

### שלב 6 — אימות

- `:app:assembleDebug`.
- התקנה על מכשיר בדיקה.
- test matrix.
- תיקוני lifecycle/OEM.
- בדיקת APK שאין בו `netguard.apk` או references פעילות.

## 25. מטריצת בדיקות חובה

### Build ו-install

- build נקי.
- debug install.
- upgrade מגרסה קיימת.
- Room migration ללא אובדן blocked apps קיימות.

### Package scope

- אפליקציה נבחרת עוברת דרך VPN.
- אפליקציה לא נבחרת אינה מושפעת.
- Chrome חופשי כאשר לא נבחר.
- Chrome מסונן כאשר נבחר.
- שינוי selection בזמן ריצה.
- uninstall/reinstall של package.

### Rules

- exact domain.
- subdomain suffix.
- IPv4 ו-CIDR.
- IPv6 ו-CIDR.
- port TCP/UDP.
- allowlist/blocklist precedence.
- cached DNS.
- CNAME.
- CDN IP משותף.

### Protocols

- HTTP.
- HTTPS עם SNI.
- HTTPS עם IP בלבד.
- QUIC blocked ו-fallback.
- אפליקציה ללא QUIC fallback.
- DoT.
- DoH ידוע ולא ידוע.
- WebSocket.
- long-lived TCP.
- UDP רגיל.

### Lifecycle

- reboot.
- service kill.
- VPN revoke.
- Wi-Fi→cellular.
- airplane mode.
- network ללא internet.
- Always-On.
- VPN אחר.
- Device Owner removal flow.

### History

- app/domain/IP attribution.
- aggregate count.
- quick rule creation.
- retention purge.
- clear history.
- אין payload או URL מלא.

## 26. קריטריוני קבלה לגרסה הראשונה

הגרסה תיחשב מוכנה לבדיקה רק כאשר:

1. `assembleDebug` מצליח.
2. NetGuard אינו ארוז ואינו מותקן על ידי SecureGuardMDM.
3. ניתן לבחור package אחד או יותר.
4. package לא נבחר ממשיך לגלוש בזמן שה-Firewall פעיל.
5. domain חסום נכשל ב-HTTP וגם ב-HTTPS ללא TLS interception.
6. IP/CIDR חסום נכשל.
7. destination history מופיעה עם package אמין או סימון `UNKNOWN` ברור.
8. quick block יוצר rule ומפעיל אותו בלי restart של האפליקציה.
9. reboot משחזר policy.
10. שגיאת engine מוצגת ולא מוסתרת.
11. לא נשמר payload.
12. נבדק לפחות על מכשיר Android פיזי אחד API 29+.

## 27. החלטות שיש לאשר לפני מימוש מלא

1. **Engine:** לבצע spike עם Firestack כמועמד ראשון, ללא התחייבות עד שה-API והביצועים הוכחו.
2. **Android ישן:** לאפשר UI של Firewall ב-API 22–28 רק במצב policy משותף, או להציג את התכונה כ-API 29+.
3. **NetFree:** האם לשמור כמצב נפרד ומחייב לכל המכשיר, או לצמצם אותו ל-gate עבור packages המסוננים בלבד.
4. **Fail behavior:** ברירת מחדל fail-open כדי לא לפגוע באפליקציות שלא נבחרו; fail-closed יידחה עד בדיקת lockdown allowlist.
5. **Retention:** 7 ימים ו-10,000 records כברירת מחדל.
6. **Monitor-first:** package חדש מתחיל ב-`MONITOR_ONLY` ולא בחסימה.

## 28. המלצה סופית

יש להתחיל ב-engine spike ולא ב-UI. אם forwarding, attribution ו-metadata אינם עובדים באופן אמין, ממשק יפה לא יפתור את הבעיה המרכזית. לאחר go/no-go חיובי, יש לבנות תחילה persistence ו-RuleEvaluator, אחר כך lifecycle ואכיפה, ורק לבסוף את מסכי הניהול.

הארכיטקטורה המומלצת היא VPN מלא עבור selected packages בלבד, עם engine מוטמע באותו APK, ללא NetGuard, ללא אפליקציית סינון נוספת וללא TLS interception. היסטוריית החיבורים תהיה metadata מקומי בלבד ותאפשר יצירת rules מהירה.

## מקורות חיצוניים

- [Android VpnService](https://developer.android.com/reference/android/net/VpnService)
- [Android VPN guide and per-app VPN](https://developer.android.com/guide/topics/connectivity/vpn)
- [VpnService.Builder](https://developer.android.com/reference/android/net/VpnService.Builder)
- [ConnectivityManager.getConnectionOwnerUid](https://developer.android.com/reference/android/net/ConnectivityManager)
- [Android Managed Configurations](https://developer.android.com/work/managed-configurations)
- [Android WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview)
- [WebView and Custom Tabs comparison](https://developer.android.com/develop/ui/views/layout/webapps/in-app-browsing-embedded-web)
- [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)
- [TLS Encrypted Client Hello, RFC 9849](https://www.rfc-editor.org/rfc/rfc9849.html)
- [QUIC, RFC 9000](https://www.rfc-editor.org/info/rfc9000/)
- [HTTP/3, RFC 9114](https://www.rfc-editor.org/info/rfc9114/)
- [Firestack](https://github.com/celzero/firestack)
- [Firestack on Maven Central](https://central.sonatype.com/artifact/com.celzero/firestack/overview)
- [PCAPdroid](https://github.com/emanuele-f/PCAPdroid)
- [NetBare](https://github.com/MegatronKing/NetBare-Android)
- [sockstun](https://github.com/heiher/sockstun)

תוכן ממקורות חיצוניים נוסח מחדש לצורך עמידה במגבלות רישוי תוכן.
