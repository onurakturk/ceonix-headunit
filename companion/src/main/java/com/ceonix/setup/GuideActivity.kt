package com.ceonix.setup

import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView

/**
 * The whole first-time setup, start to finish, with a cropped screenshot of every thing to tap.
 *
 * The main screen only knows the last step — hand the address over — but by the time a new rider
 * gets there they have already had to grant the head unit's permissions, pair it over Bluetooth and
 * press Android Auto once. None of that was written down anywhere they could see it.
 *
 * Button and screen names inside the text are never typed into the step copy: they are format
 * arguments filled from the strings the buttons themselves use (or, for the head unit, copies of
 * the head unit's own translations), so a renamed button cannot leave the guide pointing at
 * something that no longer exists.
 */
class GuideActivity : AppCompatActivity() {

    private class Step(
        @StringRes val title: Int,
        val text: CharSequence,
        @DrawableRes val images: List<Int>
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        findViewById<ImageView>(R.id.back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.got_it).setOnClickListener { finish() }

        val list = findViewById<LinearLayout>(R.id.step_list)
        val inflater = LayoutInflater.from(this)
        val androidAuto = getString(R.string.hu_android_auto)

        addSection(inflater, list, R.drawable.ic_m3_motorcycle, R.string.guide_section_head_unit)
        var number = 1
        listOf(
            Step(
                R.string.guide_permissions_title,
                bold(R.string.guide_permissions_text, getString(R.string.hu_continue)),
                listOf(R.drawable.guide_permissions)
            ),
            Step(
                R.string.guide_pair_title,
                getString(R.string.guide_pair_text),
                listOf(R.drawable.guide_bt_pair)
            ),
            Step(
                R.string.guide_aa_title,
                bold(R.string.guide_aa_text, androidAuto),
                listOf(R.drawable.guide_aa_card, R.drawable.guide_aa_pick)
            ),
            Step(
                R.string.guide_connection_title,
                bold(
                    R.string.guide_connection_text,
                    getString(R.string.hu_settings),
                    getString(R.string.hu_connection),
                    getString(R.string.hu_listening)
                ),
                listOf(R.drawable.guide_connection, R.drawable.guide_listening)
            )
        ).forEach { addStep(inflater, list, number++, it) }

        addSection(inflater, list, R.drawable.ic_m3_bt, R.string.guide_section_phone)
        listOf(
            Step(
                R.string.guide_find_title,
                bold(R.string.guide_find_text, getString(R.string.action_fix)),
                listOf(R.drawable.guide_find)
            ),
            Step(
                R.string.guide_send_title,
                bold(
                    R.string.guide_send_text,
                    getString(R.string.found_heading),
                    getString(R.string.action_send)
                ),
                listOf(R.drawable.guide_found, R.drawable.guide_pick_hu)
            ),
            Step(
                R.string.guide_done_title,
                bold(R.string.guide_done_text, getString(R.string.handed_heading), androidAuto),
                listOf(R.drawable.guide_done)
            )
        ).forEach { addStep(inflater, list, number++, it) }
    }

    private fun addSection(
        inflater: LayoutInflater,
        parent: ViewGroup,
        @DrawableRes icon: Int,
        @StringRes title: Int
    ) {
        val row = inflater.inflate(R.layout.item_guide_section, parent, false)
        row.findViewById<ImageView>(R.id.section_icon).setImageResource(icon)
        row.findViewById<TextView>(R.id.section_title).setText(title)
        parent.addView(row)
    }

    private fun addStep(inflater: LayoutInflater, parent: ViewGroup, number: Int, step: Step) {
        val card = inflater.inflate(R.layout.item_guide_step, parent, false)
        card.findViewById<TextView>(R.id.step_number).text = number.toString()
        card.findViewById<TextView>(R.id.step_title).setText(step.title)
        card.findViewById<TextView>(R.id.step_text).text = step.text

        val body = card.findViewById<LinearLayout>(R.id.step_body)
        step.images.forEach { body.addView(screenshot(it)) }
        parent.addView(card)
    }

    /**
     * A cropped screenshot in a rounded frame. The crops are all close to square or wider, so a
     * full-width image never runs taller than the screen. The thin outline keeps a dark head unit
     * screenshot from melting into the card in dark mode.
     */
    private fun screenshot(@DrawableRes image: Int): MaterialCardView {
        val dp = resources.displayMetrics.density
        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (14 * dp).toInt() }
            radius = 16 * dp
            cardElevation = 0f
            strokeWidth = (1 * dp).toInt()
            strokeColor = ContextCompat.getColor(context, R.color.m3_outline_variant)
            setCardBackgroundColor(ContextCompat.getColor(context, R.color.m3_surface_container_high))
            addView(ImageView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = ImageView.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(image)
            })
        }
    }

    /** Formats [text] and sets every argument in bold, so the names of things to tap stand out. */
    private fun bold(@StringRes text: Int, vararg args: String): CharSequence {
        val formatted = getString(text, *args)
        val spannable = SpannableString(formatted)
        args.forEach { arg ->
            val start = formatted.indexOf(arg)
            if (arg.isNotEmpty() && start >= 0) {
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD), start, start + arg.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        return spannable
    }
}
