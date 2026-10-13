package com.andrerinas.headunitrevived.main

import android.os.Build
import android.os.Bundle
import android.text.Html
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import com.andrerinas.headunitrevived.App
import com.andrerinas.headunitrevived.R
import com.andrerinas.headunitrevived.utils.AppLog

class SafetyDisclaimerDialog : DialogFragment() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Use custom theme for transparent background (CardView handles background)
        setStyle(STYLE_NO_TITLE, R.style.SafetyDialogTheme)
        isCancelable = false // Prevent back button close
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.dialog_safety_disclaimer, container, false)

        val columns = listOf(
            view.findViewById<TextView>(R.id.disclaimer_point_1),
            view.findViewById(R.id.disclaimer_point_2),
            view.findViewById(R.id.disclaimer_point_3)
        )
        val btnAccept = view.findViewById<Button>(R.id.btn_accept)

        // The three points live in one translated string, separated by a blank line. Splitting
        // here keeps all twenty translations untouched — every one of them uses this separator and
        // yields exactly three parts. Whether they end up side by side or stacked is the layout's
        // business: both variants carry the same three ids.
        val parts = getString(R.string.disclaimer_text)
            .split(POINT_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (parts.size == columns.size) {
            columns.forEachIndexed { i, column -> column.text = parts[i].asHtml() }
        } else {
            // A translation drifted off the separator. Rather than leave the text crushed into a
            // third of the width, put it all back in one full-width column — the stacked layout
            // this screen had before, scrolling included.
            AppLog.w("SafetyDisclaimer: expected ${columns.size} points but the text split into ${parts.size} — falling back to a single column.")
            columns[0].text = getString(R.string.disclaimer_text).asHtml()
            columns.drop(1).forEach { it.visibility = View.GONE }
            // Side-by-side layout only: the columns share the width by weight there, so the
            // survivor has to take all of it. The stacked layout already runs full width.
            (columns[0].layoutParams as? LinearLayout.LayoutParams)
                ?.takeIf { it.width == 0 }
                ?.weight = columns.size.toFloat()
        }

        btnAccept.setOnClickListener {
            // Save setting
            App.provide(requireContext()).settings.hasAcceptedDisclaimer = true
            dismiss()
            (activity as? MainActivity)?.checkSetupFlow()
        }

        return view
    }

    override fun onStart() {
        super.onStart()
        // Make dialog 90% width on phones, less on tablets? 
        // Or just let layout handle it?
        // DialogFragment often defaults to WRAP_CONTENT but limited width.
        // Let's set a min width.
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    @Suppress("DEPRECATION")
    private fun String.asHtml(): CharSequence =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(this, Html.FROM_HTML_MODE_LEGACY)
        } else {
            Html.fromHtml(this)
        }

    companion object {
        const val TAG = "SafetyDisclaimerDialog"

        /** The blank line every translation of disclaimer_text puts between its three points. */
        private const val POINT_SEPARATOR = "<br/><br/>"

        fun show(manager: FragmentManager) {
            if (manager.findFragmentByTag(TAG) == null) {
                SafetyDisclaimerDialog().show(manager, TAG)
            }
        }
    }
}