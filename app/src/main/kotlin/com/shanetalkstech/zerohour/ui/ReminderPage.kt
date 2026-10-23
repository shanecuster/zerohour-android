package com.shanetalkstech.zerohour.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import com.shanetalkstech.zerohour.R
import com.shanetalkstech.zerohour.data.DatabaseHolder
import com.shanetalkstech.zerohour.data.explanation
import com.shanetalkstech.zerohour.data.nowText
import com.shanetalkstech.zerohour.reminders.ReminderNotifications
import com.shanetalkstech.zerohour.reminders.ReminderPermissions
import com.shanetalkstech.zerohour.reminders.ReminderScheduler
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uniffi.zerohour_ffi.ColorSlot
import uniffi.zerohour_ffi.Database
import uniffi.zerohour_ffi.FfiException
import uniffi.zerohour_ffi.PlannedReminder
import uniffi.zerohour_ffi.ReminderConfig

/** The lead times the page offers, in minutes (0 = none). */
private val LEAD_CHOICES = listOf(0, 5, 10, 15, 30, 60)

/** The day time moves by this many minutes per tap. */
private const val DAY_TIME_STEP_MINUTES = 15L

/** The day time's form across the bridge, "09:00" (see the Rust side). */
private val DAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

/**
 * What the Reminders page has read: the saved [config], what Android allows
 * ([notificationsOk], [exactOk]), and the [next] planned reminder (null when
 * there is none in the window or reminders are off).
 */
private data class ReminderState(
    val config: ReminderConfig,
    val notificationsOk: Boolean,
    val exactOk: Boolean,
    val next: PlannedReminder?,
)

/**
 * The Reminders page, opened from "Reminders ›" in settings:
 *
 * ```
 * ╭ Reminders ───────────────────────────╮
 * │   Reminders   ‹ on ›                 │
 * │   Lead        ‹ 15 min ›             │
 * │   Day time    ‹ 9:00 AM ›            │
 * │   Digest      ‹ off ›                │
 * │                                      │
 * │ Next: Mon 2:00 PM Fill Out Your Ti…  │
 * │ ⚠ Exact alarms are off: reminders    │
 * │   may come up to an hour late.       │
 * │ [ allow exact alarms ]               │
 * │                                      │
 * │ [ close ]                            │
 * ╰──────────────────────────────────────╯
 * ```
 *
 * Every change is saved at once and the alarms are planned again. Turning
 * reminders on first asks Android for permission to show notifications
 * (Android 13 and later); refused, the setting stays off and the page says
 * how to allow them. Then, if exact alarms are not allowed, a box explains
 * why they help and offers the system setting; without them reminders still
 * work, possibly late, and the page says so.
 *
 * The page is never opened on the demo database (the settings row is greyed
 * out there), which never reminds. [columns] is the screen's width in cells,
 * so the exact-alarm box fits inside the page's border.
 */
