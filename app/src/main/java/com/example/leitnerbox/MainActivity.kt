package com.example.leitnerbox

import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.leitnerbox.ui.theme.LeitnerBoxTheme
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

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
private const val csvImportTag = "CsvImport"
private const val cardsPrefsName = "leitner_cards_prefs"
private const val cardsPrefsKey = "cards_json"

private enum class AppScreen {
    Home,
    Practice,
    Cards,
    ManageCards
}

@Composable
private fun LeitnerApp() {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences(cardsPrefsName, ComponentActivity.MODE_PRIVATE)
    }
    val cards = remember {
        mutableStateListOf<FlashCard>().apply {
            addAll(loadCards(sharedPreferences))
        }
    }
    var showLanding by remember { mutableStateOf(true) }
    var currentScreen by remember { mutableStateOf(AppScreen.Home) }
    var frontText by remember { mutableStateOf("") }
    var backText by remember { mutableStateOf("") }
    var importMessage by remember { mutableStateOf<String?>(null) }
    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openInputStream(uri)?.use(::parseFlashCardsFromCsv)
                ?: error("Unable to open the selected CSV file.")
        }.onSuccess { importedCards ->
            importMessage = if (importedCards.isEmpty()) {
                "No cards were imported. Expected columns: front, back."
            } else {
                cards.addAll(0, importedCards)
                "Imported ${importedCards.size} card${if (importedCards.size == 1) "" else "s"}."
            }
        }.onFailure { throwable ->
            Log.e(csvImportTag, "Failed to import CSV", throwable)
            importMessage = throwable.message ?: "CSV import failed."
        }
    }

    LaunchedEffect(Unit) {
        delay(2_000)
        showLanding = false
    }

    LaunchedEffect(cards) {
        snapshotFlow { cards.toList() }
            .collect { currentCards ->
                saveCards(sharedPreferences, currentCards)
            }
    }

    if (showLanding) {
        LandingScreen()
    } else {
        when (currentScreen) {
            AppScreen.Home -> {
                HomeScreen(
                    cards = cards,
                    onOpenCards = { currentScreen = AppScreen.Cards },
                    onOpenManageCards = { currentScreen = AppScreen.ManageCards },
                    onStartPractice = { currentScreen = AppScreen.Practice }
                )
            }
            AppScreen.Practice -> {
                PracticeScreen(
                    cards = cards,
                    onExit = { currentScreen = AppScreen.Home }
                )
            }
            AppScreen.Cards -> {
                CardsScreen(
                    cards = cards,
                    onRemoveCard = { cardId ->
                        cards.removeAll { it.id == cardId }
                    },
                    onBack = { currentScreen = AppScreen.Home }
                )
            }
            AppScreen.ManageCards -> {
                ManageCardsScreen(
                    frontText = frontText,
                    backText = backText,
                    importMessage = importMessage,
                    onFrontChange = { frontText = it },
                    onBackChange = { backText = it },
                    onAddCard = {
                        val front = frontText.trim()
                        val back = backText.trim()
                        if (front.isNotEmpty() && back.isNotEmpty()) {
                            cards.add(0, FlashCard(front = front, back = back))
                            frontText = ""
                            backText = ""
                            importMessage = null
                        }
                    },
                    onImportCsv = {
                        importMessage = null
                        csvPicker.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv"))
                    },
                    onBack = { currentScreen = AppScreen.Home }
                )
            }
        }
    }
}

@Composable
private fun LandingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B1F33),
                        Color(0xFF174A73),
                        Color(0xFFF4B942)
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color.White.copy(alpha = 0.14f)
            ) {
                Text(
                    text = "Leitner Box",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
                )
            }
            Text(
                text = "Train faster. Remember longer.",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )
            Text(
                text = "A focused flashcard flow for building, importing, and reviewing vocabulary.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = Color.White.copy(alpha = 0.88f)
            )
        }
    }
}

