package com.netline.app

import android.app.Application
import androidx.room.Room
import com.netline.app.data.*
import com.netline.app.monitor.MonitorController
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted

class NetlineApp : Application() {
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, NetlineDatabase::class.java, "netline.db")
        .addMigrations(NetlineDatabase.MIGRATION_1_2).build() }
    val repository by lazy { TrafficRepository(database) }
    val preferences by lazy { Preferences(this) }
    val language by lazy { preferences.settings.map { it.language }
        .stateIn(applicationScope, SharingStarted.Eagerly, defaultLanguage()) }
    val monitor by lazy { MonitorController(this) }
    override fun onCreate() {
        super.onCreate()
        // Collect for the process lifetime so overlay failures are explained even when the Activity is stopped.
        applicationScope.launch(kotlinx.coroutines.Dispatchers.Main.immediate) {
            monitor.state.map { it.error }.distinctUntilChanged().collect { message ->
                if (message != null) {
                    android.widget.Toast.makeText(this@NetlineApp, message, android.widget.Toast.LENGTH_LONG).show()
                    monitor.clearError()
                }
            }
        }
    }
}