@Composable
fun ReminderPage(look: TuiLook, columns: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ReminderState?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // True after the notification permission was refused, so the page says so
    // even on Android versions where "blocked" can't be read separately.
    var refused by remember { mutableStateOf(false) }
    // True while the exact-alarm explanation box is open.
    var askExact by remember { mutableStateOf(false) }
    // Bumped after every change, so the page is read again.
    var version by remember { mutableIntStateOf(0) }
    // Changes after every re-plan, including the one each time the app comes
    // back to the front (for example from the system settings).
    val replans by ReminderScheduler.replans.collectAsState()

    BackHandler {
        if (askExact) askExact = false else onClose()
    }

    LaunchedEffect(version, replans) {
        state = try {
            withContext(Dispatchers.IO) { readState(context) }
        } catch (e: FfiException) {
            error = e.explanation()
            null
        }
    }

    // Saves one change on a background thread, re-plans the alarms (which
    // in turn makes the page read itself again), and shows a failure under
    // the rows.
    fun change(action: (Database) -> Unit) {
        scope.launch {
            error = try {
                withContext(Dispatchers.IO) { action(DatabaseHolder.get(context)) }
                null
            } catch (e: FfiException) {
                e.explanation()
            }
            version++
            ReminderScheduler.replanSoon(context)
        }
    }

    // Switches reminders on, then offers exact alarms if they are not allowed.
    fun switchOn() {
        refused = false
        change { it.setRemindersEnabled(true) }
        if (!ReminderPermissions.exactAlarmsAllowed(context)) askExact = true
    }

    // Android's "Allow notifications?" question (Android 13 and later). The
    // answer arrives here; a refusal keeps reminders off.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) switchOn() else refused = true
    }

    // The Reminders row was tapped while off: ask for what is missing first.
    fun turnOn() {
        // The channel must exist for the system's notification settings page
        // to list it.
        ReminderNotifications.createChannel(context)
        val needsAsking = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        when {
            needsAsking -> askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            // Blocked by the user in the system settings: say so, stay off.
            !ReminderNotifications.canShow(context) -> refused = true
            else -> switchOn()
        }
    }

    val accent = look.color(ColorSlot.ACCENT)
    val warn = look.color(ColorSlot.WARN)
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TuiBox(
            look = look,
            borderColor = accent,
            modifier = Modifier.fillMaxSize(),
            title = AnnotatedString(
                " ${stringResource(R.string.reminders_title)} ",
                SpanStyle(color = accent, fontWeight = FontWeight.Bold),
            ),
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                val current = state
                if (current != null) {
                    val c = current.config
                    ChoiceRow(
                        look,
                        stringResource(R.string.reminders_enabled),
                        stringResource(if (c.enabled) R.string.value_on else R.string.value_off),
                    ) {
                        if (c.enabled) change { it.setRemindersEnabled(false) } else turnOn()
                    }
                    ChoiceRow(look, stringResource(R.string.reminders_lead), leadName(c.leadMinutes)) { forward ->
                        change { it.setReminderLead(stepLead(c.leadMinutes, forward)) }
                    }
                    ChoiceRow(look, stringResource(R.string.reminders_day_time), c.dayTimeText) { forward ->
                        change { it.setReminderDayTime(stepDayTime(c.dayTime, forward)) }
                    }
                    ChoiceRow(
                        look,
                        stringResource(R.string.reminders_digest),
                        stringResource(if (c.overdueDigest) R.string.value_on else R.string.value_off),
                    ) {
                        change { it.setOverdueDigest(!c.overdueDigest) }
                    }
                    Spacer(Modifier.height(look.cell.rows(1)))

                    if (c.enabled && current.notificationsOk) {
                        val next = current.next
                        BasicText(
                            text = if (next != null) {
                                stringResource(R.string.reminders_next, next.whenText, next.title)
                            } else {
                                stringResource(R.string.reminders_next_none)
                            },
                            style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
                        )
                        Spacer(Modifier.height(look.cell.rows(1)))
                    }
                    // Notifications blocked: refused just now, or reminders
                    // are on but Android won't show them.
                    if ((refused || c.enabled) && !current.notificationsOk) {
                        BasicText(
                            text = stringResource(R.string.reminders_blocked),
                            style = look.text.copy(color = warn),
                        )
                        TapText(
                            look,
                            stringResource(R.string.reminders_open_notification_settings),
                            accent,
                            onClick = { openNotificationSettings(context) },
                        )
                        Spacer(Modifier.height(look.cell.rows(1)))
                    }
                    if (c.enabled && current.notificationsOk && !current.exactOk) {
                        BasicText(
                            text = stringResource(R.string.reminders_inexact),
                            style = look.text.copy(color = warn),
                        )
                        TapText(
                            look,
                            stringResource(R.string.reminders_allow_exact),
                            accent,
                            onClick = { openExactAlarmSettings(context) },
                        )
                        Spacer(Modifier.height(look.cell.rows(1)))
                    }
                    MutedText(look, stringResource(R.string.reminders_day_time_hint))
                    Spacer(Modifier.height(look.cell.rows(1)))
                }

                val message = error
                if (message != null) {
                    BasicText(text = message, style = look.text.copy(color = look.color(ColorSlot.DANGER)))
                    Spacer(Modifier.height(look.cell.rows(1)))
                }
                TapText(look, stringResource(R.string.close_button), look.color(ColorSlot.MUTED), onClick = onClose)
            }
        }

        if (askExact) {
            // A layer over the page that swallows taps, so only the box reacts.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = null, indication = null, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                ExactAlarmBox(
                    look = look,
                    columns = columns,
                    onOpenSettings = {
                        askExact = false
                        openExactAlarmSettings(context)
                    },
                    onNotNow = { askExact = false },
                )
            }
        }
    }
}

