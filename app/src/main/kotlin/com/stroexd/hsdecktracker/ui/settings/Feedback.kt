package com.stroexd.hsdecktracker.ui.settings

import android.net.Uri
import android.os.Build
import com.stroexd.hsdecktracker.BuildConfig

/** Feedback goes to GitHub issues; the forms are in .github/ISSUE_TEMPLATE. */
internal object Feedback {
    private const val NEW_ISSUE = "https://github.com/stroexd/hs-deck-tracker-android/issues/new"

    fun problemUrl(): String = Uri.parse(NEW_ISSUE).buildUpon()
        .appendQueryParameter("template", "problem.yml")
        .appendQueryParameter("version", BuildConfig.VERSION_NAME)
        .appendQueryParameter("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        .build()
        .toString()

    fun ideaUrl(): String = "$NEW_ISSUE?template=idea.yml"
}
