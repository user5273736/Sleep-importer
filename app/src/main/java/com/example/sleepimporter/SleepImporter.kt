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
            
            for ((stageIndex, stage) in stages.withIndex()) {
                Log.d(TAG, "  Stage $stageIndex: ${stage.type} ${stage.start} -> ${stage.end}")
                
                if (stage.start >= stage.end) {
                    Log.w(TAG, "  Stage saltato: start >= end")
                    skippedStages++
                    continue
                }

                if (stage.start < sessionStart) {
                    Log.e(TAG, "  ERRORE: stage.start < sessionStart")
                    skippedStages++
                    continue
                }

                if (stage.end > sessionEnd) {
                    Log.e(TAG, "  ERRORE: stage.end > sessionEnd")
                    skippedStages++
                    continue
                }

                val stageValue = when (stage.type.uppercase()) {
                    "AWAKE" -> 1
                    "LIGHT" -> 2
                    "DEEP" -> 3
                    "REM" -> 4
                    else -> {
                        Log.w(TAG, "  Stage tipo sconosciuto: ${stage.type}")
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

            Log.d(TAG, "Tentativo inserimento sessione con ${sleepStages.size} stage validi")

            try {
                val session = SleepSessionRecord(
                    startTime = sessionStart,
                    startZoneOffset = startOffset,
                    endTime = sessionEnd,
                    endZoneOffset = endOffset,
                    stages = sleepStages
                )
                
                client.insertRecords(listOf(session))
                successSessions++
                Log.d(TAG, "✓ Sessione importata con successo!")
            } catch (e: Exception) {
                Log.e(TAG, "✗ Errore importazione: ${e.message}", e)
                skippedStages += stages.size
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
        if (json.length() == 0) {
            Log.d(TAG, "JSON vuoto")
            return emptyList()
        }

        val sessions = mutableListOf<SessionInfo>()
        val currentStages = mutableListOf<StageInfo>()

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val startStr = obj.getString("startTime")
            val endStr = obj.getString("endTime")
            val type = obj.getString("stage")

            val start = parseLocalDateTime(startStr)
            val end = parseLocalDateTime(endStr)

            Log.d(TAG, "Parse stage $i: $type $startStr -> $endStr = $start -> $end")

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
                    Log.d(TAG, "Nuova sessione creata: $firstStart -> $lastEnd con ${currentStages.size} stage")
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
                Log.d(TAG, "Ultima sessione: $firstStart -> $lastEnd con ${currentStages.size} stage")
                sessions.add(SessionInfo(firstStart, lastEnd, currentStages.toList()))
            }
        }

        Log.d(TAG, "Totale sessioni raggruppate: ${sessions.size}")
        return sessions
    }

private fun parseLocalDateTime(dateTimeStr: String): Instant {
    return try {
        // Se ha la Z finale, è UTC - usa Instant.parse direttamente
        if (dateTimeStr.endsWith("Z")) {
            Log.d(TAG, "Parsing UTC: $dateTimeStr")
            Instant.parse(dateTimeStr)
        } else {
            // Altrimenti è ora locale italiana
            Log.d(TAG, "Parsing locale: $dateTimeStr")
            val localDateTime = LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            localDateTime.atZone(zoneId).toInstant()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Errore parsing data: $dateTimeStr", e)
        throw Exception("Formato data non valido: $dateTimeStr - ${e.message}")
    }
}

    private suspend fun checkIfSessionExists(start: Instant, end: Instant): Boolean {
        return try {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end)
                )
            )

            val exists = response.records.any {
                it.startTime == start && it.endTime == end
            }
            
            if (exists) {
                Log.d(TAG, "Sessione duplicata trovata")
            }
            
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Errore controllo esistenza: ${e.message}", e)
            false
        }
    }
}
