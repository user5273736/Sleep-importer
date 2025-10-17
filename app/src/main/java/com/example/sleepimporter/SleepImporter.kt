package com.example.sleepimporter

import android.content.Context
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepStageRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SleepImporter(
private val client: HealthConnectClient,
private val context: Context
) {
data class ImportResult(val successCount: Int, val skippedCount: Int)

```
// Usa il fuso orario di sistema
private val zoneId = ZoneId.systemDefault()

suspend fun importFromJsonUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
    val jsonString = context.contentResolver.openInputStream(uri)?.use {
        it.bufferedReader().readText()
    } ?: throw Exception("Impossibile leggere il file")

    val json = JSONArray(jsonString)
    var success = 0
    var skipped = 0

    for (i in 0 until json.length()) {
        val obj = json.getJSONObject(i)
        val startTimeStr = obj.getString("startTime")
        val endTimeStr = obj.getString("endTime")
        val stageStr = obj.getString("stage")

        // Parse delle date locali e conversione a Instant
        val start = parseLocalDateTime(startTimeStr)
        val end = parseLocalDateTime(endTimeStr)

        val stage = when (stageStr.uppercase()) {
            "LIGHT" -> SleepStageRecord.STAGE_TYPE_LIGHT
            "DEEP" -> SleepStageRecord.STAGE_TYPE_DEEP
            "REM" -> SleepStageRecord.STAGE_TYPE_REM
            "AWAKE" -> SleepStageRecord.STAGE_TYPE_AWAKE
            "AWAKE_IN_BED" -> SleepStageRecord.STAGE_TYPE_AWAKE_IN_BED
            else -> {
                skipped++
                continue
            }
        }

        // Controlla se il record esiste già
        val exists = checkIfRecordExists(start, end, stage)
        if (exists) {
            skipped++
            continue
        }

        val record = SleepStageRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = end,
            endZoneOffset = null,
            stage = stage
        )

        try {
            client.insertRecords(listOf(record))
            success++
        } catch (e: Exception) {
            e.printStackTrace()
            skipped++
        }
    }

    ImportResult(successCount = success, skippedCount = skipped)
}

private fun parseLocalDateTime(dateTimeStr: String): Instant {
    // Parse formato: "2025-07-01T01:08:00"
    val localDateTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    return localDateTime.atZone(zoneId).toInstant()
}

private suspend fun checkIfRecordExists(start: Instant, end: Instant, stage: Int): Boolean {
    return try {
        val response = client.readRecords(
            ReadRecordsRequest(
                recordType = SleepStageRecord::class,
                timeRangeFilter = TimeRangeFilter.between(start, end)
            )
        )

        response.records.any {
            it.startTime == start && it.endTime == end && it.stage == stage
        }
    } catch (e: Exception) {
        false
    }
}
```

}
