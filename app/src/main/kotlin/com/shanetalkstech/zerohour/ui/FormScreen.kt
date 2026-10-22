package com.shanetalkstech.zerohour.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.FieldRow
import uniffi.zerohour_ffi.FormField
import uniffi.zerohour_ffi.FormView
import uniffi.zerohour_ffi.ItemForm
import uniffi.zerohour_ffi.ItemKind
import uniffi.zerohour_ffi.SaveOutcome

/**
 * Width of the label column, as in the terminal app ("Keep after" is the
 * longest label, at 10, plus a blank).
 */
private const val LABEL_CELLS = 11

/**
 * Where values and the preview lines start: "› " + the label column + one
 * blank, the same 14 columns as the terminal app's form.
 */
private const val VALUE_INDENT_CELLS = 2 + LABEL_CELLS + 1

/**
 * The add/edit form, filling the screen in one terminal-style box.
 *
 * Adding is two steps, as in the terminal app: first "Task or Event?" (each
 * with its one-line explanation), then the fields for that kind. Editing
 * skips the first step, because an item's kind can't change.
 *
 * [form] is the Rust-side form object; it holds the values and decides which
 * fields appear, the previews, and validation. After every change this
 * screen asks it for a fresh [FormView] and draws that. Only saving touches
 * the database, so only saving runs on a background thread.
 *
 * - [chooseKindFirst]: true when adding (start at "Task or Event?").
 * - [preselectedKind]: the kind marked "›" on that first step: Event when
 *   "+ add" was tapped on the Events tab, Task otherwise. Either can still
 *   be tapped.
 * - [taskIcon], [eventIcon]: the icons for the chosen icon mode.
 * - [onClose]: called when the form closes, with the status message to show
 *   on the main screen ("Added "…"") or null when cancelled.
 */
@Composable
fun FormScreen(
    look: TuiLook,
    form: ItemForm,
    chooseKindFirst: Boolean,
    taskIcon: String,
    eventIcon: String,
    onClose: (status: String?) -> Unit,
    preselectedKind: ItemKind = ItemKind.TASK,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // The screen's own state. `remember(form)` starts fresh for a new form.
    var choosingKind by remember(form) { mutableStateOf(chooseKindFirst) }
    var view by remember(form) { mutableStateOf(form.view(nowText())) }
    // The validation message under the fields (cleared by the next change,
    // like the terminal app clears it on the next key).
    var error by remember(form) { mutableStateOf<String?>(null) }
    // Which row is highlighted with "›" (the last one touched).
    var focused by remember(form) { mutableStateOf<FormField?>(null) }
    // True while a save is running, so a double tap can't save twice.
    var saving by remember(form) { mutableStateOf(false) }

    // Applies one change to the Rust form, then redraws from its new view.
    // These calls only work on memory, never the disk, so they are quick
    // enough to run right here on the UI thread.
    fun change(action: () -> Unit) {
        action()
        view = form.view(nowText())
        error = null
    }

    // Checks and saves on a background thread. Core's validation message
    // (e.g. "Please enter a title.") appears under the fields; on success the
    // form closes with "Added …" or "Saved …".
    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            val outcome = try {
                withContext(Dispatchers.IO) {
                    DatabaseHolder.get(context).saveForm(form, nowText())
                }
            } catch (e: FfiException) {
                error = e.explanation()
                saving = false
                return@launch
            }
            saving = false
            when (outcome) {
                is SaveOutcome.Invalid -> error = outcome.message
                is SaveOutcome.Added -> onClose(context.getString(R.string.status_added, outcome.title))
                is SaveOutcome.Saved -> onClose(context.getString(R.string.status_saved, outcome.title))
            }
        }
    }

    // The phone's back gesture: closes the calendar if it is open, otherwise
    // cancels the form (the terminal app's Esc).
    BackHandler {
        if (view.calendar != null) change { form.closeCalendar() } else onClose(null)
    }

    if (choosingKind) {
        ChooseKind(
            look = look,
            preselected = preselectedKind,
            taskIcon = taskIcon,
            eventIcon = eventIcon,
            onChoose = { kind ->
                change { form.setKind(kind) }
                choosingKind = false
            },
            onCancel = { onClose(null) },
        )
        return
    }

    // The fields, with the calendar drawn on top while it is open.
    Box(modifier = Modifier.fillMaxSize()) {
        FieldsBox(
            look = look,
            view = view,
            focused = focused,
            error = error,
            onFocus = { focused = it },
            onText = { field, text -> change { form.setText(field, text) } },
            onStep = { field, forward ->
                // A tap on a choice hides the keyboard: no text box is in use.
                focusManager.clearFocus()
                focused = field
                change { form.stepChoice(field, forward) }
            },
            onToggleDay = { index ->
                focusManager.clearFocus()
                focused = FormField.DAYS
                change { form.toggleDay(index.toUInt()) }
            },
            onOpenCalendar = {
                focusManager.clearFocus()
                focused = FormField.DATE
                change { form.openCalendar(nowText()) }
            },
            onSave = { save() },
            onCancel = { onClose(null) },
        )

        val calendar = view.calendar
        if (calendar != null) {
            // A layer over the whole form that swallows taps, so only the
            // calendar reacts while it is open (`indication = null`: no press
            // highlight on the empty space).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                CalendarBox(
                    look = look,
                    calendar = calendar,
                    onShift = { months -> change { form.shiftCalendar(months) } },
                    onToday = { change { form.calendarToToday(nowText()) } },
                    onToggleWeekStart = {
                        // The Rust form switches at once; the setting is saved
                        // in the background so the next form opens the same way.
                        var start = calendar.weekStart
                        change { start = form.toggleWeekStart() }
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { DatabaseHolder.get(context).setWeekStart(start) }
                            } catch (e: FfiException) {
                                error = e.explanation()
                            }
                        }
                    },
                    onPick = { iso -> change { form.pickDate(iso) } },
                    onCancel = { change { form.closeCalendar() } },
                )
            }
        }
    }
}

