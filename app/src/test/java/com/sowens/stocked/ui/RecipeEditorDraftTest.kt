package com.sowens.stocked.ui

import com.sowens.stocked.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecipeEditorDraftTest {
    private val extra=Json.parseToJsonElement("""{"brand":"Farm","isOptional":true,"quantity":0.50,"nutrition":{"calories":120,"protein":3.25,"futureNutrient":17},"futureIngredient":{"keep":true}}""").jsonObject
    @Test fun titleOrAmountEditRetainsIdentityAndEveryImportedIngredientField() {
        val original=Ingredient(id="stable",name="Rice",amount="½ cup",extra=extra)
        val result=RecipeEditorDraft.ingredient(IngredientDraft(original).copy(amount="1 cup"))
        assertEquals("stable",result.id); assertEquals(extra,result.extra); assertEquals("1 cup",result.amount)
    }
    @Test fun nutritionEditPreservesUnknownNutrientsAndUnchangedNumberEncoding() {
        val original=Ingredient(name="Rice",extra=extra)
        val draft=IngredientDraft(original)
        val result=RecipeEditorDraft.ingredient(draft.copy(nutrition=draft.nutrition+("protein" to "7.5"),optional=false))
        val nutrition=result.extra.getValue("nutrition").jsonObject
        assertEquals(JsonPrimitive(17),nutrition["futureNutrient"])
        assertEquals(JsonPrimitive(120),nutrition["calories"])
        assertEquals(7.5,nutrition.getValue("protein").jsonPrimitive.double,0.0)
        assertEquals(false,result.extra.getValue("isOptional").jsonPrimitive.boolean)
        assertEquals(extra["futureIngredient"],result.extra["futureIngredient"])
    }
    @Test fun invalidEditedQuantitiesAndNutritionCannotBeSaved() {
        val draft=IngredientDraft(Ingredient(name="Rice"))
        listOf("0","-1","NaN","Infinity","rice").forEach { quantity ->
            assertThrows(IllegalArgumentException::class.java) { RecipeEditorDraft.ingredient(draft.copy(quantity=quantity)) }
        }
        listOf("-2","NaN","Infinity").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { RecipeEditorDraft.ingredient(draft.copy(nutrition=mapOf("protein" to value))) }
        }
        assertThrows(IllegalArgumentException::class.java) { RecipeEditorDraft.ingredient(draft.copy(nutrition=mapOf("calories" to "1.5"))) }
    }
    @Test fun editingCreditsPreservesPortableSourceAndOtherRecipeMetadata() {
        val source=Json.parseToJsonElement("""{"portableSource":{"originalSourceURL":"https://example.com/recipe"},"imageData":"embedded","author":"First","difficulty":"Medium","dishRole":"side","future":9}""").jsonObject
        val result=RecipeEditorDraft.recipeMetadata(Recipe(title="Rice",extra=source),"Hard","entree","Second","Photographer","Dinner, Rice")
        assertEquals(source["portableSource"],result["portableSource"]); assertEquals(source["imageData"],result["imageData"])
        assertEquals(source["future"],result["future"]); assertEquals("Second",result.text("author"))
        assertEquals(listOf("Dinner","Rice"),result.getValue("categories").jsonArray.map { it.jsonPrimitive.content })
    }
    @Test fun sourceLinksAcceptWebAddressesAndRejectEmbeddedCredentials() {
        assertTrue(RecipeEditorDraft.publicWebURL("HTTPS://example.com/recipe"))
        assertTrue(RecipeEditorDraft.publicWebURL(""))
        assertFalse(RecipeEditorDraft.publicWebURL("file:///tmp/recipe"))
        assertFalse(RecipeEditorDraft.publicWebURL("https://person:secret@example.com/recipe"))
    }
}
