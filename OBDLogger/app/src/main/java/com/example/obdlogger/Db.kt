package com.example.obdlogger

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity
data class Trip(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val source: String,
    // Snapshot of the car profile at trip time, so old trips stay correct if you change cars.
    val car: String = "",
    val fuelType: String = "Petrol",
    val displacementL: Double = 0.0,
    val fuelPrice: Double = 0.0
)

@Entity(indices = [Index("tripId")])
data class Sample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: Long,
    val ts: Long,
    val pid: String,
    val value: Double
)

@Entity
data class DtcLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ts: Long,
    val code: String
)

@Dao
interface ObdDao {
    @Insert suspend fun insertTrip(t: Trip): Long
    @Update suspend fun updateTrip(t: Trip)
    @Insert suspend fun insertSamples(s: List<Sample>)
    @Insert suspend fun insertDtc(d: DtcLog)

    @Query("SELECT * FROM Trip ORDER BY startMs DESC") fun trips(): Flow<List<Trip>>
    @Query("SELECT * FROM DtcLog ORDER BY ts DESC") fun dtcs(): Flow<List<DtcLog>>
    @Query("SELECT * FROM Sample WHERE tripId = :id AND pid = :pid ORDER BY ts")
    suspend fun series(id: Long, pid: String): List<Sample>
    @Query("SELECT DISTINCT pid FROM Sample WHERE tripId = :id") suspend fun pidsIn(id: Long): List<String>
    @Query("SELECT * FROM Sample WHERE tripId = :id ORDER BY ts") suspend fun allSamples(id: Long): List<Sample>
    @Query("DELETE FROM Sample WHERE tripId = :id") suspend fun deleteSamples(id: Long)
    @Query("DELETE FROM Trip WHERE id = :id") suspend fun deleteTrip(id: Long)
}

@Database(entities = [Trip::class, Sample::class, DtcLog::class], version = 1, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): ObdDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "obd.db")
                .build().also { inst = it }
        }
    }
}
