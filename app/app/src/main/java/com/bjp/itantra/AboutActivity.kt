package com.bjp.itantra

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

/**
 * The About screen, in plain language.
 *
 * Built by inflating simple views into LinearLayouts in code rather than declaring ~40 rows in
 * XML. The content is a Kotlin list ([ProjectInfo]) and should stay that way: a static XML block
 * per team member and per language is duplication with no benefit, and the next person to add
 * someone would have to remember to touch two files instead of one.
 *
 * COLOUR RULES ON THIS SCREEN, which are easy to get wrong:
 *  - setTextAppearance() must be called BEFORE setTextColor(). Applying a TextAppearance
 *    re-applies that style's own text colour, so a later setTextColor is the one that sticks.
 *    Getting this backwards made every value on the first version of this page render in
 *    near-invisible pale grey on a light card.
 *  - Colours come from the theme, never from getColor(R.color.md_...). values/ and values-night/
 *    define the SAME names with different values, so a fixed lookup returns whichever scheme is
 *    currently active rather than the one you meant.
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        buildLanguageList(findViewById(R.id.lang_container))
        buildTeamBlock(findViewById(R.id.team_container))
        buildEventBlock(findViewById(R.id.event_container))

        findViewById<MaterialButton>(R.id.licences_button).setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
    }

    // ------------------------------------------------------------------ languages

    /**
     * One row per language, each labelled "Speaks aloud" or "Text only".
     *
     * Listing the split per language rather than summarising "supports 10 languages" is the whole
     * point of this block. Four of the ten are genuinely silent, and a single summary line would
     * hide that from the person deciding whether to test Tamil.
     */
    private fun buildLanguageList(parent: LinearLayout) {
        for (language in ProjectInfo.LANGUAGES) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7), 0, dp(7))
            }

            row.addView(TextView(this).apply {
                text = language.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(themed(com.google.android.material.R.attr.colorOnSurface))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // A small pill, not a full card per language: ten cards would be a wall of boxes.
            // Green-ish (primaryContainer) for spoken, muted (surfaceContainerHighest) for
            // text-only, so the difference is visible without reading the word.
            val spoken = language.speaks
            val label = TextView(this).apply {
                text = getString(
                    if (spoken) R.string.lang_capable_badge else R.string.lang_text_only_badge
                )
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                if (spoken) {
                    setBackgroundResource(R.drawable.bg_badge_capable)
                    setTextColor(themed(com.google.android.material.R.attr.colorOnPrimaryContainer))
                } else {
                    setBackgroundResource(R.drawable.bg_badge_textoonly)
                    setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                setPadding(dp(10), dp(4), dp(10), dp(4))
            }
            row.addView(label)
            parent.addView(row)
        }
    }

    // ------------------------------------------------------------------ team

    private fun buildTeamBlock(parent: LinearLayout) {
        for ((index, member) in ProjectInfo.TEAM.withIndex()) {
            if (index > 0) addDivider(parent)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(10))
            }

            // Initials in a circle. Cheaper than shipping six avatar images and it never
            // fails to load.
            val badge = TextView(this).apply {
                text = member.name.split(' ')
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercase() }
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_avatar)
                setTextColor(themed(com.google.android.material.R.attr.colorOnPrimaryContainer))
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(14) }
            }

            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            names.addView(TextView(this).apply {
                text = member.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall)
                setTextColor(themed(com.google.android.material.R.attr.colorOnSurface))
            })
            names.addView(TextView(this).apply {
                text = member.role
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
            })

            if (member.linkedIn != null) {
                val open = MaterialButton(this, null,
                    com.google.android.material.R.attr.materialIconButtonStyle).apply {
                    text = "LinkedIn"
                    setTextColor(themed(com.google.android.material.R.attr.colorPrimary))
                    setOnClickListener { openUrl(member.linkedIn) }
                }
                row.addView(badge)
                row.addView(names)
                row.addView(open)
            } else {
                val none = TextView(this).apply {
                    text = "no public profile yet"
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelSmall)
                    setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
                }
                row.addView(badge)
                row.addView(names)
                row.addView(none)
            }

            parent.addView(row)
        }
    }

    // ------------------------------------------------------------------ event

    private fun buildEventBlock(parent: LinearLayout) {
        addRow(parent, getString(R.string.about_field_ps), ProjectInfo.PS_NUMBER)
        addRow(parent, getString(R.string.about_field_title), ProjectInfo.PS_TITLE_OFFICIAL)
        addRow(parent, getString(R.string.about_field_team), ProjectInfo.TEAM_NAME)
        addRow(parent, getString(R.string.about_field_org), ProjectInfo.ORGANISATION)
        addRow(parent, getString(R.string.about_field_category), ProjectInfo.CATEGORY)
        addRow(parent, getString(R.string.about_field_theme), ProjectInfo.THEME)
        addRow(parent, getString(R.string.about_field_build), ProjectInfo.BUILD)
    }

    // ------------------------------------------------------------------ helpers

    private fun addRow(parent: LinearLayout, label: String, value: String) {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(TextView(this).apply {
            text = label
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        col.addView(TextView(this).apply {
            text = value
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(themed(com.google.android.material.R.attr.colorOnSurface))
        })
        parent.addView(col)
    }

    private fun addDivider(parent: LinearLayout) {
        parent.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1),
            )
            setBackgroundColor(themed(com.google.android.material.R.attr.colorOutlineVariant))
        })
    }

    /**
     * Open a URL, and say so rather than dying if nothing can handle it.
     *
     * On a bare emulator there is often no browser at all, and an uncaught
     * ActivityNotFoundException here would crash the one page a judge is guaranteed to open.
     */
    private fun openUrl(url: String?) {
        if (url == null) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            android.widget.Toast.makeText(
                this, "This device has no app that can open web links", android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** Resolve a colour from the ACTIVE THEME. See the note in the class doc. */
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
