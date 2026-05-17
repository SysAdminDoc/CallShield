package com.sysadmindoc.callshield.di

import android.content.Context
import com.sysadmindoc.callshield.data.SpamRepository
import com.sysadmindoc.callshield.data.repository.SpamRepositoryAdapter
import com.sysadmindoc.callshield.domain.repository.SpamCheckRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.sysadmindoc.callshield.domain.repository.BlocklistRepository as DomainBlocklistRepository
import com.sysadmindoc.callshield.domain.repository.SyncRepository as DomainSyncRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindSpamCheckRepository(adapter: SpamRepositoryAdapter): SpamCheckRepository

    @Binds
    @Singleton
    abstract fun bindSyncRepository(adapter: SpamRepositoryAdapter): DomainSyncRepository

    @Binds
    @Singleton
    abstract fun bindBlocklistRepository(adapter: SpamRepositoryAdapter): DomainBlocklistRepository

    companion object {
        @Provides
        @Singleton
        fun provideSpamRepository(
            @ApplicationContext context: Context,
        ): SpamRepository = SpamRepository.getInstance(context)

        @Provides
        @Singleton
        fun provideSpamRepositoryAdapter(
            repository: SpamRepository,
        ): SpamRepositoryAdapter = SpamRepositoryAdapter(repository)
    }
}
