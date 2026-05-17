package com.sysadmindoc.callshield.di

import com.sysadmindoc.callshield.data.CallbackDetector
import com.sysadmindoc.callshield.data.CampaignDetector
import com.sysadmindoc.callshield.data.HashWildcardMatcher
import com.sysadmindoc.callshield.data.SmsContentAnalyzer
import com.sysadmindoc.callshield.data.SmsContextChecker
import com.sysadmindoc.callshield.data.SpamHeuristics
import com.sysadmindoc.callshield.data.SpamMLScorer
import com.sysadmindoc.callshield.data.checker.CheckerDependencies
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DetectionModule {
    @Provides
    @Singleton
    fun provideSpamHeuristics(): SpamHeuristics = SpamHeuristics.shared

    @Provides
    @Singleton
    fun provideSmsContentAnalyzer(): SmsContentAnalyzer = SmsContentAnalyzer.shared

    @Provides
    @Singleton
    fun provideSpamMLScorer(): SpamMLScorer = SpamMLScorer.shared

    @Provides
    @Singleton
    fun provideCallbackDetector(): CallbackDetector = CallbackDetector.shared

    @Provides
    @Singleton
    fun provideSmsContextChecker(): SmsContextChecker = SmsContextChecker.shared

    @Provides
    @Singleton
    fun provideCampaignDetector(): CampaignDetector = CampaignDetector.shared

    @Provides
    @Singleton
    fun provideHashWildcardMatcher(): HashWildcardMatcher = HashWildcardMatcher.shared

    @Provides
    @Singleton
    fun provideCheckerDependencies(): CheckerDependencies = CheckerDependencies()
}
