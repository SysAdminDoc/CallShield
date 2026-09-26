package com.sysadmindoc.callshield.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.sysadmindoc.callshield.data.PhoneIdentityCanonicalizer

/**
 * The phone's home region, for reading numbers without a `+` (see
 * AreaCodeLookup.lookup): on a phone from China, 130 1234 5678 is a mobile
 * number, not a Maryland area code.
 */
@Composable
fun rememberHomeRegion(): String? {
    val context = LocalContext.current
    return remember(context) { PhoneIdentityCanonicalizer.cachedFromContext(context).homeRegionIso }
}
