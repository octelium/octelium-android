package com.octelium.client.auth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.octelium.client.core.auth.isLoginURLAllowed

fun openURL(context: Context, url: String, toolbarColor: Int) {
    if (!isLoginURLAllowed(url)) {
        throw IllegalArgumentException("Refusing to open a non-HTTPS URL")
    }

    val uri = url.toUri()

    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setUrlBarHidingEnabled(false)
        .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(toolbarColor).build())
        .build()

    try {
        intent.launchUrl(context, uri)
    } catch (err: ActivityNotFoundException) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (err: ActivityNotFoundException) {
            throw IllegalStateException("No web browser is available on this device")
        }
    }
}
