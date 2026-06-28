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

// ── Game modes ─────────────────────────────────────────────────────────────────
enum class GameMode { OTB, VS_ENGINE, AI_VS_AI }

// OTB analysis model (same .so binary, different display label)
enum class OtbAnalysisModel(val displayName: String) {
    RYZIX("Ryzix Engine"),
    STOCKFISH("Stockfish 16"),
}

// ── Move grade ─────────────────────────────────────────────────────────────────
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

// ── Saved game ─────────────────────────────────────────────────────────────────
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

// ── AI vs AI thinking state ────────────────────────────────────────────────────
data class AiVsAiState(
    val sfPlaysWhite: Boolean = true,
    val whiteThinking: Boolean = false,
    val blackThinking: Boolean = false,
    val whiteLabel: String = "Stockfish 16",
    val blackLabel: String = "Ryzix 1000",
    val isActive: Boolean = false,
)

// ── ViewModel ──────────────────────────────────────────────────────────────────
class GameViewModel(application: Application) : AndroidViewModel(application) {

    val chessGame = ChessGame()
    val gameState: StateFlow<GameState> = chessGame.state

    // Main engine — used for OTB analysis and VS_ENGINE mode
    private val engine = RyzixEngine(application)
    private val soundManager = SoundManager(application)
    private var engineReady = false

    // AI vs AI engines — each spawns its own libryzix.so process
    private val aiWhiteEngine = RyzixEngine(application)
    private val aiBlackEngine = RyzixEngine(application)
    private var aiWhiteReady = false
    private var aiBlackReady = false

    // ── Exposed state ────────────────────────────────────────────────────────

    private val _engineEval = MutableStateFlow(0f)
    val engineEval: StateFlow<Float> = _engineEval

    private val _isEngineThinking = MutableStateFlow(false)
    val isEngineThinking: StateFlow<Boolean> = _isEngineThinking

    private val _engineEnabled = MutableStateFlow(true)
    val engineEnabled: StateFlow<Boolean> = _engineEnabled

    private val _analysisLines = MutableStateFlow<List<AnalysisLine>>(emptyList())
    val analysisLines: StateFlow<List<AnalysisLine>> = _analysisLines

    private val _lastMoveGrade = MutableStateFlow<MoveGradeResult?>(null)
    val lastMoveGrade: StateFlow<MoveGradeResult?> = _lastMoveGrade

    private val _engineAvailable = MutableStateFlow(false)
    val engineAvailable: StateFlow<Boolean> = _engineAvailable

    private val _prefs = MutableStateFlow(AppPrefs())
    val prefs: StateFlow<AppPrefs> = _prefs

    private val _promotionPending = MutableStateFlow<Pair<String, String>?>(null)
    val promotionPending: StateFlow<Pair<String, String>?> = _promotionPending

    private val _isOtbMode = MutableStateFlow(true)
    val isOtbMode: StateFlow<Boolean> = _isOtbMode

    private val _playerIsWhite = MutableStateFlow(true)
    val playerIsWhite: StateFlow<Boolean> = _playerIsWhite

    private val _gameHistory = MutableStateFlow<List<SavedGame>>(emptyList())
    val gameHistory: StateFlow<List<SavedGame>> = _gameHistory

    private val _isReviewMode = MutableStateFlow(false)
    val isReviewMode: StateFlow<Boolean> = _isReviewMode

    private val _gameMode = MutableStateFlow(GameMode.OTB)
    val gameMode: StateFlow<GameMode> = _gameMode

    private val _aiVsAiState = MutableStateFlow(AiVsAiState())
    val aiVsAiState: StateFlow<AiVsAiState> = _aiVsAiState

    private val _otbAnalysisModel = MutableStateFlow(OtbAnalysisModel.RYZIX)
    val otbAnalysisModel: StateFlow<OtbAnalysisModel> = _otbAnalysisModel

    @Volatile private var isGradingPending = false
    @Volatile private var preMoveEval = 0f
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

    // ── Prefs & init ────────────────────────────────────────────────────────

