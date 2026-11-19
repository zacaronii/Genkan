package app.genkan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

@Composable
fun GenkanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFE8B04A),
            background = Color(0xFF111114),
            surface = Color(0xFF1C1C21),
        ),
        content = content,
    )
}

/**
 * A row the gamepad highlights through [focused] and touch activates through [onClick]. On
 * ViewModel-driven screens the activity intercepts the pad, so Compose focus never moves; pass
 * [focused] = null on a form screen (login) to highlight on Compose focus instead.
 */
@Composable
fun ActionRow(label: String, focused: Boolean?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val composeFocused by interaction.collectIsFocusedAsState()
    val focused = focused ?: composeFocused
    // Screens that own focus in a ViewModel never move Compose focus, so a scrolling parent
    // wouldn't follow the controller on its own; bring the focused row into view instead.
    val bringIntoView = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    androidx.compose.runtime.LaunchedEffect(focused) {
        if (focused) bringIntoView.bringIntoView()
    }
    Box(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoView)
            .background(if (focused) colors.primary.copy(alpha = 0.2f) else colors.surface, shape)
            .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
    }
}

/**
 * A single-line text field styled like [ActionRow]. The pad browses past it; A (or a tap) opens
 * the keyboard and Done closes it. A field that opened the keyboard on focus would take over the
 * screen as the D-pad passed, and the keyboard would then swallow the D-pad.
 */
@Composable
fun GenkanTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardType: androidx.compose.ui.text.input.KeyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var editing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(interaction) {
        interaction.interactions.collect { if (it is PressInteraction.Release) editing = true }
    }
    LaunchedEffect(editing) { if (editing) keyboard?.show() }
    val view = LocalView.current
    androidx.compose.foundation.layout.Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            readOnly = !editing,
            interactionSource = interaction,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = colors.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.primary),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                keyboard?.hide()
            }),
            // API keys and tokens are shown as dots, like any password.
            visualTransformation = if (keyboardType == androidx.compose.ui.text.input.KeyboardType.Password) {
                androidx.compose.ui.text.input.PasswordVisualTransformation()
            } else {
                androidx.compose.ui.text.input.VisualTransformation.None
            },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.isFocused) editing = false }
                .onPreviewKeyEvent { event ->
                    // However the keyboard closed (Done, B, Back), the field is back to browsing.
                    if (editing && ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) != true) {
                        editing = false
                    }
                    if (editing) return@onPreviewKeyEvent false
                    // The field would spend Up and Down on its cursor; while browsing they move on.
                    val direction = when (event.key) {
                        Key.DirectionDown -> FocusDirection.Down
                        Key.DirectionUp -> FocusDirection.Up
                        else -> null
                    }
                    when {
                        direction != null -> {
                            if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                            true
                        }
                        event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyUp) editing = true
                            true
                        }
                        else -> false
                    }
                }
                .padding(top = 6.dp)
                .background(colors.surface, shape)
                .border(2.dp, if (focused) colors.primary else Color.Transparent, shape)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        )
    }
}

/**
 * A choice from a known list, like a drop-down: A opens the options under it with the current one
 * focused, and A on an option picks it and closes them again.
 */
@Composable
fun ChoiceField(label: String, value: String, options: List<String>, onChoose: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val header = remember { FocusRequester() }
    val current = remember { FocusRequester() }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        // Focus follows: into the list on opening, back to the field on closing.
        if (open) runCatching { current.requestFocus() } else if (opened) runCatching { header.requestFocus() }
        opened = true
    }
    androidx.compose.foundation.layout.Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
        ActionRow(
            (value.ifEmpty { "Choose…" }) + if (open) "  ▴" else "  ▾",
            focused = null,
            onClick = { open = !open },
            modifier = Modifier.padding(top = 6.dp).focusRequester(header),
        )
        if (open) {
            val selected = options.indexOf(value).coerceAtLeast(0)
            options.forEachIndexed { i, option ->
                ActionRow(
                    (if (option == value) "✓  " else "     ") + option,
                    focused = null,
                    onClick = {
                        onChoose(option)
                        open = false
                    },
                    modifier = Modifier
                        .padding(start = 24.dp, top = 6.dp)
                        .then(if (i == selected) Modifier.focusRequester(current) else Modifier),
                )
            }
        }
    }
}
