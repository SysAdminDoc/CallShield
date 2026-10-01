package com.sysadmindoc.callshield.data.local

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RescuingOpenHelperFactoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val events = mutableListOf<String>()
    private val db = stub<SupportSQLiteDatabase>()
    private lateinit var wrapped: SupportSQLiteOpenHelper.Configuration

    private val roomCallback =
        object : SupportSQLiteOpenHelper.Callback(20) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                events += "create"
            }

            override fun onUpgrade(
                db: SupportSQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) {
                events += "upgrade $oldVersion to $newVersion"
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                events += "open"
            }

            override fun onCorruption(db: SupportSQLiteDatabase) {
                events += "delete"
            }
        }

    @Test
    fun `a damaged database is read before SQLite deletes it`() {
        create(beforeDelete = { events += "rescue" })

        wrapped.callback.onCorruption(db)

        assertEquals(listOf("rescue", "delete", "rebuilt"), events)
    }

    @Test
    fun `damage found while reading is left to the report already running`() {
        create(beforeDelete = {
            wrapped.callback.onCorruption(db)
            events += "rescue"
        })

        wrapped.callback.onCorruption(db)

        assertEquals(listOf("rescue", "delete", "rebuilt"), events)
    }

    @Test
    fun `a rescue that fails still lets SQLite start over`() {
        create(beforeDelete = { error("the disk is full") })

        wrapped.callback.onCorruption(db)
        events.clear()
        wrapped.callback.onCorruption(db)

        assertEquals("a later report is handled again", listOf("delete", "rebuilt"), events)
    }

    @Test
    fun `everything else is Room's own`() {
        create(beforeDelete = {})

        wrapped.callback.onCreate(db)
        wrapped.callback.onUpgrade(db, 19, 20)
        wrapped.callback.onOpen(db)

        assertEquals(listOf("create", "upgrade 19 to 20", "open"), events)
        assertEquals(20, wrapped.callback.version)
        assertEquals("callshield.db", wrapped.name)
        assertTrue(wrapped.useNoBackupDirectory)
    }

    private fun create(beforeDelete: (SupportSQLiteDatabase) -> Unit) {
        val factory =
            RescuingOpenHelperFactory(
                delegate = { configuration ->
                    wrapped = configuration
                    stub()
                },
                beforeDelete = beforeDelete,
                afterDelete = { events += "rebuilt" },
            )
        factory.create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name("callshield.db")
                .callback(roomCallback)
                .noBackupDirectory(true)
                .build(),
        )
    }

    private inline fun <reified T> stub(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T
}
