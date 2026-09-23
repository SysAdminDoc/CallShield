package com.sysadmindoc.callshield.data

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * ITU-T calling code and international prefix of each ISO 3166 region.
 * Android leaves a caller ID in national form when it can't validate it for
 * the phone's home region, and region rules read such a number in that
 * region's numbering, so they need both.
 *
 * Generated from libphonenumber's metadata (Python `phonenumbers` 9.0.39,
 * `country_code_for_region` over `SUPPORTED_REGIONS`, 245 regions).
 */
internal object RegionCallingCodes {
    private val codes: Map<String, String> =
        (
            "AC:247 AD:376 AE:971 AF:93 AG:1 AI:1 AL:355 AM:374 AO:244 AR:54 AS:1 AT:43 " +
                "AU:61 AW:297 AX:358 AZ:994 BA:387 BB:1 BD:880 BE:32 BF:226 BG:359 BH:973 BI:257 " +
                "BJ:229 BL:590 BM:1 BN:673 BO:591 BQ:599 BR:55 BS:1 BT:975 BW:267 BY:375 BZ:501 " +
                "CA:1 CC:61 CD:243 CF:236 CG:242 CH:41 CI:225 CK:682 CL:56 CM:237 CN:86 CO:57 " +
                "CR:506 CU:53 CV:238 CW:599 CX:61 CY:357 CZ:420 DE:49 DJ:253 DK:45 DM:1 DO:1 " +
                "DZ:213 EC:593 EE:372 EG:20 EH:212 ER:291 ES:34 ET:251 FI:358 FJ:679 FK:500 FM:691 " +
                "FO:298 FR:33 GA:241 GB:44 GD:1 GE:995 GF:594 GG:44 GH:233 GI:350 GL:299 GM:220 " +
                "GN:224 GP:590 GQ:240 GR:30 GT:502 GU:1 GW:245 GY:592 HK:852 HN:504 HR:385 HT:509 " +
                "HU:36 ID:62 IE:353 IL:972 IM:44 IN:91 IO:246 IQ:964 IR:98 IS:354 IT:39 JE:44 " +
                "JM:1 JO:962 JP:81 KE:254 KG:996 KH:855 KI:686 KM:269 KN:1 KP:850 KR:82 KW:965 " +
                "KY:1 KZ:7 LA:856 LB:961 LC:1 LI:423 LK:94 LR:231 LS:266 LT:370 LU:352 LV:371 " +
                "LY:218 MA:212 MC:377 MD:373 ME:382 MF:590 MG:261 MH:692 MK:389 ML:223 MM:95 MN:976 " +
                "MO:853 MP:1 MQ:596 MR:222 MS:1 MT:356 MU:230 MV:960 MW:265 MX:52 MY:60 MZ:258 " +
                "NA:264 NC:687 NE:227 NF:672 NG:234 NI:505 NL:31 NO:47 NP:977 NR:674 NU:683 NZ:64 " +
                "OM:968 PA:507 PE:51 PF:689 PG:675 PH:63 PK:92 PL:48 PM:508 PR:1 PS:970 PT:351 " +
                "PW:680 PY:595 QA:974 RE:262 RO:40 RS:381 RU:7 RW:250 SA:966 SB:677 SC:248 SD:249 " +
                "SE:46 SG:65 SH:290 SI:386 SJ:47 SK:421 SL:232 SM:378 SN:221 SO:252 SR:597 SS:211 " +
                "ST:239 SV:503 SX:1 SY:963 SZ:268 TA:290 TC:1 TD:235 TG:228 TH:66 TJ:992 TK:690 " +
                "TL:670 TM:993 TN:216 TO:676 TR:90 TT:1 TV:688 TW:886 TZ:255 UA:380 UG:256 US:1 " +
                "UY:598 UZ:998 VA:39 VC:1 VE:58 VG:1 VI:1 VN:84 VU:678 WF:681 WS:685 XK:383 " +
                "YE:967 YT:262 ZA:27 ZM:260 ZW:263"
        ).split(' ').associate { it.substringBefore(':') to it.substringAfter(':') }

    /** Calling code digits (no `+`) for [regionIso], or null for an unknown region. */
    fun forRegion(regionIso: String?): String? = regionIso?.let { codes[it.uppercase(Locale.ROOT)] }

