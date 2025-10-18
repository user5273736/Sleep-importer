package com.example.sleepimporter

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            
            // Pausa ogni 50 sessioni per evitare rate limiting
            if (index > 0 && index % 50 == 0) {
                Log.d(TAG, "Pausa rate limiting dopo $index sessioni...")
                delay(3000) // 3 secondi di pausa
            }
            
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
                if (stage.start >= stage.end) {
                    Log.w(TAG, "Stage $stageIndex saltato: start >= end")
                    skippedStages++
                    continue
                }

                if (stage.start < sessionStart) {
                    Log.e(TAG, "Stage $stageIndex ERRORE: start < sessionStart")
                    skippedStages++
                    continue
                }

                if (stage.end > sessionEnd) {
                    Log.e(TAG, "Stage $stageIndex ERRORE: end > sessionEnd")
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

            val startLocal = LocalDateTime.ofInstant(sessionStart, zoneId)
            val endLocal = LocalDateTime.ofInstant(sessionEnd, zoneId)

            // ...
            val startOffset = zoneId.rules.getOffset(startLocal)
            val endOffset = zoneId.rules.getOffset(endLocal)

            Log.d(TAG, "Offset: start=$startOffset, end=$endOffset")

            // Piccolo delay tra ogni inserimento
            delay(50)

            // 🌟 CORREZIONE: DICHIARA 'session' FUORI dal try-catch
// This is outside the try-catch and visible everywhere:
            val session = SleepSessionRecord(
                startTime = sessionStart,
                startZoneOffset = startOffset,
                endTime = sessionEnd,
                endZoneOffset = endOffset,
                stages = sleepStages
            )

            try {
                // Use 'session' without 'val'
                client.insertRecords(listOf(session)) 
                successSessions++
                Log.d(TAG, "✓ Sessione importata!")
            } catch (e: Exception) {
                // ... (retry logic here, also using 'session' without 'val')
                // Gestione specifica del rate limiting
                if (e.message?.contains("Rate limited") == true || 
                    e.message?.contains("quota has been exceeded") == true) {
                    Log.w(TAG, "Rate limit raggiunto, attendo 5 secondi...")
                    delay(5000)
                    
                    // Riprova una volta
                    try {
                        client.insertRecords(listOf(session)) // <--- Ora 'session' è risolta!
                        successSessions++
                        Log.d(TAG, "✓ Sessione importata (dopo retry)!")
                    } catch (e2: Exception) {
                        Log.e(TAG, "✗ Errore anche dopo retry: ${e2.message}", e2)
                        skippedStages += stages.size
                    }
                } else {
                    Log.e(TAG, "✗ Errore importazione: ${e.message}", e)
                    skippedStages += stages.size
                }
            }
// ...

        Log.d(TAG, "Completato: $successSessions sessioni, $skippedStages stage saltati")
        ImportResult(successCount = successSessions, skippedCount = skippedStages)
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

            if (start >= end) {
                Log.w(TAG, "Stage $i ignorato: start >= end")
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

        Log.d(TAG, "Totale sessioni: ${sessions.size}")
        return sessions
    }

    private fun parseLocalDateTime(dateTimeStr: String): Instant {
        val cleaned = dateTimeStr.replace("Z", "").trim()
        val localDateTime = LocalDateTime.parse(cleaned, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
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
            Log.e(TAG, "Errore controllo duplicati: ${e.message}", e)
            false
        }
    }
}
