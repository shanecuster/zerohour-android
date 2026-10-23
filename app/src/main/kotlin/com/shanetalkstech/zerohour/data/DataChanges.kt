package com.shanetalkstech.zerohour.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tells the screens that the data changed *outside* them, so they read it
 * again: for now, a task completed with a reminder notification's "Done"
 * button while the app is open.
 *
 * The screens' own changes (a swipe, a save, a sync) already reload them;
 * this covers the ones they cannot see. [version] is just a counter: its
 * value means nothing, only that it changed.
 */
object DataChanges {

    /** The changeable counter behind [version]. */
    private val counter = MutableStateFlow(0)

    /** Changes each time the data changed elsewhere. Screens watch it. */
    val version: StateFlow<Int> = counter.asStateFlow()

    /** Says "the data changed". Safe to call from any thread. */
    fun changed() {
        // `update` reads and replaces the value in one safe step, so two
        // threads calling at once both count.
        counter.update { it + 1 }
    }
}
