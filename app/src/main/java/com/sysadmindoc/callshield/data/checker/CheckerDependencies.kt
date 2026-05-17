package com.sysadmindoc.callshield.data.checker

import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CampaignDetector
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SmsContextChecker
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamMLScorer

data class CheckerDependencies(
    val spamHeuristics: SpamHeuristics = SpamHeuristics.shared,
    val smsContentAnalyzer: SmsContentAnalyzer = SmsContentAnalyzer.shared,
    val spamMLScorer: SpamMLScorer = SpamMLScorer.shared,
    val callbackDetector: CallbackDetector = CallbackDetector.shared,
    val smsContextChecker: SmsContextChecker = SmsContextChecker.shared,
    val campaignDetector: CampaignDetector = CampaignDetector.shared,
    val hashWildcardMatcher: HashWildcardMatcher = HashWildcardMatcher.shared,
)
