package com.sysadmindoc.callshield.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * Put [text] on the clipboard. Android 13 and later show their own card with
 * what was copied, so [confirmation] only toasts on older versions, where
 * nothing else says the copy worked. On 13 the toast sat on top of that card.
 */
fun Context.copyToClipboard(
    label: String,
    text: String,
    confirmation: String? = null,
) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    if (confirmation != null && !systemConfirmsCopies()) {
        Toast.makeText(this, confirmation, Toast.LENGTH_SHORT).show()
    }
}

/** Android 13 added the clipboard card that confirms every copy. */
fun systemConfirmsCopies(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
