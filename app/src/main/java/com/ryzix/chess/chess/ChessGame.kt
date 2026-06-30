package com.ryzix.chess.chess

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square
import com.github.bhlangonijr.chesslib.move.Move
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date

data class ChessMove(
    val from: String,
    val to: String,
    val san: String,
    val fen: String,
    val isCapture: Boolean = false,
    val promotion: String? = null,
)

data class Arrow(
    val from: String,
    val to: String,
    val color: ArrowColor = ArrowColor.GREEN,
)

enum class ArrowColor { GREEN, BLUE, RED, YELLOW }

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

data class GameState(
    val fen: String = START_FEN,
    val moves: List<ChessMove> = emptyList(),
    val selectedSquare: String? = null,
    val legalMoves: List<String> = emptyList(),
    val lastMove: Pair<String, String>? = null,
    val arrows: List<Arrow> = emptyList(),
    val isWhiteTurn: Boolean = true,
    val isGameOver: Boolean = false,
    val gameResult: String? = null,
    val isFlipped: Boolean = false,
    val currentMoveIndex: Int = -1,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val checkedKingSquare: String? = null,
    val isInHistoricalView: Boolean = false,
    val capturedByWhite: String = "",
    val capturedByBlack: String = "",
    val materialAdvantage: Int = 0,
)

class ChessGame {
    private val board = Board()
    private val moveHistory = mutableListOf<ChessMove>()

    // fenHistory[0] = start FEN, fenHistory[i] = FEN after moveHistory[i-1]
    private val fenHistory = mutableListOf<String>()
    // viewIndex: which FEN position is currently displayed
    private var viewIndex = 0

    private val _state = MutableStateFlow(GameState())
    val state: StateFlow<GameState> = _state

    init {
        board.loadFromFen(START_FEN)
        fenHistory.add(START_FEN)
        updateState()
    }

    /** True when the user is viewing a historical position (not the latest move) */
    val isInHistoricalView: Boolean get() = viewIndex < fenHistory.size - 1

    fun reset(fen: String = START_FEN) {
        board.loadFromFen(fen)
        moveHistory.clear()
        fenHistory.clear()
        fenHistory.add(fen)
        viewIndex = 0
        updateState()
    }

    fun selectSquare(squareName: String): Boolean {
        if (isInHistoricalView) {
            _state.value = _state.value.copy(selectedSquare = null, legalMoves = emptyList())
            return false
        }
        val square = Square.valueOf(squareName.uppercase())
        val current = _state.value

        if (current.selectedSquare == squareName) {
            _state.value = current.copy(selectedSquare = null, legalMoves = emptyList())
            return false
        }

        if (current.selectedSquare != null) {
            val moved = tryMove(current.selectedSquare!!, squareName)
            if (moved) return true
        }

        val piece = board.getPiece(square)
        if (piece == Piece.NONE) {
            _state.value = current.copy(selectedSquare = null, legalMoves = emptyList())
            return false
        }

        val isCurrentTurnPiece = piece.pieceSide == board.sideToMove
        if (!isCurrentTurnPiece) {
            _state.value = current.copy(selectedSquare = null, legalMoves = emptyList())
            return false
        }

        val legal = board.legalMoves()
            .filter { it.from == square }
            .map { it.to.value().lowercase() }

        _state.value = current.copy(selectedSquare = squareName, legalMoves = legal)
        return false
    }

    fun isPromotionMove(fromSq: String, toSq: String): Boolean {
        if (isInHistoricalView) return false
        return try {
            val from = Square.valueOf(fromSq.uppercase())
            val to   = Square.valueOf(toSq.uppercase())
            val piece = board.getPiece(from)
            if (piece != Piece.WHITE_PAWN && piece != Piece.BLACK_PAWN) return false
            val lm = board.legalMoves().filter { it.from == from && it.to == to }
            lm.size > 1
        } catch (e: Exception) { false }
    }

