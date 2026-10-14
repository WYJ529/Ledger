package com.example.cpen321application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.coroutines.coroutineContext

private const val MEAL_API = "https://www.themealdb.com/api/json/v1/1/"
private const val MAX_RECIPE_BYTES = 512 * 1024

private fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key).trim()

internal fun parseMealRecipe(raw: String): MealRecipe {
    val meal = JSONObject(raw).optJSONArray("meals")?.optJSONObject(0)
        ?: throw IllegalArgumentException("No recipe was returned.")
    val id = meal.text("idMeal")
    val name = meal.text("strMeal")
    require(id.matches(Regex("[0-9]+")) && name.isNotBlank()) { "Invalid recipe." }
    val ingredients = (1..20).mapNotNull { index ->
        val ingredient = meal.text("strIngredient$index")
        if (ingredient.isBlank()) null else MealIngredient(ingredient, meal.text("strMeasure$index"))
    }
    return MealRecipe(id, name, meal.text("strCategory"), meal.text("strArea"),
        meal.text("strMealThumb"), ingredients, meal.text("strInstructions"))
}

internal data class MealBatch(val recipes: List<MealRecipe>, val partial: Boolean)

internal suspend fun loadMealCandidates(preferences: MealPreferences): MealBatch = withContext(Dispatchers.IO) {
    var partial = false
    val filters = buildList {
        if (preferences.category.isNotBlank()) add("c=" + URLEncoder.encode(preferences.category, "UTF-8"))
        if (preferences.area.isNotBlank()) add("a=" + URLEncoder.encode(preferences.area, "UTF-8"))
        if (isEmpty()) add("c=" + listOf("Chicken", "Seafood", "Vegetarian", "Beef", "Pasta").random())
    }
    val ids = mutableSetOf<String>()
    for (filter in filters) {
        coroutineContext.ensureActive()
        try {
            val array = JSONObject(readMealBytes(MEAL_API + "filter.php?" + filter, MAX_RECIPE_BYTES)
                .toString(Charsets.UTF_8)).optJSONArray("meals")
            val found = if (array == null) emptyList() else (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.text("idMeal")?.takeIf { id -> id.matches(Regex("[0-9]+")) }
            }
            if (found.isEmpty()) partial = true
            ids.addAll(found.shuffled().take(if (filters.size == 1) 12 else 6))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { partial = true }
    }
    check(ids.isNotEmpty()) { "No candidates available." }
    val recipes = mutableListOf<MealRecipe>()
    // Bound both traffic and concurrent requests; no repeated random requests or premium endpoints.
    for (chunk in ids.take(12).chunked(3)) {
        coroutineContext.ensureActive()
        val loaded = coroutineScope {
            chunk.map { id -> async {
                try {
                    parseMealRecipe(readMealBytes(MEAL_API + "lookup.php?i=$id", MAX_RECIPE_BYTES).toString(Charsets.UTF_8))
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
            } }.awaitAll()
        }
        if (loaded.any { it == null }) partial = true
        recipes.addAll(loaded.filterNotNull())
    }
    check(recipes.isNotEmpty()) { "No recipes available." }
    MealBatch(recipes.distinctBy { it.id }, partial)
}
