package com.sysadmindoc.callshield.data

enum class NotificationScreeningCategory {
    RCS,
    PRIVATE_MESSAGE,
    EMAIL,
}

data class NotificationScreeningSource(
    val packageName: String,
    val stableName: String,
    val category: NotificationScreeningCategory,
    val enabledByDefault: Boolean = false,
)

/** Catalog and privacy gate for notification-layer message screening. */
object NotificationScreeningSources {
    val catalog =
        listOf(
            NotificationScreeningSource(
                "com.google.android.apps.messaging",
                "Google Messages",
                NotificationScreeningCategory.RCS,
                enabledByDefault = true,
            ),
            NotificationScreeningSource(
                "com.samsung.android.messaging",
                "Samsung Messages",
                NotificationScreeningCategory.RCS,
                enabledByDefault = true,
            ),
            NotificationScreeningSource("com.android.mms", "AOSP Messages", NotificationScreeningCategory.RCS),
            NotificationScreeningSource(
                "com.microsoft.android.smsorganizer",
                "SMS Organizer",
                NotificationScreeningCategory.RCS,
            ),
            NotificationScreeningSource(
                "org.thoughtcrime.securesms",
                "Signal",
                NotificationScreeningCategory.PRIVATE_MESSAGE,
            ),
            NotificationScreeningSource("com.whatsapp", "WhatsApp", NotificationScreeningCategory.PRIVATE_MESSAGE),
            NotificationScreeningSource(
                "com.whatsapp.w4b",
                "WhatsApp Business",
                NotificationScreeningCategory.PRIVATE_MESSAGE,
            ),
            NotificationScreeningSource("com.google.android.gm", "Gmail", NotificationScreeningCategory.EMAIL),
            NotificationScreeningSource("com.microsoft.office.outlook", "Outlook", NotificationScreeningCategory.EMAIL),
            NotificationScreeningSource("net.thunderbird.android", "Thunderbird", NotificationScreeningCategory.EMAIL),
        )

    val defaultEnabledPackages: Set<String> =
        catalog.filterTo(linkedSetOf()) { it.enabledByDefault }.mapTo(linkedSetOf()) { it.packageName }

    private val byPackage = catalog.associateBy { it.packageName }

    fun sourceFor(packageName: String): NotificationScreeningSource? = byPackage[packageName]

    fun enabledPackages(storedPackages: Set<String>?): Set<String> =
        supportedOnly(storedPackages ?: defaultEnabledPackages)

    private fun supportedOnly(packages: Set<String>): Set<String> = packages.filterTo(linkedSetOf()) { it in byPackage }

    /** Must be checked before notification extras are read. */
    fun shouldReadPackage(
        packageName: String,
        enabledPackages: Set<String>,
    ): Boolean = sourceFor(packageName) != null && packageName in enabledPackages
}
