package com.ryzix.chess.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ryzix.chess.ui.screens.components.ChessBoard
import com.ryzix.chess.ui.screens.components.GameBottomBar
import com.ryzix.chess.ui.screens.components.StockfishPanel
import com.ryzix.chess.viewmodel.GameViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(
    onBack: () -> Unit,
    vm: GameViewModel = viewModel(),
    isFullscreen: Boolean = false,
    onToggleFullscreen: () -> Unit = {},
) {
    val state            by vm.gameState.collectAsState()
    val eval             by vm.engineEval.collectAsState()
    val isThinking       by vm.isEngineThinking.collectAsState()
    val engineEnabled    by vm.engineEnabled.collectAsState()
    val engineAvailable  by vm.engineAvailable.collectAsState()
    val analysisLines    by vm.analysisLines.collectAsState()
    val moveGrade        by vm.lastMoveGrade.collectAsState()
    val prefs            by vm.prefs.collectAsState()
    val promotionPending by vm.promotionPending.collectAsState()
    val isReviewMode     by vm.isReviewMode.collectAsState()
    val isOtbMode        by vm.isOtbMode.collectAsState()

    var showMoveList      by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showNewGameDialog by remember { mutableStateOf(false) }
    var showGameOverDialog by remember { mutableStateOf(true) }
    LaunchedEffect(state.isGameOver) { if (state.isGameOver) showGameOverDialog = true }

    val bottomIsWhite = !state.isFlipped

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ── Top action bar ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Mode label
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = when {
                    isReviewMode -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                    !isOtbMode   -> Color(0xFF1E0A0B)
                    else         -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                },
            ) {
                Text(
                    text = when {
                        isReviewMode -> "Review"
                        !isOtbMode   -> "vs Ryzix"
                        else         -> "Over the Board"
                    },
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = when {
                        isReviewMode -> MaterialTheme.colorScheme.tertiary
                        !isOtbMode   -> MaterialTheme.colorScheme.primary
                        else         -> MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            IconButton(onClick = { showNewGameDialog = true }) {
                Icon(Icons.Rounded.AddCircleOutline, contentDescription = "New game",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            if (isOtbMode) {
                IconButton(onClick = { showSettingsSheet = true }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "Engine settings",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
                }
            }
        }

        // ── Opponent player bar (top) ───────────────────────────────────────────
        PlayerBar(
            name           = if (!isOtbMode) { if (!bottomIsWhite) "You" else "Ryzix" } else { if (!bottomIsWhite) "White" else "Black" },
            isWhite        = !bottomIsWhite,
            isActive       = if (!bottomIsWhite) state.isWhiteTurn else !state.isWhiteTurn,
            isGameOver     = state.isGameOver,
            isThinking     = isThinking && ((if (!bottomIsWhite) state.isWhiteTurn else !state.isWhiteTurn)),
            isEngine       = !isOtbMode && bottomIsWhite,
            modifier       = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        // ── Chess board ────────────────────────────────────────────────────────
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 2.dp)
                .aspectRatio(1f),
        ) {
            ChessBoard(
                modifier        = Modifier.fillMaxSize(),
                state           = state,
                lastMoveGrade   = if (isOtbMode) moveGrade else null,
                onSquareTap     = { sq -> vm.onSquareTap(sq) },
                showCoordinates = true,
            )
        }

        // ── Your player bar (bottom) ───────────────────────────────────────────
        PlayerBar(
            name           = if (!isOtbMode) { if (bottomIsWhite) "You" else "Ryzix" } else { if (bottomIsWhite) "White" else "Black" },
            isWhite        = bottomIsWhite,
            isActive       = if (bottomIsWhite) state.isWhiteTurn else !state.isWhiteTurn,
            isGameOver     = state.isGameOver,
            isThinking     = false,
            isEngine       = !isOtbMode && !bottomIsWhite,
            modifier       = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        // ── In-game control bar ────────────────────────────────────────────────
        GameBottomBar(
            onMenu           = { showMoveList = !showMoveList },
            onEngineStrength = { if (isOtbMode) showSettingsSheet = true },
            onBack           = { vm.navigateBack() },
            onForward        = { vm.navigateForward() },
            onUndo           = { vm.undoOtbMove() },
            onExportPgn      = { vm.sharePgn(vm.getCurrentGamePgn()) },
            canBack          = state.canGoBack,
            canForward       = state.canGoForward,
            isReviewMode     = isReviewMode,
        )

        // ── Ryzix analysis strip + optional move list ──────────────────────────
        StockfishPanel(
            eval            = eval,
            isThinking      = isThinking,
            engineEnabled   = engineEnabled,
            engineAvailable = engineAvailable,
            analysisLines   = analysisLines,
            moves           = state.moves,
            showMoveList    = showMoveList,
            moveGrade       = moveGrade,
            onToggleEngine  = { vm.toggleEngine() },
            modifier        = Modifier.fillMaxWidth(),
            isOtbMode       = isOtbMode,
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    // ── Pawn promotion picker ──────────────────────────────────────────────────
    if (promotionPending != null) {
        PromotionDialog(
            isWhite   = state.isWhiteTurn,
            onPick    = { vm.confirmPromotion(it) },
            onDismiss = { vm.cancelPromotion() },
        )
    }

    // ── Game-over overlay ──────────────────────────────────────────────────────
    if (state.isGameOver && state.gameResult != null && showGameOverDialog) {
        GameOverDialog(
            result    = state.gameResult!!,
            pgn       = vm.getCurrentGamePgn(),
            onExport  = { pgn -> vm.sharePgn(pgn) },
            onNewGame = { showNewGameDialog = true },
            onAnalyse = { showGameOverDialog = false },
            onClose   = onBack,
        )
    }

    // ── New game dialog ────────────────────────────────────────────────────────
    if (showNewGameDialog) {
        NewGameDialog(
            onStartOtb = { whiteAtBottom ->
                showNewGameDialog = false
                vm.newGame(otbMode = true, playerIsWhite = whiteAtBottom)
            },
            onStartVsEngine = { playerIsWhite ->
                showNewGameDialog = false
                vm.newGame(otbMode = false, playerIsWhite = playerIsWhite)
            },
            onDismiss = { showNewGameDialog = false },
        )
    }

    // ── Engine settings sheet (OTB only) ──────────────────────────────────────
    if (showSettingsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSettingsSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            InlineEngineSettings(
                prefs              = prefs,
                engineAvailable    = engineAvailable,
                onLevelChange      = { vm.saveLevel(it) },
                onSearchTimeChange = { vm.saveSearchTime(it) },
                onMultiPvChange    = { vm.saveMultiPv(it) },
                onThreadsChange    = { vm.saveThreads(it) },
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ── Player bar composable ──────────────────────────────────────────────────────

@Composable
private fun PlayerBar(
    name: String,
    isWhite: Boolean,
    isActive: Boolean,
    isGameOver: Boolean,
    isThinking: Boolean,
    isEngine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val avatarBg    = if (isWhite) Color(0xFFF0D9B5) else Color(0xFF2C2C2C)
    val avatarFg    = if (isWhite) Color(0xFF1A1A1A) else Color(0xFFF0D9B5)
    val pieceSymbol = if (isEngine) "♟" else if (isWhite) "♔" else "♚"
    val activeColor = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isActive && !isGameOver)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    MaterialTheme.colorScheme.surface
            )
            .border(
                width = if (isActive && !isGameOver) 1.5.dp else 0.5.dp,
                color = if (isActive && !isGameOver)
                    activeColor.copy(alpha = 0.6f)
                else
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (isEngine) Color(0xFF1A1A1A) else avatarBg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = pieceSymbol,
                fontSize = 18.sp,
                color = if (isEngine) MaterialTheme.colorScheme.primary else avatarFg,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    isGameOver     -> "Game over"
                    isActive       -> if (isEngine) "Thinking…" else "Your turn"
                    else           -> "Waiting…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    isGameOver -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    isActive   -> activeColor
                    else       -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
        }

        if (isThinking) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
            )
        }

        if (isActive && !isGameOver) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(activeColor),
            )
        }
    }
}