/**
 * The box shown after reminders are switched on without exact alarms, in the
 * style of the delete confirmation:
 *
 * [columns] is the screen's width in cells.
 *
 * ```
 * ╭ Exact alarms ──────────────────────╮
 * │ For reminders right on the minute, │
 * │ allow zerohour to set alarms ...   │
 * │                                    │
 * │ [ open settings ]   [ not now ]    │
 * ╰────────────────────────────────────╯
 * ```
 */
@Composable
private fun ExactAlarmBox(look: TuiLook, columns: Int, onOpenSettings: () -> Unit, onNotNow: () -> Unit) {
    val accent = look.color(ColorSlot.ACCENT)
    TuiBox(
        look = look,
        borderColor = accent,
        // The box paints its own background, because it sits on top of the
        // page. Four cells narrower than the screen, so the page's own
        // border still shows on both sides.
        modifier = Modifier
            .width(look.cell.columns(minOf(CONFIRM_BOX_CELLS, columns - 4)))
            .background(look.color(ColorSlot.BACKGROUND)),
        title = AnnotatedString(
            stringResource(R.string.exact_title),
            SpanStyle(color = accent, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column {
            BasicText(
                text = stringResource(R.string.exact_question),
                style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
            )
            Spacer(Modifier.height(look.cell.rows(1)))
            Row {
                TapText(look, stringResource(R.string.exact_open), accent, onClick = onOpenSettings, bold = true)
                Spacer(Modifier.width(look.cell.columns(3)))
                TapText(look, stringResource(R.string.exact_not_now), look.color(ColorSlot.MUTED), onClick = onNotNow)
            }
        }
    }
}

/**
 * Reads everything the page shows. Call on a background thread. The "Next:"
 * reminder comes from the same plan the alarms are set from.
 */
private fun readState(context: Context): ReminderState {
    val db = DatabaseHolder.get(context)
    val config = db.reminderSettings()
    val next = if (config.enabled) {
        db.planReminders(nowText(), ReminderScheduler.WINDOW_HOURS).firstOrNull()
    } else {
        null
    }
    return ReminderState(
        config = config,
        notificationsOk = ReminderNotifications.canShow(context),
        exactOk = ReminderPermissions.exactAlarmsAllowed(context),
        next = next,
    )
}

/** The lead time as shown: "none" or "15 min". */
@Composable
private fun leadName(minutes: UInt): String =
    if (minutes == 0u) {
        stringResource(R.string.reminders_lead_none)
    } else {
        stringResource(R.string.reminders_lead_minutes, minutes.toInt())
    }

/**
 * The next lead time in [LEAD_CHOICES] after (or before, when not [forward])
 * [current], going round at the ends. A saved value that is not in the list
 * (another app may have saved 45) moves to the nearest choice that way.
 */
private fun stepLead(current: UInt, forward: Boolean): UInt {
    val now = current.toInt()
    val next = if (forward) {
        LEAD_CHOICES.firstOrNull { it > now } ?: LEAD_CHOICES.first()
    } else {
        LEAD_CHOICES.lastOrNull { it < now } ?: LEAD_CHOICES.last()
    }
    return next.toUInt()
}

/**
 * The day time [DAY_TIME_STEP_MINUTES] later (or earlier) than [current]
 * ("09:00"), as "HH:mm", going round midnight. A time off the 15-minute grid
 * (say 09:07) first moves to the grid in that direction (09:15 or 09:00).
 */
private fun stepDayTime(current: String, forward: Boolean): String {
    val time = LocalTime.parse(current, DAY_TIME_FORMAT)
    val offGrid = time.minute % DAY_TIME_STEP_MINUTES
    val next = when {
        offGrid == 0L && forward -> time.plusMinutes(DAY_TIME_STEP_MINUTES)
        offGrid == 0L -> time.minusMinutes(DAY_TIME_STEP_MINUTES)
        forward -> time.plusMinutes(DAY_TIME_STEP_MINUTES - offGrid)
        else -> time.minusMinutes(offGrid)
    }
    return next.format(DAY_TIME_FORMAT)
}

/** Opens the system page with this app's notification settings. */
private fun openNotificationSettings(context: Context) {
    openSettings(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}

/**
 * Opens the system's "Alarms & reminders" page for this app (Android 12 and
 * later; older versions need no permission and never show the button).
 */
private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    openSettings(
        context,
        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
    )
}

/**
 * Starts a system settings page. Some phone makers leave pages out; then
 * the general settings for the app open instead, rather than a crash.
 */
private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            )
        } catch (again: ActivityNotFoundException) {
            // Nothing to open; the page's text still says what to do.
        }
    }
}