    fun tryMove(fromSq: String, toSq: String, promoChar: Char = 'q'): Boolean {
        if (isInHistoricalView) return false
        val from = Square.valueOf(fromSq.uppercase())
        val to   = Square.valueOf(toSq.uppercase())

        val legalMoves = board.legalMoves().filter { it.from == from && it.to == to }
        if (legalMoves.isEmpty()) return false

        val move = if (legalMoves.size > 1) {
            val targetPiece = when (promoChar.lowercaseChar()) {
                'r' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_ROOK   else Piece.BLACK_ROOK
                'b' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
                'n' -> if (board.sideToMove == Side.WHITE) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
                else -> if (board.sideToMove == Side.WHITE) Piece.WHITE_QUEEN else Piece.BLACK_QUEEN
            }
            legalMoves.firstOrNull { it.promotion == targetPiece } ?: legalMoves.first()
        } else legalMoves.first()

        val isCapture = board.getPiece(to) != Piece.NONE ||
            (board.getPiece(from).pieceType == PieceType.PAWN &&
             from.value()[0] != to.value()[0] && board.getPiece(to) == Piece.NONE)

        val san = computeSan(board, move)
        board.doMove(move)

        val suffix = when {
            board.isMated        -> "#"
            board.isKingAttacked -> "+"
            else                 -> ""
        }

        val chessMove = ChessMove(
            from = fromSq,
            to = toSq,
            san = san + suffix,
            fen = board.fen,
            isCapture = isCapture,
            promotion = move.promotion.takeIf { it != Piece.NONE }?.name,
        )
        moveHistory.add(chessMove)
        fenHistory.add(board.fen)
        viewIndex = fenHistory.size - 1
        updateState()
        return true
    }

    /** Navigate to the previous position — does NOT undo the move on the live board */
    fun navigateBack(): Boolean {
        if (viewIndex <= 0) return false
        viewIndex--
        updateState()
        return true
    }

    /** Navigate forward to the next recorded position */
    fun navigateForward(): Boolean {
        if (viewIndex >= fenHistory.size - 1) return false
        viewIndex++
        updateState()
        return true
    }

    /** Jump straight to the live (latest) position */
    fun navigateToLive() {
        viewIndex = fenHistory.size - 1
        updateState()
    }

    /** Actually undo the last move on the live board — for OTB use only */
    fun undoMove(): Boolean {
        viewIndex = fenHistory.size - 1   // snap to live first
        if (moveHistory.isEmpty()) return false
        board.undoMove()
        moveHistory.removeLastOrNull()
        fenHistory.removeLastOrNull()
        viewIndex = fenHistory.size - 1
        updateState()
        return true
    }

    fun getCurrentFen(): String = board.fen

    fun isWhiteTurn(): Boolean = board.sideToMove == Side.WHITE

    fun getPieceAt(squareName: String): Piece {
        return try {
            board.getPiece(Square.valueOf(squareName.uppercase()))
        } catch (e: Exception) { Piece.NONE }
    }

