package com.cursormobile.di

import android.content.Context
import androidx.room.Room
import com.cursormobile.data.db.AgentDao
import com.cursormobile.data.db.CursorMobileDb
import com.cursormobile.data.db.MessageDao
import com.cursormobile.data.db.OutboxDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun provideDb(@ApplicationContext ctx: Context): CursorMobileDb =
        Room.databaseBuilder(ctx, CursorMobileDb::class.java, "cursormobile.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideAgentDao(db: CursorMobileDb): AgentDao = db.agents()
    @Provides fun provideMessageDao(db: CursorMobileDb): MessageDao = db.messages()
    @Provides fun provideOutboxDao(db: CursorMobileDb): OutboxDao = db.outbox()
}
