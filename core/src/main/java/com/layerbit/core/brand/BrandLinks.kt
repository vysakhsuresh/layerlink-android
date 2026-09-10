package com.layerbit.core.brand

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
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

    /**
     * A plain AlertDialog.Builder(context).setItems(...) here previously inherited the
     * Activity's default DayNight AlertDialog chrome - a light system surface on many devices,
     * clashing with LayerLink's own fixed-dark UI. Inflating a custom view styled with the same
     * tokens the rest of the app uses (card_background/accent_main/text_*), with the dialog's own
     * window background made transparent, makes it look like part of the app instead.
     */
    fun showGetHelpDialog(context: Context) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_get_help, null)
        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        view.findViewById<View>(R.id.rowEmail).setOnClickListener {
            dialog.dismiss()
            openUrl(context, "mailto:$SUPPORT_EMAIL?subject=Request%20Support")
        }
        view.findViewById<View>(R.id.rowWhatsapp).setOnClickListener {
            dialog.dismiss()
            openUrl(context, WHATSAPP_URL)
        }
        view.findViewById<View>(R.id.btnCloseGetHelp).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
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
