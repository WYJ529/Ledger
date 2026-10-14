package com.example.cpen321application

import java.util.Locale

internal data class MealIngredient(val name: String, val measure: String = "") {
    val display: String get() = listOf(measure, name).filter { it.isNotBlank() }.joinToString(" ")
}

internal data class MealRecipe(
    val id: String,
    val name: String,
    val category: String,
    val area: String,
    val imageUrl: String,
    val ingredients: List<MealIngredient>,
    val instructions: String,
)

internal data class MealPreferences(
    val category: String = "",
    val area: String = "",
    val pantry: Set<String> = emptySet(),
)

internal data class RankedMeal(
    val recipe: MealRecipe,
    val score: Int,
    val reasons: List<String>,
    val missing: List<String>,
    val matchedCount: Int,
)

// Conservative, explicit aliases: rice must not match rice vinegar, nor egg eggplant.
// These are ingredient conveniences, not dietary or allergy guarantees.
private val pantryAliases = mapOf(
    "Chicken" to setOf("chicken", "chicken breast", "chicken breasts", "chicken thigh", "chicken thighs"),
    "Eggs" to setOf("egg", "eggs"),
    "Rice" to setOf("rice", "basmati rice", "jasmine rice", "white rice", "brown rice", "long grain rice"),
    "Tomatoes" to setOf("tomato", "tomatoes", "cherry tomatoes"),
    "Onions" to setOf("onion", "onions", "red onion", "red onions", "yellow onion", "white onion"),
    "Garlic" to setOf("garlic", "garlic clove", "garlic cloves"),
)

internal val pantryChoices = pantryAliases.keys.toList()

private fun normalizedIngredient(name: String) = name.trim().lowercase(Locale.ROOT)
    .replace(Regex("\\s+"), " ")

internal fun rankMeals(recipes: List<MealRecipe>, preferences: MealPreferences): List<RankedMeal> {
    val available = preferences.pantry.flatMap { pantryAliases[it].orEmpty() }.toSet()
    return recipes.distinctBy { it.id }.map { meal ->
        val ingredients = meal.ingredients.distinctBy { normalizedIngredient(it.name) }
        val matched = ingredients.filter { normalizedIngredient(it.name) in available }
        val missing = ingredients.filterNot { normalizedIngredient(it.name) in available }.map { it.name }
        val reasons = mutableListOf<String>()
        var score = 0
        if (preferences.category.isNotBlank() && meal.category.equals(preferences.category, true)) {
            score += 40
            reasons += "Matches your ${preferences.category} preference (+40)."
        }
        if (preferences.area.isNotBlank() && meal.area.equals(preferences.area, true)) {
            score += 30
            reasons += "Matches your ${preferences.area} cuisine choice (+30)."
        }
        // Up to 30 points for the proportion of recipe ingredients selected in the pantry.
        val pantryPoints = if (ingredients.isEmpty()) 0 else 30 * matched.size / ingredients.size
        score += pantryPoints
        if (matched.isNotEmpty()) reasons += "Uses ${matched.size} of ${ingredients.size} listed ingredients from your selection (+$pantryPoints)."
        if (reasons.isEmpty()) reasons += "An option to explore; no selected preference matched."
        RankedMeal(meal, score, reasons, missing, matched.size)
    }.sortedWith(compareByDescending<RankedMeal> { it.score }.thenBy { it.recipe.name }.thenBy { it.recipe.id })
}