// ── Promotion picker dialog ────────────────────────────────────────────────────

@Composable
private fun PromotionDialog(
    isWhite: Boolean,
    onPick: (Char) -> Unit,
    onDismiss: () -> Unit,
) {
    val pieces = listOf('q' to "Queen", 'r' to "Rook", 'b' to "Bishop", 'n' to "Knight")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promote pawn", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Choose a piece for ${if (isWhite) "White" else "Black"}:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    pieces.forEach { (char, name) ->
                        OutlinedButton(
                            onClick = { onPick(char) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = when (char) { 'q' -> "♛"; 'r' -> "♜"; 'b' -> "♝"; else -> "♞" },
                                    fontSize = 22.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Game-over dialog ───────────────────────────────────────────────────────────

@Composable
private fun GameOverDialog(
    result: String,
    pgn: String,
    onExport: (String) -> Unit,
    onNewGame: () -> Unit,
    onAnalyse: () -> Unit,
    onClose: () -> Unit,
) {
    val (title, subtitle) = when (result) {
        "1-0"     -> "White Wins!" to "Checkmate — well played!"
        "0-1"     -> "Black Wins!" to "Checkmate — well played!"
        "1/2-1/2" -> "Draw!" to "The game ended in a draw."
        else      -> "Game Over" to result
    }

    AlertDialog(
        onDismissRequest = onAnalyse,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text  = { Text(subtitle) },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onNewGame, modifier = Modifier.fillMaxWidth()) {
                    Text("New Game")
                }
                OutlinedButton(
                    onClick = onAnalyse,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Analytics, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Analyse Manually")
                }
                OutlinedButton(
                    onClick = { onExport(pgn) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Export PGN")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

// ── New game dialog ────────────────────────────────────────────────────────────

@Composable
private fun NewGameDialog(
    onStartOtb: (whiteAtBottom: Boolean) -> Unit,
    onStartVsEngine: (playerIsWhite: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = vs Ryzix, 1 = OTB

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Game", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Mode tabs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("vs Ryzix", "Over the Board").forEachIndexed { idx, label ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedTab == idx) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.weight(1f).clickable { selectedTab = idx },
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = if (selectedTab == idx) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                }

                if (selectedTab == 0) {
                    Text(
                        text = "Play against Ryzix Engine. No hints or arrows — test your skills.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "Your color:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorPickerTile(
                            symbol = "♔", label = "White",
                            bg = Color(0xFFF0D9B5), fg = Color(0xFF1A1A1A),
                            modifier = Modifier.weight(1f), onClick = { onStartVsEngine(true) },
                        )
                        ColorPickerTile(
                            symbol = "?", label = "Random",
                            bg = MaterialTheme.colorScheme.surfaceVariant,
                            fg = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f), onClick = { onStartVsEngine((0..1).random() == 0) },
                        )
                        ColorPickerTile(
                            symbol = "♚", label = "Black",
                            bg = Color(0xFF2C2C2C), fg = Color(0xFFF0D9B5),
                            modifier = Modifier.weight(1f), onClick = { onStartVsEngine(false) },
                        )
                    }
                } else {
                    Text(
                        text = "Two players, one device. Ryzix Engine shows hints and arrows.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        text = "Which side at bottom?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorPickerTile(
                            symbol = "♔", label = "White",
                            bg = Color(0xFFF0D9B5), fg = Color(0xFF1A1A1A),
                            modifier = Modifier.weight(1f), onClick = { onStartOtb(true) },
                        )
                        ColorPickerTile(
                            symbol = "?", label = "Random",
                            bg = MaterialTheme.colorScheme.surfaceVariant,
                            fg = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f), onClick = { onStartOtb((0..1).random() == 0) },
                        )
                        ColorPickerTile(
                            symbol = "♚", label = "Black",
                            bg = Color(0xFF2C2C2C), fg = Color(0xFFF0D9B5),
                            modifier = Modifier.weight(1f), onClick = { onStartOtb(false) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColorPickerTile(
    symbol: String, label: String, bg: Color, fg: Color,
    modifier: Modifier = Modifier, onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg,
        modifier = modifier
            .clickable(onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f), RoundedCornerShape(10.dp)),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = symbol, fontSize = 26.sp, color = fg)
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ── Engine settings sheet ──────────────────────────────────────────────────────

@Composable
fun InlineEngineSettings(
    prefs: com.ryzix.chess.viewmodel.AppPrefs,
    engineAvailable: Boolean = true,
    onLevelChange: (Int) -> Unit,
    onSearchTimeChange: (Int) -> Unit,
    onMultiPvChange: (Int) -> Unit,
    onThreadsChange: (Int) -> Unit,
) {
    val levelLabels = listOf("800","1200","1400","1600","1800","2000","2200","2400","2600","2700","2800","3200")

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text = "Engine settings",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(bottom = 16.dp),
        )

        EngineRow(
            label = "Engine",
            value = if (engineAvailable) "Ryzix Engine (ARM64)" else "Not available",
        )

        if (!engineAvailable) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = "Ryzix Engine binary not found. Install the APK built by GitHub Actions CI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
            return
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Strength",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Slider(
            value = prefs.levelIndex.toFloat(),
            onValueChange = { onLevelChange(it.toInt()) },
            valueRange = 0f..11f,
            steps = 10,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = levelLabels.getOrElse(prefs.levelIndex) { "?" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Level ${prefs.levelIndex + 1} / 12",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

@Composable
private fun EngineRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
