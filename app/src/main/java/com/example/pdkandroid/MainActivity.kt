package com.example.pdkandroid

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RawRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.pdk.core.Card
import com.example.pdk.core.AiProviderSettings
import com.example.pdk.core.ExternalAiController
import com.example.pdk.core.ExternalAiRequest
import com.example.pdk.core.ExternalAiResult
import com.example.pdk.core.GameEvent
import com.example.pdk.core.GameEventType
import com.example.pdk.core.GameState
import com.example.pdk.core.HandPattern
import com.example.pdk.core.PdkAiProtocol
import com.example.pdk.core.PatternType
import com.example.pdk.core.PlayerId
import com.example.pdk.core.PaoDeKuaiRules
import com.example.pdk.core.Rank
import com.example.pdk.core.RoundRecord
import com.example.pdk.core.Suit
import com.example.pdk.core.index
import com.example.pdk.core.nextCounterClockwise
import com.example.pdk.core.normalizeLocalAiName
import com.example.pdk.core.playerDisplayName
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        requestHighRefreshRate()
        val initialScene = intent.getStringExtra("scene") ?: "start"
        val mock = intent.getStringExtra("mock") ?: ""
        setContent {
            PdkTheme {
                PdkCanvasApp(initialScene = normalizeScene(initialScene), mock = mock)
            }
        }
    }

    private fun requestHighRefreshRate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val targetMode = display?.supportedModes
            ?.filter { it.refreshRate <= 120.5f }
            ?.maxByOrNull { it.refreshRate }
            ?: display?.supportedModes?.maxByOrNull { it.refreshRate }
            ?: return
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = targetMode.modeId
            preferredRefreshRate = targetMode.refreshRate
        }
    }
}

@Composable
private fun PdkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2F7D5C),
            background = Color(0xFFF7F4EA),
            onBackground = Color(0xFF1D1F1E),
        ),
        content = content,
    )
}

