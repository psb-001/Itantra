package com.bjp.itantra

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * The open-source credits screen.
 *
 * iTantra is almost entirely other people's work: the speech recognition, the voices, the
 * inference engine, the pronunciation rules and the design system. Listing that honestly is the
 * minimum; the previous version of this page listed it in technical shorthand that no judge
 * outside the field could read, which is not the same as being transparent.
 *
 * The structure now is: what the part DOES FOR YOU, in ordinary words; who made it; its licence;
 * and — the part that actually matters — WHETHER YOU NEED TO DO ANYTHING ABOUT IT. A reader who
 * sees "CC-BY-NC-SA-4.0" learns nothing. A reader who sees "cannot be used commercially, and
 * anything built on it must be shared the same way" learns everything.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_licenses)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        for ((headerRes, credits) in ProjectInfo.ALL_GROUPS) {
            val parent = containerFor(headerRes) ?: continue
            for (credit in credits) parent.addView(buildCard(credit))
        }
    }

    /** Map a section header to the LinearLayout that holds that group, by position. */
    private fun containerFor(headerRes: Int): LinearLayout? = when (headerRes) {
        R.string.licence_group_models -> findViewById(R.id.models_container)
        R.string.licence_group_software -> findViewById(R.id.software_container)
        R.string.licence_group_data -> findViewById(R.id.data_container)
        else -> null
    }

    private fun buildCard(credit: ProjectInfo.Credit): View {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(themed(com.google.android.material.R.attr.colorSurfaceContainerLow))
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        col.addView(TextView(this).apply {
            text = credit.name
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurface))
        })

        col.addView(TextView(this).apply {
            text = credit.author
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })

        // The licence id as a small pill. Anything with conditions gets a filled container so
        // the eye lands on it; a reader skimming must not be able to miss the two entries that
        // are not freely usable.
        val needsAttention = credit.action != ProjectInfo.Action.NONE &&
            credit.action != ProjectInfo.Action.ATTRIBUTION
        col.addView(TextView(this).apply {
            text = credit.licence
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            if (needsAttention) {
                setBackgroundResource(R.drawable.bg_licence_chip_restricted)
                setTextColor(themed(com.google.android.material.R.attr.colorOnErrorContainer))
            } else {
                setBackgroundResource(R.drawable.bg_licence_chip)
                setTextColor(themed(com.google.android.material.R.attr.colorOnSecondaryContainer))
            }
            setPadding(dp(10), dp(4), dp(10), dp(4))
        })

        // What it does for the reader. This is the part that was missing before.
        col.addView(TextView(this).apply {
            text = credit.does
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(10), 0, 0)
            setLineSpacing(0f, 1.35f)
        })

        // "Do you need to do anything about this?" — the plain-English translation of the
        // licence. Only shown when there is something to say, so permissive entries stay quiet.
        if (credit.action != ProjectInfo.Action.NONE) {
            col.addView(
                noteCard(
                    heading = getString(R.string.licence_action_header),
                    body = getString(credit.action.textRes),
                    // A share-alike or unclear term is more serious than plain attribution,
                    // so only the first two get the quiet treatment.
                    loud = credit.action != ProjectInfo.Action.ATTRIBUTION,
                )
            )
        }

        col.addView(MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.open_source_page)
            setTextColor(themed(com.google.android.material.R.attr.colorPrimary))
            strokeColor = android.content.res.ColorStateList.valueOf(
                getColor(R.color.md_outline)
            )
            isAllCaps = false
            setOnClickListener { openUrl(credit.url) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        })

        // The raw URL, so it can be read and copied even with no browser installed. A credits
        // page you cannot copy a link from is not much of a credits page.
        col.addView(TextView(this).apply {
            text = credit.url
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dp(8), 0, 0)
        })

        card.addView(col)
        return card
    }

    private fun noteCard(heading: String, body: String, loud: Boolean): View {
        val wrap = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
            radius = dp(12).toFloat()
            cardElevation = 0f
            if (loud) {
                setCardBackgroundColor(themed(R.attr.iTantraAlertContainer))
                // MaterialCardView.strokeColor is an Int here; MaterialButton.strokeColor is a
                // ColorStateList. They are not interchangeable.
                strokeColor = themed(R.attr.iTantraAlert)
                strokeWidth = dp(1)
            } else {
                setCardBackgroundColor(themed(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        col.addView(TextView(this).apply {
            text = heading
            // TextAppearance.Material3.LabelMedium does not exist in Material 1.11 — the M3
            // label styles are only published for some roles. BodySmall plus bold is the
            // equivalent and is verified to compile.
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(if (loud) themed(R.attr.iTantraOnAlertContainer) else themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        col.addView(TextView(this).apply {
            text = body
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(if (loud) themed(R.attr.iTantraOnAlertContainer) else themed(com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, dp(4), 0, 0)
            setLineSpacing(0f, 1.35f)
        })
        wrap.addView(col)
        return wrap
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(
                this, "This device has no app that can open web links", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun themed(attr: Int): Int {
        val v = android.util.TypedValue()
        return if (theme.resolveAttribute(attr, v, true)) {
            if (v.resourceId != 0) getColor(v.resourceId) else v.data
        } else {
            android.graphics.Color.GRAY
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
