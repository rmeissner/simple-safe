package de.thegerman.simplesafe.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

fun Context.copyToClipboard(label: String, text: String, onCopy: (String) -> Unit = {}) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.primaryClip = ClipData.newPlainText(label, text)
    onCopy(text)
}
