package xx.photocollage

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Light/dark override; SYSTEM follows the device setting. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How many accents the palette offers; the colours themselves are in ui/Common.kt. */
const val ACCENT_COUNT = 6

/** The long sides a saved collage can be, in pixels. */
val EXPORT_SIZES = listOf(1080, 1920, 2048, 3072, 4096)
const val DEFAULT_EXPORT_SIZE = 2048

/**
 * Process-wide settings, backed by SharedPreferences and exposed as [StateFlow]
 * for Compose. Call [load] once before the UI reads them.
 */
object AppSettings {
    private const val PREFS = "photocollage"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_ACCENT = "accent_index"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_EXPORT_SIZE = "export_size"
    private const val KEY_SAVE_FOLDER = "save_folder"

    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _accentIndex = MutableStateFlow(0)
    val accentIndex: StateFlow<Int> = _accentIndex.asStateFlow()

    /** The long side of a saved or shared collage, one of [EXPORT_SIZES]. */
    private val _exportSize = MutableStateFlow(DEFAULT_EXPORT_SIZE)
    val exportSize: StateFlow<Int> = _exportSize.asStateFlow()

    /** The folder a collage is saved into, by the tree URI the folder picker gave; null for Pictures/PhotoCollage. */
    private val _saveFolder = MutableStateFlow<Uri?>(null)
    val saveFolder: StateFlow<Uri?> = _saveFolder.asStateFlow()

    fun load(context: Context) {
        val prefs = prefs(context)
        _themeMode.value = enumOr(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM)
        _accentIndex.value = prefs.getInt(KEY_ACCENT, 0).coerceIn(0, ACCENT_COUNT - 1)
        _exportSize.value = prefs.getInt(KEY_EXPORT_SIZE, DEFAULT_EXPORT_SIZE).takeIf { it in EXPORT_SIZES }
            ?: DEFAULT_EXPORT_SIZE
        _saveFolder.value = prefs.getString(KEY_SAVE_FOLDER, null)?.toUri()
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        _themeMode.value = mode
        prefs(context).edit { putString(KEY_THEME, mode.name) }
    }

    fun setAccentIndex(context: Context, index: Int) {
        val clamped = index.coerceIn(0, ACCENT_COUNT - 1)
        _accentIndex.value = clamped
        prefs(context).edit { putInt(KEY_ACCENT, clamped) }
    }

    /**
     * Saves into [folder] from now on, or into Pictures/PhotoCollage when
     * null. The new folder's grant is kept across restarts and the old one's
     * given back, so they do not pile up against the system's limit.
     */
    fun setSaveFolder(context: Context, folder: Uri?) {
        val resolver = context.contentResolver
        val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (folder != null) resolver.takePersistableUriPermission(folder, access)
        val old = _saveFolder.value
        if (old != null && old != folder) runCatching { resolver.releasePersistableUriPermission(old, access) }
        _saveFolder.value = folder
        prefs(context).edit {
            if (folder == null) remove(KEY_SAVE_FOLDER) else putString(KEY_SAVE_FOLDER, folder.toString())
        }
    }

    fun setExportSize(context: Context, size: Int) {
        if (size !in EXPORT_SIZES) return
        _exportSize.value = size
        prefs(context).edit { putInt(KEY_EXPORT_SIZE, size) }
    }

    /**
     * The per-app language below API 33, where the platform does not keep one.
     * Read straight from the file: it is needed while the activity attaches,
     * before [load].
     */
    fun languageTag(context: Context): String = prefs(context).getString(KEY_LANGUAGE, null).orEmpty()

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit { putString(KEY_LANGUAGE, tag) }
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

// ---------- Language ----------

/** One entry of the language picker; [tag] empty means "follow the device". */
data class LanguageOption(val tag: String, val label: String)

/**
 * The languages this build ships, read from res/xml/locales_config.xml, each
 * named in itself. Read from the XML directly: LocaleConfig, which would do it,
 * needs API 34.
 */
fun supportedLanguages(context: Context, systemLabel: String): List<LanguageOption> {
    val tags = mutableListOf<String>()
    context.resources.getXml(R.xml.locales_config).use { parser ->
        while (parser.next() != XmlResourceParser.END_DOCUMENT) {
            if (parser.eventType == XmlResourceParser.START_TAG && parser.name == "locale") {
                parser.getAttributeValue(ANDROID_NS, "name")?.let(tags::add)
            }
        }
    }
    val languages = tags.map { tag ->
        val locale = Locale.forLanguageTag(tag)
        LanguageOption(
            tag = tag,
            label = locale.getDisplayLanguage(locale)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
        )
    }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    return listOf(LanguageOption("", systemLabel)) + languages
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

/**
 * The per-app language; empty for "follow the device". The platform holds it
 * from API 33, [AppSettings] below that.
 */
fun currentLanguageTag(context: Context): String =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.getSystemService(LocaleManager::class.java)
            .applicationLocales
            .takeUnless { it.isEmpty }
            ?.get(0)
            ?.toLanguageTag()
            .orEmpty()
    } else {
        AppSettings.languageTag(context)
    }

/**
 * From API 33 the system persists the choice and recreates the activity;
 * below that the app does both, and [localizedContext] applies it.
 */
fun setLanguageTag(activity: Activity, tag: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        activity.getSystemService(LocaleManager::class.java).applicationLocales =
            if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
    } else {
        AppSettings.setLanguageTag(activity, tag)
        activity.recreate()
    }
}

/**
 * The activity's base context in the chosen language, below API 33; from 33
 * the platform does this itself and [base] comes back as it is.
 */
fun localizedContext(base: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
    val tag = AppSettings.languageTag(base)
    val locale = if (tag.isEmpty()) Resources.getSystem().configuration.locales[0] else Locale.forLanguageTag(tag)
    Locale.setDefault(locale)
    if (tag.isEmpty()) return base
    // Only the locale is set: an empty Configuration overrides nothing else, so
    // orientation and screen size still follow the device.
    val override = Configuration().apply { setLocale(locale) }
    return base.createConfigurationContext(override)
}