    fun getAllPieces(): Map<String, Piece> {
        val result = mutableMapOf<String, Piece>()
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val piece = board.getPiece(sq)
            if (piece != Piece.NONE) result[sq.value().lowercase()] = piece
        }
        return result
    }

    fun isInCheck(): Boolean = board.isKingAttacked

    fun getLegalMovesFrom(squareName: String): List<String> {
        val square = Square.valueOf(squareName.uppercase())
        return board.legalMoves()
            .filter { it.from == square }
            .map { it.to.value().lowercase() }
    }

    fun flipBoard() { _state.value = _state.value.copy(isFlipped = !_state.value.isFlipped) }

    fun setFlipped(flipped: Boolean) { _state.value = _state.value.copy(isFlipped = flipped) }

    fun setArrows(arrows: List<Arrow>) { _state.value = _state.value.copy(arrows = arrows) }

    fun addArrow(from: String, to: String, color: ArrowColor = ArrowColor.GREEN) {
        val current = _state.value.arrows.toMutableList()
        val existing = current.indexOfFirst { it.from == from && it.to == to }
        if (existing >= 0) current.removeAt(existing) else current.add(Arrow(from, to, color))
        _state.value = _state.value.copy(arrows = current)
    }

    fun clearArrows() { _state.value = _state.value.copy(arrows = emptyList()) }

    // ── Persistence helpers ────────────────────────────────────────────────────

    fun getMovesAsUci(): String {
        return moveHistory.joinToString(",") { m ->
            val promo = m.promotion?.firstOrNull()?.lowercaseChar()
            if (promo != null) "${m.from}${m.to}$promo" else "${m.from}${m.to}"
        }
    }

    fun loadFromMoves(movesUci: String) {
        board.loadFromFen(START_FEN)
        moveHistory.clear()
        fenHistory.clear()
        fenHistory.add(START_FEN)
        viewIndex = 0
        if (movesUci.isBlank()) { updateState(); return }
        movesUci.split(",").forEach { uci ->
            if (uci.length >= 4) {
                val from  = uci.substring(0, 2)
                val to    = uci.substring(2, 4)
                val promo = if (uci.length >= 5) uci[4] else 'q'
                tryMove(from, to, promo)
            }
        }
    }

    fun generatePgn(result: String = "*"): String {
        val date = SimpleDateFormat("yyyy.MM.dd").format(Date())
        val sb = StringBuilder()
        sb.appendLine("[Event \"RyzixChess Game\"]")
        sb.appendLine("[Site \"RyzixChess Android\"]")
        sb.appendLine("[Date \"$date\"]")
        sb.appendLine("[Round \"-\"]")
        sb.appendLine("[White \"Player\"]")
        sb.appendLine("[Black \"Player\"]")
        sb.appendLine("[Result \"$result\"]")
        sb.appendLine()
        val moveSb = StringBuilder()
        moveHistory.toList().forEachIndexed { index, move ->
            if (index % 2 == 0) moveSb.append("${index / 2 + 1}. ")
            moveSb.append("${move.san} ")
        }
        moveSb.append(result)
        sb.append(moveSb.toString().trim())
        return sb.toString()
    }

    fun loadFromPgn(pgn: String): Boolean {
        return try {
            val lines = pgn.lines().filter { !it.trimStart().startsWith("[") }
            val text = lines.joinToString(" ")
                .replace(Regex("\\{[^}]*\\}"), " ")
                .replace(Regex(";[^\n]*"), " ")
            val tokens = text.split(Regex("\\s+")).map { it.trim() }
                .filter { tok ->
                    tok.isNotBlank() &&
                    !tok.matches(Regex("\\d+\\.+")) &&
                    tok !in setOf("1-0", "0-1", "1/2-1/2", "*")
                }
            if (tokens.isEmpty()) return false
            board.loadFromFen(START_FEN)
            moveHistory.clear()
            fenHistory.clear()
            fenHistory.add(START_FEN)
            viewIndex = 0
            for (san in tokens) {
                val move = sanToMove(san) ?: return false
                val from = move.from.value().lowercase()
                val to   = move.to.value().lowercase()
                val promoChar = if (move.promotion != Piece.NONE) {
                    when(move.promotion.pieceType) {
                        PieceType.QUEEN  -> 'q'; PieceType.ROOK   -> 'r'
                        PieceType.BISHOP -> 'b'; PieceType.KNIGHT -> 'n'
                        else -> 'q'
                    }
                } else 'q'
                if (!tryMove(from, to, promoChar)) return false
            }
            updateState(); true
        } catch (e: Exception) { false }
    }

    // ── Captured pieces ────────────────────────────────────────────────────────

    private fun computeCapturedPieces(displayBoard: Board): Triple<String, String, Int> {
        val initialWhite = mapOf(
            Piece.WHITE_PAWN to 8, Piece.WHITE_KNIGHT to 2, Piece.WHITE_BISHOP to 2,
            Piece.WHITE_ROOK to 2, Piece.WHITE_QUEEN to 1,
        )
        val initialBlack = mapOf(
            Piece.BLACK_PAWN to 8, Piece.BLACK_KNIGHT to 2, Piece.BLACK_BISHOP to 2,
            Piece.BLACK_ROOK to 2, Piece.BLACK_QUEEN to 1,
        )
        val onBoard = mutableMapOf<Piece, Int>()
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            val p = displayBoard.getPiece(sq)
            if (p != Piece.NONE) onBoard[p] = (onBoard[p] ?: 0) + 1
        }
        val pieceValues = mapOf(
            Piece.WHITE_QUEEN to 9, Piece.WHITE_ROOK to 5, Piece.WHITE_BISHOP to 3, Piece.WHITE_KNIGHT to 3, Piece.WHITE_PAWN to 1,
            Piece.BLACK_QUEEN to 9, Piece.BLACK_ROOK to 5, Piece.BLACK_BISHOP to 3, Piece.BLACK_KNIGHT to 3, Piece.BLACK_PAWN to 1,
        )

        // Black pieces captured BY WHITE
        val capturedByWhite = StringBuilder()
        var whiteGained = 0
        val blackOrder = listOf(
            Piece.BLACK_QUEEN to "♛", Piece.BLACK_ROOK to "♜",
            Piece.BLACK_BISHOP to "♝", Piece.BLACK_KNIGHT to "♞", Piece.BLACK_PAWN to "♟",
        )
        for ((piece, sym) in blackOrder) {
            val captured = ((initialBlack[piece] ?: 0) - (onBoard[piece] ?: 0)).coerceAtLeast(0)
            repeat(captured) { capturedByWhite.append(sym) }
            whiteGained += captured * (pieceValues[piece] ?: 0)
        }

        // White pieces captured BY BLACK
        val capturedByBlack = StringBuilder()
        var blackGained = 0
        val whiteOrder = listOf(
            Piece.WHITE_QUEEN to "♕", Piece.WHITE_ROOK to "♖",
            Piece.WHITE_BISHOP to "♗", Piece.WHITE_KNIGHT to "♘", Piece.WHITE_PAWN to "♙",
        )
        for ((piece, sym) in whiteOrder) {
            val captured = ((initialWhite[piece] ?: 0) - (onBoard[piece] ?: 0)).coerceAtLeast(0)
            repeat(captured) { capturedByBlack.append(sym) }
            blackGained += captured * (pieceValues[piece] ?: 0)
        }

        return Triple(capturedByWhite.toString(), capturedByBlack.toString(), whiteGained - blackGained)
    }

    // ── SAN generation ─────────────────────────────────────────────────────────

    private fun computeSan(board: Board, move: Move): String {
        val from = move.from; val to = move.to
        val piece = board.getPiece(from)
        if (piece.pieceType == PieceType.KING) {
            val ff = from.value()[0] - 'A'; val tf = to.value()[0] - 'A'
            if (kotlin.math.abs(ff - tf) >= 2) return if (tf > ff) "O-O" else "O-O-O"
        }
        val toStr = to.value().lowercase()
        val isCapture = board.getPiece(to) != Piece.NONE ||
            (piece.pieceType == PieceType.PAWN && from.value()[0] != to.value()[0] && board.getPiece(to) == Piece.NONE)
        if (piece.pieceType == PieceType.PAWN) {
            val promoSuffix = if (move.promotion != Piece.NONE) "=" + when(move.promotion.pieceType) {
                PieceType.QUEEN -> "Q"; PieceType.ROOK -> "R"; PieceType.BISHOP -> "B"; else -> "N"
            } else ""
            return if (isCapture) "${from.value()[0].lowercaseChar()}x$toStr$promoSuffix" else "$toStr$promoSuffix"
        }
        val pieceChar = when(piece.pieceType) {
            PieceType.KNIGHT -> "N"; PieceType.BISHOP -> "B"; PieceType.ROOK -> "R"
            PieceType.QUEEN  -> "Q"; PieceType.KING   -> "K"; else -> ""
        }
        val ambiguous = board.legalMoves().filter { m ->
            m != move && m.to == to &&
            board.getPiece(m.from).pieceType == piece.pieceType &&
            board.getPiece(m.from).pieceSide == piece.pieceSide
        }
        val disambig = when {
            ambiguous.isEmpty() -> ""
            ambiguous.all { it.from.value()[0] != from.value()[0] } -> from.value()[0].lowercaseChar().toString()
            ambiguous.all { it.from.value()[1] != from.value()[1] } -> from.value()[1].toString()
            else -> from.value().lowercase()
        }
        return "$pieceChar$disambig${if (isCapture) "x" else ""}$toStr"
    }

    private fun sanToMove(san: String): Move? {
        val lm = board.legalMoves()
        val s = san.trimEnd('+', '#', '!', '?').trim()
        if (s.isBlank()) return null
        if (s == "O-O" || s == "0-0")
            return lm.firstOrNull { m -> board.getPiece(m.from).pieceType == PieceType.KING && (m.to.value() == "G1" || m.to.value() == "G8") }
        if (s == "O-O-O" || s == "0-0-0")
            return lm.firstOrNull { m -> board.getPiece(m.from).pieceType == PieceType.KING && (m.to.value() == "C1" || m.to.value() == "C8") }
        val promoRegex = Regex("([a-hA-H])([x×]?)([a-hA-H][18])=?([QRBNqrbn])")
        val promoMatch = promoRegex.find(s)
        if (promoMatch != null) {
            val toStr = promoMatch.groupValues[3].uppercase()
            val promoChar = promoMatch.groupValues[4][0].uppercaseChar()
            val toSq = try { Square.valueOf(toStr) } catch (e: Exception) { return null }
            val side = board.sideToMove
            val promoPiece = when(promoChar) {
                'Q' -> if (side == Side.WHITE) Piece.WHITE_QUEEN  else Piece.BLACK_QUEEN
                'R' -> if (side == Side.WHITE) Piece.WHITE_ROOK   else Piece.BLACK_ROOK
                'B' -> if (side == Side.WHITE) Piece.WHITE_BISHOP else Piece.BLACK_BISHOP
                'N' -> if (side == Side.WHITE) Piece.WHITE_KNIGHT else Piece.BLACK_KNIGHT
                else -> return null
            }
            return lm.firstOrNull { it.to == toSq && it.promotion == promoPiece }
        }
        val isPieceMov = s[0].isUpperCase() && s[0] != 'O'
        val pieceType = if (isPieceMov) when(s[0]) {
            'N' -> PieceType.KNIGHT; 'B' -> PieceType.BISHOP; 'R' -> PieceType.ROOK
            'Q' -> PieceType.QUEEN;  'K' -> PieceType.KING;   else -> PieceType.PAWN
        } else PieceType.PAWN
        val stripped = (if (isPieceMov) s.substring(1) else s).replace("x", "").replace("×", "")
        if (stripped.length < 2) return null
        val toStr = stripped.takeLast(2).uppercase()
        val disambig = stripped.dropLast(2).lowercase()
        val toSq = try { Square.valueOf(toStr) } catch (e: Exception) { return null }
        return lm.firstOrNull { m ->
            m.to == toSq && board.getPiece(m.from).pieceType == pieceType &&
            (disambig.isEmpty() || m.from.value().lowercase().contains(disambig))
        }
    }

    private fun updateState() {
        val isHistorical = viewIndex < fenHistory.size - 1
        val displayFen   = fenHistory.getOrElse(viewIndex) { board.fen }

        // For historical positions use a temporary board loaded from the historical FEN
        val displayBoard = if (isHistorical) Board().also { it.loadFromFen(displayFen) } else board

        // Game-over state is always from the LIVE board
        val liveIsOver = board.isMated || board.isDraw || board.isStaleMate
        val liveResult = when {
            board.isMated     -> if (board.sideToMove == Side.WHITE) "0-1" else "1-0"
            board.isDraw      -> "1/2-1/2"
            board.isStaleMate -> "1/2-1/2"
            else              -> null
        }

        // Last move highlighted = move that produced viewIndex position
        val lastMove = if (viewIndex > 0 && viewIndex <= moveHistory.size)
            moveHistory[viewIndex - 1].let { it.from to it.to }
        else null

        val checkedKingSquare = if (displayBoard.isKingAttacked)
            findKingSquare(displayBoard, displayBoard.sideToMove)
        else null

        val (capturedByWhite, capturedByBlack, materialAdv) = computeCapturedPieces(displayBoard)

        _state.value = _state.value.copy(
            fen                = displayFen,
            moves              = moveHistory.toList(),
            selectedSquare     = null,
            legalMoves         = emptyList(),
            lastMove           = lastMove,
            isWhiteTurn        = displayBoard.sideToMove == Side.WHITE,
            isGameOver         = liveIsOver && !isHistorical,
            gameResult         = if (!isHistorical) liveResult else null,
            currentMoveIndex   = viewIndex - 1,
            canGoBack          = viewIndex > 0,
            canGoForward       = viewIndex < fenHistory.size - 1,
            checkedKingSquare  = checkedKingSquare,
            isInHistoricalView = isHistorical,
            capturedByWhite    = capturedByWhite,
            capturedByBlack    = capturedByBlack,
            materialAdvantage  = materialAdv,
        )
    }

    private fun findKingSquare(b: Board, side: Side): String? {
        val kingPiece = if (side == Side.WHITE) Piece.WHITE_KING else Piece.BLACK_KING
        for (sq in Square.values()) {
            if (sq == Square.NONE) continue
            if (b.getPiece(sq) == kingPiece) return sq.value().lowercase()
        }
        return null
    }
}