    /**
     * [digits] after [regionIso]'s international prefix (`00`, `011`, `810`,
     * `010`...), or null when they don't start with one. An unknown region
     * dials like +1, matching how the rest of the bare-number handling reads
     * it. A calling code never starts with 0, so a prefix followed by 0
     * doesn't count, which is libphonenumber's rule.
     */
    fun afterInternationalPrefix(
        digits: String,
        regionIso: String?,
    ): String? {
        val region = regionIso?.uppercase(Locale.ROOT)
        val code = region?.let { codes[it] }
        val pattern = region?.let { internationalPrefixOverrides[it] } ?: if (code == null || code == NANP) NANP_PREFIX else ITU_PREFIX
        val prefix = prefixRegexes.getOrPut(pattern) { Regex("^(?:$pattern)") }.find(digits) ?: return null
        return digits.substring(prefix.value.length).takeIf { it.isNotEmpty() && it[0] != '0' }
    }

    private const val NANP = "1"
    private const val NANP_PREFIX = "011"
    private const val ITU_PREFIX = "00"
    private val prefixRegexes = ConcurrentHashMap<String, Regex>()

    /**
     * International prefixes of the regions that don't dial out with `011`
     * (+1) or `00` (everyone else), as libphonenumber's patterns
     * (`international_prefix` from the same metadata, 49 regions).
     */
    private val internationalPrefixOverrides: Map<String, String> =
        mapOf(
            "AU" to """001[14-689]|14(?:1[14]|34|4[17]|[56]6|7[47]|88)0011""",
            "AX" to """00|99(?:[01469]|5(?:[14]1|3[23]|5[59]|77|88|9[09]))""",
            "BO" to """00(?:1\d)?""",
            "BR" to """00(?:1[245]|2[1-35]|31|4[13]|[56]5|99)""",
            "BY" to """810""",
            "CC" to """001[14-689]|14(?:1[14]|34|4[17]|[56]6|7[47]|88)0011""",
            "CL" to """(?:0|1(?:1[0-69]|2[02-5]|5[13-58]|69|7[0167]|8[018]))0""",
            "CN" to """00|1(?:[12]\d|79)\d\d00""",
            "CO" to """00(?:4(?:[14]4|56)|[579])""",
            "CU" to """119""",
            "CV" to """0""",
            "CX" to """001[14-689]|14(?:1[14]|34|4[17]|[56]6|7[47]|88)0011""",
            "FI" to """00|99(?:[01469]|5(?:[14]1|3[23]|5[59]|77|88|9[09]))""",
            "FJ" to """0(?:0|52)""",
            "GY" to """001""",
            "HK" to """00(?:30|5[09]|[126-9]?)""",
            "ID" to """00[89]""",
            "IL" to """0(?:0|1(?:05|[2-9]))""",
            "IS" to """00|1(?:0(?:01|[12]0)|100)""",
            "JP" to """010""",
            "KE" to """000""",
            "KH" to """00[14-9]""",
            "KP" to """00|99""",
            "KR" to """00(?:[125689]|3(?:[46]5|91)|7(?:00|27|3|55|6[126]))""",
            "KZ" to """810""",
            "MH" to """011""",
            "MN" to """001""",
            "MU" to """0(?:0|[24-7]0|3[03])""",
            "MV" to """0(?:0|19)""",
            "MX" to """0[09]""",
            "NG" to """009""",
            "NZ" to """0(?:0|161)""",
            "PE" to """00|19(?:1[124]|77|90)00""",
            "PG" to """00|140[1-3]""",
            "PW" to """01[12]""",
            "RU" to """810""",
            "SB" to """0[01]""",
            "SC" to """010|0[0-2]""",
            "SG" to """0[0-3]\d""",
            "SI" to """00|10(?:22|66|88|99)""",
            "TD" to """00|16""",
            "TH" to """00[1-9]""",
            "TJ" to """810""",
            "TM" to """810""",
            "TW" to """0(?:0[25-79]|19)""",
            "TZ" to """00[056]""",
            "UG" to """00[057]""",
            "UY" to """0(?:0|1[3-9]\d)""",
            "WS" to """0""",
        )
}
