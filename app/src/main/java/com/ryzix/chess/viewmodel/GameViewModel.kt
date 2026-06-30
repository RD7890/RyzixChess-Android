package com.ryzix.chess.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ryzix.chess.chess.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "a1chess_prefs")

object PrefKeys {
    val LEVEL              = intPreferencesKey("engine_level")
    val SEARCH_TIME        = intPreferencesKey("search_time_ms")
    val MULTI_PV           = intPreferencesKey("multi_pv")
    val THREADS            = intPreferencesKey("threads")
    val PLAY_AS_WHITE      = intPreferencesKey("play_as_white")
    val SHOW_ARROWS        = intPreferencesKey("show_arrows")
    val GAME_HISTORY       = stringPreferencesKey("game_history")
    val CURRENT_GAME_MOVES = stringPreferencesKey("current_game_moves")
}

data class AppPrefs(
    val levelIndex: Int = 3,
    val searchTimeMs: Int = 3000,
    val multiPv: Int = 3,
    val threads: Int = 1,
    val playAsWhite: Boolean = true,
    val showArrows: Boolean = true,
)

enum class GameMode { OTB, VS_ENGINE, AI_VS_AI }

enum class OtbAnalysisModel(val displayName: String) {
    RYZIX("Ryzix Engine"),
    STOCKFISH("Stockfish 16"),
}

enum class MoveGrade(val label: String, val symbol: String, val colorHex: Long, val iconFilename: String) {
    BRILLIANT ("Brilliant",  "!!",  0xFF00BCD4, "brilliant.png"),
    BEST      ("Best Move",  "!",   0xFF4CAF50, "best.png"),
    EXCELLENT ("Excellent",  "!",   0xFF8BC34A, "excellent.png"),
    GREAT     ("Great",      "+",   0xFF8BC34A, "great.png"),
    GOOD      ("Good",       "+",   0xFF69AA29, "good.png"),
    INACCURACY("Inaccuracy", "?!",  0xFFFF9800, "inaccuracy.png"),
    MISTAKE   ("Mistake",    "?",   0xFFFF5722, "mistake.png"),
    BLUNDER   ("Blunder",    "??",  0xFFF44336, "blunder.png"),
    MISS      ("Miss",       "??",  0xFFF44336, "miss.png"),
}

data class MoveGradeResult(
    val grade: MoveGrade,
    val playedUci: String,
    val bestUci: String,
    val cpLoss: Float,
)

data class SavedGame(
    val result: String,
    val moveCount: Int,
    val timestamp: Long,
    val movesUci: String = "",
) {
    fun toRecord() = "$result|$moveCount|$timestamp|$movesUci"

    fun generatePgn(): String {
        if (movesUci.isBlank()) return "[Result \"$result\"]\n\n$result"
        val game = ChessGame()
        movesUci.split(",").forEach { uci ->
            if (uci.length >= 4) game.tryMove(uci.take(2), uci.drop(2).take(2), if (uci.length >= 5) uci[4] else 'q')
        }
        return game.generatePgn(result)
    }

    companion object {
        fun fromRecord(s: String): SavedGame? {
            val p = s.split("|", limit = 4)
            if (p.size < 3) return null
            return SavedGame(p[0], p[1].toIntOrNull() ?: 0, p[2].toLongOrNull() ?: 0L, if (p.size >= 4) p[3] else "")
        }
    }
}

data class AiVsAiState(
    val sfPlaysWhite: Boolean  = true,
    val whiteThinking: Boolean = false,
    val blackThinking: Boolean = false,
    val whiteLabel: String     = "Stockfish 16",
    val blackLabel: String     = "Ryzix 5000",
    val isActive: Boolean      = false,
)

// ── Opening book ─────────────────────────────────────────────────────────────
// Key: "boardPart sideToMove" (first two FEN tokens)
// Value: UCI move for Ryzix to play

