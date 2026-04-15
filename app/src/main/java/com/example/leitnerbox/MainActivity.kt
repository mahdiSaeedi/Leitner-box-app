package com.example.leitnerbox

import android.Manifest
import android.os.Bundle
import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.example.leitnerbox.ui.theme.LeitnerBoxTheme
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

data class FlashCard(
    val id: String = UUID.randomUUID().toString(),
    val deckId: String,
    val front: String,
    val back: String,
    val box: Int = 1,
    val nextReviewAt: Long = System.currentTimeMillis()
)

data class Deck(
    val id: String = UUID.randomUUID().toString(),
    val name: String
)

private data class CardReviewResult(
    val wasCorrect: Boolean,
    val previousBox: Int,
    val newBox: Int,
    val nextReviewAt: Long
)

private data class PracticeSessionSummary(
    val totalReviewed: Int,
    val correctCount: Int,
    val incorrectCount: Int,
    val promotedCount: Int,
    val demotedCount: Int
)

private data class NextDueLoad(
    val dueAt: Long,
    val cardCount: Int
)

private data class CardImportResult(
    val cardsToAdd: List<FlashCard>,
    val duplicateCount: Int
)

private data class LoadedDeckData(
    val decks: List<Deck>,
    val cards: List<FlashCard>
)

