package com.shanetalkstech.zerohour.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.PasswordStore
import com.shanetalkstech.zerohour.data.SavedPassword
import com.shanetalkstech.zerohour.data.SyncRunner
import com.shanetalkstech.zerohour.data.SyncState
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.SyncConfig
import uniffi.zerohour_ffi.SyncStatus
import uniffi.zerohour_ffi.syncAccount

/** The labels in front of the fields are padded to this many cells ("Server URL"). */
private const val LABEL_CELLS = 10

/** A message under the buttons, in the "ok", "warn" or danger colour ([slot]). */
private data class SyncMessage(val text: String, val slot: ColorSlot)

/**
 * The sync settings page, opened from "Sync ›" in settings. It follows the
 * terminal app's sync popup (`c`):
 *
 * ```
 * ╭ Sync ────────────────────────────────╮
 * │   Sync       ‹ off ›                 │
 * │   Server URL https://...             │
 * │   Database   notes                   │
 * │   Username   someone                 │
 * │   Password   (saved)                 │
 * │                                      │
 * │ [ save ]                             │
 * │ [ sync now ]                         │
 * │ [ test connection ]                  │
 * │ [ reset sync state… ]                │
 * │ [ close ]                            │
 * │                                      │
 * │ Password: saved, encrypted with ...  │
 * │ Status: last synced Oct 22 2:30 PM · │
 * │ 12 documents synced                  │
 * │ Synced: 3 in, 1 out.                 │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * The rules, as in the terminal app:
 *
 * - The password box starts empty. Leaving it empty keeps the saved
 *   password; typing one replaces it when Save is tapped. What is typed is
 *   always drawn as dots, and is never shown, logged or put in a message.
 * - "Test connection" uses the details as typed (saved or not), with the
 *   typed password, or the saved one when the box is empty.
 * - Only `https://` addresses are accepted: the Rust side refuses anything
 *   else before saving or testing, with a message here.
 * - A saved password that can no longer be decrypted is removed, and the
 *   page asks for it again instead of failing.
 *
 * - "Sync now" syncs with the SAVED details (Save first after a change), on
 *   the app-wide [SyncRunner], so the header's indicator shows it too. A
 *   phone that has never synced but has items asks first, as the header
 *   does. A tap while a sync runs says "Already syncing…".
 *
 * While the demo database is in use, everything is greyed out (the demo
 * never syncs). [onClose] closes the page (the back gesture does too).
 */
