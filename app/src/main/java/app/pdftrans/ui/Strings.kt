package app.pdftrans.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

val LocalLang = staticCompositionLocalOf { "en" }

@Composable
fun t(key: String, vararg args: Any): String = Strings.get(LocalLang.current, key, *args)

object Strings {
    fun get(lang: String, key: String, vararg args: Any): String {
        val pair = M[key] ?: return key
        var s = if (lang == "ar") pair.first else pair.second
        args.forEachIndexed { i, a -> s = s.replace("{$i}", a.toString()) }
        return s
    }

    fun error(lang: String, code: String, detail: String): String {
        val k = "err_$code"
        return if (M.containsKey(k)) get(lang, k, detail) else get(lang, "err_generic") + if (detail.isNotBlank()) " ($detail)" else ""
    }

    private val M: Map<String, Pair<String, String>> = mapOf(
        // home
        "app_name" to ("مترجم PDF" to "PDF Translator"),
        "home_title" to ("ترجم ملفك إلى العربية" to "Translate your PDF into Arabic"),
        "home_sub" to ("يبقى المستند الأصلي كما هو، وتظهر الترجمة على صفحة مقابلة له دون أي تداخل." to "Your original stays intact. The Arabic appears on a facing page, never overlapping."),
        "pick" to ("اختر ملف PDF" to "Choose a PDF"),
        "pick_hint" to ("يمكنك أيضاً فتح أي ملف PDF من تطبيق الملفات بخيار «فتح باستخدام»." to "You can also open any PDF from your file manager with “Open with”."),
        "recent" to ("آخر الترجمات" to "Recent translations"),
        "recent_empty" to ("ستظهر ملفاتك المترجمة هنا." to "Your translated files will appear here."),
        "settings_title" to ("الإعدادات" to "Settings"),
        "back" to ("رجوع" to "Back"),
        "share" to ("مشاركة" to "Share"),
        "delete" to ("حذف" to "Delete"),
        "open" to ("فتح" to "Open"),
        "no_viewer" to ("لا يوجد تطبيق لعرض ملفات PDF على هذا الجهاز." to "No PDF viewer is installed on this device."),
        "saved" to ("تم حفظ النسخة." to "Copy saved."),

        // setup
        "file_meta" to ("{0} صفحة، {1} MB" to "{0} pages, {1} MB"),
        "scanned_ok" to ("{0} صفحة ممسوحة ضوئياً ستُقرأ بتقنية OCR، وهذا أبطأ قليلاً." to "{0} scanned page(s) will be read with OCR, which is a bit slower."),
        "scanned_no" to ("{0} صفحة ممسوحة ضوئياً لن تُترجم لأن OCR متوقف في الإعدادات." to "{0} scanned page(s) won’t be translated because OCR is off in Settings."),
        "sec_engine" to ("محرك الترجمة" to "Translation engine"),
        "on_device" to ("على الجهاز" to "On device"),
        "online" to ("عبر الإنترنت" to "Online"),
        "on_device_note" to ("يعمل دون إنترنت بعد تنزيل نموذج العربية مرة واحدة (نحو 30 ميجابايت). لا يُرسل أي نص إلى أي جهة." to "Works offline after a one-time Arabic download (about 30 MB). No text is sent anywhere."),
        "model_ready" to ("نموذج العربية جاهز." to "Arabic model is ready."),
        "model_missing" to ("نموذج العربية غير منزّل." to "Arabic model isn’t downloaded."),
        "model_checking" to ("جارٍ التحقق من النموذج…" to "Checking the model…"),
        "download_model" to ("نزّل النموذج" to "Download model"),
        "downloading" to ("جارٍ التنزيل…" to "Downloading…"),
        "delete_model" to ("احذف النموذج" to "Remove model"),
        "eng_google" to ("جوجل" to "Google"),
        "eng_mymemory" to ("MyMemory" to "MyMemory"),
        "eng_libre" to ("LibreTranslate" to "LibreTranslate"),
        "note_google" to ("جودة ممتازة وبلا مفتاح. يرسل نص المستند إلى خوادم جوجل، وهو استخدام غير رسمي قد يتوقف أو يخالف شروط الخدمة." to "Very good quality, no key. Sends the document text to Google’s servers. Unofficial: it may stop working and may violate Google’s terms."),
        "note_mymemory" to ("واجهة مجانية رسمية بحصة يومية صغيرة: نحو 5 آلاف حرف، أو 50 ألفاً إذا أدخلت بريدك في الإعدادات." to "Official free API with a small daily quota: about 5,000 characters, or 50,000 if you add your email in Settings."),
        "note_libre" to ("خوادم LibreTranslate العامة المجانية تعمل بلا مفتاح، وجودتها أقل. أو أدخل خادمك الخاص في الإعدادات." to "Free public LibreTranslate servers work with no key, at lower quality. Or add your own server in Settings."),
        "google_warn" to ("لاستخدام المحركات غير الرسمية (مايكروسوفت وجوجل) وافق على أنها غير رسمية وأن النص سيُرسل إلى مزوّدها." to "To use the unofficial engines (Microsoft and Google), confirm they’re unofficial and your text will be sent to their provider."),
        "google_allow" to ("أوافق وأفعّله" to "I agree, enable it"),
        "libre_need" to ("لم تُدخل عنوان الخادم بعد." to "No server address yet."),
        "open_settings" to ("افتح الإعدادات" to "Open Settings"),

        "sec_layout" to ("تخطيط الصفحة" to "Page layout"),
        "mode_smart" to ("تحت كل فقرة" to "Under each paragraph"),
        "mode_smart_d" to ("تُفسح مساحة بين الفقرات وتُكتب الترجمة تحت كل فقرة. إن لم يمكن ذلك بوضوح تُعرض الصفحة مقابلة." to "Room is made between paragraphs and the Arabic goes under each one. If that can’t be done cleanly, the page is shown facing instead."),
        "mode_line" to ("سطراً بسطر" to "Line by line"),
        "mode_line_d" to ("ترجمة بحجم ثابت تحت كل سطر دون تغيير الصفحة." to "Fixed-size translation under each line; pages keep their size."),
        "mode_para" to ("فقرة بفقرة" to "Paragraph"),
        "mode_para_d" to ("يعيد بناء المستند بالنص فقط: الفقرة ثم ترجمتها." to "Rebuilds the document as text only: each paragraph, then its Arabic."),

        "sec_style" to ("مظهر الترجمة" to "Translation style"),
        "sample_en" to ("Entropy measures the uncertainty of a source." to "Entropy measures the uncertainty of a source."),
        "sample_ar" to ("تقيس الإنتروبيا مقدار عدم اليقين في المصدر." to "تقيس الإنتروبيا مقدار عدم اليقين في المصدر."),
        "size" to ("الحجم" to "Size"),
        "auto" to ("تلقائي" to "Auto"),
        "color" to ("اللون" to "Colour"),
        "c_blue" to ("أزرق" to "Blue"), "c_black" to ("أسود" to "Black"), "c_red" to ("أحمر" to "Red"),
        "c_green" to ("أخضر" to "Green"), "c_purple" to ("بنفسجي" to "Purple"), "c_orange" to ("برتقالي" to "Orange"),
        "font" to ("الخط" to "Font"),
        "bold" to ("خط عريض" to "Bold text"),
        "align" to ("المحاذاة" to "Alignment"),
        "a_right" to ("يمين" to "Right"), "a_center" to ("وسط" to "Center"), "a_left" to ("يسار" to "Left"),
        "pages" to ("الصفحات" to "Pages"),
        "pages_hint" to ("الكل، أو مثل 1-10,15,20-" to "All, or e.g. 1-10,15,20-"),
        "start" to ("ابدأ الترجمة" to "Start translating"),
        "need_model" to ("نزّل نموذج العربية أولاً." to "Download the Arabic model first."),
        "need_google" to ("فعّل المحركات غير الرسمية أولاً." to "Enable the unofficial engines first."),
        "need_libre" to ("أدخل عنوان الخادم في الإعدادات." to "Add the server address in Settings."),

        // run
        "working" to ("جارٍ ترجمة {0}" to "Translating {0}"),
        "stage_extract" to ("قراءة الصفحات {0} من {1}" to "Reading pages {0} of {1}"),
        "stage_translate" to ("ترجمة النص {0} من {1}" to "Translating text {0} of {1}"),
        "stage_render" to ("بناء الملف {0} من {1}" to "Building the file {0} of {1}"),
        "keep_open" to ("أبقِ التطبيق مفتوحاً حتى تنتهي العملية." to "Keep the app open until it finishes."),
        "cancel" to ("إلغاء" to "Cancel"),
        "done_title" to ("اكتملت الترجمة" to "Translation finished"),
        "done_meta" to ("{0} صفحة في {1} ثانية" to "{0} pages in {1} seconds"),
        "save_copy" to ("حفظ نسخة" to "Save a copy"),
        "another" to ("ترجمة ملف آخر" to "Translate another file"),
        "notes" to ("ملاحظات" to "Notes"),
        "note_ocr" to ("{0} صفحة قُرئت بتقنية OCR." to "{0} page(s) were read with OCR."),
        "note_scanned_no" to ("{0} صفحة ممسوحة تُركت دون ترجمة." to "{0} scanned page(s) were left untranslated."),
        "note_expanded" to ("{0} صفحة صارت أطول لتتسع الترجمة بين الفقرات." to "{0} page(s) were made taller to fit the translation between paragraphs."),
        "note_tight" to ("{0} سطر في مواضع ضيقة (أعمدة أو جداول). جرّب نمط «فقرة بفقرة»." to "{0} line(s) sit in tight spots (columns or tables). Try Paragraph layout."),
        "note_failed" to ("{0} مقطع لم يُترجم وبقي بالإنجليزية." to "{0} segment(s) couldn’t be translated and stayed in English."),
        "note_rot" to ("{0} صفحة مدوّرة تُركت دون ترجمة. استخدم «فقرة بفقرة»." to "{0} rotated page(s) were left untranslated. Use Paragraph layout."),
        "note_para" to ("نمط الفقرات يعيد بناء النص فقط، فلا تُحفظ الصور والجداول." to "Paragraph layout rebuilds the text only, so images and tables aren’t kept."),
        // v2: modes, engines, quality
        "mode_facing" to ("صفحة مقابلة (موصى به)" to "Facing pages (recommended)"),
        "mode_facing_d" to ("الصفحة الأصلية ثم صفحة بالعربية بالحجم نفسه. لا تداخل أبداً، وتناسب الطباعة." to "The original page, then an Arabic page of the same size. Never overlaps, and prints well."),
        "mode_replace" to ("استبدال النص" to "Replace the text"),
        "mode_replace_d" to ("يُكتب العربي مكان الإنجليزي في موضعه. إن لم يتسع بحجم مقروء تُعرض الصفحة مقابلة." to "Arabic is drawn where the English was. If it can’t fit at a readable size, the page is shown facing instead."),
        "eng_microsoft" to ("مايكروسوفت" to "Microsoft"),
        "note_microsoft" to ("ترجمة جيدة ومجانية وبلا مفتاح عبر مترجم متصفح Edge. يرسل النص إلى خوادم مايكروسوفت، وهو استخدام غير رسمي قد يتغير أو يتوقف." to "Good quality, free, no key, through the translator Edge uses. Sends the text to Microsoft’s servers. Unofficial: it may change or stop."),
        "note_unofficial" to ("مايكروسوفت وجوجل محركان غير رسميين يعملان بلا مفتاح. أنت من يقرر استخدامهما." to "Microsoft and Google are unofficial, keyless engines. Using them is your decision."),
        "sec_quality" to ("جودة الترجمة" to "Translation quality"),
        "cloud_retry" to ("إعادة الفقرات الضعيفة عبر الإنترنت" to "Retry weak paragraphs online"),
        "cloud_retry_note" to ("مع المحرك المحلي: إذا بدت ترجمة فقرة رديئة تُرسل هذه الفقرة فقط إلى محرك إنترنت لإعادة ترجمتها. متوقف افتراضياً حفاظاً على الخصوصية." to "With the on-device engine: if a paragraph looks poor, only that paragraph is sent to an online engine to be redone. Off by default for privacy."),
        "digits" to ("شكل الأرقام" to "Digits"),
        "d_latin" to ("1 2 3" to "1 2 3"),
        "d_arabic" to ("١ ٢ ٣" to "١ ٢ ٣"),
        "glossary" to ("قاموس المصطلحات" to "Glossary"),
        "glossary_note" to ("سطر لكل مصطلح بالشكل: إنجليزي = عربي. تُترجم المصطلحات دائماً كما تكتبها." to "One term per line: English = Arabic. Terms are always translated the way you write them."),
        "note_engine" to ("المحرك المستخدم: {0}" to "Engine used: {0}"),
        "note_switched" to ("تحوّلت الترجمة إلى محرك {0} لأن المحرك الأول لم يستجب." to "Switched to {0} because the first engine stopped responding."),
        "note_fell" to ("{0} صفحة لم يتسع لها التخطيط المختار بوضوح فعُرضت صفحتين متقابلتين (الصفحات: {1})." to "{0} page(s) couldn’t keep the chosen layout cleanly, so they’re shown as facing pages (pages: {1})."),
        "note_retried" to ("{0} فقرة أُعيدت ترجمتها بمحرك آخر لتحسين النتيجة." to "{0} paragraph(s) were re-translated by another engine for a better result."),
        "note_weak" to ("{0} فقرة ما زالت ترجمتها مشكوكاً فيها. راجعها سريعاً." to "{0} paragraph(s) still look doubtful. Worth a quick check."),
        "note_replace" to ("نمط الاستبدال يغطي النص الأصلي بمستطيل، لكن النص الأصلي يبقى في الملف تحته." to "Replace layout paints over the original text, but the original text stays in the file underneath."),
        "failed_title" to ("تعذّرت الترجمة" to "Couldn’t translate"),

        // settings
        "language" to ("اللغة" to "Language"),
        "theme" to ("المظهر" to "Appearance"),
        "th_system" to ("النظام" to "System"), "th_light" to ("فاتح" to "Light"), "th_dark" to ("داكن" to "Dark"),
        "sec_online" to ("محركات الإنترنت" to "Online engines"),
        "google_toggle" to ("السماح بالمحركات غير الرسمية (مايكروسوفت، جوجل)" to "Allow the unofficial engines (Microsoft, Google)"),
        "libre_url" to ("عنوان LibreTranslate (اتركه فارغاً لاستخدام الخوادم المجانية)" to "LibreTranslate address (leave empty for the free servers)"),
        "libre_key" to ("مفتاح API (اختياري)" to "API key (optional)"),
        "memory_email" to ("بريدك لحصة أكبر في MyMemory (اختياري)" to "Your email for a larger MyMemory quota (optional)"),
        "sec_reading" to ("قراءة الملفات" to "Reading files"),
        "ocr_toggle" to ("قراءة الصفحات الممسوحة (OCR)" to "Read scanned pages (OCR)"),
        "sec_model" to ("النموذج على الجهاز" to "On-device model"),
        "sec_about" to ("حول التطبيق" to "About"),
        "about_text" to ("تطبيق مفتوح المصدر برخصة MIT. يُبنى الملف على جهازك، ولا يُرسل النص عبر الإنترنت إلا إذا اخترت محركاً خارجياً. مكتبتا ML Kit للترجمة وOCR من جوجل تعملان محلياً لكنهما ليستا مفتوحتي المصدر." to
            "Open source under the MIT licence. The file is built on your phone, and text goes online only if you pick an online engine. Google’s ML Kit libraries for translation and OCR run locally but aren’t open source."),

        // errors
        "err_generic" to ("حدث خطأ غير متوقع." to "Something unexpected went wrong."),
        "err_model_missing" to ("نزّل نموذج العربية أولاً من شاشة الإعداد." to "Download the Arabic model first on the setup screen."),
        "err_google_optin" to ("فعّل المحركات غير الرسمية أولاً من الإعدادات." to "Enable the unofficial engines first, in Settings."),
        "err_libre_url" to ("أدخل عنوان خادم LibreTranslate في الإعدادات." to "Add your LibreTranslate address in Settings."),
        "err_password" to ("الملف محمي بكلمة مرور." to "The PDF is password protected."),
        "err_cannot_open" to ("تعذّر فتح الملف: {0}" to "Couldn’t open the file: {0}"),
        "err_empty_pdf" to ("الملف لا يحتوي على صفحات." to "The PDF has no pages."),
        "err_no_text" to ("لا يوجد نص إنجليزي قابل للترجمة في هذه الصفحات." to "There’s no English text to translate on these pages."),
        "err_bad_range" to ("نطاق الصفحات غير صالح: {0}" to "Invalid page range: {0}"),
        "err_empty_pages" to ("لا توجد صفحات مطابقة لـ {0}." to "No pages match {0}."),
        "err_bad_size" to ("الحجم يجب أن يكون بين 3 و72." to "Size must be between 3 and 72."),
        "err_bad_color" to ("لون غير صالح: {0}" to "Invalid colour: {0}"),
        "err_engine_failing" to ("تعذّر الوصول إلى محرك الترجمة. تحقق من الإنترنت أو جرّب محركاً آخر." to "Couldn’t reach the translation engine. Check your connection or try another engine."),
        "err_quota" to ("نفدت الحصة المجانية لهذا المحرك. جرّب محركاً آخر أو عد لاحقاً." to "This engine’s free quota ran out. Try another engine or come back later.")
    )
}