@Composable
private fun PdkCanvasApp(initialScene: String, mock: String) {
    val context = LocalContext.current
    val persistence = remember(context) { AndroidPersistence(context.applicationContext) }
    val initialSettings = remember(persistence) { persistence.loadSettings() }
    var statSummaries by remember(persistence) { mutableStateOf(persistence.loadStatSummaries()) }
    var scene by remember { mutableStateOf(initialScene) }
    var frame by remember { mutableIntStateOf(0) }
    var handledEvents by remember { mutableIntStateOf(0) }
    val hitMapRef = remember { AtomicReference(emptyList<HitRegion>()) }
    var pendingScene by remember { mutableStateOf(if (initialScene == "loading") "start" else "") }
    var gameResourcesLoaded by remember { mutableStateOf(initialScene.startsWith("game") || initialScene == "round-result") }
    var overlay by remember { mutableStateOf<NativeOverlay?>(if (initialScene == "about") NativeOverlay.About else null) }
    var playerName by remember { mutableStateOf(initialSettings.playerName) }
    var masterVolume by remember { mutableFloatStateOf(initialSettings.masterVolume) }
    val providers = remember {
        mutableStateListOf<AiProviderDraft>().apply { addAll(initialSettings.providers) }
    }
    var selectedProviderIndex by remember { mutableIntStateOf(0) }
    var ai1ProviderName by remember { mutableStateOf(initialSettings.ai1) }
    var ai2ProviderName by remember { mutableStateOf(initialSettings.ai2) }
    var dealElapsed by remember { mutableFloatStateOf(0f) }
    var dealSoundsScheduled by remember { mutableStateOf(false) }
    var handsSorted by remember { mutableStateOf(!initialScene.startsWith("game")) }
    var sortAnimation by remember { mutableFloatStateOf(0f) }
    var playAnimation by remember { mutableFloatStateOf(0f) }
    var bombAnimation by remember { mutableFloatStateOf(0f) }
    var roundResultDelay by remember { mutableFloatStateOf(0f) }
    var roundResultVisible by remember { mutableStateOf(initialScene == "round-result") }
    var lastAnimatedPlayer by remember { mutableStateOf(PlayerId.Player) }
    var dragPathIndices by remember { mutableStateOf(emptySet<Int>()) }
    val soundPlayer = remember(context) { SoundPlayer(context.applicationContext) }
    val initialRoundSeed = if (mock == "deal" && (initialScene.startsWith("game") || initialScene == "round-result")) 20260606u else 0u
    val game = remember {
        GameState().apply {
            if (initialScene.startsWith("game") || initialScene == "round-result") {
                startNewRound(playerName, initialRoundSeed)
            }
        }
    }
    val atlas = if (scene.startsWith("game") || scene == "round-result") {
        ImageBitmap.imageResource(R.drawable.poker_cards)
    } else {
        null
    }
    val currentHandsSorted by rememberUpdatedState(handsSorted)
    val currentSortAnimation by rememberUpdatedState(sortAnimation)
    val currentScene by rememberUpdatedState(scene)

    DisposableEffect(soundPlayer) {
        onDispose { soundPlayer.release() }
    }

    LaunchedEffect(masterVolume, soundPlayer) {
        soundPlayer.setMasterVolume(masterVolume)
    }

    fun resetGameAnimations() {
        dealElapsed = 0f
        dealSoundsScheduled = false
        handsSorted = false
        sortAnimation = 0f
        playAnimation = 0f
        bombAnimation = 0f
        roundResultDelay = 0f
        roundResultVisible = false
        lastAnimatedPlayer = PlayerId.Player
    }

    fun prepareNewRound() {
        game.startNewRound(playerName)
        handledEvents = 0
        resetGameAnimations()
    }

    fun startRoundDirect() {
        prepareNewRound()
        pendingScene = ""
        scene = "game"
    }

    fun startRoundFromMenu() {
        prepareNewRound()
        if (gameResourcesLoaded) {
            pendingScene = ""
            scene = "game"
            soundPlayer.play(SoundId.RoundStart)
            return
        }
        pendingScene = "game"
        scene = "loading"
    }

    fun handleCanvasAction(action: CanvasAction, interactionReady: Boolean) {
        fun runEventBackedAction(actionBlock: () -> Boolean) {
            val beforeEvents = game.events.size
            val ok = actionBlock()
            if (!ok && game.events.size == beforeEvents) soundPlayer.play(SoundId.InvalidMove)
        }

        when (action) {
            CanvasAction.Start -> {
                startRoundFromMenu()
                soundPlayer.play(SoundId.Confirm)
            }
            CanvasAction.Help -> {
                scene = "help"
                soundPlayer.play(SoundId.ButtonClick)
            }
            CanvasAction.Stats -> {
                scene = "stats"
                soundPlayer.play(SoundId.ButtonClick)
            }
            CanvasAction.Settings -> {
                scene = "settings"
                soundPlayer.play(SoundId.ButtonClick)
            }
            CanvasAction.About -> {
                scene = "about"
                soundPlayer.play(SoundId.ButtonClick)
            }
            CanvasAction.Back -> {
                if (currentScene.startsWith("game") || currentScene == "round-result") {
                    overlay = NativeOverlay.ReturnToMenu
                    soundPlayer.play(SoundId.ButtonClick)
                } else {
                    overlay = null
                    scene = "start"
                    soundPlayer.play(SoundId.Cancel)
                }
            }
            CanvasAction.Autoplay -> {
                game.toggleAutoplay()
                soundPlayer.play(SoundId.ButtonClick)
            }
            CanvasAction.Play -> {
                if (interactionReady) runEventBackedAction { game.playSelected() } else soundPlayer.play(SoundId.InvalidMove)
            }
            CanvasAction.Pass -> {
                if (interactionReady) runEventBackedAction { game.passHuman() } else soundPlayer.play(SoundId.InvalidMove)
            }
            CanvasAction.Hint -> {
                if (!interactionReady) {
                    soundPlayer.play(SoundId.InvalidMove)
                } else {
                    runEventBackedAction {
                        if (game.canCurrentPlayerPass()) game.passHuman() else game.applyHint()
                    }
                }
            }
            CanvasAction.NewRound -> {
                startRoundDirect()
                soundPlayer.play(SoundId.Confirm)
            }
            CanvasAction.Exit -> {
                overlay = NativeOverlay.ExitApp
                soundPlayer.play(SoundId.Pause)
            }
            CanvasAction.CancelOverlay -> {
                overlay = null
                soundPlayer.play(SoundId.Resume)
            }
            CanvasAction.ConfirmExit -> {
                overlay = null
                scene = "start"
                soundPlayer.play(SoundId.Cancel)
            }
            CanvasAction.MainMenu -> {
                overlay = null
                scene = "start"
                soundPlayer.play(SoundId.Cancel)
            }
            CanvasAction.ToggleAi1 -> Unit
            CanvasAction.ToggleAi2 -> Unit
            is CanvasAction.Card -> Unit
        }
        frame++
    }

    fun consumeNewEvents() {
        if (handledEvents >= game.events.size) return
        game.events.drop(handledEvents).forEach { event ->
            soundPlayer.playForEvent(event, spring = game.lastRoundRecord.spring)
            when (event.type) {
                GameEventType.RoundStarted -> resetGameAnimations()
                GameEventType.CardsPlayed -> {
                    playAnimation = 1f
                    lastAnimatedPlayer = event.player
                }
                GameEventType.Bomb -> bombAnimation = 1f
                GameEventType.RoundEnded -> {
                    roundResultDelay = 0f
                    roundResultVisible = false
                    statSummaries = persistence.recordRound(game.lastRoundRecord, playerName)
                }
                else -> Unit
            }
        }
        handledEvents = game.events.size
    }

    LaunchedEffect(game, soundPlayer) {
        var lastFrameNanos = withFrameNanos { it }
        while (true) {
            val frameNanos = withFrameNanos { it }
            val dt = ((frameNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
            lastFrameNanos = frameNanos
            consumeNewEvents()
            if (!game.roundOver) {
                if (!handsSorted) {
                    if (!dealSoundsScheduled) {
                        soundPlayer.scheduleDealCards()
                        dealSoundsScheduled = true
                    }
                    dealElapsed += dt
                    if (dealElapsed >= DealDurationSeconds) {
                        handsSorted = true
                        sortAnimation = 1f
                        soundPlayer.play(SoundId.Hint)
                    }
                } else if (sortAnimation > 0f) {
                    sortAnimation = max(0f, sortAnimation - dt * SortAnimationSpeed)
                }
                if (handsSorted && sortAnimation <= 0f) {
                    game.update(dt)
                }
            } else if (game.players.any { it.hand.isNotEmpty() }) {
                roundResultDelay += dt
                if (roundResultDelay >= RoundResultDelaySeconds) {
                    roundResultVisible = true
                }
            }
            playAnimation = max(0f, playAnimation - dt * PlayAnimationSpeed)
            bombAnimation = max(0f, bombAnimation - dt * BombAnimationSpeed)
            consumeNewEvents()
            frame++
        }
    }

    LaunchedEffect(ai1ProviderName, ai2ProviderName, providers.toList()) {
        game.setLocalAiKind(PlayerId.Ai1, ai1ProviderName)
        game.setLocalAiKind(PlayerId.Ai2, ai2ProviderName)
        val providerMap = buildMap {
            providers.firstOrNull { it.name == ai1ProviderName }?.let { put(PlayerId.Ai1, it.toSettings()) }
            providers.firstOrNull { it.name == ai2ProviderName }?.let { put(PlayerId.Ai2, it.toSettings()) }
        }
        game.setExternalAiController(if (providerMap.isEmpty()) null else AndroidLlmAiController(providerMap))
    }

    LaunchedEffect(scene, pendingScene) {
        if (scene == "loading" && pendingScene.isNotBlank()) {
            if (pendingScene == "game") soundPlayer.play(SoundId.RoundStart)
            delay(700)
            if (pendingScene == "game") gameResourcesLoaded = true
            scene = pendingScene
            pendingScene = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF7F4EA)),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    fun actionAt(offset: Offset): CanvasAction? {
                        val virtualOffset = screenToVirtual(offset, Size(size.width.toFloat(), size.height.toFloat()))
                            ?: return null
                        return hitMapRef.get().lastOrNull { it.rect.contains(virtualOffset) }?.action
                    }

                    awaitEachGesture {
                        if (currentScene in NativeScenes || overlay != null) {
                            awaitFirstDown(requireUnconsumed = false)
                            return@awaitEachGesture
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val action = actionAt(down.position) ?: return@awaitEachGesture
                        val interactionReady = currentHandsSorted && currentSortAnimation <= 0f
                        if (action !is CanvasAction.Card || !interactionReady) {
                            handleCanvasAction(action, interactionReady)
                            return@awaitEachGesture
                        }

                        val dragStartCard = action.index
                        val path = mutableListOf(dragStartCard)
                        var moved = false
                        dragPathIndices = path.toSet()
                        down.consume()

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.first()
                            val card = actionAt(change.position) as? CanvasAction.Card
                            if (card != null) {
                                if (path.lastOrNull() != card.index) path += card.index
                                if (card.index != dragStartCard) moved = true
                            }
                            dragPathIndices = path.toSet()
                            change.consume()
                            if (!change.pressed) break
                        }

                        val sound = if (moved && path.size > 1) {
                            val beforeEvents = game.events.size
                            val ok = game.selectBestPatternFromDraggedCards(path)
                            if (ok) {
                                if (handledEvents == beforeEvents && game.events.size > beforeEvents) handledEvents = game.events.size
                                if (game.toast.startsWith("已取消")) SoundId.DeselectCard else SoundId.SelectCard
                            } else {
                                SoundId.InvalidMove
                            }
                        } else {
                            val wasSelected = game.selectedIndices.contains(dragStartCard)
                            game.togglePlayerCard(dragStartCard)
                            if (wasSelected) SoundId.DeselectCard else SoundId.SelectCard
                        }
                        dragPathIndices = emptySet()
                        soundPlayer.play(sound)
                        frame++
                    }
                },
        ) {
            val hits = mutableListOf<HitRegion>()
            drawRect(Color(0xFF0B4B2D))
            val transform = canvasTransform(size)
            withTransform({
                translate(transform.offset.x, transform.offset.y)
                scale(transform.scale, transform.scale, Offset.Zero)
                clipRect(0f, 0f, transform.virtualWidth, CanvasHeight)
            }) {
                when (scene) {
                    "start" -> drawStartScene(hits, transform.virtualWidth)
                    "loading" -> drawLoadingScene(pendingScene.ifBlank { "game" }, transform.virtualWidth)
                    "help" -> drawInfoScene("帮助", helpText(), hits, transform.virtualWidth)
                    "stats" -> drawInfoScene("统计", game.toast.ifBlank { "暂无本局统计。开始一局后可查看最近结果。" }, hits, transform.virtualWidth)
                    "settings" -> drawInfoScene("设置", "", hits, transform.virtualWidth)
                    "about" -> drawInfoScene("关于", "跑得快 Kotlin Android 迁移版。\n横屏 Canvas 场景系统，规则与 AI 在纯 JVM core 中测试。\n图像、声音和网络均使用 Android 原生能力。", hits, transform.virtualWidth)
                    "round-result" -> atlas?.let { drawGameScene(game, it, hits, forceRoundResult = true, visuals = GameVisuals(frame, dealElapsed, handsSorted, sortAnimation, playAnimation, bombAnimation, roundResultVisible, lastAnimatedPlayer), layoutWidth = transform.virtualWidth, dragPathIndices = dragPathIndices) }
                    "game", "game-deal" -> atlas?.let { drawGameScene(game, it, hits, forceRoundResult = false, visuals = GameVisuals(frame, dealElapsed, handsSorted, sortAnimation, playAnimation, bombAnimation, roundResultVisible, lastAnimatedPlayer), layoutWidth = transform.virtualWidth, dragPathIndices = dragPathIndices) }
                }
            }
            hitMapRef.set(hits)
        }

        when (scene) {
            "start", "about" -> StartMenuScreen(
                onStart = {
                    startRoundFromMenu()
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onStats = {
                    scene = "stats"
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onSettings = {
                    scene = "settings"
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onHelp = {
                    scene = "help"
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onAbout = {
                    overlay = NativeOverlay.About
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onExit = {
                    overlay = NativeOverlay.ExitApp
                    soundPlayer.play(SoundId.ButtonClick)
                    soundPlayer.play(SoundId.Pause)
                },
            )
            "help" -> HelpNativeScreen(
                onBack = {
                    scene = "start"
                    soundPlayer.play(SoundId.Cancel)
                },
            )
            "stats" -> StatsNativeScreen(
                summaries = statSummaries,
                onBack = {
                    scene = "start"
                    soundPlayer.play(SoundId.Cancel)
                },
            )
            "settings" -> SettingsNativeScreen(
                playerName = playerName,
                onPlayerNameChange = { playerName = it },
                masterVolume = masterVolume,
                onMasterVolumeChange = { masterVolume = it },
                providers = providers,
                selectedProviderIndex = selectedProviderIndex,
                onSelectedProviderIndexChange = { selectedProviderIndex = it.coerceIn(0, max(0, providers.lastIndex)) },
                ai1ProviderName = ai1ProviderName,
                ai2ProviderName = ai2ProviderName,
                onAi1ProviderNameChange = { ai1ProviderName = it },
                onAi2ProviderNameChange = { ai2ProviderName = it },
                onProviderChange = { index, provider ->
                    if (index in providers.indices) {
                        val oldName = providers[index].name
                        providers[index] = provider.copy(name = uniqueProviderName(providers.mapIndexedNotNull { i, item -> item.name.takeIf { i != index } }, provider.name.ifBlank { oldName }))
                        if (ai1ProviderName == oldName) ai1ProviderName = providers[index].name
                        if (ai2ProviderName == oldName) ai2ProviderName = providers[index].name
                    }
                },
                onAddProvider = {
                    val name = uniqueProviderName(providers.map { it.name }, "provider")
                    providers += AiProviderDraft(name, "openai", "", "", "")
                    selectedProviderIndex = providers.lastIndex
                    soundPlayer.play(SoundId.ButtonClick)
                },
                onRemoveProvider = {
                    if (selectedProviderIndex in providers.indices) {
                        val removedName = providers[selectedProviderIndex].name
                        providers.removeAt(selectedProviderIndex)
                        if (ai1ProviderName == removedName) ai1ProviderName = "basic"
                        if (ai2ProviderName == removedName) ai2ProviderName = "basic"
                        selectedProviderIndex = selectedProviderIndex.coerceAtMost(providers.lastIndex)
                        soundPlayer.play(SoundId.ButtonClick)
                    }
                },
                onSave = {
                    persistence.saveSettings(AppSettingsDraft(playerName, masterVolume, ai1ProviderName, ai2ProviderName, providers.toList()))
                    scene = "start"
                    soundPlayer.play(SoundId.Confirm)
                },
                onCancel = {
                    scene = "start"
                    soundPlayer.play(SoundId.Cancel)
                },
            )
        }

        NativeDialogs(
            overlay = overlay,
            onDismiss = {
                overlay = null
                soundPlayer.play(
                    when (it) {
                        NativeOverlay.ReturnToMenu, NativeOverlay.About -> SoundId.Resume
                        NativeOverlay.ExitApp -> SoundId.Cancel
                    },
                )
            },
            onConfirmReturnToMenu = {
                overlay = null
                scene = "start"
                soundPlayer.play(SoundId.Cancel)
            },
            onConfirmExit = {
                overlay = null
                soundPlayer.play(SoundId.Confirm)
                (context as? Activity)?.finish()
            },
        )
    }
}

private fun normalizeScene(scene: String): String = when (scene) {
    "game", "game-deal", "loading", "help", "stats", "settings", "about", "round-result" -> scene
    else -> "start"
}

private sealed class CanvasAction {
    data object Start : CanvasAction()
    data object Help : CanvasAction()
    data object Stats : CanvasAction()
    data object Settings : CanvasAction()
    data object About : CanvasAction()
    data object Back : CanvasAction()
    data object Autoplay : CanvasAction()
    data object Play : CanvasAction()
    data object Pass : CanvasAction()
    data object Hint : CanvasAction()
    data object NewRound : CanvasAction()
    data object Exit : CanvasAction()
    data object CancelOverlay : CanvasAction()
    data object ConfirmExit : CanvasAction()
    data object MainMenu : CanvasAction()
    data object ToggleAi1 : CanvasAction()
    data object ToggleAi2 : CanvasAction()
    data class Card(val index: Int) : CanvasAction()
}

private data class HitRegion(val rect: Rect, val action: CanvasAction)
private enum class NativeOverlay { ReturnToMenu, ExitApp, About }
private val NativeScenes = setOf("start", "help", "stats", "settings", "about")
private data class CanvasTransform(val scale: Float, val offset: Offset, val virtualWidth: Float)
private data class GameVisuals(
    val frame: Int,
    val dealElapsed: Float,
    val handsSorted: Boolean,
    val sortAnimation: Float,
    val playAnimation: Float,
    val bombAnimation: Float,
    val roundResultVisible: Boolean,
    val lastAnimatedPlayer: PlayerId,
)

private data class AiProviderDraft(
    val name: String,
    val type: String,
    val endpoint: String,
    val apiKey: String,
    val model: String,
) {
    fun toSettings(): AiProviderSettings = AiProviderSettings(type, endpoint, apiKey, model)
}

private data class AppSettingsDraft(
    val playerName: String = "李姐",
    val masterVolume: Float = 0.8f,
    val ai1: String = "basic",
    val ai2: String = "basic",
    val providers: List<AiProviderDraft> = emptyList(),
)

private data class StatSummary(
    val rounds: Int = 0,
    val scores: List<Int> = listOf(0, 0, 0),
    val bombs: Int = 0,
    val springLosers: Int = 0,
    val bestSingleRoundPlayerScore: Int = 0,
)

private data class StatSummaries(
    val today: StatSummary = StatSummary(),
    val month: StatSummary = StatSummary(),
    val history: StatSummary = StatSummary(),
)

private data class DailyStat(
    val date: String,
    val rounds: List<PersistedRound> = emptyList(),
)

private data class PersistedRound(
    val startedAt: String = "",
    val endedAt: String = "",
    val winner: String = "player",
    val playerName: String = "",
    val scores: List<Int> = listOf(0, 0, 0),
    val remainingCards: List<Int> = listOf(0, 0, 0),
    val bombs: Int = 0,
    val springEnabled: Boolean = false,
    val springLosers: Int = 0,
)

private class AndroidPersistence(private val context: Context) {
    private val settingsFile: File
        get() = File(context.filesDir, "appsettings.json")
    private val statDir: File
        get() = File(context.filesDir, "stat")

    fun loadSettings(): AppSettingsDraft {
        val root = readJsonObject(settingsFile) ?: return AppSettingsDraft()
        val providers = loadProviders(root.optJSONObject("aiProviders"))
        return AppSettingsDraft(
            playerName = root.optString("playerName", "李姐").ifBlank { "李姐" },
            masterVolume = root.optDouble("masterVolume", 0.8).toFloat().coerceIn(0f, 1f),
            ai1 = normalizeLocalAiName(root.optString("ai1", "basic")),
            ai2 = normalizeLocalAiName(root.optString("ai2", "basic")),
            providers = providers,
        )
    }

    fun saveSettings(settings: AppSettingsDraft) {
        val root = JSONObject()
            .put("playerName", settings.playerName)
            .put("masterVolume", settings.masterVolume.toDouble())
            .put("windowWidth", 1280)
            .put("windowHeight", 720)
            .put("ai1", normalizeLocalAiName(settings.ai1))
            .put("ai2", normalizeLocalAiName(settings.ai2))

        if (settings.providers.isNotEmpty()) {
            val providers = JSONObject()
            settings.providers.forEach { provider ->
                if (provider.name.isBlank()) return@forEach
                providers.put(
                    provider.name,
                    JSONObject()
                        .put("type", provider.type)
                        .put("endpoint", provider.endpoint)
                        .put("apiKey", provider.apiKey)
                        .put("model", provider.model),
                )
            }
            root.put("aiProviders", providers)
        }
        writeJson(settingsFile, root)
    }

    fun loadStatSummaries(): StatSummaries {
        val today = todayDateKey()
        val month = today.substring(0, 6)
        val days = loadAllDays()
        return StatSummaries(
            today = summarize(days.filter { it.date == today }),
            month = summarize(days.filter { it.date.startsWith(month) }),
            history = summarize(days),
        )
    }

    fun recordRound(round: RoundRecord, playerName: String): StatSummaries {
        val today = todayDateKey()
        val day = loadDay(today)
        val now = nowTimeText()
        val saved = PersistedRound(
            startedAt = now,
            endedAt = now,
            winner = playerKey(round.winner),
            playerName = playerName,
            scores = normalizeScores(round.scores),
            remainingCards = normalizeScores(round.remainingCards),
            bombs = round.bombs.coerceAtLeast(0),
            springEnabled = round.spring,
            springLosers = round.springLosers.coerceAtLeast(0),
        )
        saveDay(day.copy(date = today, rounds = day.rounds + saved))
        return loadStatSummaries()
    }

    private fun loadProviders(providers: JSONObject?): List<AiProviderDraft> {
        if (providers == null) return emptyList()
        val result = mutableListOf<AiProviderDraft>()
        val keys = providers.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val item = providers.optJSONObject(name) ?: continue
            result += AiProviderDraft(
                name = name,
                type = item.optString("type", "openai"),
                endpoint = item.optString("endpoint", ""),
                apiKey = item.optString("apiKey", ""),
                model = item.optString("model", ""),
            )
        }
        return result
    }

    private fun loadAllDays(): List<DailyStat> {
        val files = statDir.listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
            ?: return emptyList()
        return files.map { loadDay(it.nameWithoutExtension) }
    }

    private fun loadDay(date: String): DailyStat {
        val root = readJsonObject(dayFile(date)) ?: return DailyStat(date)
        val actualDate = root.optString("date", date).ifBlank { date }
        val roundsJson = root.optJSONArray("rounds") ?: return DailyStat(actualDate)
        val rounds = buildList {
            for (index in 0 until roundsJson.length()) {
                roundsJson.optJSONObject(index)?.let { add(roundFromJson(it)) }
            }
        }
        return DailyStat(actualDate, rounds)
    }

    private fun saveDay(day: DailyStat) {
        val rounds = JSONArray()
        day.rounds.forEach { rounds.put(roundToJson(it)) }
        writeJson(
            dayFile(day.date),
            JSONObject()
                .put("date", day.date)
                .put("rounds", rounds),
        )
    }

    private fun dayFile(date: String): File = File(statDir, "$date.json")

    private fun summarize(days: List<DailyStat>): StatSummary {
        var rounds = 0
        val scores = MutableList(3) { 0 }
        var bombs = 0
        var springLosers = 0
        var bestSingle = 0
        days.forEach { day ->
            day.rounds.forEach { round ->
                val roundScores = normalizeScores(round.scores)
                rounds++
                repeat(3) { scores[it] += roundScores[it] }
                bombs += round.bombs
                springLosers += round.springLosers
                bestSingle = max(bestSingle, roundScores[0])
            }
        }
        return StatSummary(rounds, scores, bombs, springLosers, bestSingle)
    }

    private fun roundToJson(round: PersistedRound): JSONObject {
        val bombs = JSONArray()
        repeat(round.bombs.coerceAtLeast(0)) {
            bombs.put(JSONObject().put("by", "player").put("score", 20))
        }
        val losers = JSONArray()
        val loserKeys = listOf("player", "ai1", "ai2")
        repeat(round.springLosers.coerceAtLeast(0)) { losers.put(loserKeys[it % loserKeys.size]) }
        return JSONObject()
            .put("startedAt", round.startedAt)
            .put("endedAt", round.endedAt)
            .put("winner", round.winner)
            .put("playerName", round.playerName)
            .put("scores", scoresToJson(round.scores))
            .put("remainingCards", scoresToJson(round.remainingCards))
            .put("bombs", bombs)
            .put("spring", JSONObject().put("enabled", round.springEnabled).put("losers", losers))
    }

    private fun roundFromJson(json: JSONObject): PersistedRound {
        val spring = json.optJSONObject("spring")
        val bombsValue = json.opt("bombs")
        val bombs = if (bombsValue is JSONArray) {
            bombsValue.length()
        } else if (bombsValue is Number) {
            bombsValue.toInt()
        } else {
            0
        }
        return PersistedRound(
            startedAt = json.optString("startedAt", ""),
            endedAt = json.optString("endedAt", ""),
            winner = json.optString("winner", "player"),
            playerName = json.optString("playerName", ""),
            scores = scoresFromJson(json.opt("scores")),
            remainingCards = scoresFromJson(json.opt("remainingCards")),
            bombs = bombs,
            springEnabled = spring?.optBoolean("enabled", false) ?: false,
            springLosers = spring?.optJSONArray("losers")?.length() ?: json.optInt("springLosers", 0),
        )
    }

    private fun scoresToJson(scores: List<Int>): JSONObject {
        val normalized = normalizeScores(scores)
        return JSONObject()
            .put("player", normalized[0])
            .put("ai1", normalized[1])
            .put("ai2", normalized[2])
    }

    private fun scoresFromJson(value: Any?): List<Int> {
        return when (value) {
            is JSONObject -> listOf(value.optInt("player", 0), value.optInt("ai1", 0), value.optInt("ai2", 0))
            is JSONArray -> List(3) { index -> value.optInt(index, 0) }
            else -> listOf(0, 0, 0)
        }
    }

    private fun normalizeScores(scores: List<Int>): List<Int> = List(3) { index -> scores.getOrNull(index) ?: 0 }

    private fun readJsonObject(file: File): JSONObject? = runCatching {
        if (!file.isFile) return null
        JSONObject(file.readText(Charsets.UTF_8))
    }.getOrNull()

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString(2), Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(json.toString(2), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun todayDateKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun nowTimeText(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())

    private fun playerKey(player: PlayerId): String = when (player) {
        PlayerId.Player -> "player"
        PlayerId.Ai1 -> "ai1"
        PlayerId.Ai2 -> "ai2"
    }
}

@Composable
private fun StartMenuScreen(
    onStart: () -> Unit,
    onStats: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
    onExit: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color(0xFFF1EBDD))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color(0xFFF1EBDD))
            drawRoundRect(
                Color(0xFF0C3F31),
                topLeft = Offset(size.width * 0.43f, -28f),
                size = Size(size.width * 0.63f, size.height + 72f),
                cornerRadius = CornerRadius(30f, 30f),
            )
            drawOval(
                Color(0xFF166447),
                topLeft = Offset(size.width * 0.49f, size.height * 0.18f),
                size = Size(size.width * 0.38f, size.height * 0.58f),
                style = Stroke(5f),
            )
        }
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 40.dp, vertical = 22.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.width(430.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "极客版跑得快",
                    color = Color(0xFF17342A),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text("单机三人场  48 张固定规则", color = Color(0xFF506258), fontSize = 17.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HomeTag("Kotlin")
                    HomeTag("Compose Canvas")
                    HomeTag("SoundPool")
                }
                Text(
                    "黑桃 3 先出 · 要得起必须出 · 本地/LLM AI",
                    color = Color(0xFF263B31),
                    fontSize = 15.sp,
                )
                Text(
                    "Android 原生迁移版保留牌桌 Canvas 手感。\n菜单和设置使用 Compose 原生控件。",
                    color = Color(0xFF5E6E66),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
            Column(
                modifier = Modifier.width(300.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MenuButton("开始游戏", onStart, primary = true)
                MenuButton("积分统计", onStats)
                MenuButton("设置", onSettings)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuButton("帮助", onHelp, modifier = Modifier.weight(1f))
                    MenuButton("关于", onAbout, modifier = Modifier.weight(1f))
                }
                MenuButton("退出", onExit, quiet = true)
                Text(
                    "github.com/sdcb/pdk-android",
                    color = Color(0xFFD9E6D0),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun HelpNativeScreen(onBack: () -> Unit) {
    NativePage {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("帮助", color = Color(0xFFF5E070), fontSize = 26.sp)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                InfoPanel("游戏规则", sharedGameRulesText(), Modifier.weight(1f))
                InfoPanel("界面与计分", humanHelpText(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onBack, modifier = Modifier.width(120.dp).height(36.dp)) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun StatsNativeScreen(summaries: StatSummaries, onBack: () -> Unit) {
    NativePage {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("积分统计", color = Color(0xFFF5E070), fontSize = 26.sp)
            Spacer(Modifier.height(14.dp))
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StatPanel("今日", summaries.today, Modifier.weight(1f))
                StatPanel("本月", summaries.month, Modifier.weight(1f))
                StatPanel("历史", summaries.history, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = onBack, modifier = Modifier.width(120.dp).height(36.dp)) {
                Text("返回")
            }
        }
    }
}

@Composable
private fun SettingsNativeScreen(
    playerName: String,
    onPlayerNameChange: (String) -> Unit,
    masterVolume: Float,
    onMasterVolumeChange: (Float) -> Unit,
    providers: List<AiProviderDraft>,
    selectedProviderIndex: Int,
    onSelectedProviderIndexChange: (Int) -> Unit,
    ai1ProviderName: String,
    ai2ProviderName: String,
    onAi1ProviderNameChange: (String) -> Unit,
    onAi2ProviderNameChange: (String) -> Unit,
    onProviderChange: (Int, AiProviderDraft) -> Unit,
    onAddProvider: () -> Unit,
    onRemoveProvider: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val selectedProvider = providers.getOrNull(selectedProviderIndex)
    val providerOptions = listOf("basic", "strong") + providers.map { it.name }
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF1EBDD)) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("设置", color = Color(0xFF17342A), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("玩家、音量和 AI Provider", color = Color(0xFF607268), fontSize = 11.sp)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onSave, modifier = Modifier.width(80.dp).height(34.dp)) { Text("保存") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.width(80.dp).height(34.dp)) { Text("取消") }
            }

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    modifier = Modifier.width(300.dp).fillMaxHeight(),
                    color = Color(0xFFFBF7EC),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactTextField("玩家名", playerName, Modifier.fillMaxWidth()) { onPlayerNameChange(it) }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("音量", color = Color(0xFF17342A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                Text("${(masterVolume * 100f).toInt()}%", color = Color(0xFF607268), fontSize = 13.sp)
                            }
                            Slider(value = masterVolume, onValueChange = { onMasterVolumeChange(it.coerceIn(0f, 1f)) })
                        }
                        Text("AI 控制", color = Color(0xFF17342A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        AiRouteRow("AI1", ai1ProviderName, providerOptions, onAi1ProviderNameChange)
                        AiRouteRow("AI2", ai2ProviderName, providerOptions, onAi2ProviderNameChange)
                    }
                }

                Surface(
                    modifier = Modifier.width(210.dp).fillMaxHeight(),
                    color = Color(0xFFFBF7EC),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI Providers", color = Color(0xFF17342A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            providers.forEachIndexed { index, provider ->
                                val selected = index == selectedProviderIndex
                                if (selected) {
                                    Button(onClick = { onSelectedProviderIndexChange(index) }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                                        Text(provider.name)
                                    }
                                } else {
                                    OutlinedButton(onClick = { onSelectedProviderIndexChange(index) }, modifier = Modifier.fillMaxWidth().height(36.dp)) {
                                        Text(provider.name)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onAddProvider, modifier = Modifier.weight(1f).height(36.dp)) { Text("新增") }
                            OutlinedButton(onClick = onRemoveProvider, modifier = Modifier.weight(1f).height(36.dp)) { Text("删除") }
                        }
                    }
                }

                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    color = Color(0xFFFBF7EC),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Column(Modifier.padding(10.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Provider 详情", color = Color(0xFF17342A), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderTextField("名称", selectedProvider?.name.orEmpty(), Modifier.weight(1f)) { value ->
                                selectedProvider?.let { onProviderChange(selectedProviderIndex, it.copy(name = value.ifBlank { it.name })) }
                            }
                            ProviderTextField("type", selectedProvider?.type.orEmpty(), Modifier.weight(1f)) { value ->
                                selectedProvider?.let { onProviderChange(selectedProviderIndex, it.copy(type = value)) }
                            }
                        }
                        ProviderTextField("endpoint", selectedProvider?.endpoint.orEmpty(), Modifier.fillMaxWidth()) { value ->
                            selectedProvider?.let { onProviderChange(selectedProviderIndex, it.copy(endpoint = value)) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProviderTextField("apiKey", selectedProvider?.apiKey.orEmpty(), Modifier.weight(1f)) { value ->
                                selectedProvider?.let { onProviderChange(selectedProviderIndex, it.copy(apiKey = value)) }
                            }
                            ProviderTextField("model", selectedProvider?.model.orEmpty(), Modifier.weight(1f)) { value ->
                                selectedProvider?.let { onProviderChange(selectedProviderIndex, it.copy(model = value)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NativeDialogs(
    overlay: NativeOverlay?,
    onDismiss: (NativeOverlay) -> Unit,
    onConfirmReturnToMenu: () -> Unit,
    onConfirmExit: () -> Unit,
) {
    when (overlay) {
        NativeOverlay.ReturnToMenu -> AlertDialog(
            onDismissRequest = { onDismiss(overlay) },
            title = { Text("返回游戏菜单？") },
            confirmButton = { TextButton(onClick = onConfirmReturnToMenu) { Text("回主菜单") } },
            dismissButton = { TextButton(onClick = { onDismiss(overlay) }) { Text("继续") } },
        )
        NativeOverlay.ExitApp -> AlertDialog(
            onDismissRequest = { onDismiss(overlay) },
            title = { Text("确认关闭窗口？") },
            confirmButton = { TextButton(onClick = onConfirmExit) { Text("确认退出") } },
            dismissButton = { TextButton(onClick = { onDismiss(overlay) }) { Text("取消") } },
        )
        NativeOverlay.About -> AlertDialog(
            onDismissRequest = { onDismiss(overlay) },
            title = { Text("关于极客版跑得快") },
            text = {
                Column(Modifier.height(210.dp).verticalScroll(rememberScrollState())) {
                    Text(aboutText(), fontSize = 14.sp, lineHeight = 20.sp)
                }
            },
            confirmButton = { TextButton(onClick = { onDismiss(overlay) }) { Text("知道了") } },
        )
        null -> Unit
    }
}

@Composable
private fun NativePage(content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF082F24), content = content)
}

@Composable
private fun HomeTag(label: String) {
    Text(
        label,
        color = Color(0xFF1F5A44),
        fontSize = 12.sp,
        modifier = Modifier
            .border(1.dp, Color(0xFF8CA99A), RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    )
}

@Composable
private fun MenuButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    quiet: Boolean = false,
) {
    val colors = when {
        primary -> ButtonDefaults.buttonColors(containerColor = Color(0xFFF2CF63), contentColor = Color(0xFF17342A))
        quiet -> ButtonDefaults.buttonColors(containerColor = Color(0xFF245544), contentColor = Color(0xFFE8F3DF))
        else -> ButtonDefaults.buttonColors(containerColor = Color(0xFFEAF0E1), contentColor = Color(0xFF17342A))
    }
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(38.dp),
        shape = RoundedCornerShape(8.dp),
        colors = colors,
        elevation = ButtonDefaults.buttonElevation(defaultElevation = if (primary) 4.dp else 1.dp),
    ) {
        Text(label)
    }
}

@Composable
private fun InfoPanel(title: String, body: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxHeight(), color = Color(0xFF102A24)) {
        Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(title, color = Color(0xFFF5E070), fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(body, color = Color(0xFFE5F2DC), fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

@Composable
private fun StatPanel(title: String, summary: StatSummary, modifier: Modifier = Modifier) {
    val text = """
局数: ${summary.rounds}

玩家得分: ${summary.scores[0]}
AI1 得分: ${summary.scores[1]}
AI2 得分: ${summary.scores[2]}

炸弹次数: ${summary.bombs}
关圆鸡人数: ${summary.springLosers}
历史最高单局: ${summary.bestSingleRoundPlayerScore}
""".trimIndent()
    InfoPanel(title, text, modifier)
}

@Composable
private fun AiRouteRow(label: String, selected: String, options: List<String>, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = Color(0xFF1D1F1E), fontSize = 13.sp, modifier = Modifier.width(34.dp))
        options.forEach { option ->
            val normalizedSelected = normalizeLocalAiName(selected)
            val display = when (option) {
                "basic" -> "基础"
                "strong" -> "强AI"
                else -> option
            }
            if (option == normalizedSelected || option == selected) {
                Button(onClick = { onChange(option) }, modifier = Modifier.height(30.dp)) { Text(display, fontSize = 12.sp) }
            } else {
                OutlinedButton(onClick = { onChange(option) }, modifier = Modifier.height(30.dp)) { Text(display, fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun ProviderTextField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    CompactTextField(label, value, modifier, onChange)
}

@Composable
private fun CompactTextField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Column(modifier) {
        Text(label, color = Color(0xFF607268), fontSize = 11.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color(0xFF1D1F1E), fontSize = 14.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .border(1.dp, Color(0xFF9BA49F), RoundedCornerShape(7.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

private fun uniqueProviderName(existing: List<String>, base: String): String {
    if (base !in existing) return base
    var index = 2
    while ("$base$index" in existing) index++
    return "$base$index"
}

private fun sharedGameRulesText(): String = """
目标：先把手牌跑完。
三家各 16 张。
黑桃 3 玩家先出，但第一手不强制包含黑桃 3。
牌点从小到大：3 4 5 6 7 8 9 10 J Q K A 2。
花色不参与大小比较。
牌型：单张、对子、至少两连对、三带二、飞机、至少五张顺子、炸弹。
顺子不能使用 2；10JQKA 可以，JQKA2 和 A2345 不可以。
飞机为至少两组连续三张，可带每组三张数量到两倍数量的零牌，只比三张主体。
炸弹为四张同点数，4 个 3 最小，4 个 K 最大；炸弹可压非炸弹。
三带二可以带两张零牌，不要求对子；不能三带一；炸弹只能按炸弹出，不能当四带三。
最后一手牌不足带牌时，三张主体可以直接出完或只带一张。
要得起必须出，不能不要。
""".trimIndent()

private fun humanHelpText(): String = """
计分：赢家获得两家有效剩余牌数，剩一张不扣分；关圆鸡每人扣 32。
炸弹立即 +20，另外两家各 -10，结算也会展示且不参与春天翻倍。
托管会用基础 AI 代替玩家行动，可随时取消。
""".trimIndent()

private fun aboutText(): String = """
由 sdcb 开发，为妈妈做的一款单机跑得快。
这是 Android / Kotlin 迁移版，代码地址：
https://github.com/sdcb/pdk-android

使用技术：Kotlin、Android SDK、Jetpack Compose、
Compose Canvas、SoundPool、协程调度、Gradle、
JVM 单元测试。

游戏内牌桌、手牌、动画和触摸操作保留 Canvas 驱动；
菜单、设置、帮助和对话框使用 Android 原生 Compose 控件。
""".trimIndent()

private const val CanvasWidth = 1280f
private const val CanvasHeight = 720f
private const val PlayerCardWidth = 157.5f
private const val PlayerCardHeight = 220.5f
private const val PlayerCardTop = 448f
private const val AiCardWidth = 77.25f
private const val AiCardHeight = 108.15f
private const val AiCardTopOffset = 57f
private const val AiCardOuterPadding = 92f
private const val PlayedCardWidth = 87f
private const val PlayedCardHeight = 123f
private const val PlayedCardTop = 255f
private const val FullHandCardCount = 16
private const val AtlasVisibleRatio = 80f / 240f
private const val DealSoundCount = 16
private const val DealCardIntervalSeconds = 1f / 6f
private const val DealDurationSeconds = 16f * DealCardIntervalSeconds + 0.1f
private const val SortAnimationSpeed = 2.4f
private const val PlayAnimationSpeed = 3.2f
private const val BombAnimationSpeed = 2.4f
private const val RoundResultDelaySeconds = 1.2f

private fun canvasTransform(size: Size): CanvasTransform {
    val heightScale = size.height / CanvasHeight
    val widthAtHeightScale = size.width / heightScale
    if (widthAtHeightScale >= CanvasWidth) {
        return CanvasTransform(
            scale = heightScale,
            offset = Offset.Zero,
            virtualWidth = widthAtHeightScale,
        )
    }
    val scale = size.width / CanvasWidth
    return CanvasTransform(
        scale = scale,
        offset = Offset(0f, (size.height - CanvasHeight * scale) / 2f),
        virtualWidth = CanvasWidth,
    )
}

private fun screenToVirtual(point: Offset, size: Size): Offset? {
    val transform = canvasTransform(size)
    val x = (point.x - transform.offset.x) / transform.scale
    val y = (point.y - transform.offset.y) / transform.scale
    if (x !in 0f..transform.virtualWidth || y !in 0f..CanvasHeight) return null
    return Offset(x, y)
}

private fun DrawScope.drawStartScene(hits: MutableList<HitRegion>, layoutWidth: Float) {
    drawRect(Color(0xFFF7F4EA))
    drawTextNative("跑得快", Offset(72f, 122f), 76f, Color(0xFF1D1F1E), bold = true)
    drawTextNative("Kotlin Android 横屏 Canvas", Offset(78f, 174f), 30f, Color(0xFF66716C))

    val start = Rect(layoutWidth - 390f, 92f, layoutWidth - 70f, 178f)
    drawCanvasButton(start, "开始游戏", primary = true)
    hits += HitRegion(start, CanvasAction.Start)

    val y = 240f
    val labels = listOf("帮助" to CanvasAction.Help, "统计" to CanvasAction.Stats, "设置" to CanvasAction.Settings, "关于" to CanvasAction.About)
    labels.forEachIndexed { index, pair ->
        val rect = Rect(layoutWidth - 390f, y + index * 88f, layoutWidth - 70f, y + 64f + index * 88f)
        drawCanvasButton(rect, pair.first, primary = false)
        hits += HitRegion(rect, pair.second)
    }

    drawRoundRect(Color(0xFF0B4B2D), topLeft = Offset(74f, 230f), size = Size(layoutWidth - 560f, CanvasHeight - 300f), cornerRadius = CornerRadius(18f, 18f))
    drawOval(Color(0xFF1B6A46), topLeft = Offset(130f, 265f), size = Size(layoutWidth - 680f, CanvasHeight - 380f), style = Stroke(4f))
    drawTextNative("黑桃 3 先出 · 要得起必须出 · 本地/LLM AI", Offset(108f, CanvasHeight - 92f), 30f, Color(0xFF34463D))
}

private fun DrawScope.drawLoadingScene(target: String, layoutWidth: Float) {
    drawRect(Color(0xFF0B4B2D))
    drawTextNative("加载中", Offset(layoutWidth / 2f, CanvasHeight / 2f - 42f), 52f, Color(0xFFF4D36D), alignCenter = true, bold = true)
    drawTextNative("准备 ${if (target == "game") "牌图和音效" else target}", Offset(layoutWidth / 2f, CanvasHeight / 2f + 20f), 26f, Color(0xFFDDE9D5), alignCenter = true)
    val progress = ((System.currentTimeMillis() % 700L).toFloat() / 700f).coerceIn(0f, 1f)
    val bar = Rect(layoutWidth / 2f - 250f, CanvasHeight / 2f + 70f, layoutWidth / 2f + 250f, CanvasHeight / 2f + 88f)
    drawRoundRect(Color(0xFF17231D), topLeft = bar.topLeft, size = bar.size, cornerRadius = CornerRadius(8f, 8f))
    drawRoundRect(Color(0xFFD9B947), topLeft = bar.topLeft, size = Size(bar.width * (0.12f + progress * 0.88f), bar.height), cornerRadius = CornerRadius(8f, 8f))
}

private fun DrawScope.drawInfoScene(title: String, body: String, hits: MutableList<HitRegion>, layoutWidth: Float) {
    drawRect(Color(0xFFF7F4EA))
    val back = Rect(36f, 34f, 156f, 88f)
    drawCanvasButton(back, "返回", primary = false)
    hits += HitRegion(back, CanvasAction.Back)
    drawTextNative(title, Offset(190f, 76f), 48f, Color(0xFF1D1F1E), bold = true)
    drawRoundRect(Color.White, topLeft = Offset(56f, 124f), size = Size(layoutWidth - 112f, CanvasHeight - 170f), cornerRadius = CornerRadius(16f, 16f))
    body.lines().forEachIndexed { index, line ->
        drawTextNative(line, Offset(92f, 178f + index * 42f), 28f, Color(0xFF34463D))
    }
}

private fun DrawScope.drawSettingsScene(ai1Remote: Boolean, ai2Remote: Boolean, hits: MutableList<HitRegion>, layoutWidth: Float) {
    drawRect(Color(0xFFF7F4EA))
    val back = Rect(36f, 34f, 156f, 88f)
    drawCanvasButton(back, "返回", primary = false)
    hits += HitRegion(back, CanvasAction.Back)
    drawTextNative("设置", Offset(190f, 76f), 48f, Color(0xFF1D1F1E), bold = true)
    drawRoundRect(Color.White, topLeft = Offset(56f, 124f), size = Size(layoutWidth - 112f, CanvasHeight - 170f), cornerRadius = CornerRadius(16f, 16f))
    drawTextNative("AI 控制", Offset(92f, 184f), 34f, Color(0xFF1D1F1E), bold = true)
    val ai1 = Rect(92f, 222f, 400f, 284f)
    val ai2 = Rect(430f, 222f, 738f, 284f)
    drawCanvasButton(ai1, "AI1: ${if (ai1Remote) "LLM" else "本地"}", ai1Remote)
    drawCanvasButton(ai2, "AI2: ${if (ai2Remote) "LLM" else "本地"}", ai2Remote)
    hits += HitRegion(ai1, CanvasAction.ToggleAi1)
    hits += HitRegion(ai2, CanvasAction.ToggleAi2)
    drawTextNative("LLM 使用 OpenAI-compatible chat completions 协议；未配置 API Key 时会自动回退本地 AI。", Offset(92f, 342f), 25f, Color(0xFF34463D))
    drawTextNative("所有设置交互均在 Canvas 内完成。", Offset(92f, 382f), 25f, Color(0xFF34463D))
}

private fun DrawScope.drawGameScene(
    state: GameState,
    atlas: ImageBitmap,
    hits: MutableList<HitRegion>,
    forceRoundResult: Boolean,
    visuals: GameVisuals,
    layoutWidth: Float,
    dragPathIndices: Set<Int>,
) {
    drawRect(Color(0xFF0B4B2D))
    val back = Rect(24f, 24f, 68f, 68f)
    drawCanvasCircleButton(back, "<", primary = false)
    hits += HitRegion(back, CanvasAction.Back)
    drawTextNative(
        if (state.roundOver) "本局结束" else "轮到：${playerDisplayName(state.currentPlayer, state.players[0].name)}",
        Offset(layoutWidth / 2f, 61f),
        36f,
        Color(0xFFF4F6D8),
        alignCenter = true,
        bold = true,
    )

    val table = Rect(0f, 92f, layoutWidth, CanvasHeight - 96f)
    val shake = bombShakeOffset(visuals)
    withTransform({
        translate(shake.x, shake.y)
    }) {
        drawRoundRect(Color(0xFF0B4B2D), topLeft = table.topLeft, size = table.size, cornerRadius = CornerRadius(18f, 18f))
        drawRoundRect(Color(0xFF0F5C38), topLeft = Offset(table.left + 12f, table.top + 12f), size = Size(table.width - 24f, table.height - 24f), cornerRadius = CornerRadius(14f, 14f))
        drawOval(Color(0x447A7D3E), topLeft = Offset(table.left + table.width * 0.22f, table.top + table.height * 0.18f), size = Size(table.width * 0.56f, table.height * 0.54f), style = Stroke(4f))
        if (visuals.bombAnimation > 0f) {
            val pulse = 1f + visuals.bombAnimation * 0.08f
            drawOval(
                Color(0xFFFFC145).copy(alpha = visuals.bombAnimation),
                topLeft = Offset(table.left + table.width * 0.22f - 14f * pulse, table.top + table.height * 0.18f - 14f * pulse),
                size = Size(table.width * 0.56f + 28f * pulse, table.height * 0.54f + 28f * pulse),
                style = Stroke(6f),
            )
        }

        drawPlayerLabel(state, PlayerId.Ai1, Offset(table.left + 78f, table.top + 28f))
        drawPlayerLabel(state, PlayerId.Ai2, Offset(table.right - 280f, table.top + 28f))
        val aiArea = Rect(table.left + AiCardOuterPadding, table.top + AiCardTopOffset, table.right - AiCardOuterPadding, table.top + AiCardTopOffset + AiCardHeight)
        drawAiCards(atlas, state.players[1].hand, PlayerId.Ai1, aiArea, state.roundOver, visuals, layoutWidth)
        drawAiCards(atlas, state.players[2].hand, PlayerId.Ai2, aiArea, state.roundOver, visuals, layoutWidth)

        if (state.talkText.isNotBlank()) {
            val x = if (state.talkPlayer == PlayerId.Ai1) table.left + 150f else table.right - 390f
            val bubble = Rect(x, table.top + 100f, x + 250f, table.top + 150f)
            drawRoundRect(Color(0xEEFFFFFF), topLeft = bubble.topLeft, size = bubble.size, cornerRadius = CornerRadius(10f, 10f))
            drawTextNative(state.talkText, Offset(bubble.center.x, bubble.center.y + 9f), 22f, Color(0xFF1D1F1E), alignCenter = true, bold = true)
        }

        if (state.lastCards.isNotEmpty()) {
            drawTextNative(
                "上家：${PaoDeKuaiRules.patternDescription(state.lastPattern ?: HandPattern())}",
                Offset(layoutWidth / 2f, PlayedCardTop - 22f),
                30f,
                Color.White,
                alignCenter = true,
                bold = true,
            )
            drawPlayedCards(atlas, state.lastCards, PlayedCardTop, table, visuals, layoutWidth)
        } else if (visuals.handsSorted) {
            drawTextNative("桌面等待出牌", Offset(layoutWidth / 2f, PlayedCardTop + PlayedCardHeight * 0.52f), 28f, Color(0xBDE7F4DA), alignCenter = true)
        }

        val hand = state.players[0].hand
        val firstHandCard = playerCardRectFor(0, hand.size, layoutWidth)
        drawPlayerCards(
            atlas = atlas,
            cards = hand,
            start = firstHandCard.topLeft,
            cardWidth = PlayerCardWidth,
            cardHeight = PlayerCardHeight,
            step = visibleStepForWidth(PlayerCardWidth),
            selected = state.selectedIndices,
            highlighted = state.hintIndices.toSet() + dragPathIndices,
            hits = hits,
            visuals = visuals,
            layoutWidth = layoutWidth,
        )
        if (!visuals.handsSorted) {
            drawAtlasCard(atlas, null, Offset(layoutWidth / 2f - PlayerCardWidth / 2f, table.top + table.height * 0.48f - PlayerCardHeight / 2f), PlayerCardWidth, PlayerCardHeight)
        }
    }

    val firstCardRect = playerCardRectFor(0, FullHandCardCount, layoutWidth)
    drawTextNative(state.toast.ifBlank { "选择手牌后在右下角出牌；要不起时可不要。" }, Offset(34f, CanvasHeight - 12f), 24f, Color(0xFFE7F4DA))
    val actionY = firstCardRect.top - 52f
    val actions = listOf(
        Rect(firstCardRect.left, actionY, firstCardRect.left + 100f, actionY + 42f) to ((if (state.autoplay) "取消托管" else "托管") to CanvasAction.Autoplay),
        Rect(firstCardRect.left + 112f, actionY, firstCardRect.left + 202f, actionY + 42f) to ("不要" to CanvasAction.Pass),
        Rect(firstCardRect.left + 214f, actionY, firstCardRect.left + 304f, actionY + 42f) to ("提示" to CanvasAction.Hint),
        Rect(firstCardRect.left + 316f, actionY, firstCardRect.left + 416f, actionY + 42f) to ("出牌" to CanvasAction.Play),
    )
    actions.forEach { (rect, pair) ->
        drawCanvasButton(rect, pair.first, primary = pair.second == CanvasAction.Play)
        if (visuals.handsSorted && visuals.sortAnimation <= 0f) hits += HitRegion(rect, pair.second)
    }

    if (forceRoundResult || (state.roundOver && visuals.roundResultVisible)) {
        drawResultPanel(state, hits, layoutWidth)
    }
}

private fun DrawScope.drawConfirmExitOverlay(hits: MutableList<HitRegion>, layoutWidth: Float) {
    drawRect(Color(0x99000000))
    val rect = Rect(layoutWidth / 2f - 240f, CanvasHeight / 2f - 110f, layoutWidth / 2f + 240f, CanvasHeight / 2f + 110f)
    drawRoundRect(Color.White, topLeft = rect.topLeft, size = rect.size, cornerRadius = CornerRadius(14f, 14f))
    drawTextNative("返回主菜单？", Offset(rect.center.x, rect.top + 66f), 40f, Color(0xFF1D1F1E), alignCenter = true, bold = true)
    val cancel = Rect(rect.left + 52f, rect.bottom - 70f, rect.left + 202f, rect.bottom - 18f)
    val ok = Rect(rect.right - 202f, rect.bottom - 70f, rect.right - 52f, rect.bottom - 18f)
    drawCanvasButton(cancel, "取消", primary = false)
    drawCanvasButton(ok, "确认", primary = true)
    hits += HitRegion(cancel, CanvasAction.CancelOverlay)
    hits += HitRegion(ok, CanvasAction.ConfirmExit)
}

private fun DrawScope.drawResultPanel(state: GameState, hits: MutableList<HitRegion>, layoutWidth: Float) {
    val record = state.lastRoundRecord
    val rect = Rect(layoutWidth / 2f - 290f, 150f, layoutWidth / 2f + 290f, 590f)
    drawRect(Color(0x99000000), topLeft = Offset.Zero, size = Size(layoutWidth, CanvasHeight))
    drawRoundRect(Color(0xFA14241F), topLeft = rect.topLeft, size = rect.size, cornerRadius = CornerRadius(14f, 14f))
    drawRoundRect(Color(0xFFEAD77A), topLeft = rect.topLeft, size = rect.size, cornerRadius = CornerRadius(14f, 14f), style = Stroke(1.4f))
    val win = record.winner == PlayerId.Player
    drawTextNative(
        if (win) "胜利" else "失败",
        Offset(rect.center.x, rect.top + 70f),
        42f,
        if (win) Color(0xFFF5D640) else Color(0xFFC7DBEA),
        alignCenter = true,
        bold = true,
    )
    val lines = buildList {
        add("本局得分  玩家 ${record.scores[0]}    AI1 ${record.scores[1]}    AI2 ${record.scores[2]}")
        add("剩余牌数  玩家 ${record.remainingCards[0]}    AI1 ${record.remainingCards[1]}    AI2 ${record.remainingCards[2]}")
        add("炸弹次数  ${record.bombs}    关圆鸡人数 ${record.springLosers}")
        if (record.bombs > 0) add("炸弹固定分已计入，不参与春天翻倍")
        if (record.spring) add("触发关圆鸡 / 春天")
    }
    lines.forEachIndexed { index, line ->
        drawTextNative(line, Offset(rect.left + 70f, rect.top + 132f + index * 38f), 24f, Color(0xFFF1F5DC))
    }
    val newRound = Rect(rect.left + 120f, rect.bottom - 90f, rect.left + 280f, rect.bottom - 42f)
    val mainMenu = Rect(rect.left + 300f, rect.bottom - 90f, rect.left + 460f, rect.bottom - 42f)
    drawCanvasButton(newRound, "再来一局", primary = true)
    drawCanvasButton(mainMenu, "主菜单", primary = false)
    hits += HitRegion(newRound, CanvasAction.NewRound)
    hits += HitRegion(mainMenu, CanvasAction.MainMenu)
}

private fun DrawScope.drawPlayerLabel(state: GameState, player: PlayerId, position: Offset) {
    val text = "${playerDisplayName(player, state.players[0].name)} ${state.players[player.index()].hand.size} 张"
    drawTextNative(text, position, 24f, Color.White, bold = true)
}

private fun DrawScope.drawPlayerCards(
    atlas: ImageBitmap,
    cards: List<Card>,
    start: Offset,
    cardWidth: Float,
    cardHeight: Float,
    step: Float,
    selected: Set<Int>,
    highlighted: Set<Int>,
    hits: MutableList<HitRegion>?,
    visuals: GameVisuals,
    layoutWidth: Float,
) {
    cards.forEachIndexed { index, card ->
        val finalTopLeft = Offset(start.x + index * step, start.y + if (index in selected) -18f else 0f)
        val topLeft = animatedCardTopLeft(
            finalTopLeft = finalTopLeft,
            oldTopLeft = Offset(start.x + visualOldIndex(index, cards.size) * step, start.y),
            cardWidth = cardWidth,
            cardHeight = cardHeight,
            index = index,
            visuals = visuals,
            layoutWidth = layoutWidth,
        )
        if (index in highlighted) {
            drawRect(
                Color(0x99F2C94C),
                topLeft = Offset(topLeft.x - 4f, topLeft.y - 4f),
                size = Size(cardWidth + 8f, cardHeight + 8f),
            )
        }
        drawAtlasCard(atlas, card, topLeft, cardWidth, cardHeight)
        if (visuals.handsSorted && visuals.sortAnimation <= 0f) {
            hits?.add(HitRegion(Rect(finalTopLeft, Size(cardWidth, cardHeight)), CanvasAction.Card(index)))
        }
    }
}

private fun DrawScope.drawAiCards(
    atlas: ImageBitmap,
    cards: List<Card>,
    player: PlayerId,
    area: Rect,
    reveal: Boolean,
    visuals: GameVisuals,
    layoutWidth: Float,
) {
    val cardWidth = AiCardWidth
    val cardHeight = AiCardHeight
    val step = visibleStepForWidth(cardWidth)
    val total = cardRowWidth(FullHandCardCount, cardWidth)
    val startX = if (player == PlayerId.Ai1) area.left else area.right - total
    val start = Offset(startX, area.top)
    cards.forEachIndexed { index, card ->
        val visualIndex = if (player == PlayerId.Ai2) FullHandCardCount - cards.size + index else index
        val oldVisualIndex = if (player == PlayerId.Ai2) {
            FullHandCardCount - cards.size + visualOldIndex(index, cards.size)
        } else {
            visualOldIndex(index, cards.size)
        }
        val finalTopLeft = Offset(start.x + visualIndex * step, start.y)
        val oldTopLeft = Offset(start.x + oldVisualIndex * step, start.y)
        val topLeft = animatedCardTopLeft(finalTopLeft, oldTopLeft, cardWidth, cardHeight, index, visuals, layoutWidth)
        drawAtlasCard(atlas, if (reveal) card else null, topLeft, cardWidth, cardHeight)
    }
    if (!visuals.handsSorted) {
        val progress = cards.indices.count { dealProgress(it, visuals.dealElapsed) >= 1f }
        drawTextNative("$progress/16", Offset(start.x + total / 2f, area.top + cardHeight + 16f), 18f, Color(0xCCE7F4DA), alignCenter = true)
    }
}

private fun DrawScope.drawPlayedCards(
    atlas: ImageBitmap,
    cards: List<Card>,
    y: Float,
    table: Rect,
    visuals: GameVisuals,
    layoutWidth: Float,
) {
    val cardWidth = PlayedCardWidth
    val cardHeight = PlayedCardHeight
    val step = visibleStepForWidth(cardWidth)
    val total = cardRowWidth(cards.size, cardWidth)
    val start = Offset((layoutWidth - total) / 2f, y)
    val source = when (visuals.lastAnimatedPlayer) {
        PlayerId.Ai1 -> Offset(table.left + 250f, table.top + 96f)
        PlayerId.Ai2 -> Offset(table.right - 250f, table.top + 96f)
        PlayerId.Player -> Offset(layoutWidth / 2f, table.bottom - 46f)
    }
    val t = easeInOutWithLinearMiddle(1f - visuals.playAnimation)
    val scale = if (visuals.playAnimation > 0f) lerp(0.35f, 1f, t) else 1f
    cards.forEachIndexed { index, card ->
        val finalTopLeft = Offset(start.x + index * step, start.y)
        var topLeft = finalTopLeft
        var width = cardWidth
        var height = cardHeight
        if (visuals.playAnimation > 0f) {
            val finalCenter = Offset(finalTopLeft.x + cardWidth / 2f, finalTopLeft.y + cardHeight / 2f)
            val center = Offset(lerp(source.x, finalCenter.x, t), lerp(source.y, finalCenter.y, t))
            width = cardWidth * scale
            height = cardHeight * scale
            topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
        }
        drawAtlasCard(atlas, card, topLeft, width, height)
    }
}

private fun DrawScope.animatedCardTopLeft(
    finalTopLeft: Offset,
    oldTopLeft: Offset,
    cardWidth: Float,
    cardHeight: Float,
    index: Int,
    visuals: GameVisuals,
    layoutWidth: Float,
): Offset {
    if (!visuals.handsSorted) {
        val t = easeInOutWithLinearMiddle(dealProgress(index, visuals.dealElapsed))
        val source = Offset(layoutWidth / 2f - cardWidth / 2f, CanvasHeight / 2f - cardHeight / 2f)
        return Offset(lerp(source.x, finalTopLeft.x, t), lerp(source.y, finalTopLeft.y, t))
    }
    if (visuals.sortAnimation > 0f) {
        val t = easeInOutWithLinearMiddle(1f - visuals.sortAnimation)
        return Offset(lerp(oldTopLeft.x, finalTopLeft.x, t), lerp(oldTopLeft.y, finalTopLeft.y, t))
    }
    return finalTopLeft
}

private fun dealProgress(index: Int, elapsed: Float): Float =
    ((elapsed - index * DealCardIntervalSeconds) / DealCardIntervalSeconds).coerceIn(0f, 1f)

private fun visualOldIndex(index: Int, count: Int): Int = if (count <= 1) index else count - 1 - index

private fun visibleStepForWidth(cardWidth: Float): Float = cardWidth * AtlasVisibleRatio

private fun cardRowWidth(count: Int, cardWidth: Float): Float =
    if (count <= 0) 0f else cardWidth + max(0, count - 1) * visibleStepForWidth(cardWidth)

private fun playerCardRectFor(index: Int, count: Int, layoutWidth: Float): Rect {
    val totalWidth = cardRowWidth(count, PlayerCardWidth)
    val startX = layoutWidth / 2f - totalWidth / 2f
    val y = PlayerCardTop
    val x = startX + index * visibleStepForWidth(PlayerCardWidth)
    return Rect(x, y, x + PlayerCardWidth, y + PlayerCardHeight)
}

private fun bombShakeOffset(visuals: GameVisuals): Offset {
    if (visuals.bombAnimation <= 0f) return Offset.Zero
    val power = visuals.bombAnimation
    val x = (sin(visuals.frame * 2.7) * 8.0 * power).toFloat()
    val y = (sin(visuals.frame * 4.1 + 0.8) * 5.0 * power).toFloat()
    return Offset(x, y)
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t.coerceIn(0f, 1f)

private fun easeInOutWithLinearMiddle(t: Float, edge: Float = 0.2f): Float {
    val p = t.coerceIn(0f, 1f)
    val e = edge.coerceIn(0.001f, 0.499f)
    val velocity = 1f / (1f - e)
    return when {
        p < e -> velocity * p * p / (2f * e)
        p > 1f - e -> {
            val q = 1f - p
            1f - velocity * q * q / (2f * e)
        }
        else -> velocity * (p - e / 2f)
    }
}

private fun DrawScope.drawAtlasCard(atlas: ImageBitmap, card: Card?, topLeft: Offset, width: Float, height: Float) {
    val source = atlasSource(card)
    drawImage(
        image = atlas,
        srcOffset = IntOffset(source.first, source.second),
        srcSize = IntSize(240, 336),
        dstOffset = IntOffset(topLeft.x.toInt(), topLeft.y.toInt()),
        dstSize = IntSize(width.toInt(), height.toInt()),
    )
}

private fun atlasSource(card: Card?): Pair<Int, Int> {
    val index = if (card == null) 54 else {
        val suitOffset = when (card.suit) {
            Suit.Spades -> 0
            Suit.Hearts -> 13
            Suit.Diamonds -> 26
            Suit.Clubs -> 39
        }
        val rankOffset = when (card.rank) {
            Rank.Ace -> 0
            Rank.Two -> 1
            Rank.Three -> 2
            Rank.Four -> 3
            Rank.Five -> 4
            Rank.Six -> 5
            Rank.Seven -> 6
            Rank.Eight -> 7
            Rank.Nine -> 8
            Rank.Ten -> 9
            Rank.Jack -> 10
            Rank.Queen -> 11
            Rank.King -> 12
        }
        suitOffset + rankOffset
    }
    return (index % 13) * 241 to (index / 13) * 337
}

private fun DrawScope.drawCanvasButton(rect: Rect, label: String, primary: Boolean) {
    val fill = if (primary) Color(0xFF2F6B56) else Color(0xFF2B5D4D)
    val stroke = if (primary) Color(0xFFEAD77A) else Color(0xCCEBE0A0)
    val text = Color(0xFFFAFAE7)
    drawRect(fill, topLeft = rect.topLeft, size = rect.size)
    drawRect(Color(0x33000000), topLeft = Offset(rect.left + 2f, rect.top + 2f), size = Size(rect.width - 4f, rect.height - 4f), style = Stroke(1f))
    drawRect(stroke, topLeft = rect.topLeft, size = rect.size, style = Stroke(1.5f))
    drawTextNative(label, Offset(rect.center.x, rect.center.y + 10f), 24f, text, alignCenter = true, bold = true)
}

private fun DrawScope.drawCanvasCircleButton(rect: Rect, label: String, primary: Boolean) {
    val fill = if (primary) Color(0xFF2F6B56) else Color(0xFF2B5D4D)
    val stroke = if (primary) Color(0xFFEAD77A) else Color(0xCCEBE0A0)
    drawOval(fill, topLeft = rect.topLeft, size = rect.size)
    drawOval(stroke, topLeft = rect.topLeft, size = rect.size, style = Stroke(1.5f))
    drawTextNative(label, Offset(rect.center.x, rect.center.y + 10f), 24f, Color(0xFFFAFAE7), alignCenter = true, bold = true)
}

private fun DrawScope.drawTextNative(
    text: String,
    position: Offset,
    size: Float,
    color: Color,
    alignCenter: Boolean = false,
    bold: Boolean = false,
) {
    drawIntoCanvas { canvas ->
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toArgbCompat()
            textSize = size
            textAlign = if (alignCenter) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        canvas.nativeCanvas.drawText(text, position.x, position.y, paint)
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)

private fun helpText(): String = """
三人固定跑得快，黑桃 3 先出。
牌型包含单张、对子、顺子、连对、三带二、飞机和炸弹。
要得起必须出；两家连续不要后，由上次出牌者重新领出。
所有操作都在横屏 Canvas 内完成。
""".trimIndent()

private enum class SoundId(@RawRes val rawRes: Int, val volume: Float) {
    ButtonClick(R.raw.ui_button_click, 0.55f),
    Confirm(R.raw.ui_confirm, 0.60f),
    Cancel(R.raw.ui_cancel, 0.55f),
    Toast(R.raw.ui_toast, 0.45f),
    Pause(R.raw.ui_pause, 0.50f),
    Resume(R.raw.ui_resume, 0.50f),
    SelectCard(R.raw.card_select, 0.50f),
    DeselectCard(R.raw.card_deselect, 0.48f),
    DealCard(R.raw.card_deal, 0.42f),
    PlayCards(R.raw.card_play, 0.62f),
    Pass(R.raw.card_pass, 0.52f),
    Hint(R.raw.card_hint, 0.50f),
    InvalidMove(R.raw.game_invalid_move, 0.58f),
    TurnPrompt(R.raw.game_turn_prompt, 0.52f),
    RoundStart(R.raw.game_round_start, 0.50f),
    RoundEnd(R.raw.game_round_end, 0.55f),
    Bomb(R.raw.event_bomb, 0.75f),
    Spring(R.raw.event_spring, 0.70f),
    Win(R.raw.event_win, 0.68f),
    Lose(R.raw.event_lose, 0.58f),
    AiTalk(R.raw.event_ai_talk, 0.42f),
}

private class SoundPlayer(context: Context) {
    private val soundPool: SoundPool
    private val soundIds: MutableMap<SoundId, Int> = mutableMapOf()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()
    private val scheduledSounds = PriorityBlockingQueue(
        32,
        compareBy<ScheduledSound> { it.dueNanos }.thenBy { it.sequence },
    )
    private val nextSequence = AtomicLong()
    private val released = AtomicBoolean(false)
    @Volatile
    private var masterVolume: Float = 0.8f
    private val worker = Thread({ runScheduler() }, "PdkAudioScheduler").apply {
        isDaemon = true
        start()
    }

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(attrs).build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) loaded += sampleId
        }
        SoundId.entries.forEach { id ->
            soundIds[id] = soundPool.load(context, id.rawRes, 1)
        }
    }

    fun play(id: SoundId) {
        scheduleAt(id, System.nanoTime())
    }

    fun setMasterVolume(volume: Float) {
        masterVolume = volume.coerceIn(0f, 1f)
    }

    fun schedule(id: SoundId, delayNanos: Long) {
        scheduleAt(id, System.nanoTime() + delayNanos.coerceAtLeast(0L))
    }

    fun scheduleDealCards(count: Int = DealSoundCount, intervalSeconds: Float = DealCardIntervalSeconds) {
        val startNanos = System.nanoTime()
        val intervalNanos = (intervalSeconds * NanosPerSecond).toLong()
        repeat(count) { index ->
            scheduleAt(SoundId.DealCard, startNanos + (index + 1).toLong() * intervalNanos)
        }
    }

    fun playForEvent(event: GameEvent, spring: Boolean) {
        when (event.type) {
            GameEventType.CardsPlayed -> play(SoundId.PlayCards)
            GameEventType.Passed -> play(SoundId.Pass)
            GameEventType.InvalidMove -> play(SoundId.InvalidMove)
            GameEventType.Hint -> play(SoundId.Hint)
            GameEventType.Bomb -> play(SoundId.Bomb)
            GameEventType.RoundEnded -> {
                play(SoundId.RoundEnd)
                if (spring) schedule(SoundId.Spring, ResultSoundSpacingNanos)
                schedule(if (event.player == PlayerId.Player) SoundId.Win else SoundId.Lose, if (spring) ResultSoundSpacingNanos * 2 else ResultSoundSpacingNanos)
            }
            GameEventType.RoundStarted -> Unit
            GameEventType.Talk -> play(if (event.player == PlayerId.Player) SoundId.TurnPrompt else SoundId.AiTalk)
        }
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        scheduledSounds.put(ScheduledSound(null, Long.MIN_VALUE, nextSequence.incrementAndGet()))
        LockSupport.unpark(worker)
        worker.join(200)
        soundPool.release()
    }

    private fun scheduleAt(id: SoundId, dueNanos: Long, retryCount: Int = 0) {
        if (released.get()) return
        scheduledSounds.put(ScheduledSound(id, dueNanos, nextSequence.incrementAndGet(), retryCount))
        LockSupport.unpark(worker)
    }

    private fun runScheduler() {
        while (true) {
            val sound = scheduledSounds.take()
            val id = sound.id ?: return
            val waitNanos = sound.dueNanos - System.nanoTime()
            if (waitNanos > 0L) {
                scheduledSounds.put(sound)
                LockSupport.parkNanos(this, min(waitNanos, MaxSchedulerParkNanos))
                continue
            }
            playDueSound(id, sound.retryCount)
        }
    }

    private fun playDueSound(id: SoundId, retryCount: Int) {
        if (released.get()) return
        val sample = soundIds[id] ?: return
        if (sample !in loaded) {
            if (retryCount < MaxLoadRetries) {
                scheduleAt(id, System.nanoTime() + LoadRetryNanos, retryCount + 1)
            }
            return
        }
        val volume = id.volume * masterVolume
        soundPool.play(sample, volume, volume, 1, 0, 1f)
    }

    private data class ScheduledSound(
        val id: SoundId?,
        val dueNanos: Long,
        val sequence: Long,
        val retryCount: Int = 0,
    )

    private companion object {
        private const val NanosPerSecond = 1_000_000_000f
        private const val MaxSchedulerParkNanos = 2_000_000L
        private const val LoadRetryNanos = 10_000_000L
        private const val MaxLoadRetries = 50
        private const val ResultSoundSpacingNanos = 80_000_000L
    }
}

private class AndroidLlmAiController(
    private val providers: Map<PlayerId, AiProviderSettings>,
) : ExternalAiController {
    private val pending = AtomicBoolean(false)
    private val result = AtomicReference<ExternalAiResult?>(null)
    private var worker: Thread? = null

    override fun canHandle(player: PlayerId): Boolean = player in providers

    override fun hasPending(): Boolean = pending.get()

    override fun start(request: ExternalAiRequest) {
        cancel()
        pending.set(true)
        worker = Thread {
            val nextResult = runCatching {
                val provider = providers[request.player]
                    ?: return@runCatching ExternalAiResult(ok = false, errorMessage = "未配置 ${request.player} 的外部 AI")
                if (provider.apiKey.isBlank()) {
                    return@runCatching ExternalAiResult(ok = false, errorMessage = "AI API Key 为空，已回退本地 AI")
                }
                val messages = PdkAiProtocol.buildMessages(request)
                val body = PdkAiProtocol.buildRequestJson(provider, messages)
                val connection = (URL(provider.endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 30_000
                    readTimeout = 30_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Authorization", "Bearer ${provider.apiKey}")
                }
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()
                if (status !in 200..299) {
                    ExternalAiResult(ok = false, errorMessage = "AI HTTP $status: $response")
                } else {
                    val parsed = PdkAiProtocol.parseResponse(response)
                    ExternalAiResult(
                        ok = parsed.ok,
                        requestedAction = parsed.action,
                        reasoningContent = parsed.reasoningContent,
                        toolCallId = parsed.toolCallId,
                        toolName = parsed.toolName,
                        toolArgumentsJson = parsed.toolArgumentsJson,
                        errorMessage = parsed.errorMessage,
                    )
                }
            }.getOrElse { error ->
                ExternalAiResult(ok = false, errorMessage = error.message ?: error::class.java.simpleName)
            }
            result.set(nextResult)
            pending.set(false)
        }.also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    override fun tryGetResult(): ExternalAiResult? = result.getAndSet(null)

    override fun cancel() {
        worker?.interrupt()
        worker = null
        pending.set(false)
        result.set(null)
    }
}