@Composable
fun SyncPage(look: TuiLook, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // The form's fields. The password is only what is typed now; the saved
    // one is read when it is needed and never put into a field.
    var enabled by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var database by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var typedPassword by remember { mutableStateOf("") }

    var loaded by remember { mutableStateOf(false) }
    var demo by remember { mutableStateOf(false) }
    // The sync status (last synced, documents, first-sync warning).
    var status by remember { mutableStateOf<SyncStatus?>(null) }
    // Where "Sync now" stands, from the app-wide runner.
    val syncState by SyncRunner.state.collectAsState()
    // True after "Sync now" was tapped here, so its result shows under the
    // buttons (until another button is used).
    var showSyncResult by remember { mutableStateOf(false) }
    var confirmFirstSync by remember { mutableStateOf(false) }
    // Whether a password is saved for the details as they were last saved.
    var passwordSaved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<SyncMessage?>(null) }
    var clockWarning by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    // True while a save, test or reset runs, so a second tap does nothing.
    var busy by remember { mutableStateOf(false) }
    // Bumped to read the saved state again (after a save or a reset).
    var version by remember { mutableIntStateOf(0) }

    // The details as typed, for the Rust side.
    fun config() = SyncConfig(enabled = enabled, url = url, database = database, username = username)

    // Texts used inside the background jobs below, read here because
    // `stringResource` only works while drawing.
    val enterAgain = stringResource(R.string.sync_password_unreadable)

    BackHandler {
        if (confirmReset) confirmReset = false else onClose()
    }

    // Reads the saved details (and whether a password is saved for them) on
    // a background thread when the page opens and after a save or reset.
    LaunchedEffect(version) {
        try {
            val (screen, saved) = withContext(Dispatchers.IO) {
                val db = DatabaseHolder.get(context)
                val s = db.syncScreen()
                status = db.syncStatus(nowText())
                s to PasswordStore.load(context, syncAccount(s.config))
            }
            if (!loaded) {
                enabled = screen.config.enabled
                url = screen.config.url
                database = screen.config.database
                username = screen.config.username
                loaded = true
            }
            demo = screen.demo
            passwordSaved = saved is SavedPassword.Found
            if (saved is SavedPassword.Unreadable) message = SyncMessage(enterAgain, ColorSlot.WARN)
        } catch (e: FfiException) {
            message = SyncMessage(e.explanation(), ColorSlot.DANGER)
        }
    }

    // When a sync finishes, read the status again (last synced, documents).
    LaunchedEffect(syncState) {
        if (syncState !is SyncState.Running && syncState !is SyncState.Idle) version++
    }

    val alreadySyncing = stringResource(R.string.sync_already_running)

    // Starts "Sync now" with the saved details, unless one is running.
    fun startSync() {
        focusManager.clearFocus()
        clockWarning = null
        message = null
        showSyncResult = true
        if (!SyncRunner.start(context)) message = SyncMessage(alreadySyncing, ColorSlot.MUTED)
    }

    // Runs [action] on a background thread unless something is already
    // running. It returns the message to show; a Rust error shows its
    // explanation in the danger colour instead.
    fun runJob(action: suspend () -> SyncMessage) {
        if (busy) return
        busy = true
        showSyncResult = false
        focusManager.clearFocus()
        scope.launch {
            message = try {
                withContext(Dispatchers.IO) { action() }
            } catch (e: FfiException) {
                SyncMessage(e.explanation(), ColorSlot.DANGER)
            }
            busy = false
            version++
        }
    }

    val savedText = stringResource(R.string.sync_saved)
    val savedOffText = stringResource(R.string.sync_saved_off)
    val testingText = stringResource(R.string.sync_testing)
    val resetDoneText = stringResource(R.string.sync_reset_done)

    // Save: the details first (the Rust side checks them, https only), then
    // the typed password, under the account name of the details just saved.
    fun save() = runJob {
        val details = config()
        DatabaseHolder.get(context).saveSyncSettings(details)
        if (typedPassword.isNotEmpty()) {
            PasswordStore.save(context, syncAccount(details), typedPassword)
            typedPassword = ""
        }
        SyncMessage(if (details.enabled) savedText else savedOffText, ColorSlot.OK)
    }

    // Test: the typed password, or the saved one for these details.
    fun test() {
        message = SyncMessage(testingText, ColorSlot.MUTED)
        clockWarning = null
        runJob {
            val details = config()
            val password = typedPassword.ifEmpty {
                when (val saved = PasswordStore.load(context, syncAccount(details))) {
                    is SavedPassword.Found -> saved.password
                    // An empty password makes the Rust side say "enter the password".
                    SavedPassword.None -> ""
                    SavedPassword.Unreadable -> return@runJob SyncMessage(enterAgain, ColorSlot.WARN)
                }
            }
            val result = DatabaseHolder.get(context).testConnection(details, password, nowText())
            clockWarning = result.clockSkewSecs?.let { skewText(context, it) }
            SyncMessage(
                context.getString(R.string.sync_connected, result.docCount.toString()),
                ColorSlot.OK,
            )
        }
    }

    val muted = look.color(ColorSlot.MUTED)
    val accent = look.color(ColorSlot.ACCENT)
    val foreground = look.color(ColorSlot.FOREGROUND)
    TuiBox(
        look = look,
        borderColor = accent,
        modifier = Modifier.fillMaxSize(),
        title = AnnotatedString(
            " ${stringResource(R.string.sync_title)} ",
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            if (!loaded) return@Column
            if (demo) {
                // Greyed out: nothing here can be used with the demo.
                MutedText(look, stringResource(R.string.sync_demo_note))
                Spacer(Modifier.height(look.cell.rows(1)))
                TapText(look, stringResource(R.string.close_button), muted, onClick = onClose)
                return@Column
            }

            // The on/off switch, in the settings page's ‹ value › style.
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = look.cell.rows(LINE_ROWS)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    text = "  " + stringResource(R.string.sync_enabled).padEnd(LABEL_CELLS) + " ",
                    style = look.text.copy(color = muted),
                )
                TapText(look, "‹ ", accent, onClick = { enabled = !enabled }, largeTarget = true)
                BasicText(
                    text = stringResource(if (enabled) R.string.value_on else R.string.value_off),
                    style = look.text.copy(color = foreground),
                )
                TapText(look, " › ", accent, onClick = { enabled = !enabled }, largeTarget = true)
            }
            SyncField(look, stringResource(R.string.sync_url), url, { url = it }, FieldKind.URL)
            SyncField(look, stringResource(R.string.sync_database), database, { database = it }, FieldKind.PLAIN)
            SyncField(look, stringResource(R.string.sync_username), username, { username = it }, FieldKind.PLAIN)
            SyncField(
                look,
                stringResource(R.string.sync_password),
                typedPassword,
                { typedPassword = it },
                FieldKind.PASSWORD,
                placeholder = stringResource(if (passwordSaved) R.string.sync_password_kept else R.string.none_placeholder),
            )
            Spacer(Modifier.height(look.cell.rows(1)))

            if (confirmFirstSync) {
                // The header's first-sync question, inline like the reset one.
                val items = status?.localItems?.toInt() ?: 0
                BasicText(
                    text = pluralStringResource(R.plurals.first_sync_question, items, items),
                    style = look.text.copy(color = look.color(ColorSlot.WARN)),
                )
                Row {
                    TapText(
                        look,
                        stringResource(R.string.first_sync_continue),
                        look.color(ColorSlot.WARN),
                        bold = true,
                        onClick = {
                            confirmFirstSync = false
                            startSync()
                        },
                    )
                    Spacer(Modifier.width(look.cell.columns(2)))
                    TapText(look, stringResource(R.string.cancel_button), foreground, onClick = { confirmFirstSync = false })
                }
            } else if (confirmReset) {
                // The terminal app's question, with buttons for its y and n.
                BasicText(
                    text = stringResource(R.string.sync_reset_question),
                    style = look.text.copy(color = look.color(ColorSlot.WARN)),
                )
                Row {
                    TapText(
                        look,
                        stringResource(R.string.sync_reset_yes),
                        look.color(ColorSlot.WARN),
                        bold = true,
                        onClick = {
                            confirmReset = false
                            runJob {
                                DatabaseHolder.get(context).resetSyncState()
                                SyncMessage(resetDoneText, ColorSlot.OK)
                            }
                        },
                    )
                    Spacer(Modifier.width(look.cell.columns(2)))
                    TapText(look, stringResource(R.string.cancel_button), foreground, onClick = { confirmReset = false })
                }
            } else {
                TapText(look, stringResource(R.string.save_button), accent, onClick = ::save, bold = true)
                TapText(
                    look,
                    stringResource(R.string.sync_now_button),
                    foreground,
                    onClick = {
                        if (status?.firstSyncWarning == true && syncState != SyncState.Running) {
                            confirmFirstSync = true
                        } else {
                            startSync()
                        }
                    },
                )
                TapText(look, stringResource(R.string.sync_test_button), foreground, onClick = ::test)
                TapText(
                    look,
                    stringResource(R.string.sync_reset_button),
                    foreground,
                    onClick = {
                        message = null
                        confirmReset = true
                    },
                )
                TapText(look, stringResource(R.string.close_button), muted, onClick = onClose)
            }
            Spacer(Modifier.height(look.cell.rows(1)))

            // Where the password is, and what has been synced.
            MutedText(
                look,
                stringResource(if (passwordSaved) R.string.sync_password_saved_info else R.string.sync_password_none_info),
            )
            status?.let { MutedText(look, syncStatusLine(it, syncState)) }
            clockWarning?.let {
                BasicText(text = it, style = look.text.copy(color = look.color(ColorSlot.WARN)))
            }
            message?.let {
                Spacer(Modifier.height(look.cell.rows(1)))
                BasicText(text = it.text, style = look.text.copy(color = look.color(it.slot)))
            }
            // What the last "Sync now" from this page did: every line, most
            // important first.
            if (showSyncResult) {
                for (line in syncResultLines(syncState)) {
                    BasicText(text = line.text, style = look.text.copy(color = look.color(line.slot)))
                }
            }
        }
    }
}

