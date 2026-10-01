package com.sysadmindoc.callshield.data.local

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SQLite deletes a database the moment a query finds a damaged page
 * ([SupportSQLiteOpenHelper.Callback.onCorruption]), before the error reaches
 * the caller, and opens an empty one on the next query. This hands the
 * damaged file to [beforeDelete] first and calls [afterDelete] once the empty
 * one is on its way.
 */
internal class RescuingOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory,
    private val beforeDelete: (SupportSQLiteDatabase) -> Unit,
    private val afterDelete: () -> Unit,
) : SupportSQLiteOpenHelper.Factory {
    // Reading a damaged table inside beforeDelete reports corruption again,
    // and so can another thread meanwhile. Only the first report deletes.
    private val rescuing = AtomicBoolean(false)

    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        val rescuingCallback =
            object : SupportSQLiteOpenHelper.Callback(callback.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = callback.onConfigure(db)

                override fun onCreate(db: SupportSQLiteDatabase) = callback.onCreate(db)

                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = callback.onUpgrade(db, oldVersion, newVersion)

                override fun onDowngrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = callback.onDowngrade(db, oldVersion, newVersion)

                override fun onOpen(db: SupportSQLiteDatabase) = callback.onOpen(db)

                @Suppress("TooGenericExceptionCaught")
                override fun onCorruption(db: SupportSQLiteDatabase) {
                    if (!rescuing.compareAndSet(false, true)) return
                    try {
                        try {
                            beforeDelete(db)
                        } catch (e: Exception) {
                            Log.e(TAG, "Couldn't save anything from the damaged database", e)
                        }
                        callback.onCorruption(db)
                    } finally {
                        rescuing.set(false)
                    }
                    afterDelete()
                }
            }
        return delegate.create(
            SupportSQLiteOpenHelper.Configuration(
                configuration.context,
                configuration.name,
                rescuingCallback,
                configuration.useNoBackupDirectory,
                configuration.allowDataLossOnRecovery,
            ),
        )
    }

    private companion object {
        const val TAG = "RescuingOpenHelper"
    }
}
