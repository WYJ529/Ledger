package com.example.cpen321application

import org.junit.Assert.*
import org.junit.Test

class MealRecommendationTest {
    private fun meal(id: String, category: String = "Chicken", area: String = "Chinese", vararg ingredients: String) =
        MealRecipe(id, "Meal $id", category, area, "", ingredients.map { MealIngredient(it) }, "Cook.")

    @Test fun matchingPreferencesOutrankUnrelatedMeals() {
        val matching = meal("1", "Chicken", "Chinese", "Eggs", "Rice")
        val other = meal("2", "Beef", "Italian", "Beef")
        val ranked = rankMeals(listOf(other, matching), MealPreferences("Chicken", "Chinese", setOf("Eggs")))
        assertEquals("1", ranked.first().recipe.id)
        assertEquals(85, ranked.first().score)
        assertEquals(listOf("Rice"), ranked.first().missing)
        assertEquals(3, ranked.first().reasons.size)
    }

    @Test fun pantryMatchingDoesNotConfuseSimilarIngredientNames() {
        val result = rankMeals(listOf(meal("1", "", "", "Eggplant", "Rice Vinegar", " eggs ", "Basmati Rice")),
            MealPreferences(pantry = setOf("Eggs", "Rice"))).single()
        assertEquals(2, result.matchedCount)
        assertEquals(15, result.score)
        assertEquals(listOf("Eggplant", "Rice Vinegar"), result.missing)
    }

    @Test fun changingAnswersChangesTheWinner() {
        val meals = listOf(meal("1", "Chicken", "Chinese"), meal("2", "Seafood", "Japanese"))
        assertEquals("1", rankMeals(meals, MealPreferences("Chicken", "Chinese")).first().recipe.id)
        assertEquals("2", rankMeals(meals, MealPreferences("Seafood", "Japanese")).first().recipe.id)
    }

    @Test fun duplicateIngredientsAndCandidatesAreNotDoubleCounted() {
        val candidate = meal("1", "Chicken", "Chinese", "Eggs", " eggs ")
        val results = rankMeals(listOf(candidate, candidate), MealPreferences("Chicken", "Chinese", setOf("Eggs")))
        assertEquals(1, results.size)
        assertEquals(100, results.single().score)
        assertEquals(1, results.single().matchedCount)
        assertTrue(results.single().missing.isEmpty())
    }

    @Test fun missingIngredientsAndNoPreferencesAreHonest() {
        val result = rankMeals(listOf(meal("1")), MealPreferences()).single()
        assertEquals(0, result.score)
        assertTrue(result.reasons.single().contains("no selected preference matched"))
        assertTrue(rankMeals(emptyList(), MealPreferences()).isEmpty())
    }

    @Test fun tiesHaveStableOrder() {
        val results = rankMeals(listOf(meal("2"), meal("1")), MealPreferences())
        assertEquals(listOf("1", "2"), results.map { it.recipe.id })
    }
}
