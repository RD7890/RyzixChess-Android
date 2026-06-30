# Ryzix Chess — Native Kotlin Android App

[![Build Status](https://github.com/RD7890/RyzixChess-Android/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/RD7890/RyzixChess-Android/actions/workflows/build.yml)
[![Latest Release](https://img.shields.io/github/v/release/RD7890/RyzixChess-Android?include_prereleases&label=release)](https://github.com/RD7890/RyzixChess-Android/releases/latest)
[![Platform](https://img.shields.io/badge/platform-Android%20ARM64-green)](https://github.com/RD7890/RyzixChess-Android/releases)

Native Android chess app built with Jetpack Compose — powered by the **Ryzix Engine** and **Stockfish 16**, both running as native ARM64 binaries.

## Features
- Play vs Ryzix Engine (London System opening book, adjustable strength)
- Engine Battle — watch Stockfish 16 vs Ryzix fight it out
- Over the Board (2 players, 1 device, engine shows hints)
- Real-time eval bar + multi-PV analysis arrows
- Move grading (Brilliant / Best / Excellent / Inaccuracy / Mistake / Blunder)
- Game history — save, review, continue, export PGN
- Theme settings — board colour, piece set
- Dark theme matching original RyzixChess

## Stack
- Kotlin + Jetpack Compose + Material 3
- `chesslib` — chess rules / move validation
- `datastore-preferences` — persistent settings
- Ryzix Engine (native ARM64 `.so`) via UCI over stdin/stdout
- Stockfish 16 (native ARM64 `.so`) via UCI over stdin/stdout

## Project structure
```
app/src/main/java/com/ryzix/chess/
├── MainActivity.kt
├── chess/
│   ├── ChessGame.kt         ← chess logic (chesslib wrapper)
│   ├── RyzixEngine.kt       ← UCI engine communication (both engines)
│   └── SoundManager.kt      ← move sounds
├── viewmodel/
│   └── GameViewModel.kt     ← game + engine state
└── ui/
    ├── theme/               ← dark theme, colors, typography
    ├── navigation/          ← nav graph + bottom pager
    └── screens/
        ├── HomeScreen.kt
        ├── GameScreen.kt
        ├── SettingsScreen.kt
        ├── ThemeSettingsScreen.kt
        └── components/
            ├── ChessBoard.kt    ← board + pieces + arrows
            ├── MovePanel.kt     ← move list + eval bar
            └── GameBottomBar.kt ← undo/flip/new/settings
```

## CI/CD

Every push to `main` or `release/**` triggers a GitHub Actions build that:
1. Downloads the latest **Ryzix Engine** release (`libryzix.so`)
2. Downloads **Stockfish 16** ARM64 (`libstockfish.so`)
3. Signs the APK with the committed PKCS12 keystore
4. Publishes a GitHub Release with the signed APK attached

[→ View all builds](https://github.com/RD7890/RyzixChess-Android/actions)  
[→ Download latest APK](https://github.com/RD7890/RyzixChess-Android/releases/latest)