/**
 * Step 1 when adding: "Task or Event?", each a tappable block with its name
 * and the terminal app's one-line explanation. The [preselected] kind is
 * marked the way the terminal app marks the chosen one ("›", its name in its
 * section colour); the other's name is muted:
 *
 * ```
 * ╭ Add ─────────────────────────────╮
 * │ › ☐ Task                         │
 * │     Something to do, optionally  │
 * │     with a due date.             │
 * │   ◷ Event                        │
 * │     A date to count down to, ... │
 * ╰──────────────────────────────────╯
 * ```
 */
@Composable
private fun ChooseKind(
    look: TuiLook,
    preselected: ItemKind,
    taskIcon: String,
    eventIcon: String,
    onChoose: (ItemKind) -> Unit,
    onCancel: () -> Unit,
) {
    val accent = look.color(ColorSlot.ACCENT)
    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " ${stringResource(R.string.form_add)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            KindOption(
                look = look,
                icon = taskIcon,
                name = stringResource(R.string.kind_task),
                hint = stringResource(R.string.kind_task_hint),
                color = look.color(ColorSlot.TASK),
                chosen = preselected == ItemKind.TASK,
                onClick = { onChoose(ItemKind.TASK) },
            )
            Spacer(Modifier.height(look.cell.rows(1)))
            KindOption(
                look = look,
                icon = eventIcon,
                name = stringResource(R.string.kind_event),
                hint = stringResource(R.string.kind_event_hint),
                color = look.color(ColorSlot.EVENT),
                chosen = preselected == ItemKind.EVENT,
                onClick = { onChoose(ItemKind.EVENT) },
            )
            Spacer(Modifier.height(look.cell.rows(1)))
            TapText(look, stringResource(R.string.cancel_button), look.color(ColorSlot.MUTED), onClick = onCancel)
        }
    }
}

/**
 * One choice on the "Task or Event?" step: the icon and name and the
 * explanation under it, indented 4 like the terminal app. When [chosen], the
 * line starts with an accent "›" and the name is bold in the section [color];
 * otherwise it starts with two blanks and the name is muted. The whole block
 * is one tap target.
 */
@Composable
private fun KindOption(
    look: TuiLook,
    icon: String,
    name: String,
    hint: String,
    color: Color,
    chosen: Boolean,
    onClick: () -> Unit,
) {
    val nameStyle = if (chosen) {
        SpanStyle(color = color, fontWeight = FontWeight.Bold)
    } else {
        SpanStyle(color = look.color(ColorSlot.MUTED))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = look.cell.rows(1)),
    ) {
        BasicText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = look.color(ColorSlot.ACCENT))) {
                    append(if (chosen) "› " else "  ")
                }
                withStyle(nameStyle) { append("$icon $name") }
            },
            style = look.text,
        )
        BasicText(
            text = hint,
            style = look.text.copy(color = look.color(ColorSlot.MUTED)),
            modifier = Modifier.padding(start = look.cell.columns(4)),
        )
    }
}

