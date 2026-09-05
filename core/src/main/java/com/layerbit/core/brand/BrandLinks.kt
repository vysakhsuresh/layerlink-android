package com.layerbit.core.brand

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.layerbit.core.R

/**
 * Company-wide links shared by every app in the Layerbit AI family - the "Powered by" footer,
 * Get Help, and Buy Me a Coffee all resolve through here so a new app picks up the same
 * destinations for free (see view_brand_footer.xml for the matching UI).
 */
object BrandLinks {
    const val WEBSITE_URL = "https://layerbit.co.in"
    const val COFFEE_URL = "https://www.buymeacoffee.com/layerbit"
    const val WHATSAPP_URL = "https://wa.me/916282595823"
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"

    fun openWebsite(context: Context) = openUrl(context, WEBSITE_URL)

    fun openCoffee(context: Context) = openUrl(context, COFFEE_URL)

    fun showGetHelpDialog(context: Context) {
        val options = arrayOf(
            context.getString(R.string.core_send_email),
            context.getString(R.string.core_chat_whatsapp)
        )
        AlertDialog.Builder(context)
            .setTitle(R.string.core_get_help)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> openUrl(context, "mailto:$SUPPORT_EMAIL?subject=Request%20Support")
                    1 -> openUrl(context, WHATSAPP_URL)
                }
            }
            .show()
    }

    private fun openUrl(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            // No app can handle this (e.g. no browser/WhatsApp installed) - nothing sensible
            // to recover to, so just no-op rather than crash.
        }
    }
}