private val LONDON_BOOK_WHITE = mapOf(
    // Move 1: d2d4
    "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w" to "d2d4",
    // Move 2: Nf3 — after various Black first moves
    "rnbqkbnr/ppp1pppp/8/3p4/3P4/8/PPP1PPPP/RNBQKBNR w" to "g1f3",   // 1...d5
    "rnbqkb1r/pppppppp/5n2/8/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...Nf6
    "rnbqkbnr/pppp1ppp/4p3/8/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...e6
    "rnbqkbnr/ppp1pppp/3p4/8/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...d6
    "rnbqkbnr/pp1ppppp/8/2p5/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...c5
    "rnbqkbnr/pp1ppppp/2p5/8/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...c6
    "rnbqkbnr/ppppp1pp/8/5p2/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...f5
    "rnbqkbnr/pppppp1p/6p1/8/3P4/8/PPP1PPPP/RNBQKBNR w"  to "g1f3",   // 1...g6
    "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR w"    to "g1f3",   // (never reached — black to move)
    // Move 3: Bf4 — after various 2nd Black moves
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w" to "c1f4", // 1.d4 d5 2.Nf3 Nf6
    "rnbqkbnr/ppp2ppp/4p3/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w"  to "c1f4", // 1.d4 d5 2.Nf3 e6
    "rnbqkbnr/pp2pppp/2p5/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w"  to "c1f4", // 1.d4 d5 2.Nf3 c6
    "rnbqkb1r/pppppppp/5n2/8/3P4/5N2/PPP1PPPP/RNBQKB1R w"   to "c1f4", // 1.d4 Nf6 2.Nf3
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P4/5N2/PPP1PPPP/RNBQKB1R w" to "c1f4", // 1.d4 Nf6 2.Nf3 d5
    "rnbqkb1r/pppp1ppp/4pn2/8/3P4/5N2/PPP1PPPP/RNBQKB1R w"  to "c1f4", // 1.d4 Nf6 2.Nf3 e6
    // Move 4: e3
    "rnbqkb1r/ppp1pppp/5n2/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R w" to "e2e3",
    "rnbqkbnr/ppp2ppp/4p3/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R w"  to "e2e3",
    "rnbqkb1r/ppp2ppp/4pn2/3p4/3P1B2/5N2/PPP1PPPP/RN1QKB1R w" to "e2e3",
    "rnbqkb1r/pppp1ppp/4pn2/8/3P1B2/5N2/PPP1PPPP/RN1QKB1R w"  to "e2e3",
)

private val CARO_KANN_BOOK_BLACK = mapOf(
    // 1...c6 vs 1.e4
    "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b"     to "c7c6",
    // 2...d5 vs 2.d4
    "rnbqkbnr/pp1ppppp/2p5/8/3PP3/8/PPP2PPP/RNBQKBNR b"   to "d7d5",
    // Exchange var 3.Nc3 or 3.Nd2 → dxe4
    "rnbqkbnr/pp2pppp/2p5/3p4/3PP3/2N5/PPP2PPP/R1BQKBNR b" to "d5e4",
    "rnbqkbnr/pp2pppp/2p5/3p4/3PP3/8/PPPN1PPP/R1BQKBNR b"  to "d5e4",
    // Advance 3.e5 → Bf5
    "rnbqkbnr/pp2pppp/2p5/3pP3/3P4/8/PPP2PPP/RNBQKBNR b"  to "c8f5",
    // Exchange 3.exd5 → cxd5
    "rnbqkbnr/pp2pppp/2p5/3P4/3P4/8/PPP2PPP/RNBQKBNR b"   to "c6d5",
    // vs 1.d4 → d5
    "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b"     to "d7d5",
    // vs 1.Nf3 → d5
    "rnbqkbnr/pppppppp/8/8/8/5N2/PPPPPPPP/RNBQKB1R b"     to "d7d5",
    // vs 1.c4 → e6 (Queen's Indian style)
    "rnbqkbnr/pppppppp/8/8/2P5/8/PP1PPPPP/RNBQKBNR b"     to "e7e6",
    // Caro-Kann 4th move continuations (after 3...dxe4)
    "rnbqkbnr/pp2pppp/2p5/8/3Pp3/2N5/PPP2PPP/R1BQKBNR w"  to "c3e4",   // white takes back
)

class GameViewModel(application: Application) : AndroidViewModel(application) {

    val chessGame = ChessGame()
    val gameState: StateFlow<GameState> = chessGame.state

    // ── Engines ──────────────────────────────────────────────────────────────
    private val engine        = RyzixEngine(application, "libryzix.so")
    private val sfAiEngine    = RyzixEngine(application, "libstockfish.so")
    private val ryzixAiEngine = RyzixEngine(application, "libryzix.so")

    private val soundManager = SoundManager(application)
    private var engineReady    = false
    private var sfAiReady      = false
    private var ryzixAiReady   = false

    // ── Exposed state ─────────────────────────────────────────────────────────
    private val _engineEval       = MutableStateFlow(0f)
    val engineEval: StateFlow<Float> = _engineEval