private val boxIntervalsInDays = listOf(0, 1, 3, 7, 14)
private const val dayInMillis = 24L * 60L * 60L * 1000L
private const val csvImportTag = "CsvImport"
private const val decksPrefsKey = "decks_json"
private const val defaultDeckName = "General"
const val cardsPrefsName = "leitner_cards_prefs"
const val cardsPrefsKey = "cards_json"

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
    val loadedDeckData = remember(sharedPreferences) {
        loadDeckData(sharedPreferences)
    }
    val decks = remember {
        mutableStateListOf<Deck>().apply {
            addAll(loadedDeckData.decks)
        }
    }
    val cards = remember {
        mutableStateListOf<FlashCard>().apply {
            addAll(loadedDeckData.cards)
        }
    }
    var showLanding by remember { mutableStateOf(true) }
    var currentScreen by remember { mutableStateOf(AppScreen.Home) }
    var selectedDeckId by remember {
        mutableStateOf(decks.firstOrNull()?.id ?: createDefaultDeck().id)
    }
    var frontText by remember { mutableStateOf("") }
    var backText by remember { mutableStateOf("") }
    var deckNameText by remember { mutableStateOf("") }
    var csvMessage by remember { mutableStateOf<String?>(null) }
    val selectedDeck = decks.firstOrNull { it.id == selectedDeckId } ?: decks.first()
    val selectedDeckCards = cards.filter { it.deckId == selectedDeck.id }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    val csvPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openInputStream(uri)?.use(::parseFlashCardsFromCsv)
                ?: error("Unable to open the selected CSV file.")
        }.onSuccess { importedCards ->
            if (importedCards.isEmpty()) {
                csvMessage = "No cards were imported. Expected columns: front, back."
            } else {
                val importResult = filterNewCards(
                    existingCards = selectedDeckCards,
                    candidateCards = importedCards.map { it.copy(deckId = selectedDeck.id) }
                )
                if (importResult.cardsToAdd.isNotEmpty()) {
                    cards.addAll(0, importResult.cardsToAdd)
                }
                csvMessage = csvImportMessage(importResult)
            }
        }.onFailure { throwable ->
            Log.e(csvImportTag, "Failed to import CSV", throwable)
            csvMessage = throwable.message ?: "CSV import failed."
        }
    }
    val csvExporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                writeFlashCardsToCsv(outputStream, selectedDeckCards)
            } ?: error("Unable to create the selected CSV file.")
        }.onSuccess {
            csvMessage = "Exported ${selectedDeckCards.size} card${if (selectedDeckCards.size == 1) "" else "s"} from ${selectedDeck.name}."
        }.onFailure { throwable ->
            Log.e(csvImportTag, "Failed to export CSV", throwable)
            csvMessage = throwable.message ?: "CSV export failed."
        }
    }

    LaunchedEffect(Unit) {
        delay(2_000)
        showLanding = false
    }

    LaunchedEffect(Unit) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(cards) {
        snapshotFlow { cards.toList() }
            .collect { currentCards ->
                saveDeckData(
                    sharedPreferences = sharedPreferences,
                    decks = decks,
                    cards = currentCards
                )
                scheduleDueReminder(context, currentCards)
            }
    }

    LaunchedEffect(decks) {
        snapshotFlow { decks.toList() }
            .collect { currentDecks ->
                saveDeckData(
                    sharedPreferences = sharedPreferences,
                    decks = currentDecks,
                    cards = cards
                )
                if (currentDecks.none { it.id == selectedDeckId }) {
                    selectedDeckId = currentDecks.first().id
                }
            }
    }

    if (showLanding) {
        LandingScreen()
    } else {
        when (currentScreen) {
            AppScreen.Home -> {
                HomeScreen(
                    deck = selectedDeck,
                    cards = selectedDeckCards,
                    decks = decks,
                    selectedDeckId = selectedDeckId,
                    onSelectDeck = { selectedDeckId = it },
                    onOpenCards = { currentScreen = AppScreen.Cards },
                    onOpenManageCards = { currentScreen = AppScreen.ManageCards },
                    onOpenHome = { currentScreen = AppScreen.Home },
                    onStartPractice = { currentScreen = AppScreen.Practice }
                )
            }
            AppScreen.Practice -> {
                PracticeScreen(
                    deck = selectedDeck,
                    cards = cards,
                    decks = decks,
                    selectedDeckId = selectedDeckId,
                    onSelectDeck = { selectedDeckId = it },
                    onOpenHome = { currentScreen = AppScreen.Home },
                    onOpenCards = { currentScreen = AppScreen.Cards },
                    onOpenManageCards = { currentScreen = AppScreen.ManageCards },
                    onExit = { currentScreen = AppScreen.Home }
                )
            }
            AppScreen.Cards -> {
                CardsScreen(
                    deck = selectedDeck,
                    cards = selectedDeckCards,
                    allDecks = decks,
                    selectedDeckId = selectedDeckId,
                    onSelectDeck = { selectedDeckId = it },
                    onRemoveCard = { cardId ->
                        cards.removeAll { it.id == cardId }
                    },
                    onRestoreCard = { card, index ->
                        cards.add(index.coerceIn(0, cards.size), card)
                    },
                    onEditCard = { cardId, front, back ->
                        val index = cards.indexOfFirst { it.id == cardId }
                        if (index >= 0) {
                            cards[index] = cards[index].copy(front = front, back = back)
                        }
                    },
                    onOpenHome = { currentScreen = AppScreen.Home },
                    onOpenCards = { currentScreen = AppScreen.Cards },
                    onOpenManageCards = { currentScreen = AppScreen.ManageCards },
                    onBack = { currentScreen = AppScreen.Home }
                )
            }
            AppScreen.ManageCards -> {
                ManageCardsScreen(
                    deck = selectedDeck,
                    decks = decks,
                    selectedDeckId = selectedDeckId,
                    frontText = frontText,
                    backText = backText,
                    deckNameText = deckNameText,
                    csvMessage = csvMessage,
                    onSelectDeck = { selectedDeckId = it },
                    onFrontChange = { frontText = it },
                    onBackChange = { backText = it },
                    onDeckNameChange = { deckNameText = it },
                    onAddCard = {
                        val front = frontText.trim()
                        val back = backText.trim()
                        if (front.isNotEmpty() && back.isNotEmpty()) {
                            val newCard = FlashCard(deckId = selectedDeck.id, front = front, back = back)
                            if (hasDuplicateCard(selectedDeckCards, newCard)) {
                                csvMessage = "Duplicate card not added. \"$front\" already exists with the same back."
                            } else {
                                cards.add(0, newCard)
                                frontText = ""
                                backText = ""
                                csvMessage = null
                            }
                        }
                    },
                    onCreateDeck = {
                        val deckName = deckNameText.trim()
                        if (deckName.isEmpty()) {
                            csvMessage = "Enter a deck name before creating a deck."
                        } else if (decks.any { it.name.equals(deckName, ignoreCase = true) }) {
                            csvMessage = "Deck \"$deckName\" already exists."
                        } else {
                            val newDeck = Deck(name = deckName)
                            decks.add(newDeck)
                            selectedDeckId = newDeck.id
                            deckNameText = ""
                            csvMessage = "Created deck \"$deckName\"."
                        }
                    },
                    onImportCsv = {
                        csvMessage = null
                        csvPicker.launch(arrayOf("text/*", "text/comma-separated-values", "application/csv"))
                    },
                    onExportCsv = {
                        csvMessage = null
                        csvExporter.launch("${selectedDeck.name.lowercase().replace(' ', '-')}-cards.csv")
                    },
                    onOpenHome = { currentScreen = AppScreen.Home },
                    onOpenCards = { currentScreen = AppScreen.Cards },
                    onOpenManageCards = { currentScreen = AppScreen.ManageCards },
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
    deck: Deck,
    cards: List<FlashCard>,
    decks: List<Deck>,
    selectedDeckId: String,
    onSelectDeck: (String) -> Unit,
    onOpenHome: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit,
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
                        DeckSelector(
                            decks = decks,
                            selectedDeckId = selectedDeckId,
                            onSelectDeck = onSelectDeck,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    AppOverflowMenu(
                        onOpenHome = onOpenHome,
                        onOpenCards = onOpenCards,
                        onOpenManageCards = onOpenManageCards
                    )
                }
            }

            item {
                StatsRow(cards = cards, dueCount = dueCount)
            }

            item {
                LeitnerOverviewPanel(cards = cards)
            }

            item {
                SectionCard(title = "Practice") {
                    Text(
                        text = if (dueCount > 0) {
                            "$dueCount card${if (dueCount == 1) "" else "s"} ready to review in ${deck.name}."
                        } else {
                            "No cards are due yet in ${deck.name}. New cards become available immediately."
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
    deck: Deck,
    decks: List<Deck>,
    selectedDeckId: String,
    frontText: String,
    backText: String,
    deckNameText: String,
    csvMessage: String?,
    onSelectDeck: (String) -> Unit,
    onFrontChange: (String) -> Unit,
    onBackChange: (String) -> Unit,
    onDeckNameChange: (String) -> Unit,
    onAddCard: () -> Unit,
    onCreateDeck: () -> Unit,
    onImportCsv: () -> Unit,
    onExportCsv: () -> Unit,
    onOpenHome: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit,
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
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Manage decks and cards",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Create study pools by language, topic, or exam, then add cards into the selected deck.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DeckSelector(
                            decks = decks,
                            selectedDeckId = selectedDeckId,
                            onSelectDeck = onSelectDeck,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = onBack) {
                            Text("Back")
                        }
                        AppOverflowMenu(
                            onOpenHome = onOpenHome,
                            onOpenCards = onOpenCards,
                            onOpenManageCards = onOpenManageCards
                        )
                    }
                }
            }

            item {
                SectionCard(title = "Create a deck") {
                    OutlinedTextField(
                        value = deckNameText,
                        onValueChange = onDeckNameChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Deck name") },
                        singleLine = true
                    )
                    Button(
                        onClick = onCreateDeck,
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text("Create deck")
                    }
                }
            }

            item {
                SectionCard(title = "Add a flashcard") {
                    Text(
                        text = "Adding to ${deck.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = frontText,
                        onValueChange = onFrontChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
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
                SectionCard(title = "Import and export CSV") {
                    Text(
                        text = "Current deck: ${deck.name}. CSV format: front,back",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onImportCsv) {
                            Text("Choose CSV file")
                        }
                        TextButton(onClick = onExportCsv) {
                            Text("Export CSV")
                        }
                    }
                    csvMessage?.let { message ->
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
    deck: Deck,
    cards: MutableList<FlashCard>,
    decks: List<Deck>,
    selectedDeckId: String,
    onSelectDeck: (String) -> Unit,
    onOpenHome: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit,
    onExit: () -> Unit
) {
    var isFlipped by remember { mutableStateOf(false) }
    val deckCards = cards.filter { it.deckId == deck.id }
    val sessionCardIds = remember(deck.id, deckCards.map { it.id to it.nextReviewAt }) {
        mutableStateListOf<String>().apply {
            addAll(deckCards.filter { it.nextReviewAt <= System.currentTimeMillis() }.map { it.id })
        }
    }
    var correctCount by remember { mutableStateOf(0) }
    var incorrectCount by remember { mutableStateOf(0) }
    var promotedCount by remember { mutableStateOf(0) }
    var demotedCount by remember { mutableStateOf(0) }
    val currentCard = cards.firstOrNull { it.id == sessionCardIds.firstOrNull() }
    val sessionSummary = remember(correctCount, incorrectCount, promotedCount, demotedCount) {
        PracticeSessionSummary(
            totalReviewed = correctCount + incorrectCount,
            correctCount = correctCount,
            incorrectCount = incorrectCount,
            promotedCount = promotedCount,
            demotedCount = demotedCount
        )
    }
    val nextDueLoad = upcomingDueLoad(deckCards)

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
                        text = if (sessionSummary.totalReviewed == 0) {
                            "There are no due cards to review right now."
                        } else {
                            "There are no more due cards in this session."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    SessionSummaryCard(
                        summary = sessionSummary,
                        nextDueLoad = nextDueLoad,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp)
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
                                text = "Practice ${deck.name}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${sessionCardIds.size} left in this session",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            DeckSelector(
                                decks = decks,
                                selectedDeckId = selectedDeckId,
                                onSelectDeck = onSelectDeck,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onExit) {
                                Text("Exit")
                            }
                            AppOverflowMenu(
                                onOpenHome = onOpenHome,
                                onOpenCards = onOpenCards,
                                onOpenManageCards = onOpenManageCards
                            )
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
                                    val result = updateCard(cards, currentCard.id, wasCorrect = false)
                                    incorrectCount += 1
                                    if (result.newBox < result.previousBox) {
                                        demotedCount += 1
                                    }
                                    sessionCardIds.remove(currentCard.id)
                                    isFlipped = false
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Not yet")
                            }
                            Button(
                                onClick = {
                                    val result = updateCard(cards, currentCard.id, wasCorrect = true)
                                    correctCount += 1
                                    if (result.newBox > result.previousBox) {
                                        promotedCount += 1
                                    }
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
    deck: Deck,
    cards: List<FlashCard>,
    allDecks: List<Deck>,
    selectedDeckId: String,
    onSelectDeck: (String) -> Unit,
    onRemoveCard: (String) -> Unit,
    onRestoreCard: (FlashCard, Int) -> Unit,
    onEditCard: (String, String, String) -> Unit,
    onOpenHome: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var editingCard by remember { mutableStateOf<FlashCard?>(null) }
    var deletedCard by remember { mutableStateOf<FlashCard?>(null) }
    var deletedIndex by remember { mutableStateOf<Int?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedBox by remember { mutableStateOf<Int?>(null) }
    var boxMenuExpanded by remember { mutableStateOf(false) }
    val filteredCards = cards.filter { card ->
        val matchesQuery = searchQuery.isBlank() ||
            card.front.contains(searchQuery, ignoreCase = true) ||
            card.back.contains(searchQuery, ignoreCase = true)
        val matchesBox = selectedBox == null || card.box == selectedBox
        matchesQuery && matchesBox
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
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
                            text = "${deck.name}: ${filteredCards.size} visible of ${cards.size} card${if (cards.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        DeckSelector(
                            decks = allDecks,
                            selectedDeckId = selectedDeckId,
                            onSelectDeck = onSelectDeck,
                            modifier = Modifier.padding(top = 12.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onBack) {
                            Text("Back")
                        }
                        AppOverflowMenu(
                            onOpenHome = onOpenHome,
                            onOpenCards = onOpenCards,
                            onOpenManageCards = onOpenManageCards
                        )
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
                    SectionCard(title = "Find cards") {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Search front or back") },
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box {
                                OutlinedButton(onClick = { boxMenuExpanded = true }) {
                                    Text(selectedBox?.let { "Box $it" } ?: "All boxes")
                                }
                                DropdownMenu(
                                    expanded = boxMenuExpanded,
                                    onDismissRequest = { boxMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("All boxes") },
                                        onClick = {
                                            selectedBox = null
                                            boxMenuExpanded = false
                                        }
                                    )
                                    (1..boxIntervalsInDays.size).forEach { box ->
                                        DropdownMenuItem(
                                            text = { Text("Box $box") },
                                            onClick = {
                                                selectedBox = box
                                                boxMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                            if (searchQuery.isNotBlank() || selectedBox != null) {
                                TextButton(
                                    onClick = {
                                        searchQuery = ""
                                        selectedBox = null
                                    }
                                ) {
                                    Text("Clear filters")
                                }
                            }
                        }
                    }
                }
            }

            item {
                if (cards.isEmpty()) {
                    Spacer(modifier = Modifier.height(0.dp))
                } else if (filteredCards.isEmpty()) {
                    EmptyState(
                        title = "No matching cards",
                        subtitle = "Try a different search term or switch back to all boxes."
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
                            filteredCards.forEachIndexed { index, card ->
                                if (index > 0) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                }
                                CardTableRow(
                                    card = card,
                                    onEdit = { editingCard = card },
                                    onRemove = {
                                        val index = cards.indexOfFirst { it.id == card.id }
                                        deletedCard = card
                                        deletedIndex = index
                                        onRemoveCard(card.id)
                                        scope.launch {
                                            snackbarHostState.currentSnackbarData?.dismiss()
                                            val result = snackbarHostState.showSnackbar(
                                                message = "\"${card.front}\" deleted",
                                                actionLabel = "Undo"
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                val cardToRestore = deletedCard
                                                val indexToRestore = deletedIndex
                                                if (cardToRestore != null && indexToRestore != null) {
                                                    onRestoreCard(cardToRestore, indexToRestore)
                                                }
                                            }
                                            deletedCard = null
                                            deletedIndex = null
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingCard?.let { card ->
        EditCardDialog(
            card = card,
            onDismiss = { editingCard = null },
            onSave = { front, back ->
                onEditCard(card.id, front, back)
                editingCard = null
            }
        )
    }
}

@Composable
private fun AppOverflowMenu(
    onOpenHome: () -> Unit,
    onOpenCards: () -> Unit,
    onOpenManageCards: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

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
                text = { Text("Home") },
                onClick = {
                    menuExpanded = false
                    onOpenHome()
                }
            )
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

@Composable
private fun DeckSelector(
    decks: List<Deck>,
    selectedDeckId: String,
    onSelectDeck: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(selectedDeckId) { mutableStateOf(false) }
    val selectedDeckName = decks.firstOrNull { it.id == selectedDeckId }?.name ?: ""

    Box(modifier = modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(if (selectedDeckName.isBlank()) "Select deck" else selectedDeckName)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            decks.forEach { deck ->
                DropdownMenuItem(
                    text = { Text(deck.name) },
                    onClick = {
                        expanded = false
                        onSelectDeck(deck.id)
                    }
                )
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TableCell(
            text = "Front",
            weight = 1.25f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Back",
            weight = 1.25f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Box",
            weight = 0.5f,
            fontWeight = FontWeight.Bold
        )
        TableCell(
            text = "Due",
            weight = 0.8f,
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
    onEdit: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TableCell(text = card.front, weight = 1.25f)
        TableCell(text = card.back, weight = 1.25f)
        TableCell(text = card.box.toString(), weight = 0.5f)
        TableCell(text = reviewLabel(card.nextReviewAt), weight = 0.8f)
        Row(
            modifier = Modifier.weight(0.7f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "\u270E",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(32.dp)
            ) {
                Text(
                    text = "\u00D7",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun EditCardDialog(
    card: FlashCard,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var frontText by remember(card.id) { mutableStateOf(card.front) }
    var backText by remember(card.id) { mutableStateOf(card.back) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit card") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = frontText,
                    onValueChange = { frontText = it },
                    label = { Text("Word or phrase") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = backText,
                    onValueChange = { backText = it },
                    label = { Text("Meaning in English") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val front = frontText.trim()
                    val back = backText.trim()
                    if (front.isNotEmpty() && back.isNotEmpty()) {
                        onSave(front, back)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun SessionSummaryCard(
    summary: PracticeSessionSummary,
    nextDueLoad: NextDueLoad?,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = "Session summary",
        modifier = modifier
    ) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatPill(label = "Correct", value = summary.correctCount.toString())
            StatPill(label = "Incorrect", value = summary.incorrectCount.toString())
            StatPill(label = "Promoted", value = summary.promotedCount.toString())
            StatPill(label = "Demoted", value = summary.demotedCount.toString())
        }
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "Next due load",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (nextDueLoad == null) {
                    Text(
                        text = "No upcoming reviews are scheduled.",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                } else {
                    Text(
                        text = "${nextDueLoad.cardCount} card${if (nextDueLoad.cardCount == 1) "" else "s"} ${reviewLabel(nextDueLoad.dueAt).lowercase()}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "This is the next review batch unlocked by the current schedule.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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
): CardReviewResult {
    val index = cards.indexOfFirst { it.id == cardId }
    if (index == -1) error("Card not found for review result")

    val current = cards[index]
    val newBox = if (wasCorrect) min(current.box + 1, boxIntervalsInDays.size) else 1
    val intervalDays = boxIntervalsInDays[newBox - 1]
    val nextReviewAt = System.currentTimeMillis() + intervalDays * dayInMillis

    cards[index] = current.copy(
        box = newBox,
        nextReviewAt = nextReviewAt
    )

    return CardReviewResult(
        wasCorrect = wasCorrect,
        previousBox = current.box,
        newBox = newBox,
        nextReviewAt = nextReviewAt
    )
}

private fun reviewLabel(nextReviewAt: Long): String {
    val delta = nextReviewAt - System.currentTimeMillis()
    if (delta <= 0L) return "Due now"
    val days = (delta + dayInMillis - 1) / dayInMillis
    return if (days == 1L) "Due in 1 day" else "Due in $days days"
}

private fun upcomingDueLoad(cards: List<FlashCard>): NextDueLoad? {
    val nextDueAt = cards
        .map { it.nextReviewAt }
        .filter { it > System.currentTimeMillis() }
        .minOrNull()
        ?: return null

    return NextDueLoad(
        dueAt = nextDueAt,
        cardCount = cards.count { it.nextReviewAt == nextDueAt }
    )
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
                FlashCard(deckId = "", front = front, back = back)
            }
        }
}

private fun writeFlashCardsToCsv(outputStream: OutputStream, cards: List<FlashCard>) {
    outputStream.writer().use { writer ->
        writer.appendLine("front,back")
        cards.forEach { card ->
            writer.append(csvValue(card.front))
            writer.append(',')
            writer.append(csvValue(card.back))
            writer.appendLine()
        }
    }
}

private fun csvValue(value: String): String {
    val escaped = value.replace("\"", "\"\"")
    return "\"$escaped\""
}

private fun hasDuplicateCard(cards: List<FlashCard>, candidate: FlashCard): Boolean {
    return cards.any { existing ->
        existing.front == candidate.front && existing.back == candidate.back
    }
}

private fun filterNewCards(
    existingCards: List<FlashCard>,
    candidateCards: List<FlashCard>
): CardImportResult {
    val knownPairs = existingCards
        .map { it.front to it.back }
        .toMutableSet()
    val cardsToAdd = mutableListOf<FlashCard>()
    var duplicateCount = 0

    candidateCards.forEach { candidate ->
        val pair = candidate.front to candidate.back
        if (pair in knownPairs) {
            duplicateCount += 1
        } else {
            knownPairs += pair
            cardsToAdd += candidate
        }
    }

    return CardImportResult(
        cardsToAdd = cardsToAdd,
        duplicateCount = duplicateCount
    )
}

private fun csvImportMessage(result: CardImportResult): String {
    val importedCount = result.cardsToAdd.size
    val duplicateCount = result.duplicateCount

    return when {
        importedCount == 0 && duplicateCount > 0 ->
            "No cards were imported. Skipped $duplicateCount duplicate card${if (duplicateCount == 1) "" else "s"}."
        duplicateCount == 0 ->
            "Imported $importedCount card${if (importedCount == 1) "" else "s"}."
        else ->
            "Imported $importedCount card${if (importedCount == 1) "" else "s"} and skipped $duplicateCount duplicate card${if (duplicateCount == 1) "" else "s"}."
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

fun loadCards(sharedPreferences: android.content.SharedPreferences): List<FlashCard> {
    return loadDeckData(sharedPreferences).cards
}

private fun createDefaultDeck(): Deck = Deck(name = defaultDeckName)

private fun loadDeckData(sharedPreferences: android.content.SharedPreferences): LoadedDeckData {
    val storedDecks = sharedPreferences.getString(decksPrefsKey, null)
    val defaultDeck = createDefaultDeck()
    val decks = runCatching {
        if (storedDecks == null) {
            listOf(defaultDeck)
        } else {
            val array = JSONArray(storedDecks)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Deck(
                    id = item.getString("id"),
                    name = item.getString("name")
                )
            }.ifEmpty { listOf(defaultDeck) }
        }
    }.getOrElse { listOf(defaultDeck) }

    val defaultDeckId = decks.first().id
    val cardsJson = sharedPreferences.getString(cardsPrefsKey, null)
    val cards = runCatching {
        if (cardsJson == null) {
            defaultCards(defaultDeckId)
        } else {
            val array = JSONArray(cardsJson)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                FlashCard(
                    id = item.getString("id"),
                    deckId = item.optString("deckId", defaultDeckId).ifBlank { defaultDeckId },
                    front = item.getString("front"),
                    back = item.getString("back"),
                    box = item.optInt("box", 1),
                    nextReviewAt = item.optLong("nextReviewAt", System.currentTimeMillis())
                )
            }
        }
    }.getOrElse {
        defaultCards(defaultDeckId)
    }

    return LoadedDeckData(
        decks = decks,
        cards = cards
    )
}

private fun saveDeckData(
    sharedPreferences: android.content.SharedPreferences,
    decks: List<Deck>,
    cards: List<FlashCard>
) {
    val decksJson = JSONArray().apply {
        decks.forEach { deck ->
            put(
                JSONObject().apply {
                    put("id", deck.id)
                    put("name", deck.name)
                }
            )
        }
    }
    val cardsJson = JSONArray().apply {
        cards.forEach { card ->
            put(
                JSONObject().apply {
                    put("id", card.id)
                    put("deckId", card.deckId)
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
        .putString(decksPrefsKey, decksJson.toString())
        .putString(cardsPrefsKey, cardsJson.toString())
        .apply()
}

private fun defaultCards(defaultDeckId: String): List<FlashCard> = listOf(
    FlashCard(deckId = defaultDeckId, front = "hond", back = "dog"),
    FlashCard(deckId = defaultDeckId, front = "huis", back = "house"),
    FlashCard(deckId = defaultDeckId, front = "dank je wel", back = "thank you")
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
private fun LeitnerOverviewPanel(cards: List<FlashCard>) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabTitles = listOf("Status", "Review plan")

    SectionCard(title = "Leitner overview") {
        when (selectedTab) {
            0 -> LeitnerStatusContent(cards = cards)
            else -> LeitnerReviewPlanContent(cards = cards)
        }
        SecondaryTabRow(selectedTabIndex = selectedTab) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun LeitnerStatusContent(cards: List<FlashCard>) {
    val totalCards = cards.size.coerceAtLeast(1)

    Text(
        text = "Distribution across the five repetition boxes.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxStatusRow(box = 1, label = "New", count = cards.count { it.box == 1 }, totalCards = totalCards)
        BoxStatusRow(box = 2, label = "1 day", count = cards.count { it.box == 2 }, totalCards = totalCards)
        BoxStatusRow(box = 3, label = "3 days", count = cards.count { it.box == 3 }, totalCards = totalCards)
        BoxStatusRow(box = 4, label = "7 days", count = cards.count { it.box == 4 }, totalCards = totalCards)
        BoxStatusRow(box = 5, label = "14 days", count = cards.count { it.box == 5 }, totalCards = totalCards)
    }
}

@Composable
private fun LeitnerReviewPlanContent(cards: List<FlashCard>) {
    val now = System.currentTimeMillis()
    val dueCards = cards.count { it.nextReviewAt <= now }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Due right now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "$dueCards cards",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = if (dueCards == 0) "All caught up" else "Ready to review",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ReviewPlanRow(box = 1, label = "Immediate", dueCount = cards.count { it.box == 1 && it.nextReviewAt <= now })
        ReviewPlanRow(box = 2, label = "1 day", dueCount = cards.count { it.box == 2 && it.nextReviewAt <= now })
        ReviewPlanRow(box = 3, label = "3 days", dueCount = cards.count { it.box == 3 && it.nextReviewAt <= now })
        ReviewPlanRow(box = 4, label = "7 days", dueCount = cards.count { it.box == 4 && it.nextReviewAt <= now })
        ReviewPlanRow(box = 5, label = "14 days", dueCount = cards.count { it.box == 5 && it.nextReviewAt <= now })
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
private fun BoxStatusRow(box: Int, label: String, count: Int, totalCards: Int) {
    val progress = count / totalCards.toFloat()

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        contentColor = MaterialTheme.colorScheme.primary
                    ) {
                        Text(
                            text = box.toString(),
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Column {
                        Text(
                            text = "Box $box",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            )
        }
    }
}

@Composable
private fun ReviewPlanRow(box: Int, label: String, dueCount: Int) {
    val hasDueCards = dueCount > 0

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (hasDueCards) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(
                            color = if (hasDueCards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(50)
                        )
                )
                Column {
                    Text(
                        text = "Box $box",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = dueCount.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (hasDueCards) "Ready now" else "No cards due",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasDueCards) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
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
