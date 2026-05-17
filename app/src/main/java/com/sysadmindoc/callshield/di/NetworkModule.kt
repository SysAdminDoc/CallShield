package com.sysadmindoc.callshield.di

import com.sysadmindoc.callshield.data.remote.GitHubDataSource
import com.sysadmindoc.callshield.data.remote.HotFeedDataSource
import com.sysadmindoc.callshield.data.remote.HttpClient
import com.sysadmindoc.callshield.data.remote.SpamDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = HttpClient.shared

    @Provides
    @Singleton
    fun provideGitHubDataSource(): GitHubDataSource = GitHubDataSource()

    @Provides
    @Singleton
    fun provideSpamDataSource(source: GitHubDataSource): SpamDataSource = source

    @Provides
    @Singleton
    fun provideHotFeedDataSource(source: GitHubDataSource): HotFeedDataSource = source
}
