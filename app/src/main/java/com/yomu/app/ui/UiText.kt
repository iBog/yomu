package com.yomu.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Text that is chosen where the state is made but written where it is shown, so it follows the app
 * language. A [UiText] among [args] is resolved first.
 */
class UiText(@StringRes val res: Int, vararg val args: Any) {
    fun resolve(context: Context): String =
        context.getString(res, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())

    override fun equals(other: Any?): Boolean = other is UiText && res == other.res && args.contentEquals(other.args)

    override fun hashCode(): Int = 31 * res + args.contentHashCode()
}

@Composable
fun UiText.asString(): String = resolve(LocalContext.current)
