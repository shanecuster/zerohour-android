package com.shanetalkstech.zerohour.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import com.shanetalkstech.zerohour.R
import uniffi.zerohour_ffi.ColorSlot

/** The box is this many cells wide at most, like the terminal app's. */
internal const val CONFIRM_BOX_CELLS = 50

/**
 * "Really delete?", asked after a line is swiped left, in a box like the
 * terminal app's (`draw_confirm_delete` in its `tui/src/ui/settings.rs`):
 *
 * ```
 * ╭ Delete? ───────────────────────╮
 * │ Delete "Pay electricity bill"? │
 * │                                │
 * │ [ delete ]   [ keep ]          │
 * ╰────────────────────────────────╯
 * ```
 *
 * The border and "delete" are in the danger colour, "keep" in the accent
 * colour. [columns] is the screen's width in cells (the box never gets wider
 * than the screen). [title] is the item's title as typed.
 */
@Composable
fun ConfirmDeleteBox(
    look: TuiLook,
    columns: Int,
    title: String,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val danger = look.color(ColorSlot.DANGER)
    TuiBox(
        look = look,
        borderColor = danger,
        // The box paints its own background, because it sits on top of the list.
        modifier = Modifier
            .width(look.cell.columns(minOf(CONFIRM_BOX_CELLS, columns)))
            .background(look.color(ColorSlot.BACKGROUND)),
        title = AnnotatedString(
            stringResource(R.string.delete_title),
            SpanStyle(color = danger, fontWeight = FontWeight.Bold),
        ),
    ) {
        Column {
            // A long title simply wraps onto more lines.
            BasicText(
                text = stringResource(R.string.delete_question, title),
                style = look.text.copy(color = look.color(ColorSlot.FOREGROUND)),
            )
            Spacer(Modifier.height(look.cell.rows(1)))
            Row {
                TapText(look, stringResource(R.string.delete_yes), danger, onClick = onDelete, bold = true)
                Spacer(Modifier.width(look.cell.columns(3)))
                TapText(look, stringResource(R.string.delete_no), look.color(ColorSlot.ACCENT), onClick = onKeep, bold = true)
            }
        }
    }
}
