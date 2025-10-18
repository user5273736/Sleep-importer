package com.example.sleepimporter

import android.content.Context
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
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

    private val zoneId = ZoneId.of("Europe/Rome")

    suspend fun importFromJsonUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val jsonString = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw Exception("Impossibile leggere il file")

        val json = JSONArray(jsonString)
        
        val stagesBySession = groupStagesBySession(json)
        
        var successSessions = 0
        var skippedStages = 0

        for ((sessionStart, sessionEnd, stages) in stagesBySession) {
            if (sessionStart.isAfter(sessionEnd) || sessionStart == sessionEnd) {
                skippedStages += stages.size
                continue
            }

            val exists = checkIfSessionExists(sessionStart, sessionEnd)
            if (exists) {
                skippedStages += stages.size
                continue
            }

            val sleepStages = stages.mapNotNull { stage ->
                if (stage.start.isAfter(stage.end) || stage.start == stage.end) {
                    skippedStages++
                    return@mapNotNull null
                }

                val stageValue = when (stage.type.uppercase()) {
                    "AWAKE" -> 1
                    "LIGHT" -> 2
                    "DEEP" -> 3
                    "REM" -> 4
                    else -> {
                        skippedStages++
                        return@mapNotNull null
                    }
                }
                
                SleepSessionRecord.Stage(
                    startTime = stage.start,
                    endTime = stage.end,
                    stage = stageValue
                )
            }

            if (sleepStages.isEmpty()) {
                skippedStages += stages.size
                continue
            }

            val session = SleepSessionRecord(
                startTime = sessionStart,
                startZoneOffset = zoneId.rules.getOffset(LocalDateTime.ofInstant(sessionStart, zoneId)),
                endTime = sessionEnd,
                endZoneOffset = zoneId.rules.getOffset(LocalDateTime.ofInstant(sessionEnd, zoneId)),
                stages = sleepStages
            )

            try {
                client.insertRecords(listOf(session))
                successSessions++
            } catch (e: Exception) {
                e.printStackTrace()
                skippedStages += stages.size
            }
        }

        ImportResult(successCount = successSessions, skippedCount = skippedStages)
    }

    private data class StageInfo(val start: Instant, val end: Instant, val type: String)
    private data class SessionInfo(val start: Instant, val end: Instant, val stages: List<StageInfo>)

    private fun groupStagesBySession(json: JSONArray): List<SessionInfo> {
        if (json.length() == 0) return emptyList()

        val sessions = mutableListOf<SessionInfo>()
        val currentStages = mutableListOf<StageInfo>()

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val start = parseLocalDateTime(obj.getString("startTime"))
            val end = parseLocalDateTime(obj.getString("endTime"))
            val type = obj.getString("stage")

            if (start.isAfter(end) || start == end) {
                continue
            }

            val gap = if (currentStages.isNotEmpty()) {
                java.time.Duration.between(currentStages.last().end, start).toMinutes()
            } else {
                0
            }

            if (gap > 30 && currentStages.isNotEmpty()) {
                val firstStart = currentStages.first().start
                val lastEnd = currentStages.last().end
                sessions.add(SessionInfo(firstStart, lastEnd, currentStages.toList()))
                currentStages.clear()
            }

            currentStages.add(StageInfo(start, end, type))
        }

        if (currentStages.isNotEmpty()) {
            val firstStart = currentStages.first().start
            val lastEnd = currentStages.last().end
            sessions.add(SessionInfo(firstStart, lastEnd, currentStages.toList()))
        }

        return sessions
    }

    private fun parseLocalDateTime(dateTimeStr: String): Instant {
        val localDateTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
        return localDateTime.atZone(zoneId).toInstant()
    }

    private suspend fun checkIfSessionExists(start: Instant, end: Instant): Boolean {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )

            response.records.any {
                it.startTime == start && it.endTime == end
            }
        } catch (e: Exception) {
            false
        }
    }
}
