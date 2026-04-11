package com.example.leitnerbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.leitnerbox.ui.theme.LeitnerBoxTheme
import java.util.UUID
import kotlin.math.min

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LeitnerBoxTheme {
                LeitnerApp()
            }
        }
    }
}

private data class FlashCard(
    val id: String = UUID.randomUUID().toString(),
    val front: String,
    val back: String,
    val box: Int = 1,
    val nextReviewAt: Long = System.currentTimeMillis()
)

private val boxIntervalsInDays = listOf(0, 1, 3, 7, 14)
private const val dayInMillis = 24L * 60L * 60L * 1000L

@Composable
private fun LeitnerApp() {
    val cards = remember {
        mutableStateListOf(
            FlashCard(front = "hond", back = "dog"),
            FlashCard(front = "huis", back = "house"),
            FlashCard(front = "dank je wel", back = "thank you")
        )
    }
    var isPracticeMode by remember { mutableStateOf(false) }
    var frontText by remember { mutableStateOf("") }
    var backText by remember { mutableStateOf("") }

    if (isPracticeMode) {
        PracticeScreen(
            cards = cards,
            onExit = { isPracticeMode = false }
        )
    } else {
        HomeScreen(
            cards = cards,
            frontText = frontText,
            backText = backText,
            onFrontChange = { frontText = it },
            onBackChange = { backText = it },
            onAddCard = {
                val front = frontText.trim()
                val back = backText.trim()
                if (front.isNotEmpty() && back.isNotEmpty()) {
                    cards.add(0, FlashCard(front = front, back = back))
                    frontText = ""
                    backText = ""
                }
            },
            onStartPractice = { isPracticeMode = true }
        )
    }
}

@Composable
private fun HomeScreen(
    cards: List<FlashCard>,
    frontText: String,
    backText: String,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onAddCard: () -> Unit,
    onStartPractice: () -> Unit
) {
    val dueCount = cards.count { it.nextReviewAt <= System.currentTimeMillis() }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text(
                        text = "Dutch Leitner Box",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Build your card pool, then start a focused practice session.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                StatsRow(cards = cards, dueCount = dueCount)
            }

            item {
                SectionCard(title = "Practice") {
                    Text(
                        text = if (dueCount > 0) {
                            "$dueCount card${if (dueCount == 1) "" else "s"} ready to review."
                        } else {
                            "No cards are due yet. New cards become available immediately."
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Button(
                        onClick = onStartPractice,
                        enabled = cards.isNotEmpty(),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text("Start practice")
                    }
                }
            }

            item {
                SectionCard(title = "Add a flashcard") {
                    OutlinedTextField(
                        value = frontText,
                        onValueChange = onFrontChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Dutch word or phrase") }
                    )
                    OutlinedTextField(
                        value = backText,
                        onValueChange = onBackChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        label = { Text("Meaning in English") }
                    )
                    Button(
                        onClick = onAddCard,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Add card")
                    }
                }
            }

            item {
                SectionCard(title = "All cards") {
                    if (cards.isEmpty()) {
                        EmptyState(
                            title = "No flashcards yet",
                            subtitle = "Add your first Dutch word above."
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            cards.forEach { card ->
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = card.front,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = card.back,
                                            style = MaterialTheme.typography.bodyLarge,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                        Text(
                                            text = "Box ${card.box} - ${reviewLabel(card.nextReviewAt)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PracticeScreen(
    cards: MutableList<FlashCard>,
    onExit: () -> Unit
) {
    var isFlipped by remember { mutableStateOf(false) }
    val dueCards = cards.filter { it.nextReviewAt <= System.currentTimeMillis() }
    val currentCard = dueCards.firstOrNull()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp)
        ) {
            if (currentCard == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .align(Alignment.Center),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Practice complete",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "There are no more due cards in this session.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Button(
                        onClick = onExit,
                        modifier = Modifier.padding(top = 20.dp)
                    ) {
                        Text("Back to cards")
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Practice",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${dueCards.size} due now",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(onClick = onExit) {
                            Text("Exit")
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Tap the card to flip it",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        FlippableFlashCard(
                            card = currentCard,
                            isFlipped = isFlipped,
                            onFlip = { isFlipped = !isFlipped },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (isFlipped) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    updateCard(cards, currentCard.id, wasCorrect = false)
                                    isFlipped = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Not yet")
                            }
                            Button(
                                onClick = {
                                    updateCard(cards, currentCard.id, wasCorrect = true)
                                    isFlipped = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Know it")
                            }
                        }
                    } else {
                        Text(
                            text = "Front side only until you flip the card.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FlippableFlashCard(
    card: FlashCard,
    isFlipped: Boolean,
    onFlip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "cardRotation"
    )
    val showBack = rotation > 90f

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 12f * density
                }
                .clickable(onClick = onFlip),
            colors = CardDefaults.cardColors(
                containerColor = if (showBack) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = RoundedCornerShape(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                if (showBack) {
                    CardFace(
                        title = "Back",
                        text = card.back,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { rotationY = 180f }
                    )
                } else {
                    CardFace(
                        title = "Front",
                        text = card.front,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

@Composable
private fun CardFace(
    title: String,
    text: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = text,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Tap to flip",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .alpha(0.8f)
        )
    }
}

private fun updateCard(
    cards: MutableList<FlashCard>,
    cardId: String,
    wasCorrect: Boolean
) {
    val index = cards.indexOfFirst { it.id == cardId }
    if (index == -1) return

    val current = cards[index]
    val newBox = if (wasCorrect) min(current.box + 1, boxIntervalsInDays.size) else 1
    val intervalDays = boxIntervalsInDays[newBox - 1]
    val nextReviewAt = System.currentTimeMillis() + intervalDays * dayInMillis

    cards[index] = current.copy(
        box = newBox,
        nextReviewAt = nextReviewAt
    )
}

private fun reviewLabel(nextReviewAt: Long): String {
    val delta = nextReviewAt - System.currentTimeMillis()
    if (delta <= 0L) return "Due now"
    val days = (delta + dayInMillis - 1) / dayInMillis
    return if (days == 1L) "Due in 1 day" else "Due in $days days"
}

@Composable
private fun StatsRow(cards: List<FlashCard>, dueCount: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatPill(label = "Cards", value = cards.size.toString())
        StatPill(label = "Due now", value = dueCount.toString())
        StatPill(label = "Box 5", value = cards.count { it.box == 5 }.toString())
    }
}

@Composable
private fun StatPill(label: String, value: String) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Column(
                modifier = Modifier.padding(top = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun LeitnerAppPreview() {
    LeitnerBoxTheme {
        LeitnerApp()
    }
}