@Composable
private fun HomeScreen(
    cards: List<FlashCard>,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit,
    onStartPractice: () -> Unit
) {
    val dueCount = cards.count { it.nextReviewAt <= System.currentTimeMillis() }
    var menuExpanded by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Leitner Box",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Build your card pool, then start a focused practice session.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Text(
                                text = "\u2630",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("View all cards") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenCards()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Add or import cards") },
                                onClick = {
                                    menuExpanded = false
                                    onOpenManageCards()
                                }
                            )
                        }
                    }
                }
            }

            item {
                StatsRow(cards = cards, dueCount = dueCount)
            }

            item {
                LeitnerStatusCard(cards = cards)
            }

            item {
                LeitnerReviewPlanCard(cards = cards)
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
        }
    }
}

@Composable
private fun ManageCardsScreen(
    frontText: String,
    backText: String,
    importMessage: String?,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onAddCard: () -> Unit,
    onImportCsv: () -> Unit,
    onBack: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Add and import cards",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Create a card manually or load a CSV with front and back columns.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            }

            item {
                SectionCard(title = "Add a flashcard") {
                    OutlinedTextField(
                        value = frontText,
                        onValueChange = onFrontChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Word or phrase") }
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
                SectionCard(title = "Import from CSV") {
                    Text(
                        text = "CSV format: front,back",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(onClick = onImportCsv) {
                        Text("Choose CSV file")
                    }
                    importMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp)
                ) {
                    Text("Return home")
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
    val sessionCardIds = remember {
        mutableStateListOf<String>().apply {
            addAll(cards.filter { it.nextReviewAt <= System.currentTimeMillis() }.map { it.id })
        }
    }
    val currentCard = cards.firstOrNull { it.id == sessionCardIds.firstOrNull() }

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
                                text = "${sessionCardIds.size} left in this session",
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
                                    sessionCardIds.remove(currentCard.id)
                                    isFlipped = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Not yet")
                            }
                            Button(
                                onClick = {
                                    updateCard(cards, currentCard.id, wasCorrect = true)
                                    sessionCardIds.remove(currentCard.id)
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
private fun CardsScreen(
    cards: List<FlashCard>,
    onRemoveCard: (String) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Card library",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${cards.size} available card${if (cards.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            }

            item {
                if (cards.isEmpty()) {
                    EmptyState(
                        title = "No flashcards yet",
                        subtitle = "Add cards from the home screen or import them from CSV."
                    )
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                    shape = RoundedCornerShape(20.dp)
                                )
                        ) {
                            CardTableHeader()
                            cards.forEachIndexed { index, card ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                CardTableRow(
                                    card = card,
                                    onRemove = { onRemoveCard(card.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardTableHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        TableCell(
            text = "Front",
            weight = 1.4f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Back",
            weight = 1.4f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Box",
            weight = 0.5f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Review",
            weight = 0.9f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "",
            weight = 0.7f,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CardTableRow(
    card: FlashCard,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableCell(text = card.front, weight = 1.4f)
        TableCell(text = card.back, weight = 1.4f)
        TableCell(text = card.box.toString(), weight = 0.5f)
        TableCell(text = reviewLabel(card.nextReviewAt), weight = 0.9f)
        TextButton(
            onClick = onRemove,
            modifier = Modifier.weight(0.7f)
        ) {
            Text(
                text = "\u00D7",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun RowScope.TableCell(
    text: String,
    weight: Float,
    fontWeight: FontWeight? = null
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = fontWeight,
        color = MaterialTheme.colorScheme.onSurface
    )
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

private fun parseFlashCardsFromCsv(inputStream: InputStream): List<FlashCard> {
    val rows = buildList {
        BufferedReader(InputStreamReader(inputStream)).useLines { lines ->
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    add(parseCsvLine(line))
                }
            }
        }
    }

    if (rows.isEmpty()) return emptyList()

    val hasHeader = rows.first().let { firstRow ->
        firstRow.size >= 2 &&
            firstRow[0].trim().equals("front", ignoreCase = true) &&
            firstRow[1].trim().equals("back", ignoreCase = true)
    }

    return rows
        .drop(if (hasHeader) 1 else 0)
        .mapNotNull { columns ->
            val front = columns.getOrNull(0)?.trim().orEmpty()
            val back = columns.getOrNull(1)?.trim().orEmpty()
            if (front.isEmpty() || back.isEmpty()) {
                null
            } else {
                FlashCard(front = front, back = back)
            }
        }
}

private fun parseCsvLine(line: String): List<String> {
    val values = mutableListOf<String>()
    val current = StringBuilder()
    var inQuotes = false
    var index = 0

    while (index < line.length) {
        val char = line[index]
        when {
            char == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                current.append('"')
                index++
            }
            char == '"' -> inQuotes = !inQuotes
            char == ',' && !inQuotes -> {
                values.add(current.toString())
                current.clear()
            }
            else -> current.append(char)
        }
        index++
    }

    values.add(current.toString())
    return values
}

private fun loadCards(sharedPreferences: android.content.SharedPreferences): List<FlashCard> {
    val json = sharedPreferences.getString(cardsPrefsKey, null) ?: return defaultCards()

    return runCatching {
        val array = JSONArray(json)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            FlashCard(
                id = item.getString("id"),
                front = item.getString("front"),
                back = item.getString("back"),
                box = item.optInt("box", 1),
                nextReviewAt = item.optLong("nextReviewAt", System.currentTimeMillis())
            )
        }
    }.getOrElse {
        defaultCards()
    }
}

private fun saveCards(
    sharedPreferences: android.content.SharedPreferences,
    cards: List<FlashCard>
) {
    val json = JSONArray().apply {
        cards.forEach { card ->
            put(
                JSONObject().apply {
                    put("id", card.id)
                    put("front", card.front)
                    put("back", card.back)
                    put("box", card.box)
                    put("nextReviewAt", card.nextReviewAt)
                }
            )
        }
    }

    sharedPreferences
        .edit()
        .putString(cardsPrefsKey, json.toString())
        .apply()
}

private fun defaultCards(): List<FlashCard> = listOf(
    FlashCard(front = "hond", back = "dog"),
    FlashCard(front = "huis", back = "house"),
    FlashCard(front = "dank je wel", back = "thank you")
)

@Composable
private fun StatsRow(cards: List<FlashCard>, dueCount: Int) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatPill(label = "Cards", value = cards.size.toString())
        StatPill(label = "Due now", value = dueCount.toString())
        StatPill(label = "Mastered", value = cards.count { it.box == 5 }.toString())
    }
}

@Composable
private fun LeitnerStatusCard(cards: List<FlashCard>) {
    SectionCard(title = "Leitner status") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BoxStatusPill(box = 1, label = "New", count = cards.count { it.box == 1 })
            BoxStatusPill(box = 2, label = "1 day", count = cards.count { it.box == 2 })
            BoxStatusPill(box = 3, label = "3 days", count = cards.count { it.box == 3 })
            BoxStatusPill(box = 4, label = "7 days", count = cards.count { it.box == 4 })
            BoxStatusPill(box = 5, label = "14 days", count = cards.count { it.box == 5 })
        }
    }
}

@Composable
private fun LeitnerReviewPlanCard(cards: List<FlashCard>) {
    val now = System.currentTimeMillis()

    SectionCard(title = "Review by box") {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ReviewDuePill(box = 1, dueCount = cards.count { it.box == 1 && it.nextReviewAt <= now })
            ReviewDuePill(box = 2, dueCount = cards.count { it.box == 2 && it.nextReviewAt <= now })
            ReviewDuePill(box = 3, dueCount = cards.count { it.box == 3 && it.nextReviewAt <= now })
            ReviewDuePill(box = 4, dueCount = cards.count { it.box == 4 && it.nextReviewAt <= now })
            ReviewDuePill(box = 5, dueCount = cards.count { it.box == 5 && it.nextReviewAt <= now })
        }
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
private fun BoxStatusPill(box: Int, label: String, count: Int) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Box $box",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Column {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ReviewDuePill(box: Int, dueCount: Int) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Box $box",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dueCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
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
