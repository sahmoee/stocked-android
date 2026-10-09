package com.sowens.stocked.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class NutritionWirePolicyTest {
    @Test fun partialNutritionExportsCompleteSwiftSchemaWithoutMutatingLocalFacts() {
        val local = buildJsonObject { put("calories", 120); put("futureNutrient", "preserved") }
        val ingredient = Ingredient(name="Rice", extra=buildJsonObject { put("nutrition",local) })
        val state = KitchenState(userRecipes=listOf(Recipe(title="Rice",ingredients=listOf(ingredient))))
        val row=Json.parseToJsonElement(BackupCodec.export(state)).jsonObject.getValue("userRecipes").jsonArray.single().jsonObject.getValue("ingredients").jsonArray.single().jsonObject
        val exported=row.getValue("nutrition").jsonObject
        assertEquals(120,exported.getValue("calories").jsonPrimitive.int)
        assertEquals("",exported.getValue("servingSize").jsonPrimitive.content)
        NutritionWirePolicy.decimalKeys.forEach { assertEquals(0.0,exported.getValue(it).jsonPrimitive.double,0.0); assertFalse(exported.getValue(it).jsonPrimitive.isString) }
        assertEquals("preserved",exported.getValue("futureNutrient").jsonPrimitive.content)
        assertEquals(local,state.userRecipes.single().ingredients.single().extra["nutrition"])
        assertFalse(local.containsKey("protein"))
    }
    @Test fun absentNutritionStaysAbsent() {
        assertNull(NutritionWirePolicy.normalize(null))
        assertEquals(JsonNull,NutritionWirePolicy.normalize(JsonNull))
    }
    @Test fun suppliedFactsArePreserved() {
        val normalized=NutritionWirePolicy.normalize(buildJsonObject { put("protein",12.5);put("servingSize","one cup") })!!.jsonObject
        assertEquals(12.5,normalized.getValue("protein").jsonPrimitive.double,0.0)
        assertEquals("one cup",normalized.getValue("servingSize").jsonPrimitive.content)
    }
    @Test(expected=IllegalArgumentException::class) fun malformedNutritionIsNotSilentlyReplaced() {
        NutritionWirePolicy.normalize(buildJsonObject { put("protein","unknown") })
    }
}
