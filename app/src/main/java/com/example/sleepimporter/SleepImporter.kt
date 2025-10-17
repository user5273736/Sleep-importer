package com.example.sleepimporter

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepStageRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import org.json.JSONArray
import java.io.File
import java.time.Instant

class SleepImporter(private val client: HealthConnectClient) {

    data class ImportResult(val successCount: Int, val skippedCount: Int)

    suspend fun importFromJsonFile(file: File): ImportResult {
        val json = JSONArray(file.readText())
        var success = 0
        var skipped = 0

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val start = Instant.parse(obj.getString("startTime"))
            val end = Instant.parse(obj.getString("endTime"))
            val stage = when (obj.getString("stage").uppercase()) {
                "LIGHT" -> SleepStageRecord.STAGE_TYPE_LIGHT
                "DEEP" -> SleepStageRecord.STAGE_TYPE_DEEP
                "REM" -> SleepStageRecord.STAGE_TYPE_REM
                "AWAKE" -> SleepStageRecord.STAGE_TYPE_AWAKE
                else -> continue
            }

            val existing = client.readRecords(
                ReadRecordsRequest(
                    SleepStageRecord::class,
                    timeRangeFilter = androidx.health.connect.client.time.TimeRangeFilter.between(start, end)
                )
            ).records.any {
                it.startTime == start && it.endTime == end
            }

            if (existing) {
                skipped++
                continue
            }

            client.insertRecords(listOf(
                SleepStageRecord(
                    startTime = start,
                    endTime = end,
                    stage = stage
                )
            ))

            success++
        }

        return ImportResult(success, skipped)
    }
}
