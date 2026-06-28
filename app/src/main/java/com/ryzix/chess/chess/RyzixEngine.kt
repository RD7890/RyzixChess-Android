package com.ryzix.chess.chess

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.*

data class EngineSettings(
    val skillLevel: Int = 4,
    val searchTimeMs: Int = 1000,
    val multiPv: Int = 3,
    val threads: Int = 1,
    // UCI ELO limiting — set to true + eloRating for humanoid weak play
    val limitStrength: Boolean = false,
    val eloRating: Int = 1500,
)

data class AnalysisLine(
    val rank: Int,
    val move: String,
    val eval: Float,
    val isMate: Boolean = false,
    val mateIn: Int = 0,
    val continuation: List<String> = emptyList(),
    val depth: Int = 0,
)

val STOCKFISH_LEVELS = listOf(
    EngineSettings(skillLevel = 0,  searchTimeMs = 500,  multiPv = 3, threads = 1),
    EngineSettings(skillLevel = 2,  searchTimeMs = 500,  multiPv = 3, threads = 1),
    EngineSettings(skillLevel = 4,  searchTimeMs = 610,  multiPv = 3, threads = 1),
    EngineSettings(skillLevel = 6,  searchTimeMs = 765,  multiPv = 3, threads = 1),
    EngineSettings(skillLevel = 8,  searchTimeMs = 920,  multiPv = 3, threads = 1),
    EngineSettings(skillLevel = 10, searchTimeMs = 1075, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 12, searchTimeMs = 1225, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 14, searchTimeMs = 1380, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 16, searchTimeMs = 1535, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 18, searchTimeMs = 1690, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 19, searchTimeMs = 1845, multiPv = 3, threads = 2),
    EngineSettings(skillLevel = 20, searchTimeMs = 2000, multiPv = 3, threads = 2),
)

// Full-strength "Stockfish 16" config for AI vs AI
val SF_BATTLE_SETTINGS = EngineSettings(
    skillLevel    = 20,
    searchTimeMs  = 2000,
    multiPv       = 1,
    threads       = 2,
    limitStrength = false,
)

// Humanoid 1000-ELO "Ryzix" config for AI vs AI
val RYZIX_1000_SETTINGS = EngineSettings(
    skillLevel    = 0,
    searchTimeMs  = 400,
    multiPv       = 1,
    threads       = 1,
    limitStrength = true,
    eloRating     = 1000,
)