/**
 * Step 2: the box of fields, titled " New task " / " Edit event " in the
 * section colour, then the validation message (if any) and Save / Cancel.
 * The callbacks pass each user action up to [FormScreen].
 */
@Composable
private fun FieldsBox(
    look: TuiLook,
    view: FormView,
    focused: FormField?,
    error: String?,
    onFocus: (FormField) -> Unit,
    onText: (FormField, String) -> Unit,
    onStep: (FormField, Boolean) -> Unit,
    onToggleDay: (Int) -> Unit,
    onOpenCalendar: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val isTask = view.kind == ItemKind.TASK
    val sectionColor = look.color(if (isTask) ColorSlot.TASK else ColorSlot.EVENT)
    val title = stringResource(
        when {
            view.editing && isTask -> R.string.form_edit_task
            view.editing -> R.string.form_edit_event
            isTask -> R.string.form_new_task
            else -> R.string.form_new_event
        },
    )
    TuiBox(
        look = look,
        borderColor = sectionColor,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(" $title ", SpanStyle(color = sectionColor, fontWeight = FontWeight.Bold)),
    ) {
        // Scrolls when the fields don't fit (for example with the keyboard up).
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            for (row in view.rows) {
                // `key` keeps each row's own state (such as a text box's cursor)
                // with that row when rows above it appear or disappear.
                key(row.field) {
                    FieldLine(
                        look = look,
                        row = row,
                        isFocused = row.field == focused,
                        onFocus = { onFocus(row.field) },
                        onText = { onText(row.field, it) },
                        onStep = { onStep(row.field, it) },
                        onToggleDay = onToggleDay,
                        onOpenCalendar = onOpenCalendar,
                    )
                }
            }
            Spacer(Modifier.height(look.cell.rows(1)))
            if (error != null) {
                BasicText(
                    text = error,
                    style = look.text.copy(color = look.color(ColorSlot.DANGER)),
                    modifier = Modifier.padding(start = look.cell.columns(2)),
                )
            }
            Row {
                TapText(
                    look,
                    stringResource(R.string.save_button),
                    look.color(ColorSlot.ACCENT),
                    onClick = onSave,
                    bold = true,
                )
                Spacer(Modifier.width(look.cell.columns(3)))
                TapText(look, stringResource(R.string.cancel_button), look.color(ColorSlot.MUTED), onClick = onCancel)
            }
        }
    }
}

/**
 * One field: "› Label      value", then, depending on the field, the day
 * checkboxes, the preview line, or the calendar link under it.
 */
