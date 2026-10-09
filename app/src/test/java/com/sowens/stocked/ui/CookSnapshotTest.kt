package com.sowens.stocked.ui

import com.sowens.stocked.data.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CookSnapshotTest {
    @Test fun processRestorePreservesCompletionIdentityAndProgress() {
        val source=CookSnapshot(recipe=Recipe(id="recipe-1",title="Soup",ingredients=listOf(Ingredient(name="Carrots",amount="1/2 cup")),instructions=listOf("Slice","Simmer")),servings=2,stage=1,currentStep=1,checkedIngredients=setOf(0),completedSteps=setOf(0),notes="Use a small pan",status="paused")
        val restored=Json.decodeFromString<CookSnapshot>(Json.encodeToString(source))
        assertEquals(source.completionToken,restored.completionToken)
        assertEquals("Soup",restored.recipe.title); assertEquals("Simmer",restored.recipe.instructions[restored.currentStep])
        assertEquals(setOf(0),restored.checkedIngredients); assertEquals(setOf(0),restored.completedSteps)
        assertEquals("Use a small pan",restored.notes); assertEquals("paused",restored.status); assertEquals(2,restored.servings)
    }
    @Test fun differentNewSessionsHaveDifferentIdempotencyTokens() {
        val recipe=Recipe(title="Soup")
        assertNotEquals(CookSnapshot(recipe,4).completionToken,CookSnapshot(recipe,4).completionToken)
    }
    @Test fun validSnapshotPassesInvariantChecks() {
        CookSnapshotRules.validate(CookSnapshot(Recipe(title="Soup",instructions=listOf("Simmer")),4))
    }
    @Test fun invalidProgressAndTokenRefused() {
        val base=CookSnapshot(Recipe(title="Soup",ingredients=listOf(Ingredient(name="Carrot")),instructions=listOf("Simmer")),4)
        listOf(base.copy(status="unknown"),base.copy(currentStep=2),base.copy(completedSteps=setOf(9)),base.copy(checkedIngredients=setOf(-1)),base.copy(completionToken="not-a-token")).forEach { bad -> assertThrows(IllegalArgumentException::class.java) { CookSnapshotRules.validate(bad) } }
    }
    @Test fun staleWorkspaceActionsCannotModifyReplacement() {
        val original=CookSnapshot(Recipe(title="Soup"),4)
        val replacement=CookSnapshot(Recipe(title="Salad"),2)
        assertThrows(IllegalStateException::class.java) { CookSnapshotRules.expected(replacement,original.completionToken) }
        assertThrows(IllegalStateException::class.java) { CookSnapshotRules.allowStart(replacement,original.completionToken) }
        assertThrows(IllegalStateException::class.java) { CookSnapshotRules.allowStart(original,null) }
        assertEquals(original,CookSnapshotRules.expected(original,original.completionToken))
    }
}
