package com.yomu.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Content that is greyed out and takes no touch while [disabled], for a section the choice above has turned off. */
@Composable
internal fun Dimmed(disabled: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.alpha(if (disabled) DISABLED_ALPHA else 1f)) {
        content()
        if (disabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) awaitPointerEvent().changes.forEach { it.consume() }
                        }
                    }
            )
        }
    }
}

private const val DISABLED_ALPHA = 0.4f