    private fun loadPrefs() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.data.collect { data ->
                _prefs.value = AppPrefs(
                    levelIndex   = data[PrefKeys.LEVEL] ?: 3,
                    searchTimeMs = data[PrefKeys.SEARCH_TIME] ?: 3000,
                    multiPv      = data[PrefKeys.MULTI_PV] ?: 3,
                    threads      = data[PrefKeys.THREADS] ?: 1,
                    playAsWhite  = (data[PrefKeys.PLAY_AS_WHITE] ?: 1) == 1,
                    showArrows   = (data[PrefKeys.SHOW_ARROWS] ?: 1) == 1,
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

    // ── Main engine output collector ────────────────────────────────────────

    private fun collectMainEngineOutput() {
        viewModelScope.launch {
            engine.bestMoveFlow.collect { bestMove ->
                // Ignore in AI vs AI mode — those engines have their own collectors
                if (_gameMode.value == GameMode.AI_VS_AI) return@collect

                _isEngineThinking.value = false
                engineMoveInFlight = false

                // Arrows only in OTB
                if (_isOtbMode.value && _engineEnabled.value && _prefs.value.showArrows && _analysisLines.value.isNotEmpty()) {
                    updateArrows(_analysisLines.value)
                }

                // Move grading only in OTB
                if (_isOtbMode.value && isGradingPending && _analysisLines.value.isNotEmpty()) {
                    isGradingPending = false
                    gradeLastMove(_analysisLines.value.firstOrNull()?.eval ?: _engineEval.value)
                }

                // VS_ENGINE: engine plays its turn automatically
                if (!_isOtbMode.value && _gameMode.value == GameMode.VS_ENGINE && !gameState.value.isGameOver) {
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
        viewModelScope.launch { engine.evalFlow.collect     { _engineEval.value     = it } }
        viewModelScope.launch { engine.analysisFlow.collect { _analysisLines.value  = it } }
    }

    // ── AI vs AI engine output collectors ──────────────────────────────────

    private fun collectAiVsAiOutput() {
        // White engine collector
        viewModelScope.launch {
            aiWhiteEngine.bestMoveFlow.collect { bestMove ->
                if (_gameMode.value != GameMode.AI_VS_AI) return@collect
                if (!gameState.value.isWhiteTurn || gameState.value.isGameOver) return@collect
                _aiVsAiState.value = _aiVsAiState.value.copy(whiteThinking = false)
                handleAiVsAiMove(bestMove)
            }
        }
        // Black engine collector
        viewModelScope.launch {
            aiBlackEngine.bestMoveFlow.collect { bestMove ->
                if (_gameMode.value != GameMode.AI_VS_AI) return@collect
                if (gameState.value.isWhiteTurn || gameState.value.isGameOver) return@collect
                _aiVsAiState.value = _aiVsAiState.value.copy(blackThinking = false)
                handleAiVsAiMove(bestMove)
            }
        }
    }

    private suspend fun handleAiVsAiMove(bestMove: String) {
        if (gameState.value.isGameOver) return
        val from  = bestMove.take(2)
        val to    = bestMove.drop(2).take(2)
        val promo = if (bestMove.length >= 5) bestMove[4] else 'q'
        chessGame.tryMove(from, to, promo)
        soundManager.play("move")
        if (gameState.value.isGameOver) {
            soundManager.play("confirmation")
            saveCurrentGame()
            return
        }
        scheduleNextAiMove()
    }

    private fun scheduleNextAiMove() {
        if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver) return
        val isWhiteTurn  = gameState.value.isWhiteTurn
        val sfPlaysWhite = _aiVsAiState.value.sfPlaysWhite
        val isSfTurn     = (isWhiteTurn == sfPlaysWhite)

        viewModelScope.launch {
            // Humanoid think delays:
            // Stockfish 16 — 0.8-2.5s  (strong, deliberate)
            // Ryzix 1000  — 2.0-5.0s  (human-like, slower, uncertain)
            val delayMs = if (isSfTurn) (800L..2500L).random() else (2000L..5000L).random()

            _aiVsAiState.value = _aiVsAiState.value.copy(
                whiteThinking = isWhiteTurn,
                blackThinking = !isWhiteTurn,
            )
            delay(delayMs)

            if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver) {
                _aiVsAiState.value = _aiVsAiState.value.copy(whiteThinking = false, blackThinking = false)
                return@launch
            }

            val fen      = chessGame.getCurrentFen()
            val settings = if (isSfTurn) SF_BATTLE_SETTINGS else RYZIX_1000_SETTINGS
            val aiEngine = if (isWhiteTurn) aiWhiteEngine else aiBlackEngine
            aiEngine.applySettings(settings)
            aiEngine.startSearch(fen, settings)
        }
    }

    // ── AI vs AI: start ─────────────────────────────────────────────────────

    fun startAiVsAi(sfPlaysWhite: Boolean) {
        engine.stop()
        aiWhiteEngine.stop()
        aiBlackEngine.stop()
        _isEngineThinking.value = false
        engineMoveInFlight = false
        _engineEval.value = 0f
        _analysisLines.value = emptyList()
        _lastMoveGrade.value = null
        _promotionPending.value = null
        _isReviewMode.value = false
        _isOtbMode.value = false
        _gameMode.value = GameMode.AI_VS_AI
        isGradingPending = false
        gradeCache.clear()
        chessGame.reset()
        chessGame.clearArrows()
        chessGame.setFlipped(false)

        val whiteLabel = if (sfPlaysWhite) "Stockfish 16" else "Ryzix 1000"
        val blackLabel = if (sfPlaysWhite) "Ryzix 1000"  else "Stockfish 16"
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
            // Lazily init AI engines (each spawns its own libryzix.so process)
            if (!aiWhiteReady) { aiWhiteReady = aiWhiteEngine.init(); delay(300) }
            if (!aiBlackReady) { aiBlackReady = aiBlackEngine.init(); delay(300) }
            if (!aiWhiteReady || !aiBlackReady) return@launch
            delay(600)
            // White always starts
            scheduleNextAiMove()
        }
    }

    fun stopAiVsAi() {
        aiWhiteEngine.stop()
        aiBlackEngine.stop()
        _aiVsAiState.value = _aiVsAiState.value.copy(
            whiteThinking = false, blackThinking = false, isActive = false,
        )
    }

    fun resumeAiVsAi() {
        if (_gameMode.value != GameMode.AI_VS_AI || gameState.value.isGameOver) return
        _aiVsAiState.value = _aiVsAiState.value.copy(isActive = true)
        scheduleNextAiMove()
    }

    // ── OTB analysis model ───────────────────────────────────────────────────

    fun setOtbAnalysisModel(model: OtbAnalysisModel) {
        _otbAnalysisModel.value = model
    }

    // ── Move grading ──────────────────────────────────────────────────────────

    private fun gradeLastMove(postEval: Float) {
        val fen = chessGame.getCurrentFen()
        gradeCache[fen]?.let { _lastMoveGrade.value = it; return }
        val cpLoss = preMoveEval + postEval
        val preBestUci = preMoveLines.firstOrNull()?.move ?: ""
        val lastMove = gameState.value.moves.lastOrNull()
        val playedUci = if (lastMove != null) "${lastMove.from}${lastMove.to}" else ""
        val isBestMove = playedUci.take(4) == preBestUci.take(4)
        val grade = when {
            cpLoss <= 0.05f && isBestMove -> MoveGrade.BEST
            cpLoss <= 0.05f              -> MoveGrade.BRILLIANT
            cpLoss <= 0.20f              -> MoveGrade.EXCELLENT
            cpLoss <= 0.45f              -> MoveGrade.GREAT
            cpLoss <= 0.75f              -> MoveGrade.GOOD
            cpLoss <= 1.25f              -> MoveGrade.INACCURACY
            cpLoss <= 2.00f              -> MoveGrade.MISTAKE
            else                         -> MoveGrade.BLUNDER
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
        val base = STOCKFISH_LEVELS.getOrElse(_prefs.value.levelIndex) { STOCKFISH_LEVELS[3] }
        val settings = base.copy(multiPv = maxOf(3, _prefs.value.multiPv), searchTimeMs = maxOf(3000, base.searchTimeMs))
        engine.applySettings(settings)
        _isEngineThinking.value = true
        engine.startAnalysis(chessGame.getCurrentFen(), settings)
    }

    // ── Square tap (OTB + VS_ENGINE only) ────────────────────────────────────

    fun onSquareTap(square: String) {
        if (_gameMode.value == GameMode.AI_VS_AI) return  // spectator — no interaction during AI battle
        val state = gameState.value
        if (state.isGameOver || _isReviewMode.value) return
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
            soundManager.play("move")
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
        }
    }

    fun cancelPromotion() { _promotionPending.value = null }

    fun triggerEngineMove() {
        if (!engineReady || gameState.value.isGameOver) return
        val settings = STOCKFISH_LEVELS.getOrElse(_prefs.value.levelIndex) { STOCKFISH_LEVELS[3] }
        engineMoveInFlight = true
        _isEngineThinking.value = true
        engine.applySettings(settings)
        engine.startSearch(chessGame.getCurrentFen(), settings)
    }

    fun toggleEngine() {
        _engineEnabled.value = !_engineEnabled.value
        if (_engineEnabled.value) runAnalysis()
        else { engine.stop(); _isEngineThinking.value = false; _analysisLines.value = emptyList(); chessGame.clearArrows() }
    }

    // ── New game (OTB / VS_ENGINE) ────────────────────────────────────────────

    fun newGame(otbMode: Boolean = true, playerIsWhite: Boolean = true) {
        // Stop AI engines if they were running
        if (_gameMode.value == GameMode.AI_VS_AI) { aiWhiteEngine.stop(); aiBlackEngine.stop() }
        engine.stop()
        _isEngineThinking.value = false
        engineMoveInFlight = false
        _engineEval.value = 0f
        _analysisLines.value = emptyList()
        _lastMoveGrade.value = null
        _promotionPending.value = null
        _isReviewMode.value = false
        isGradingPending = false
        _isOtbMode.value = otbMode
        _playerIsWhite.value = playerIsWhite
        _gameMode.value = if (otbMode) GameMode.OTB else GameMode.VS_ENGINE
        _aiVsAiState.value = AiVsAiState()
        gradeCache.clear()
        chessGame.reset()
        chessGame.clearArrows()
        chessGame.setFlipped(!playerIsWhite)
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PrefKeys.CURRENT_GAME_MOVES] = "" }
        }
        viewModelScope.launch {
            delay(400)
            if (otbMode) runAnalysis()
            else if (!playerIsWhite) triggerEngineMove()
        }
    }

    fun navigateBack() {
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
        if (_gameMode.value == GameMode.AI_VS_AI) { aiWhiteEngine.stop(); aiBlackEngine.stop() }
        engine.stop(); _isEngineThinking.value = false
        _analysisLines.value = emptyList(); _lastMoveGrade.value = null
        _promotionPending.value = null; _isReviewMode.value = true
        _isOtbMode.value = true; _gameMode.value = GameMode.OTB
        _aiVsAiState.value = AiVsAiState()
        if (game.movesUci.isNotBlank()) chessGame.loadFromMoves(game.movesUci) else chessGame.reset()
        viewModelScope.launch { delay(200); if (_engineEnabled.value) runAnalysis() }
    }

    fun continueGameFromHistory(game: SavedGame) {
        if (_gameMode.value == GameMode.AI_VS_AI) { aiWhiteEngine.stop(); aiBlackEngine.stop() }
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
            if (_gameMode.value == GameMode.AI_VS_AI) { aiWhiteEngine.stop(); aiBlackEngine.stop() }
            engine.stop(); _isEngineThinking.value = false
            _analysisLines.value = emptyList(); _lastMoveGrade.value = null
            _promotionPending.value = null; _isReviewMode.value = true
            _isOtbMode.value = true; _gameMode.value = GameMode.OTB
            _aiVsAiState.value = AiVsAiState()
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
        val s = gameState.value
        val game = SavedGame("*", s.moves.size, System.currentTimeMillis(), movesUci)
        viewModelScope.launch {
            val current = _gameHistory.value.toMutableList()
            current.removeAll { it.result == "*" }; current.add(0, game)
            val kept = current.take(50); _gameHistory.value = kept
            getApplication<Application>().dataStore.edit { it[PrefKeys.GAME_HISTORY] = kept.joinToString("\n") { g -> g.toRecord() } }
        }
    }

    private fun saveCurrentGame() {
        val movesUci = chessGame.getMovesAsUci()
        val s = gameState.value
        val game = SavedGame(s.gameResult ?: "*", s.moves.size, System.currentTimeMillis(), movesUci)
        viewModelScope.launch {
            val current = _gameHistory.value.toMutableList()
            current.removeAll { it.result == "*" }; current.add(0, game)
            val kept = current.take(50); _gameHistory.value = kept
            getApplication<Application>().dataStore.edit { it[PrefKeys.GAME_HISTORY] = kept.joinToString("\n") { g -> g.toRecord() } }
        }
    }

    private fun persistCurrentGame() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[PrefKeys.CURRENT_GAME_MOVES] = chessGame.getMovesAsUci() }
        }
    }

    fun saveLevel(index: Int) {
        viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.LEVEL] = index } }
        engine.stop()
        if (_isOtbMode.value) runAnalysis()
    }
    fun saveSearchTime(ms: Int) { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.SEARCH_TIME] = ms } } }
    fun saveMultiPv(n: Int)     { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.MULTI_PV]    = n  } } }
    fun saveThreads(n: Int)     { viewModelScope.launch { getApplication<Application>().dataStore.edit { it[PrefKeys.THREADS]     = n  } } }

    override fun onCleared() {
        super.onCleared()
        engine.quit()
        aiWhiteEngine.quit()
        aiBlackEngine.quit()
        soundManager.release()
    }
}
