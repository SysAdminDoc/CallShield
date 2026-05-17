package com.sysadmindoc.callshield.data.checker

import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CampaignDetector
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SmsContextChecker
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamMLScorer

data class CheckerDependencies(
    val spamHeuristics: SpamHeuristics = SpamHeuristics,
    val smsContentAnalyzer: SmsContentAnalyzer = SmsContentAnalyzer,
    val spamMLScorer: SpamMLScorer = SpamMLScorer,
    val callbackDetector: CallbackDetector = CallbackDetector,
    val smsContextChecker: SmsContextChecker = SmsContextChecker,
    val campaignDetector: CampaignDetector = CampaignDetector,
    val hashWildcardMatcher: HashWildcardMatcher = HashWildcardMatcher,
)
