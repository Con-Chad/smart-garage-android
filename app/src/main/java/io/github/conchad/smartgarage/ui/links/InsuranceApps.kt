package io.github.conchad.smartgarage.ui.links

import android.content.Context
import android.content.Intent

/**
 * Opens the insurer's own app from the home card's "Insurance plan" row (D-A24). The policy's free-text
 * `provider` is matched by keyword against a small table of Play package names; the app is launched with
 * `getLaunchIntentForPackage`, which needs the matching `<queries><package>` entries in the manifest (Android 11+
 * package visibility). No match or not installed: the row is plain text. Nothing here talks to the network.
 *
 * Package names marked "unverified" were not checked against the Play Store at the time of writing; a wrong one
 * only means the row stays plain text. Editable list: add a line and a `<package>` entry to the manifest.
 */
object InsuranceApps {
    class Entry(val keywords: List<String>, val packageName: String)

    val table: List<Entry> = listOf(
        Entry(listOf("progressive"), "com.progressive.mobile"),
        Entry(listOf("geico"), "com.geico.mobile"),
        Entry(listOf("state farm", "statefarm"), "com.statefarm.pocketagent"),
        Entry(listOf("allstate"), "com.allstate.view"),
        Entry(listOf("usaa"), "com.usaa.mobile.android.usaa"),
        Entry(listOf("farmers"), "com.farmers.ifarmers"),
        Entry(listOf("nationwide"), "com.nationwide.mobile.android.nwmobile"),
        Entry(listOf("root"), "com.joinroot.root"),
        Entry(listOf("lemonade"), "com.lemonadeinc.lemonade"),
        Entry(listOf("liberty mutual", "liberty"), "com.libertymutual.lmmobile"), // unverified
        Entry(listOf("american family", "amfam"), "com.amfam.myamfam"), // unverified
        Entry(listOf("travelers"), "com.travelers.mytravelers"), // unverified
        Entry(listOf("huk"), "de.huk.meinehuk"), // unverified (HUK-Coburg / HUK24)
        Entry(listOf("allianz"), "de.allianz.meineallianz"), // unverified
    )

    /** The package for a provider name, matched by whole word so "Root" doesn't fire on "Grootinsure". */
    fun packageFor(provider: String?): String? {
        val folded = provider?.lowercase()?.replace(Regex("[^a-z0-9]+"), " ")?.trim().orEmpty()
        if (folded.isEmpty()) return null
        val padded = " $folded "
        return table.firstOrNull { entry -> entry.keywords.any { " $it " in padded } }?.packageName
    }

    /** A launch intent for the insurer's app when it is installed, else null. */
    fun launchIntent(context: Context, provider: String?): Intent? {
        val packageName = packageFor(provider) ?: return null
        return runCatching { context.packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
    }

    /** Opens the app; false when there is nothing to open. */
    fun open(context: Context, provider: String?): Boolean {
        val intent = launchIntent(context, provider) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }
}