class RyzixEngine(
    private val context: Context,
    /** Native library filename to use as the UCI engine process (e.g. "libryzix.so" or "libstockfish.so") */
    private val libName: String = "libryzix.so",
) {
    companion object {
        private const val TAG = "RyzixEngine"
    }

    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private var reader: BufferedReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _bestMoveFlow = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val bestMoveFlow: SharedFlow<String> = _bestMoveFlow

    private val _evalFlow = MutableSharedFlow<Float>(extraBufferCapacity = 10)
    val evalFlow: SharedFlow<Float> = _evalFlow

    private val _analysisFlow = MutableSharedFlow<List<AnalysisLine>>(extraBufferCapacity = 10)
    val analysisFlow: SharedFlow<List<AnalysisLine>> = _analysisFlow

    private val lineBuffer = mutableMapOf<Int, AnalysisLine>()
    private var lastEmittedDepth = -1

    var isReady = false
        private set

    @Volatile private var pendingFen: String? = null
    @Volatile private var pendingSettings: EngineSettings? = null
    @Volatile private var isAnalysisPending = false

    @Volatile private var pendingSearchFen: String? = null
    @Volatile private var pendingSearchSettings: EngineSettings? = null
    @Volatile private var isSearchPending = false

    @Volatile private var stoppingForRestart = false

    private fun findBinary(): File? {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val binary = File(nativeDir, libName)
        Log.d(TAG, "Looking for engine binary: ${binary.absolutePath}")
        return if (binary.exists() && binary.length() > 0L) {
            Log.d(TAG, "Found engine binary ($libName): ${binary.length()} bytes")
            binary
        } else {
            Log.e(TAG, "Engine binary NOT found: $nativeDir/$libName")
            null
        }
    }

    fun init(): Boolean {
        return try {
            val binary = findBinary() ?: return false
            if (!binary.canExecute()) binary.setExecutable(true)
            val pb = ProcessBuilder(binary.absolutePath)
            pb.redirectErrorStream(true)
            process = pb.start()
            writer = BufferedWriter(OutputStreamWriter(process!!.outputStream))
            reader = BufferedReader(InputStreamReader(process!!.inputStream))
            Log.d(TAG, "Engine process started")
            sendCommand("uci")
            startReadLoop()
            sendCommand("isready")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init engine: ${e.message}", e)
            false
        }
    }

    private fun startReadLoop() {
        scope.launch {
            try {
                while (true) {
                    val line = reader?.readLine() ?: break
                    Log.d(TAG, "ENG> $line")
                    when {
                        line == "readyok" -> {
                            isReady = true
                            if (isAnalysisPending) {
                                isAnalysisPending = false
                                val fen = pendingFen
                                val settings = pendingSettings
                                pendingFen = null
                                pendingSettings = null
                                if (fen != null && settings != null) {
                                    stoppingForRestart = false
                                    sendCommand("ucinewgame")
                                    sendCommand("position fen $fen")
                                    sendCommand("go movetime ${settings.searchTimeMs}")
                                }
                            }
                            if (isSearchPending) {
                                isSearchPending = false
                                val fen = pendingSearchFen
                                val settings = pendingSearchSettings
                                pendingSearchFen = null
                                pendingSearchSettings = null
                                if (fen != null && settings != null) {
                                    stoppingForRestart = false
                                    sendCommand("ucinewgame")
                                    sendCommand("position fen $fen")
                                    sendCommand("go movetime ${settings.searchTimeMs}")
                                }
                            }
                        }

                        line.startsWith("bestmove") -> {
                            val wasRestart = stoppingForRestart
                            stoppingForRestart = false

                            if (!wasRestart && lineBuffer.isNotEmpty()) {
                                val sorted = lineBuffer.values.sortedBy { it.rank }
                                _analysisFlow.emit(sorted)
                            }

                            lineBuffer.clear()
                            lastEmittedDepth = -1

                            if (!wasRestart) {
                                val parts = line.split(" ")
                                val move = if (parts.size >= 2) parts[1] else null
                                if (move != null && move != "(none)") {
                                    _bestMoveFlow.emit(move)
                                }
                            }
                        }

                        line.startsWith("info") && line.contains("multipv") -> {
                            parseMultiPvLine(line)?.let { al ->
                                lineBuffer[al.rank] = al
                                if (al.rank == 1) _evalFlow.emit(al.eval)
                                val expectedPvCount = pendingSettings?.multiPv ?: 3
                                val allSameDepth = lineBuffer.values.isNotEmpty() &&
                                    lineBuffer.values.all { it.depth == al.depth }
                                val haveEnough = lineBuffer.size >= minOf(expectedPvCount, lineBuffer.size + 1)
                                if (allSameDepth && haveEnough && al.depth > lastEmittedDepth) {
                                    lastEmittedDepth = al.depth
                                    _analysisFlow.emit(lineBuffer.values.sortedBy { it.rank })
                                }
                            }
                        }

                        line.startsWith("info") && line.contains("score cp") && !line.contains("multipv") ->
                            parseEval(line)?.let { _evalFlow.emit(it) }

                        line.startsWith("info") && line.contains("score mate") && !line.contains("multipv") ->
                            parseMate(line)?.let { m -> _evalFlow.emit(if (m > 0) 10f else -10f) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read loop error: ${e.message}")
            }
        }
    }

    fun applySettings(settings: EngineSettings) {
        // ELO limiting must be set before skill level — order matters for some engines
        if (settings.limitStrength) {
            sendCommand("setoption name UCI_LimitStrength value true")
            sendCommand("setoption name UCI_Elo value ${settings.eloRating}")
        } else {
            sendCommand("setoption name UCI_LimitStrength value false")
            sendCommand("setoption name Skill Level value ${settings.skillLevel}")
        }
        sendCommand("setoption name Threads value ${settings.threads}")
        sendCommand("setoption name MultiPV value ${settings.multiPv}")
    }

    fun startAnalysis(fen: String, settings: EngineSettings) {
        lineBuffer.clear()
        lastEmittedDepth = -1
        pendingFen = fen
        pendingSettings = settings
        isAnalysisPending = true
        stoppingForRestart = true
        sendCommand("stop")
        sendCommand("isready")
    }

    fun startSearch(fen: String, settings: EngineSettings) {
        lineBuffer.clear()
        lastEmittedDepth = -1
        pendingSearchFen = fen
        pendingSearchSettings = settings
        isSearchPending = true
        stoppingForRestart = true
        sendCommand("stop")
        sendCommand("isready")
    }

    fun stop() {
        lineBuffer.clear()
        lastEmittedDepth = -1
        isAnalysisPending = false
        isSearchPending = false
        stoppingForRestart = false
        pendingFen = null
        pendingSettings = null
        pendingSearchFen = null
        pendingSearchSettings = null
        sendCommand("stop")
    }

    fun quit() {
        sendCommand("quit")
        scope.cancel()
        process?.destroy()
        writer?.close()
        reader?.close()
    }

    private fun sendCommand(cmd: String) {
        try {
            writer?.write(cmd)
            writer?.newLine()
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Send command failed: $cmd -> ${e.message}")
        }
    }

    private fun parseMultiPvLine(line: String): AnalysisLine? {
        return try {
            val tokens = line.split(" ").filter { it.isNotBlank() }
            var rank = -1; var evalCp: Float? = null; var isMate = false
            var mateIn = 0; var pvStart = -1; var depth = 0; var i = 0
            while (i < tokens.size) {
                when (tokens[i]) {
                    "multipv" -> { rank  = tokens.getOrNull(i+1)?.toIntOrNull() ?: -1; i++ }
                    "depth"   -> { depth = tokens.getOrNull(i+1)?.toIntOrNull() ?: 0;  i++ }
                    "score"   -> {
                        when (tokens.getOrNull(i+1)) {
                            "cp"   -> { evalCp = tokens.getOrNull(i+2)?.toFloatOrNull()?.div(100f); i += 2 }
                            "mate" -> {
                                isMate = true; mateIn = tokens.getOrNull(i+2)?.toIntOrNull() ?: 0
                                evalCp = if (mateIn > 0) 100f else -100f; i += 2
                            }
                        }
                    }
                    "pv" -> { pvStart = i+1; i = tokens.size; continue }
                }
                i++
            }
            if (rank < 1 || evalCp == null) return null
            val pvMoves = if (pvStart >= 0) tokens.drop(pvStart) else emptyList()
            val mainMove = pvMoves.firstOrNull()?.trimEnd() ?: return null
            if (mainMove.length < 4) return null
            AnalysisLine(rank, mainMove, evalCp, isMate, mateIn, pvMoves.drop(1).take(5).map { it.trimEnd() }, depth)
        } catch (e: Exception) { null }
    }

    private fun parseEval(line: String): Float? = try {
        val idx = line.indexOf("score cp")
        if (idx < 0) null else line.substring(idx+9).trim().split(" ")[0].toFloat() / 100f
    } catch (e: Exception) { null }

    private fun parseMate(line: String): Int? = try {
        val idx = line.indexOf("score mate")
        if (idx < 0) null else line.substring(idx+11).trim().split(" ")[0].toInt()
    } catch (e: Exception) { null }
}