/** What a [SyncField] holds, which decides its keyboard and how it is drawn. */
private enum class FieldKind {
    /** A web address: the URL keyboard, no autocorrect. */
    URL,

    /** A name: no autocorrect and no capital letters added. */
    PLAIN,

    /** The password: the password keyboard, drawn as dots. */
    PASSWORD,
}

/**
 * One text field: "  Label      value", like the form's. The value is a
 * single line in the theme's text colour with an accent cursor; when it is
 * empty and not being typed in, [placeholder] shows muted.
 *
 * A value too long for the line (a long server address) shows its END while
 * it is not being edited: "…ample.org/couch", like the terminal app's sync
 * popup, so the part that differs is visible. Tapping it shows the whole
 * text with the cursor at the end, and the field scrolls sideways to keep
 * the cursor in view while typing. The password keeps its dots instead.
 */
@Composable
private fun SyncField(
    look: TuiLook,
    label: String,
    value: String,
    onValue: (String) -> Unit,
    kind: FieldKind,
    placeholder: String? = null,
) {
    var focused by remember { mutableStateOf(false) }
    // The field keeps its own copy of the text together with the cursor
    // position (`TextFieldValue`), so the cursor can be put at the end when
    // the field is tapped. Every change is passed on with [onValue].
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    // The last [value] seen. A new one that differs from what is in the
    // field came from the page itself (the saved details being loaded), so
    // the field takes it. Checked while drawing, as in the add/edit form, so
    // quick typing is never overwritten by an older value.
    var seen by remember { mutableStateOf(value) }
    if (value != seen) {
        seen = value
        if (field.text != value) field = TextFieldValue(value, TextRange(value.length))
    }
    val keyboard = when (kind) {
        FieldKind.URL -> KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        )
        FieldKind.PLAIN -> KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Next,
        )
        FieldKind.PASSWORD -> KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = look.cell.rows(LINE_ROWS)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text = "  " + label.padEnd(LABEL_CELLS) + " ",
            style = look.text.copy(color = look.color(if (focused) ColorSlot.ACCENT else ColorSlot.MUTED)),
        )
        // `BoxWithConstraints` says how wide the value's space is, so the
        // field knows how many characters fit on its line.
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val cells = (maxWidth / look.cell.width).toInt()
            BasicTextField(
                value = field,
                onValueChange = { new ->
                    field = new
                    if (new.text != value) onValue(new.text)
                },
                singleLine = true,
                textStyle = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
                cursorBrush = SolidColor(look.color(ColorSlot.ACCENT)),
                keyboardOptions = keyboard,
                // What is drawn (the stored text is never changed): dots for
                // the password, so it is never on screen; the end of a value
                // too long for the line while it is not being edited.
                visualTransformation = when {
                    kind == FieldKind.PASSWORD -> PasswordVisualTransformation()
                    !focused && field.text.length > cells -> TailTransformation(cells)
                    else -> VisualTransformation.None
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged {
                        // Just tapped: put the cursor at the end, so the end
                        // of a long value stays in view.
                        if (it.isFocused && !focused) {
                            field = field.copy(selection = TextRange(field.text.length))
                        }
                        focused = it.isFocused
                    },
                decorationBox = { innerTextField ->
                    Box {
                        if (placeholder != null && field.text.isEmpty() && !focused) {
                            BasicText(text = placeholder, style = look.text.copy(color = look.color(ColorSlot.MUTED)))
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

/**
 * Draws a text longer than [cells] characters as "…" followed by its last
 * `cells - 1` characters, so a long value shows its end on one line. Only the
 * drawing changes; the text itself is untouched.
 *
 * A `VisualTransformation` must also say how positions in the real text map
 * to positions in the drawn text and back (an `OffsetMapping`), so a tap
 * lands in a sensible place: everything hidden behind the "…" maps to the
 * "…", and every shown character to itself.
 */
private class TailTransformation(private val cells: Int) : VisualTransformation {
    /** Builds the drawn text and the position mapping for [text]. */
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = maxOf(cells - 1, 1)
        // How many characters at the start are hidden behind the "…".
        val hidden = maxOf(text.length - shown, 0)
        if (hidden == 0) return TransformedText(text, OffsetMapping.Identity)
        val drawn = AnnotatedString("…") + text.subSequence(hidden, text.length)
        val mapping = object : OffsetMapping {
            /** A position in the real text -> where it is drawn. */
            override fun originalToTransformed(offset: Int): Int =
                if (offset <= hidden) 0 else offset - hidden + 1

            /** A drawn position -> the position in the real text. */
            override fun transformedToOriginal(offset: Int): Int =
                if (offset == 0) hidden else hidden + offset - 1
        }
        return TransformedText(drawn, mapping)
    }

    /** Two tail transformations are the same when they keep the same number of cells. */
    override fun equals(other: Any?): Boolean = other is TailTransformation && other.cells == cells

    /** Matches [equals]. */
    override fun hashCode(): Int = cells
}

/**
 * The clock warning after a test, in the terminal app's words: how many
 * minutes (rounded up) this phone's clock is ahead of or behind the server's.
 */
private fun skewText(context: Context, seconds: Long): String {
    val minutes = (abs(seconds) + 59) / 60
    val id = if (seconds > 0) R.string.sync_clock_ahead else R.string.sync_clock_behind
    return context.getString(id, minutes.toString())
}
