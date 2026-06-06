package com.privatesolarmon.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** True when the device is currently in landscape orientation. */
@Composable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * Renders a screen as a pinned [header] over a body of card [sections].
 *
 * - Portrait: a single vertically-scrolling column (identical to the classic layout).
 * - Landscape: the sections are tiled across two balanced, independently-scrolling columns so the
 *   extra horizontal space is used instead of one tall scroll.
 */
@Composable
fun AdaptiveScreen(
    landscape: Boolean,
    header: @Composable () -> Unit,
    sections: List<@Composable () -> Unit>,
) {
    if (landscape) {
        Column(Modifier.fillMaxSize()) {
            header()
            Row(
                Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 0.dp).padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                for (col in 0..1) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        sections.filterIndexed { i, _ -> i % 2 == col }.forEach { it() }
                    }
                }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 20.dp)) {
            header()
            Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                sections.forEach { it() }
            }
        }
    }
}

/**
 * Landscape body that gives every entry in [columns] its own full-height, independently-scrolling
 * column, left to right. When the columns all fit they share the width evenly; when they don't
 * (e.g. the inverter's five panels) they take [minColumnWidth] each and the row scrolls
 * horizontally.
 */
@Composable
fun LandscapeColumns(
    header: @Composable () -> Unit,
    columns: List<@Composable () -> Unit>,
    minColumnWidth: Dp = 320.dp,
    columnWidths: List<Dp?> = emptyList(),
    fillWhenFits: Boolean = true,
) {
    fun widthAt(i: Int): Dp = columnWidths.getOrNull(i) ?: minColumnWidth
    Column(Modifier.fillMaxSize()) {
        header()
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(bottom = 20.dp)) {
            val spacing = 14.dp
            val n = columns.size.coerceAtLeast(1)
            val needed = (0 until n).fold(spacing * (n - 1)) { acc, i -> acc + widthAt(i) }
            when {
                // Everything fits and the caller wants the columns to share the width evenly.
                needed <= maxWidth && fillWhenFits ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        columns.forEach { col ->
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { col() }
                        }
                    }
                // Fits, but columns keep their natural widths, left-aligned (e.g. a narrow flow panel).
                needed <= maxWidth ->
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(spacing)) {
                        columns.forEachIndexed { i, col ->
                            Column(Modifier.width(widthAt(i)).fillMaxHeight().verticalScroll(rememberScrollState())) { col() }
                        }
                    }
                // Too wide to fit: fixed widths with horizontal scroll.
                else ->
                    Row(
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing),
                    ) {
                        columns.forEachIndexed { i, col ->
                            Column(Modifier.width(widthAt(i)).fillMaxHeight().verticalScroll(rememberScrollState())) { col() }
                        }
                    }
            }
        }
    }
}