    private val _isEngineThinking = MutableStateFlow(false)
    val isEngineThinking: StateFlow<Boolean> = _isEngineThinking

    private val _engineEnabled    = MutableStateFlow(true)
    val engineEnabled: StateFlow<Boolean> = _engineEnabled

    private val _analysisLines    = MutableStateFlow<List<AnalysisLine>>(emptyList())
    val analysisLines: StateFlow<List<AnalysisLine>> = _analysisLines

    private val _lastMoveGrade    = MutableStateFlow<MoveGradeResult?>(null)
    val lastMoveGrade: StateFlow<MoveGradeResult?> = _lastMoveGrade

    private val _engineAvailable  = MutableStateFlow(false)
    val engineAvailable: StateFlow<Boolean> = _engineAvailable

    private val _prefs            = MutableStateFlow(AppPrefs())
    val prefs: StateFlow<AppPrefs> = _prefs

    private val _promotionPending = MutableStateFlow<Pair<String, String>?>(null)
    val promotionPending: StateFlow<Pair<String, String>?> = _promotionPending

    private val _isOtbMode        = MutableStateFlow(true)
    val isOtbMode: StateFlow<Boolean> = _isOtbMode

    private val _playerIsWhite    = MutableStateFlow(true)
    val playerIsWhite: StateFlow<Boolean> = _playerIsWhite

    private val _gameHistory      = MutableStateFlow<List<SavedGame>>(emptyList())
    val gameHistory: StateFlow<List<SavedGame>> = _gameHistory

    private val _isReviewMode     = MutableStateFlow(false)
    val isReviewMode: StateFlow<Boolean> = _isReviewMode

    private val _gameMode         = MutableStateFlow(GameMode.OTB)
    val gameMode: StateFlow<GameMode> = _gameMode

    private val _aiVsAiState      = MutableStateFlow(AiVsAiState())
    val aiVsAiState: StateFlow<AiVsAiState> = _aiVsAiState

    private val _otbAnalysisModel = MutableStateFlow(OtbAnalysisModel.RYZIX)
    val otbAnalysisModel: StateFlow<OtbAnalysisModel> = _otbAnalysisModel

    @Volatile private var isGradingPending   = false
    @Volatile private var preMoveEval        = 0f
    @Volatile private var preMoveLines: List<AnalysisLine> = emptyList()
    @Volatile private var engineMoveInFlight = false
    private var gameInitialized = false
    private val gradeCache = mutableMapOf<String, MoveGradeResult>()

    init {
        loadPrefs()
        initMainEngine()
        collectMainEngineOutput()
        collectAiVsAiOutput()
    }