@Composable
private fun FieldLine(
    look: TuiLook,
    row: FieldRow,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onText: (String) -> Unit,
    onStep: (Boolean) -> Unit,
    onToggleDay: (Int) -> Unit,
    onOpenCalendar: () -> Unit,
) {
    val accent = look.color(ColorSlot.ACCENT)
    val foreground = look.color(ColorSlot.FOREGROUND)
    // The label: accent and bold with a "›" in front when this field is the
    // one in use, muted otherwise (as in the terminal app).
    val labelStyle = if (isFocused) {
        look.text.copy(color = accent, fontWeight = FontWeight.Bold)
    } else {
        look.text.copy(color = look.color(ColorSlot.MUTED))
    }
    val marker = if (isFocused) "› " else "  "
    val indent = Modifier.padding(start = look.cell.columns(VALUE_INDENT_CELLS))

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = look.cell.rows(LINE_ROWS)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text = marker + row.label.padEnd(LABEL_CELLS) + " ", style = labelStyle)
            when (row.field) {
                FormField.TITLE, FormField.DATE, FormField.TIME, FormField.EVERY_N, FormField.NOTES ->
                    TextInput(look, row.field, row.value, isFocused, onFocus, onText, Modifier.weight(1f))

                FormField.PRIORITY, FormField.REPEAT, FormField.EVERY_UNIT -> {
                    // "‹ Medium ›": the arrows step back and forward through
                    // the choices, starting in the value column like the
                    // terminal app's. The arrows' blanks are part of their
                    // tap targets, which are enlarged to at least 48dp
                    // (`largeTarget`) without moving the text.
                    TapText(look, "‹ ", accent, onClick = { onStep(false) }, largeTarget = true)
                    BasicText(text = row.value, style = look.text.copy(color = foreground))
                    TapText(look, " › ", accent, onClick = { onStep(true) }, largeTarget = true)
                }

                FormField.KEEP_AFTER_PASS -> {
                    val box = if (row.checked) "[x]" else "[ ]"
                    TapText(
                        look,
                        "$box ${stringResource(R.string.keep_after_pass)}",
                        foreground,
                        onClick = { onStep(true) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // The day checkboxes are too wide to share the line with the
                // label on a phone, so they get the next line.
                FormField.DAYS -> Unit
            }
        }

        if (row.field == FormField.DAYS) {
            Row(modifier = Modifier.padding(start = look.cell.columns(2))) {
                // `forEachIndexed` gives each box its position, which is what
                // the Rust side needs to know which day was tapped.
                row.days.forEachIndexed { index, day ->
                    val box = if (day.on) "[x]" else "[ ]"
                    TapText(look, box + day.label, foreground, onClick = { onToggleDay(index) })
                    Spacer(Modifier.width(look.cell.columns(1)))
                }
            }
        }

        // The preview under the date and time: how the typed text was
        // understood, in the colour core chose (it wraps under the value).
        val preview = row.preview
        if (preview != null) {
            BasicText(
                text = preview.text,
                style = look.text.copy(color = look.color(preview.slot)),
                modifier = indent,
            )
        }
        if (row.field == FormField.DATE) {
            TapText(look, stringResource(R.string.open_calendar), accent, onClick = onOpenCalendar, modifier = indent)
        }
    }
}

/**
 * A one-line text box in the terminal style: the bundled font, the theme's
 * text colour, an accent-coloured cursor, and no frame.
 *
 * The text box keeps its own copy of the text together with the cursor
 * position (`TextFieldValue`); every change is passed on with [onText]. If
 * the Rust side changes the text itself (a date picked in the calendar), the
 * new [value] replaces the copy, with the cursor at the end.
 */
@Composable
private fun TextInput(
    look: TuiLook,
    field: FormField,
    value: String,
    isFocused: Boolean,
    onFocus: () -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The last [value] this box has seen. When a new one arrives that is not
    // what is in the box already, the change came from the Rust side (a
    // picked date), so the box takes it. This is checked right here while
    // drawing, not later in an effect: a later check could see a value that
    // is one keystroke old and wipe out letters typed quickly.
    var seen by remember { mutableStateOf(value) }
    if (value != seen) {
        seen = value
        if (text.text != value) text = TextFieldValue(value, TextRange(value.length))
    }

    // The keyboard to show: numbers for "every N", plain text (no
    // autocorrect fighting "fri" or "+3d") for the date and time, sentences
    // for the title and notes. "Next" moves on to the next text box.
    val keyboard = when (field) {
        FormField.EVERY_N -> KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        FormField.DATE, FormField.TIME ->
            KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next)
        else -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next)
    }
    // An empty date or time shows "(none)" while not being edited, as in the
    // terminal app.
    val placeholder = if (field == FormField.DATE || field == FormField.TIME) {
        stringResource(R.string.none_placeholder)
    } else {
        null
    }

    BasicTextField(
        value = text,
        onValueChange = { new ->
            // "Every N" takes digits only; filtering here too keeps the box
            // showing exactly what the Rust side stored.
            val cleaned = if (field == FormField.EVERY_N) {
                new.copy(text = new.text.filter { it in '0'..'9' })
            } else {
                new
            }
            text = cleaned
            if (cleaned.text != value) onText(cleaned.text)
        },
        singleLine = true,
        textStyle = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
        cursorBrush = SolidColor(look.color(ColorSlot.ACCENT)),
        keyboardOptions = keyboard,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
        // The "decoration" draws around the editable text: here, just the
        // placeholder behind it when the box is empty and not in use.
        decorationBox = { innerTextField ->
            Box {
                if (placeholder != null && text.text.isEmpty() && !isFocused) {
                    BasicText(text = placeholder, style = look.text.copy(color = look.color(ColorSlot.MUTED)))
                }
                innerTextField()
            }
        },
    )
}
