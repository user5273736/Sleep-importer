package com.example.sleepimporter

import android.content.Context
import android.net.Uri
import android.util.Log
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.OffsetDateTime

class SleepImporter(
    private val client: HealthConnectClient,
    private val context: Context
) {
    data class ImportResult(val successCount: Int, val skippedCount: Int)

    private val zoneId = ZoneId.of("Europe/Rome")
    private val TAG = "SleepImporter"

    suspend fun importFromJsonUri(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val jsonString = context.contentResolver.openInputStream(uri)?.use {
            it.bufferedReader().readText()
        } ?: throw Exception("Impossibile leggere il file")

        val json = JSONArray(jsonString)
        Log.d(TAG, "Trovati ${json.length()} stage nel file JSON")
        
        val stagesBySession = groupStagesBySession(json)
        Log.d(TAG, "Raggruppati in ${stagesBySession.size} sessioni")
for (i in 0 until json.length()) {
    val obj = json.getJSONObject(i)
    val start = parseLocalDateTime(obj.getString("startTime"))
    val end = parseLocalDateTime(obj.getString("endTime"))
    val type = obj.getString("stage")

    Log.d(TAG, "Stage $i: $type - start: $start, end: $end")

    // resto del codice...
}

        var successSessions = 0
        var skippedStages = 0

        for ((index, sessionInfo) in stagesBySession.withIndex()) {
            val (sessionStart, sessionEnd, stages) = sessionInfo
            
            Log.d(TAG, "Sessione $index: $sessionStart -> $sessionEnd (${stages.size} stage)")
            
            if (sessionStart >= sessionEnd) {
                Log.w(TAG, "Sessione saltata: start >= end")
                skippedStages += stages.size
                continue
            }

            val exists = checkIfSessionExists(sessionStart, sessionEnd)
            if (exists) {
                Log.d(TAG, "Sessione già esistente, saltata")
                skippedStages += stages.size
                continue
            }

            val sleepStages = mutableListOf<SleepSessionRecord.Stage>()
            
            for (stage in stages) {
                if (stage.start >= stage.end) {
                    Log.w(TAG, "Stage saltato: start >= end")
                    skippedStages++
                    continue
                }

                val stageValue = when (stage.type.uppercase()) {
                    "AWAKE" -> 1
                    "LIGHT" -> 2
                    "DEEP" -> 3
                    "REM" -> 4
                    else -> {
                        Log.w(TAG, "Stage tipo sconosciuto: ${stage.type}")
                        skippedStages++
                        continue
                    }
                }
                
                sleepStages.add(
                    SleepSessionRecord.Stage(
                        startTime = stage.start,
                        endTime = stage.end,
                        stage = stageValue
                    )
                )
            }

            if (sleepStages.isEmpty()) {
                Log.w(TAG, "Nessuno stage valido per questa sessione")
                skippedStages += stages.size
                continue
            }

            val startOffset = ZoneOffset.ofHours(if (isWinterTime(sessionStart)) 1 else 2)
            val endOffset = ZoneOffset.ofHours(if (isWinterTime(sessionEnd)) 1 else 2)

            Log.d(TAG, "Creazione sessione: start=$sessionStart, end=$sessionEnd, stages=${sleepStages.size}")

// Controllo sessione
if (!sessionStart.isBefore(sessionEnd)) {
    Log.e(TAG, "Errore: sessionStart NON è prima di sessionEnd")
}

// Controllo ogni stage
sleepStages.forEachIndexed { index, stage ->
    if (stage.startTime.isBefore(sessionStart)) {
        Log.e(TAG, "Stage $index errore: stage.startTime (${stage.startTime}) < sessionStart ($sessionStart)")
    }
    if (stage.endTime.isAfter(sessionEnd)) {
        Log.e(TAG, "Stage $index errore: stage.endTime (${stage.endTime}) > sessionEnd ($sessionEnd)")
    }
    if (!stage.startTime.isBefore(stage.endTime)) {
        Log.e(TAG, "Stage $index errore: stage.startTime (${stage.startTime}) NON è prima di stage.endTime (${stage.endTime})")
    }
}

// Se i controlli passano, crea la sessione
try {
    val session = SleepSessionRecord(
        startTime = sessionStart,
        startZoneOffset = startOffset,
        endTime = sessionEnd,
        endZoneOffset = endOffset,
        stages = sleepStages
    )
    // Resto del codice...
} catch (e: Exception) {
    Log.e(TAG, "Errore nella creazione di SleepSessionRecord: ${e.message}", e)
}

        }

        Log.d(TAG, "Import completato: $successSessions sessioni, $skippedStages stage saltati")
        ImportResult(successCount = successSessions, skippedCount = skippedStages)
    }

    private fun isWinterTime(instant: Instant): Boolean {
        val localDateTime = LocalDateTime.ofInstant(instant, zoneId)
        val month = localDateTime.monthValue
        return month < 3 || month > 10
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

            if (start >= end) {
                Log.w(TAG, "Stage ignorato: start >= end")
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
                if (firstStart < lastEnd) {
                    sessions.add(SessionInfo(firstStart, lastEnd, currentStages.toList()))
                }
                currentStages.clear()
            }

            currentStages.add(StageInfo(start, end, type))
        }

        if (currentStages.isNotEmpty()) {
            val firstStart = currentStages.first().start
            val lastEnd = currentStages.last().end
            if (firstStart < lastEnd) {
                sessions.add(SessionInfo(firstStart, lastEnd, currentStages.toList()))
            }
        }

        return sessions
    }

    private fun parseLocalDateTime(dateTimeStr: String): Instant {
    return OffsetDateTime.parse(dateTimeStr).toInstant()
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
            Log.e(TAG, "Errore controllo esistenza: ${e.message}", e)
            false
        }
    }
}
