package xx.photocollage.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xx.photocollage.ACCENT_COUNT
import xx.photocollage.Notice
import xx.photocollage.R
import xx.photocollage.ThemeMode

// ---------- Theme ----------

// The window is a distinct tone from the containers, so dialogs and cards stand
// out against the screen behind them. The same values as in the other apps
// built on this skeleton.
val WindowLight = Color(0xFFF1F2F4)
val WindowDark = Color(0xFF121212)

// Material3's baseline `error` is a soft, low-chroma rose in the dark scheme
// (meant for text on a dark surface, not a button fill) — nowhere near as
// loud as the trash button needs to read. Same red as the accent palette's
// own "red" choice, so it stays a proper red in both themes.
private val ErrorRed = Color(0xFFE53935)

private val LightColors = lightColorScheme(
    background = WindowLight,
    surface = WindowLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color.White,
    error = ErrorRed,
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    background = WindowDark,
    surface = WindowDark,
    surfaceContainerLowest = Color(0xFF1A1A1A),
    surfaceContainerLow = Color(0xFF1F1F1F),
    surfaceContainer = Color(0xFF242424),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF303030),
    error = ErrorRed,
    onError = Color.White,
)

/**
 * Accent choices, the same six as in the other apps built on this skeleton:
 * mid-tones that hold contrast on both windows and carry white text as a
 * button fill.
 */
val AccentPalette = listOf(
    Color(0xFF00897B), // teal
    Color(0xFF1E88E5), // blue
    Color(0xFF5C6BC0), // indigo
    Color(0xFF8E24AA), // purple
    Color(0xFFEF6C00), // orange
    Color(0xFFE53935), // red
).also { check(it.size == ACCENT_COUNT) }

fun accentAt(index: Int): Color = AccentPalette[index.coerceIn(AccentPalette.indices)]

/** Free of Compose, so the activity can ask it before the first frame. */
fun isDarkTheme(mode: ThemeMode, systemInDark: Boolean): Boolean = when (mode) {
    ThemeMode.SYSTEM -> systemInDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun PhotoCollageTheme(themeMode: ThemeMode, accentIndex: Int, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode, isSystemInDarkTheme())
    MaterialTheme(colorScheme = accentScheme(dark, accentAt(accentIndex)), content = content)
}

/** How much of the accent a container takes over the window it lies on: a pale wash in the light theme, deeper in the dark. */
private const val ACCENT_WASH_LIGHT = 0.18f
private const val ACCENT_WASH_DARK = 0.45f

/**
 * The theme's colours with every one Material would paint its own lilac in
 * taken from [accent] instead: the fill of buttons and of tonal ones such as a
 * dialog's Cancel, their containers, and the tint of raised surfaces.
 */
internal fun accentScheme(dark: Boolean, accent: Color): ColorScheme {
    val base = if (dark) DarkColors else LightColors
    val container = if (dark) {
        lerp(base.surfaceContainerHigh, accent, ACCENT_WASH_DARK)
    } else {
        lerp(Color.White, accent, ACCENT_WASH_LIGHT)
    }
    val onContainer = if (dark) Color.White else base.onSurface
    return base.copy(
        primary = accent,
        onPrimary = Color.White,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = accent,
        secondary = accent,
        onSecondary = Color.White,
        secondaryContainer = container,
        onSecondaryContainer = onContainer,
        tertiary = accent,
        onTertiary = Color.White,
        tertiaryContainer = container,
        onTertiaryContainer = onContainer,
        surfaceTint = accent,
    )
}

// ---------- Text ----------

/** A notice as one line: its text, then its numbers after a colon. */
fun noticeText(context: Context, notice: Notice): String {
    val head = context.getString(notice.text)
    return if (notice.args.isEmpty()) head else "$head: ${notice.args.joinToString()}"
}

// ---------- Small controls ----------

/** An icon button a size up from Material's 40dp: the full 48dp it takes up for touch anyway, so a row of them is
 * no wider for it — with a glyph to match. */
val LARGE_BUTTON = 48.dp
val LARGE_ICON = 28.dp

/** Every icon button in a top bar: [LARGE_BUTTON] with a [LARGE_ICON] glyph, unless given
 * [size] and [iconSize]. Without a [tint] the glyph takes the content colour, which also
 * dims it while disabled. */
@Composable
fun LargeIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color? = null,
    size: Dp = LARGE_BUTTON,
    iconSize: Dp = LARGE_ICON,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(size)) {
        if (tint == null) {
            Icon(icon, contentDescription, Modifier.size(iconSize))
        } else {
            Icon(icon, contentDescription, Modifier.size(iconSize), tint = tint)
        }
    }
}

/** The ⋮ button that opens an [AppMenu]; [tint] as for [LargeIconButton]. */
@Composable
fun MoreButton(onClick: () -> Unit, enabled: Boolean = true, tint: Color? = null) {
    LargeIconButton(Icons.Filled.MoreVert, stringResource(R.string.more_options), onClick, enabled, tint)
}

// ---------- Dialog pieces ----------

private val DIALOG_BUTTON_PADDING = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** A dialog's main button; [danger] paints it red, for a step that cannot be undone. */
@Composable
fun DialogConfirmButton(text: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        contentPadding = DIALOG_BUTTON_PADDING,
        colors = if (danger) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(text, maxLines = 1)
    }
}

/** A dialog's way out — Cancel and the like — filled with the accent like the main button, not a pale wash of it. */
@Composable
fun DialogDismissButton(text: String, onClick: () -> Unit) {
    Button(onClick = onClick, contentPadding = DIALOG_BUTTON_PADDING) {
        Text(text, maxLines = 1)
    }
}

/**
 * Every drop-down menu in the app: a raised card with an outline, so it
 * stands off the screen behind it in both themes.
 */
@Composable
fun AppMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(16.dp),
        containerColor = scheme.surfaceContainerHighest,
        shadowElevation = 12.dp,
        border = BorderStroke(1.dp, scheme.outline),
        content = content,
    )
}

/**
 * One line of an [AppMenu], in the size every menu here uses, with [icon]
 * before it if given; [danger] in red, for a step that cannot be undone.
 */
@Composable
fun AppMenuItem(
    text: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        leadingIcon = icon?.let { { Icon(it, contentDescription = null) } },
        text = {
            if (danger) {
                Text(text, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            } else {
                Text(text, style = MaterialTheme.typography.titleLarge)
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

/** Single choice from a labelled list; scrolls, since the language list can grow. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    ChoiceRow(label(option), selected = option == selected) { onPick(option) }
                }
            }
        },
        confirmButton = { DialogDismissButton(stringResource(R.string.cancel), onDismiss) },
    )
}

/** One line of a single choice: a radio button and its [text], the whole line tappable. */
@Composable
fun ChoiceRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
    }
}

/** One accent colour, ringed when it is the one in force. */
@Composable
fun AccentSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(color = color, shape = CircleShape)
            .border(
                width = if (selected) 3.dp else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

// ---------- Screens ----------

/** A screen filling the window, with a back button and title above whatever [content] puts in its Column — used
 * for every screen the ⋮ menu opens, so they navigate like the rest of the app instead of popping up as a dialog.
 * [actions] sits at the title row's trailing end, for a screen-specific button. */
@Composable
fun AppScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            actions()
        }
        content()
    }
}
