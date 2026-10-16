package com.netline.app

import android.app.Application
import androidx.room.Room
import com.netline.app.data.*
import com.netline.app.monitor.MonitorController

class NetlineApp : Application() {
    val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    val database by lazy { Room.databaseBuilder(this, NetlineDatabase::class.java, "netline.db")
        .addMigrations(NetlineDatabase.MIGRATION_1_2).build() }
    val repository by lazy { TrafficRepository(database) }
    val preferences by lazy { Preferences(this) }
    val monitor by lazy { MonitorController(this) }
}
