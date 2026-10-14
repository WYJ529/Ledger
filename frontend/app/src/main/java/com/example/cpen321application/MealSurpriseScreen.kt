package com.example.cpen321application

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

private const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

@Composable
internal fun MealSurpriseScreen(onSetTimer: () -> Unit) {
    var category by rememberSaveable { mutableStateOf("") }
    var area by rememberSaveable { mutableStateOf("") }
    var pantryText by rememberSaveable { mutableStateOf("") }
    var step by rememberSaveable { mutableStateOf(0) }
    var showQuiz by rememberSaveable { mutableStateOf(true) }
    var requestKey by rememberSaveable { mutableStateOf(0) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep only small quiz values in the saved-state Bundle. Reload candidates after recreation.
    var batch by remember { mutableStateOf<MealBatch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val loading = !showQuiz && batch == null && error == null
    val pantry = pantryText.split('|').filter { it.isNotBlank() }.toSet()
    val preferences = MealPreferences(category, area, pantry)
    val ranked = remember(batch, category, area, pantryText) {
        rankMeals(batch?.recipes.orEmpty(), preferences).take(3)
    }

    LaunchedEffect(showQuiz, requestKey) {
        if (showQuiz) return@LaunchedEffect
        error = null
        batch = null
        selectedId = null
        try { batch = loadMealCandidates(preferences) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "We couldn't load recipes. Check your connection and try again." }
    }

    Text("What should I eat?", style = MaterialTheme.typography.headlineMedium)
    if (showQuiz) {
        Text("Your countdown is over. Make three quick choices for a recipe surprise tailored to you.")
        Text("Question ${step + 1} of 3", color = MaterialTheme.colorScheme.primary)
        when (step) {
            0 -> {
                Text("1. What sounds good?", style = MaterialTheme.typography.titleLarge)
                PreferenceChoices(listOf("", "Chicken", "Beef", "Seafood", "Vegetarian"), category) { category = it }
                Text("A preference, not a strict dietary filter.", style = MaterialTheme.typography.bodySmall)
            }
            1 -> {
                Text("2. Which cuisine?", style = MaterialTheme.typography.titleLarge)
                PreferenceChoices(listOf("", "Chinese", "Japanese", "Italian", "Indian", "Mexican"), area) { area = it }
            }
            2 -> {
                Text("3. What do you have?", style = MaterialTheme.typography.titleLarge)
                Text("Choose any ingredients on hand, or leave this empty.")
                pantryChoices.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            FilterChip(selected = item in pantry, onClick = {
                                pantryText = (if (item in pantry) pantry - item else pantry + item).joinToString("|")
                            }, label = { Text(item) })
                        }
                    }
                }
                Text("We'll show ingredients not selected here. Check the full recipe for substitutions and dietary needs.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Previous") }
            Button(onClick = {
                if (step < 2) step++ else { batch = null; error = null; requestKey++; showQuiz = false }
            }) { Text(if (step < 2) "Next" else "Find my meals") }
        }
    } else {
        Text("${category.ifBlank { "Any category" }} / ${area.ifBlank { "Any cuisine" }}")
        Text("On hand: ${pantry.joinToString().ifBlank { "nothing selected" }}", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showQuiz = true; step = 0 }) { Text("Change my answers") }
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Comparing recipe ingredients and preferences...")
        }
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error)
            Button(onClick = { error = null; requestKey++ }) { Text("Try again") }
        }
        if (batch != null) {
            Text("Your top ${ranked.size}", style = MaterialTheme.typography.titleLarge)
            Text("Ranked from ${batch!!.recipes.size} sampled recipes. These are preference scores, not percentages or a search of every recipe.", style = MaterialTheme.typography.bodySmall)
            if (batch!!.partial) Text("Some recipes could not be loaded. These results use the available recipes.", style = MaterialTheme.typography.bodySmall)
            if (ranked.all { it.score == 0 }) Text("No preference matches in this sample. Try different answers or browse these ideas.")
            ranked.forEachIndexed { index, result ->
                val meal = result.recipe
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${index + 1}. ${meal.name}", style = MaterialTheme.typography.titleMedium)
                        Text("${meal.area} / ${meal.category} / ${result.score} points", color = MaterialTheme.colorScheme.primary)
                        result.reasons.forEach { Text("\u2022 $it") }
                        Text(if (meal.ingredients.isEmpty()) "Ingredient information unavailable."
                            else if (result.missing.isEmpty()) "All listed ingredients match your selection."
                            else "Not selected on hand: ${result.missing.joinToString()}.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { selectedId = if (selectedId == meal.id) null else meal.id }) {
                            Text(if (selectedId == meal.id) "Hide recipe" else "View recipe")
                        }
                        if (selectedId == meal.id) MealDetails(meal)
                    }
                }
            }
            Text("Scoring: category +40, cuisine +30, ingredient coverage up to +30. Pantry matching uses a small list of ingredient aliases; it may miss other forms. Please check all ingredients yourself.", style = MaterialTheme.typography.bodySmall)
        }
    }
    OutlinedButton(onClick = onSetTimer) { Text("Set another timer") }
    Text("Recipes and photos provided by TheMealDB.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun PreferenceChoices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    options.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onSelect(option) },
                    label = { Text(option.ifBlank { "No preference" }) })
            }
        }
    }
}

@Composable
private fun MealDetails(recipe: MealRecipe) {
    val uriHandler = LocalUriHandler.current
    var linkError by remember(recipe.id) { mutableStateOf(false) }
    MealPhoto(recipe.imageUrl, recipe.name)
    Text("Ingredients", style = MaterialTheme.typography.titleMedium)
    if (recipe.ingredients.isEmpty()) Text("Ingredients were not supplied by TheMealDB.")
    recipe.ingredients.forEach { Text("\u2022 ${it.display}") }
    Text("Recipe", style = MaterialTheme.typography.titleMedium)
    Text(recipe.instructions.ifBlank { "Instructions were not supplied by TheMealDB." })
    TextButton(onClick = {
        linkError = runCatching { uriHandler.openUri("https://www.themealdb.com/meal/${recipe.id}") }.isFailure
    }) { Text("View on TheMealDB") }
    if (linkError) Text("No browser is available to open the recipe.")
}

@Composable
private fun MealPhoto(imageUrl: String, name: String) {
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    var finished by remember(imageUrl) { mutableStateOf(false) }
    LaunchedEffect(imageUrl) {
        try {
            image = withContext(Dispatchers.IO) {
                val bytes = readMealBytes(imageUrl, MAX_IMAGE_BYTES)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                var sample = 1
                while (bounds.outWidth / sample > 1000 || bounds.outHeight / sample > 1000) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { image = null }
        finally { finished = true }
    }
    val bitmap = image
    if (bitmap != null) {
        Card(Modifier.fillMaxWidth()) {
            Image(bitmap, contentDescription = name, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f), contentScale = ContentScale.Crop)
        }
    } else if (finished) {
        Text("Photo unavailable - the recipe is still shown below.", style = MaterialTheme.typography.bodySmall)
    }
}

internal fun readMealBytes(address: String, maximum: Int): ByteArray {
    val url = URL(address)
    require(url.protocol == "https" && url.host in setOf("www.themealdb.com", "themealdb.com"))
    val connection = url.openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        check(connection.responseCode in 200..299) { "TheMealDB request failed." }
        return connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(output.size() + count <= maximum) { "Response is too large." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    } finally { connection.disconnect() }
}
