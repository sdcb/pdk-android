package com.example.pdkandroid

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PdkTheme {
                SoundCanvasScreen()
            }
        }
    }
}

@Composable
private fun PdkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF2F7D5C),
            secondary = Color(0xFFF2B84B),
            background = Color(0xFFF7F4EA),
            surface = Color(0xFFFFFFFF),
            onPrimary = Color.White,
            onBackground = Color(0xFF1D1F1E),
            onSurface = Color(0xFF1D1F1E),
        ),
        content = content,
    )
}

@Composable
private fun SoundCanvasScreen() {
    val context = LocalContext.current
    val soundPlayer = remember { SoundPlayer(context.applicationContext) }
    var taps by remember { mutableIntStateOf(0) }

    DisposableEffect(soundPlayer) {
        onDispose { soundPlayer.release() }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.safeDrawing.asPaddingValues())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Hello World",
                        fontSize = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Canvas taps: $taps",
                        color = Color(0xFF66716C),
                        fontSize = 14.sp,
                    )
                }
                Button(
                    onClick = {
                        taps += 1
                        soundPlayer.play()
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Play")
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(360.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF10231B)),
            ) {
                SoundCanvas(
                    taps = taps,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(soundPlayer) {
                            detectTapGestures {
                                taps += 1
                                soundPlayer.play()
                            }
                        },
                )
            }
        }
    }
}

@Composable
private fun SoundCanvas(taps: Int, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "canvas")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val minSide = minOf(size.width, size.height)
        val baseRadius = minSide * 0.24f
        val pulse = 1f + ((taps % 7) * 0.035f)
        val orbitRadius = baseRadius * (1.1f + phase * 0.18f)

        drawRect(color = Color(0xFF10231B))

        for (i in 0 until 3) {
            val radius = baseRadius * pulse + (i * minSide * 0.075f) + phase * minSide * 0.065f
            drawCircle(
                color = Color(0xFF76D0A5).copy(alpha = 0.28f - i * 0.055f),
                radius = radius,
                center = center,
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }

        for (i in 0 until 18) {
            val angle = (PI * 2.0 * (i / 18.0 + phase)).toFloat()
            val inner = Offset(
                x = center.x + cos(angle) * orbitRadius * 0.55f,
                y = center.y + sin(angle) * orbitRadius * 0.55f,
            )
            val outer = Offset(
                x = center.x + cos(angle) * orbitRadius,
                y = center.y + sin(angle) * orbitRadius,
            )
            drawLine(
                color = if (i % 2 == 0) Color(0xFFF2B84B) else Color(0xFF71B7E6),
                start = inner,
                end = outer,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
                alpha = 0.82f,
            )
        }

        drawOval(
            color = Color(0xFFF7F4EA),
            topLeft = Offset(center.x - baseRadius * 0.9f, center.y - baseRadius * 0.55f),
            size = Size(baseRadius * 1.8f, baseRadius * 1.1f),
        )
        drawCircle(
            color = Color(0xFFF2B84B),
            radius = baseRadius * 0.34f,
            center = center.copy(y = center.y - baseRadius * 0.1f),
        )
    }
}

private class SoundPlayer(context: Context) {
    private val soundPool: SoundPool
    private val soundId: Int
    private var loaded = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(attrs)
            .build()

        soundId = soundPool.load(context, R.raw.beep, 1)
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            loaded = sampleId == soundId && status == 0
        }
    }

    fun play() {
        if (loaded) {
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }
    }

    fun release() {
        soundPool.release()
    }
}