    // ── Prefs ─────────────────────────────────────────────────────────────────
    private fun loadPrefs() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data.collect { data ->
                _prefs.value = AppPrefs(
                    levelIndex   = data[PrefKeys.LEVEL]          ?: 3,
                    searchTimeMs = data[PrefKeys.SEARCH_TIME]    ?: 3000,
                    multiPv      = data[PrefKeys.MULTI_PV]       ?: 3,
                    threads      = data[PrefKeys.THREADS]         ?: 1,
                    playAsWhite  = (data[PrefKeys.PLAY_AS_WHITE]  ?: 1) == 1,
                    showArrows   = (data[PrefKeys.SHOW_ARROWS]    ?: 1) == 1,
                )
                val raw = data[PrefKeys.GAME_HISTORY] ?: ""
                _gameHistory.value = raw.split("\n").filter { it.isNotBlank() }
                    .mapNotNull { SavedGame.fromRecord(it) }
                if (!gameInitialized) {
                    val saved = data[PrefKeys.CURRENT_GAME_MOVES] ?: ""
                    if (saved.isNotBlank()) chessGame.loadFromMoves(saved)
                    gameInitialized = true
                }
            }
        }
    }

    private fun initMainEngine() {
        viewModelScope.launch {
            engineReady = engine.init()
            _engineAvailable.value = engineReady
            if (engineReady) {
                val s = STOCKFISH_LEVELS.getOrElse(_prefs.value.levelIndex) { STOCKFISH_LEVELS[3] }
                engine.applySettings(s)
                delay(300)
                runAnalysis()
            }
        }
    }

    // ── Main engine collector (OTB + VS_ENGINE) ───────────────────────────────
    private fun collectMainEngineOutput() {
        viewModelScope.launch {
            engine.bestMoveFlow.collect { bestMove ->
                if (_gameMode.value == GameMode.AI_VS_AI) return@collect
                _isEngineThinking.value = false
                engineMoveInFlight = false

                if (_isOtbMode.value && _engineEnabled.value && _prefs.value.showArrows && _analysisLines.value.isNotEmpty())
                    updateArrows(_analysisLines.value)

                if (_isOtbMode.value && isGradingPending && _analysisLines.value.isNotEmpty()) {
                    isGradingPending = false
                    gradeLastMove(_analysisLines.value.firstOrNull()?.eval ?: _engineEval.value)
                }

                if (!_isOtbMode.value && _gameMode.value == GameMode.VS_ENGINE && !gameState.value.isGameOver
                    && !gameState.value.isInHistoricalView) {
                    val isEngineTurn = if (_playerIsWhite.value) !gameState.value.isWhiteTurn
                                       else gameState.value.isWhiteTurn
                    if (isEngineTurn) {
                        val from  = bestMove.take(2)
                        val to    = bestMove.drop(2).take(2)
                        val promo = if (bestMove.length >= 5) bestMove[4] else 'q'
                        delay(300)
                        chessGame.tryMove(from, to, promo)
                        soundManager.play("move")
                        if (gameState.value.isGameOver) { soundManager.play("confirmation"); saveCurrentGame() }
                    }
                }
            }
        }
        viewModelScope.launch { engine.evalFlow.collect     { _engineEval.value    = it } }
        viewModelScope.launch { engine.analysisFlow.collect { _analysisLines.value = it } }
    }

    // ── AI vs AI collectors ───────────────────────────────────────────────────
    private fun collectAiVsAiOutput() {
        viewModelScope.launch {
            sfAiEngine.bestMoveFlow.collect { bestMove ->
                if (_gameMode.value != GameMode.AI_VS_AI) return@collect
                if (gameState.value.isGameOver) return@collect
                if (gameState.value.isInHistoricalView) return@collect
                val isWhite = gameState.value.isWhiteTurn
                _aiVsAiState.value = _aiVsAiState.value.copy(
                    whiteThinking = if (isWhite) false else _aiVsAiState.value.whiteThinking,
                    blackThinking = if (!isWhite) false else _aiVsAiState.value.blackThinking,
                )
                handleAiVsAiMove(bestMove)
            }
        }
        viewModelScope.launch {
            ryzixAiEngine.bestMoveFlow.collect { bestMove ->
                if (_gameMode.value != GameMode.AI_VS_AI) return@collect
                if (gameState.value.isGameOver) return@collect
                if (gameState.value.isInHistoricalView) return@collect
                val isWhite = gameState.value.isWhiteTurn
                _aiVsAiState.value = _aiVsAiState.value.copy(
                    whiteThinking = if (isWhite) false else _aiVsAiState.value.whiteThinking,
                    blackThinking = if (!isWhite) false else _aiVsAiState.value.blackThinking,
                )
                handleAiVsAiMove(bestMove)
            }
        }
    }

    private suspend fun handleAiVsAiMove(bestMove: String) {
        if (gameState.value.isGameOver || gameState.value.isInHistoricalView) return
        val from  = bestMove.take(2)
        val to    = bestMove.drop(2).take(2)
        val promo = if (bestMove.length >= 5) bestMove[4] else 'q'
        chessGame.tryMove(from, to, promo)
        soundManager.play("move")
        if (gameState.value.isGameOver) {
            soundManager.play("confirmation"); saveCurrentGame(); return
        }
        scheduleNextAiMove()
    }

    private fun scheduleNextAiMove() {
        if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver) return
        if (gameState.value.isInHistoricalView) return

        val isWhiteTurn  = gameState.value.isWhiteTurn
        val sfPlaysWhite = _aiVsAiState.value.sfPlaysWhite
        val isSfTurn     = (isWhiteTurn == sfPlaysWhite)

        viewModelScope.launch {
            val delayMs = if (isSfTurn) (600L..1500L).random() else (800L..2000L).random()
            _aiVsAiState.value = _aiVsAiState.value.copy(
                whiteThinking = isWhiteTurn,
                blackThinking = !isWhiteTurn,
            )
            delay(delayMs)

            if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver
                || gameState.value.isInHistoricalView) {
                _aiVsAiState.value = _aiVsAiState.value.copy(whiteThinking = false, blackThinking = false)
                return@launch
            }

            val currentIsWhiteTurn = gameState.value.isWhiteTurn
            val currentIsSfTurn    = (currentIsWhiteTurn == _aiVsAiState.value.sfPlaysWhite)

            val fen      = chessGame.getCurrentFen()

            // Check opening book for Ryzix in battle
            if (!currentIsSfTurn) {
                val bookKey   = fen.split(" ").take(2).joinToString(" ")
                val bookMove  = if (_aiVsAiState.value.sfPlaysWhite) CARO_KANN_BOOK_BLACK[bookKey]
                                else LONDON_BOOK_WHITE[bookKey]
                if (bookMove != null) {
                    val bFrom  = bookMove.take(2)
                    val bTo    = bookMove.drop(2).take(2)
                    val bPromo = if (bookMove.length >= 5) bookMove[4] else 'q'
                    chessGame.tryMove(bFrom, bTo, bPromo)
                    soundManager.play("move")
                    if (gameState.value.isGameOver) { soundManager.play("confirmation"); saveCurrentGame() }
                    else scheduleNextAiMove()
                    return@launch
                }
            }

            // Use engine — SF16 at max, Ryzix at max
            val settings = if (currentIsSfTurn) SF_BATTLE_SETTINGS else RYZIX_BATTLE_SETTINGS
            val aiEngine = if (currentIsSfTurn) sfAiEngine else ryzixAiEngine
            aiEngine.applySettings(settings)
            aiEngine.startSearch(fen, settings)
        }
    }

    // ── Start AI vs AI ────────────────────────────────────────────────────────
    fun startAiVsAi(sfPlaysWhite: Boolean, thinkSecs: Int = 5) {
        engine.stop(); sfAiEngine.stop(); ryzixAiEngine.stop()
        _isEngineThinking.value  = false
        engineMoveInFlight       = false
        _engineEval.value        = 0f
        _analysisLines.value     = emptyList()
        _lastMoveGrade.value     = null
        _promotionPending.value  = null
        _isReviewMode.value      = false
        _isOtbMode.value         = false
        _gameMode.value          = GameMode.AI_VS_AI
        isGradingPending         = false
        gradeCache.clear()
        chessGame.reset()
        chessGame.clearArrows()
        chessGame.setFlipped(false)

        val whiteLabel = if (sfPlaysWhite) "Stockfish 16" else "Ryzix 5000"
        val blackLabel = if (sfPlaysWhite) "Ryzix 5000"   else "Stockfish 16"
        _aiVsAiState.value = AiVsAiState(
            sfPlaysWhite  = sfPlaysWhite,
            whiteThinking = false,
            blackThinking = false,
            whiteLabel    = whiteLabel,
            blackLabel    = blackLabel,
            isActive      = true,
        )

        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PrefKeys.CURRENT_GAME_MOVES] = "" }
        }

        viewModelScope.launch {
            if (!sfAiReady) {
                sfAiReady = sfAiEngine.init()
                if (sfAiReady) sfAiReady = sfAiEngine.waitForReady(8000)
            }
            if (!ryzixAiReady) {
                ryzixAiReady = ryzixAiEngine.init()
                if (ryzixAiReady) ryzixAiReady = ryzixAiEngine.waitForReady(5000)
            }
            if (!sfAiReady || !ryzixAiReady) return@launch
            scheduleNextAiMove()
        }
    }

    fun stopAiVsAi() {
        sfAiEngine.stop(); ryzixAiEngine.stop()
        _aiVsAiState.value = _aiVsAiState.value.copy(whiteThinking = false, blackThinking = false, isActive = false)
    }

    fun resumeAiVsAi() {
        if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver) return
        if (gameState.value.isInHistoricalView) chessGame.navigateToLive()
        _aiVsAiState.value = _aiVsAiState.value.copy(isActive = true)
        scheduleNextAiMove()
    }

    // ── OTB model ─────────────────────────────────────────────────────────────
    fun setOtbAnalysisModel(model: OtbAnalysisModel) { _otbAnalysisModel.value = model }

    // ── Move grading ──────────────────────────────────────────────────────────
    private fun gradeLastMove(postEval: Float) {
        val fen = chessGame.getCurrentFen()
        gradeCache[fen]?.let { _lastMoveGrade.value = it; return }
        val cpLoss     = preMoveEval + postEval
        val preBestUci = preMoveLines.firstOrNull()?.move ?: ""
        val lastMove   = gameState.value.moves.lastOrNull()
        val playedUci  = if (lastMove != null) "${lastMove.from}${lastMove.to}" else ""
        val isBestMove = playedUci.take(4) == preBestUci.take(4)
        val grade = when {
            isBestMove && cpLoss <= 0f  -> MoveGrade.BRILLIANT
            isBestMove                  -> MoveGrade.BEST
            cpLoss <= 0.10f             -> MoveGrade.EXCELLENT
            cpLoss <= 0.25f             -> MoveGrade.GREAT
            cpLoss <= 0.50f             -> MoveGrade.GOOD
            cpLoss <= 1.00f             -> MoveGrade.INACCURACY
            cpLoss <= 2.00f             -> MoveGrade.MISTAKE
            else                        -> MoveGrade.BLUNDER
        }
        val result = MoveGradeResult(grade, playedUci, preBestUci, cpLoss)
        gradeCache[fen] = result
        _lastMoveGrade.value = result
    }

    private fun updateArrows(lines: List<AnalysisLine>) {
        val arrows = lines.take(3).mapIndexedNotNull { idx, line ->
            val from = line.move.take(2).takeIf { it.length == 2 } ?: return@mapIndexedNotNull null
            val to   = line.move.drop(2).take(2).takeIf { it.length == 2 } ?: return@mapIndexedNotNull null
            Arrow(from, to, when (idx) { 0 -> ArrowColor.GREEN; 1 -> ArrowColor.YELLOW; else -> ArrowColor.RED })
        }
        chessGame.setArrows(arrows)
    }

    private fun runAnalysis() {
        if (!engineReady || !_engineEnabled.value) return
        val base     = STOCKFISH_LEVELS.getOrElse(_prefs.value.levelIndex) { STOCKFISH_LEVELS[3] }
        val settings = base.copy(multiPv = maxOf(3, _prefs.value.multiPv), searchTimeMs = maxOf(3000, base.searchTimeMs))
        engine.applySettings(settings)
        _isEngineThinking.value = true
        engine.startAnalysis(chessGame.getCurrentFen(), settings)
    }

    // ── Board interaction (OTB + VS_ENGINE only) ──────────────────────────────
    fun onSquareTap(square: String) {
        if (_gameMode.value == GameMode.AI_VS_AI) return
        val state = gameState.value
        if (state.isGameOver || _isReviewMode.value || state.isInHistoricalView) return
        if (!_isOtbMode.value) {
            val isPlayerTurn = if (_playerIsWhite.value) state.isWhiteTurn else !state.isWhiteTurn
            if (!isPlayerTurn || engineMoveInFlight) return
        }
        val prevSelected = state.selectedSquare
        if (prevSelected != null && state.legalMoves.contains(square)) {
            if (chessGame.isPromotionMove(prevSelected, square)) {
                _promotionPending.value = Pair(prevSelected, square); chessGame.selectSquare(prevSelected); return
            }
            val moved = chessGame.tryMove(prevSelected, square)
            if (moved) {
                val isCapture = gameState.value.moves.lastOrNull()?.isCapture ?: false
                soundManager.play(if (isCapture) "capture" else "move")
                chessGame.clearArrows()
                persistCurrentGame()
                if (_isOtbMode.value) {
                    isGradingPending = engineReady && _engineEnabled.value
                    preMoveEval  = _engineEval.value
                    preMoveLines = _analysisLines.value
                    _lastMoveGrade.value = null
                    _analysisLines.value = emptyList()
                    _engineEval.value = 0f
                    runAnalysis()
                    if (gameState.value.isGameOver) { soundManager.play("confirmation"); saveCurrentGame() }
                } else {
                    _analysisLines.value = emptyList()
                    _engineEval.value = 0f
                    triggerEngineMove()
                }
                return
            }
        }
        chessGame.selectSquare(square)
    }

    fun confirmPromotion(promoChar: Char) {
        val pending = _promotionPending.value ?: return
        _promotionPending.value = null
        val moved = chessGame.tryMove(pending.first, pending.second, promoChar)
        if (moved) {
            soundManager.play("move"); chessGame.clearArrows(); persistCurrentGame()
            if (_isOtbMode.value) {
                isGradingPending = engineReady && _engineEnabled.value
                preMoveEval  = _engineEval.value
                preMoveLines = _analysisLines.value
                _lastMoveGrade.value = null
                _analysisLines.value = emptyList()
                _engineEval.value = 0f
                runAnalysis()
                if (gameState.value.isGameOver) { soundManager.play("confirmation"); saveCurrentGame() }
            } else {
                _analysisLines.value = emptyList()
                _engineEval.value = 0f
                triggerEngineMove()
            }
        }
    }

    fun cancelPromotion() { _promotionPending.value = null }

    fun triggerEngineMove() {
        if (!engineReady || gameState.value.isGameOver || gameState.value.isInHistoricalView) return

        // Check opening book first
        val fen     = chessGame.getCurrentFen()
        val bookKey = fen.split(" ").take(2).joinToString(" ")
        val book    = if (_playerIsWhite.value) CARO_KANN_BOOK_BLACK else LONDON_BOOK_WHITE
        val bookMove = book[bookKey]

        if (bookMove != null) {
            viewModelScope.launch {
                delay(400L + (100L..600L).random())
                if (gameState.value.isGameOver || gameState.value.isInHistoricalView) return@launch
                val bFrom  = bookMove.take(2)
                val bTo    = bookMove.drop(2).take(2)
                val bPromo = if (bookMove.length >= 5) bookMove[4] else 'q'
                val moved  = chessGame.tryMove(bFrom, bTo, bPromo)
                if (moved) {
                    soundManager.play("move")
                    if (gameState.value.isGameOver) { soundManager.play("confirmation"); saveCurrentGame() }
                }
            }
            return
        }

        val settings = STOCKFISH_LEVELS.getOrElse(_prefs.value.levelIndex) { STOCKFISH_LEVELS[3] }
        engineMoveInFlight = true
        _isEngineThinking.value = true
        engine.applySettings(settings)
        engine.startSearch(fen, settings)
    }

    fun toggleEngine() {
        _engineEnabled.value = !_engineEnabled.value
        if (_engineEnabled.value) runAnalysis()
        else { engine.stop(); _isEngineThinking.value = false; _analysisLines.value = emptyList(); chessGame.clearArrows() }
    }

    // ── New game ──────────────────────────────────────────────────────────────
    fun newGame(otbMode: Boolean = true, playerIsWhite: Boolean = true) {
        if (_gameMode.value == GameMode.AI_VS_AI) { sfAiEngine.stop(); ryzixAiEngine.stop() }
        engine.stop()
        _isEngineThinking.value = false; engineMoveInFlight = false
        _engineEval.value = 0f; _analysisLines.value = emptyList()
        _lastMoveGrade.value = null; _promotionPending.value = null
        _isReviewMode.value = false; isGradingPending = false
        _isOtbMode.value = otbMode; _playerIsWhite.value = playerIsWhite
        _gameMode.value = if (otbMode) GameMode.OTB else GameMode.VS_ENGINE
        _aiVsAiState.value = AiVsAiState(); gradeCache.clear()
        chessGame.reset(); chessGame.clearArrows(); chessGame.setFlipped(!playerIsWhite)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PrefKeys.CURRENT_GAME_MOVES] = "" }
        }
        viewModelScope.launch {
            delay(400)
            if (otbMode) runAnalysis() else if (!playerIsWhite) triggerEngineMove()
        }
    }

    fun navigateBack() {
        if (_gameMode.value == GameMode.AI_VS_AI && _aiVsAiState.value.isActive) stopAiVsAi()
        engine.stop(); _isEngineThinking.value = false; _lastMoveGrade.value = null
        chessGame.navigateBack(); chessGame.clearArrows()
        if (_engineEnabled.value && _isOtbMode.value) runAnalysis()
    }

    fun navigateForward() {
        _lastMoveGrade.value = null
        chessGame.navigateForward(); chessGame.clearArrows()
        gradeCache[gameState.value.fen]?.let { _lastMoveGrade.value = it }
        if (_engineEnabled.value && _isOtbMode.value) runAnalysis()
    }

    fun undoOtbMove() {
        if (_isReviewMode.value) return
        engine.stop(); _isEngineThinking.value = false; _lastMoveGrade.value = null
        chessGame.undoMove()
        if (!_isOtbMode.value) chessGame.undoMove()
        chessGame.clearArrows(); persistCurrentGame()
        if (_engineEnabled.value && _isOtbMode.value) runAnalysis()
    }

    fun flipBoard() { chessGame.flipBoard() }

    // ── History ───────────────────────────────────────────────────────────────
    fun loadGameFromHistory(game: SavedGame) {
        if (_gameMode.value == GameMode.AI_VS_AI) { sfAiEngine.stop(); ryzixAiEngine.stop() }
        engine.stop(); _isEngineThinking.value = false
        _analysisLines.value = emptyList(); _lastMoveGrade.value = null
        _promotionPending.value = null; _isReviewMode.value = true
        _isOtbMode.value = true; _gameMode.value = GameMode.OTB; _aiVsAiState.value = AiVsAiState()
        if (game.movesUci.isNotBlank()) chessGame.loadFromMoves(game.movesUci) else chessGame.reset()
        viewModelScope.launch { delay(200); if (_engineEnabled.value) runAnalysis() }
    }

    fun continueGameFromHistory(game: SavedGame) {
        if (_gameMode.value == GameMode.AI_VS_AI) { sfAiEngine.stop(); ryzixAiEngine.stop() }
        engine.stop(); _isEngineThinking.value = false
        _analysisLines.value = emptyList(); _lastMoveGrade.value = null
        _promotionPending.value = null; _isReviewMode.value = false
        _isOtbMode.value = true; _gameMode.value = GameMode.OTB
        _aiVsAiState.value = AiVsAiState(); gradeCache.clear()
        if (game.movesUci.isNotBlank()) chessGame.loadFromMoves(game.movesUci) else chessGame.reset()
        viewModelScope.launch { delay(200); if (_engineEnabled.value) runAnalysis() }
    }

    fun clearHistory() {
        viewModelScope.launch {
            _gameHistory.value = emptyList()
            getApplication<Application>().dataStore.edit { it[PrefKeys.GAME_HISTORY] = "" }
        }
    }

    fun loadGameFromPgn(pgn: String): Boolean {
        val ok = chessGame.loadFromPgn(pgn)
        if (ok) {
            if (_gameMode.value == GameMode.AI_VS_AI) { sfAiEngine.stop(); ryzixAiEngine.stop() }
            engine.stop(); _isEngineThinking.value = false
            _analysisLines.value = emptyList(); _lastMoveGrade.value = null
            _promotionPending.value = null; _isReviewMode.value = true
            _isOtbMode.value = true; _gameMode.value = GameMode.OTB; _aiVsAiState.value = AiVsAiState()
            viewModelScope.launch { delay(200); if (_engineEnabled.value) runAnalysis() }
        }
        return ok
    }

    fun getCurrentGamePgn(): String = chessGame.generatePgn(gameState.value.gameResult ?: "*")

    fun sharePgn(pgn: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Chess Game PGN")
            putExtra(Intent.EXTRA_TEXT, pgn)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        getApplication<Application>().startActivity(
            Intent.createChooser(intent, "Export PGN").also { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    fun hasActiveGame(): Boolean =
        gameState.value.moves.isNotEmpty() && !gameState.value.isGameOver && !_isReviewMode.value

    fun saveGameInProgress() {
        val movesUci = chessGame.getMovesAsUci()
        if (movesUci.isBlank()) return
        val s    = gameState.value
        val game = SavedGame("*", s.moves.size, System.currentTimeMillis(), movesUci)
        viewModelScope.launch {
            val current = _gameHistory.value.toMutableList()
            current.removeAll { it.result == "*" }; current.add(0, game)
            val kept = current.take(50); _gameHistory.value = kept
            getApplication<Application>().dataStore.edit {
                it[PrefKeys.GAME_HISTORY] = kept.joinToString("\n") { g -> g.toRecord() }
            }
        }
    }

    private fun saveCurrentGame() {
        val movesUci = chessGame.getMovesAsUci()
        val s    = gameState.value
        val game = SavedGame(s.gameResult ?: "*", s.moves.size, System.currentTimeMillis(), movesUci)
        viewModelScope.launch {
            val current = _gameHistory.value.toMutableList()
            current.removeAll { it.result == "*" }; current.add(0, game)
            val kept = current.take(50); _gameHistory.value = kept
            getApplication<Application>().dataStore.edit {
                it[PrefKeys.GAME_HISTORY] = kept.joinToString("\n") { g -> g.toRecord() }
            }
        }
    }

    private fun persistCurrentGame() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it[PrefKeys.CURRENT_GAME_MOVES] = chessGame.getMovesAsUci()
            }
        }
    }

    fun saveLevel(index: Int) {
        viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.LEVEL] = index } }
        engine.stop(); if (_isOtbMode.value) runAnalysis()
    }
    fun saveSearchTime(ms: Int) { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.SEARCH_TIME] = ms } } }
    fun saveMultiPv(n: Int)     { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.MULTI_PV]    = n  } } }
    fun saveThreads(n: Int)     { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.THREADS]     = n  } } }

    override fun onCleared() {
        super.onCleared()
        engine.quit(); sfAiEngine.quit(); ryzixAiEngine.quit(); soundManager.release()
    }
}
