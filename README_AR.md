<div dir="rtl">

<div align="right">

**[English](README.md)** | [中文](README_CN.md) | العربية

</div>

<p align="center">
  <img src="https://github.com/niki914/zafiro/blob/main/res/banner/socialify.svg?raw=true" alt="zafiro"/>
</p>

<p align="center">
  <a href="https://github.com/niki914/zafiro"><img src="https://img.shields.io/github/stars/niki914/zafiro?label=stars" alt="stars"/></a>
  <a href="https://deepwiki.com/niki914/zafiro"><img src="https://github.com/niki914/zafiro/blob/main/res/badges/ask_deepwiki.svg?raw=true" alt="Ask DeepWiki"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/v/release/niki914/zafiro?include_prereleases" alt="release"/></a>
  <a href="https://github.com/niki914/zafiro/releases/latest"><img src="https://img.shields.io/github/downloads/niki914/zafiro/total" alt="downloads"/></a>
  <img src="https://img.shields.io/badge/kotlin-87.7k-blue" alt="kotlin lines"/>
  <a href="https://app.codacy.com/gh/niki914/zafiro/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/b4cadbe5d2d74e3885106562cbd9715b" alt="Codacy"/></a>
</p>

## ما هو Zafiro؟

إن **Zafiro** Agent ذكي مفتوح المصدر يعمل على أجهزة Android، ويدعم [BYOK](https://www.ibm.com/think/topics/byok). يفهم شاشتك، ويتحكم بجهازك، وينفّذ عمليات عبر التطبيقات — مع دعم كامل لـ Skills وMCP والذاكرة. يمكنه أيضًا تشغيل Shell وPython 3 أصليًا وتغليف الأدوات بنفسه لإنجاز مهام مثل البحث على الويب وتنزيل الملفات، أو الاتصال بجهاز تطوير بعيد عبر SSH.

<table align="center" width="100%">
  <tr>
    <th width="25%" align="center">استخدام الهاتف</th>
    <th width="25%" align="center">توليد صفحات الويب</th>
    <th width="25%" align="center">وكلاء خارجيون</th>
    <th width="25%" align="center">متعدد الوسائط</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__phone_control.gif?raw=true" alt="تنقّل واجهة رسومية ذاتي على الهاتف والتحكم بالجهاز عبر مؤشر الشاشة" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__html_generation.gif?raw=true" alt="توليد شيفرة ديناميكي ومعاينة فورية في المتصفح المحلي" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__pi_agent_mcp.gif?raw=true" alt="خادم MCP على Android يعرّض قدرات النظام للوكلاء الخارجيين" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__image_understanding.jpg?raw=true" alt="رؤية متعددة الوسائط وفهم الصور على الجهاز" width="90%" /></td>
  </tr>
  <tr>
    <th width="25%" align="center">مزوّدو النماذج</th>
    <th width="25%" align="center">Termux</th>
    <th width="25%" align="center">حماية الأمان</th>
    <th width="25%" align="center">إدارة الجلسات</th>
  </tr>
  <tr>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__multi_providers.jpg?raw=true" alt="مفاتيح API مخصّصة للنماذج وإعداد عدة مزوّدين" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__remote_machine_ssh.jpg?raw=true" alt="دمج أجهزة التطوير البعيدة عبر SSH وTermux" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__safety_guard.jpg?raw=true" alt="حماية بشرية ضمن حلقة الموافقة واعتراض آمن لعمليات Shell الحساسة" width="90%" /></td>
    <td width="25%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/demos/zafiro__conversation_management.jpg?raw=true" alt="إدارة سجل المحادثات محليًا وتفرّع الجلسات" width="90%" /></td>
  </tr>
</table>

> [!IMPORTANT]
> بالنسبة للتطبيقات الأصلية، يتحكم Zafiro بالهاتف عبر خدمة إمكانية الوصول؛ وبالنسبة للتطبيقات غير الأصلية يمكنه أيضًا العمل عبر [Shizuku](https://github.com/RikkaApps/Shizuku) دون Root، ويحصل مستخدمو Root على التجربة الكاملة.
>
> يمكنك تنزيل إصدار من [Releases](https://github.com/niki914/zafiro/releases/latest)، أو اختيار [البناء من المصدر](#البناء-من-المصدر).

## القدرات الأساسية

### لغة تصميم واجهة عصرية

- واجهة عصرية ومتقنة عبر **[Material 3 Expressive](https://m3.material.io/)** و**Apple Liquid Glass**
- **سمات مخصّصة** - ألوان سمات متعددة مع الوضع الداكن واستخراج الألوان الديناميكي
- **دعم متعدد اللغات** - English، 中文، 日本語، Español، Русский، العربية
- **تفاعل في الخلفية** - كرة عائمة أنيقة وواجهة في درج الإشعارات لمتابعة Agent والتفاعل معه لحظيًا

### التحكم بالهاتف

- **التفاعل مع الشاشة** - فتح التطبيقات، اختيار الموسيقى، طلب الطعام — كل ذلك نيابةً عنك
- **مرئي بالكامل** - يعرض مؤشر على الشاشة كل خطوة يقوم بها Agent

### نظام Agent

- **جاهز للاستخدام** - دعم مدمج لـ OpenAI وAnthropic وGoogle Gemini وDeepSeek وOpenRouter وKimi وAlibaba Bailian وSiliconFlow وغيرها
- **قابل للتوسّع حسب الحاجة** - دعم كامل لـ Skills وMCP، مع ذاكرة ومساحة عمل — وسّعه بطريقتك
- **إدارة الأذونات** - بقواعد قابلة للتهيئة، كل أمر وكل شيفرة تحت سيطرتك
- **إدارة السياق** - اطّلع على استخدام نافذة السياق في أي وقت واضغطه
- **إدارة المحادثات** - تفرّع أي دور واسترجعه وأعد توليده؛ تبقى كل بيانات المحادثة محلية تمامًا، ويمكن تبديل النماذج أثناء الجلسة نفسها

### أدوات Python

- **تشغيل الشيفرة مباشرةً** - شغّل Python 3 أصليًا على الجهاز
- **أدوات وصفية** - غلّف أدوات Python الخاصة بك؛ يعيد Agent استخدام الأدوات التي ينشئها ويصونها
- **سيناريوهات مدمجة** - البحث على الويب وقراءة صفحات الويب وتثبيت APK تعمل مباشرةً

### بيئات بعيدة

- استخدم [Termux](https://github.com/termux/termux-app) لتشغيل أوامر وأدوات Linux على جهاز Android
- اتصل بجهاز تطوير أو خادم عبر **SSH** لتنفيذ مهام بعيدة
- أطلق مهام تطوير من هاتفك عبر **[Claude Code](https://github.com/anthropics/claude-code)** لتنفيذها بواسطة Coding Agent بعيد

## المجتمع

- للنقاش وطرح الأسئلة وتقديم الملاحظات: [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl)
- للإبلاغ عن الأخطاء أو طلب الميزات: [GitHub Issues](https://github.com/niki914/zafiro/issues)

عند الإبلاغ عن مشكلة، يُرجى تقديم أكبر قدر ممكن من التفاصيل: طراز الهاتف وإصدار Android، والمساعد الصوتي في النظام وإصداره، وإصدار Zafiro، وخطوات إعادة الإنتاج، ولقطات شاشة أو تسجيلات.

## الرعاية

نرحّب بالرعاية عبر القنوات التالية:

- عبر [Afdian](https://afdian.com/a/niki914)
- عبر العملات الرقمية: [![Tip in Crypto](https://tip.md/badge.svg)](https://tip.md/niki914) - 0x5f1469519c87edf2245ea04fbb54284c9b76ad3d

## الاستحواذ على المساعد الصوتي

عبر إطار [LSPosed](https://github.com/lsposed/lsposed)، يمكن لـ Zafiro الاستحواذ على المساعد الصوتي في النظام — نادِ Breeno أو XiaoAi، وسيكون المجيب الفعلي هو Agent الخاص بك. يمكنك تحديد، بناءً على كلمات مفتاحية، أي الطلبات تذهب إلى Zafiro وأيها يمر إلى المساعد الأصلي. بعد الاستحواذ، يحتفظ المساعد بكامل قدرات Agent بما في ذلك التحكم بالجهاز.

> [!NOTE]
> يتطلب الاستحواذ على المساعد الصوتي في النظام **Root + LSPosed**، ويدعم حاليًا: OPPO - Breeno
>
> قد يتأثر توفر الاستحواذ بطراز الهاتف وإصدار النظام وإصدار المساعد الصوتي وقيود نظام الشركة المصنّعة. عندما لا يدعم جهازك الاستحواذ على مساعد النظام بعد، يمكنك استخدام واجهة محادثة Zafiro بكامل قدرات Agent.

<details>
<summary>عرض العروض التوضيحية</summary>

<table align="center" width="100%">
  <tr>
    <th width="50%" align="center">Breeno</th>
    <th width="50%" align="center">XiaoAi</th>
  </tr>
  <tr>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/breeno__termux.gif?raw=true" alt="عرض توضيحي للاستحواذ على المساعد الصوتي Breeno في ColorOS" width="80%" /></td>
    <td width="50%" align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/takeover/hyper__intro.gif?raw=true" alt="عرض توضيحي للاستحواذ على المساعد الصوتي XiaoAi في HyperOS" width="80%" /></td>
  </tr>
</table>

</details>

## التقنيات المستخدمة

| الفئة             | التقنية                                                                |
|-------------------|------------------------------------------------------------------------|
| لغة البرمجة        | [Kotlin](https://kotlinlang.org/)                                      |
| إطار الواجهة       | [Jetpack Compose](https://developer.android.com/jetpack/compose)       |
| لغة التصميم        | [Material Design 3 Expressive](https://m3.material.io/)                |
| الزجاج السائل      | [Android Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass)   |
| وقت تشغيل Agent    | [Okia](https://github.com/niki914/okia)                                |
| وقت تشغيل Python   | [Chaquopy](https://chaquo.com/chaquopy/)                               |
| استحواذ النظام      | [LSPosed](https://github.com/lsposed/lsposed) + Xposed API             |
| الطرفية / SSH      | [libterm](https://github.com/niki914/libterm)                          |
| عرض SVG           | [coil-resvg](https://github.com/hash-sequence/coil-resvg) (resvg)      |
| زوايا G2          | [Capsule](https://github.com/Kyant0/Capsule)                           |

## البدء السريع

### البناء من المصدر

<div dir="ltr">

```bash
./gradlew assembleDebug
```

</div>

### المتطلبات

- يتطلب Android Studio (أو Android SDK + JDK 17)
- جهاز يعمل بنظام Android 11 أو أحدث

<details>
<summary>توقيع إصدار Release</summary>

لبناء نسخة Release بنفسك، استخدم مفتاح التوقيع الخاص بك:

<div dir="ltr">

```bash
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/absolute/path/to/my-release.jks \
  -PRELEASE_STORE_PASSWORD=yourStorePassword \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=yourKeyPassword
```

</div>

</details>

## هيكل المشروع

<div dir="ltr">

```
./
├── app/                 # التطبيق الرئيسي: Compose UI وAgentRuntimeService وخطافات Xposed
├── business/            # طبقة الأعمال الأساسية (فصل العقود عن التنفيذ)
│   ├── api/             # واجهات العقود العامة للأعمال
│   ├── agent/           # منطق Agent الأساسي وتنسيق المحادثات وتدفق الحالة
│   ├── files/           # مرفقات الملفات وحلّ مسارات المستندات
│   ├── notification/    # الإشعار الدائم وخدمة المقدمة وإدارة قنوات الإشعارات
│   ├── permission/      # مدير أذونات موحّد (منح تلقائي عبر Root / Shizuku، وموافقة الأدوات)
│   └── application/     # إدارة التطبيقات ودورة حياتها
├── agent-runtime/       # Agent runtime: استدعاء LLM وتنفيذ Tool/Skill/MCP وPython runtime
├── xsettings/           # وحدة إعدادات خفيفة (تعتمد عليها الوحدات حسب الحاجة)
├── store/               # تخزين Store الدائم وجسر IPC (XIpcBridge)
├── ui-kit/              # مكوّنات Compose المشتركة وقشرة LiquidScreen والتنقّل
├── remote-view/         # مكوّنات RemoteView عبر العمليات / العائمة
├── xposed-api/          # أنواع أحداث Xposed والثوابت المشتركة (بين التطبيق الرئيسي وعملية المضيف)
├── xposed-runtime/      # Xposed runtime وفئات Hook الأساسية
└── libs/
    ├── logging/         # مكتبة التسجيل
    ├── okia/            # مكتبة أساس Okia Agent runtime
    └── libterm/         # مكتبة الطرفية (عدة خلفيات: libsu وshizuku وssh وغيرها)
```

</div>

## خارطة الطريق

- تكامل المساعد الصوتي في النظام (بدون استحواذ Xposed)
- استيراد / مشاركة الملفات من خارج التطبيق
- إضافة Skills مدمجة مثل Termux، وتلميحات `/skill`، واستيراد Skills من السحابة

## المساهمة

نرحّب بـ Pull Requests!

### الخطوات

1. اعمل Fork لهذا المشروع
2. أنشئ فرعًا للميزة (`git checkout -b feat/your-feature`)
3. ثبّت تغييراتك (`git commit -m 'feat: add your feature'`، مع اتّباع [Conventional Commits](https://www.conventionalcommits.org/en/))
4. ادفع إلى الفرع (`git push origin feat/your-feature`)
5. افتح Pull Request

### استخدام Coding Agent

قبل المساهمة باستخدام Coding Agents مثل Codex، تأكد من أنهم قرؤوا ملف [AGENTS.md](AGENTS.md) الخاص بالمشروع.

## الترخيص

الترخيص MIT — راجع [LICENSE](LICENSE).

<p align="center">
  صُنع بـ ✨️ بواسطة <a href="https://github.com/niki914">niki914</a>
</p>

</div>
