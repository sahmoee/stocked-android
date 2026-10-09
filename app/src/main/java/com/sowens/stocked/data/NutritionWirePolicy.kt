package com.sowens.stocked.data

import kotlinx.serialization.json.*

/** Swift synthesized NutritionFacts requires all fields. Defaults are transport
 * compatibility only: never write these inferred zeroes back to local metadata. */
object NutritionWirePolicy {
    val decimalKeys = listOf("totalFat", "saturatedFat", "transFat", "cholesterol", "sodium", "totalCarbs", "dietaryFiber", "totalSugars", "addedSugars", "protein", "vitaminD", "calcium", "iron", "potassium")
    fun normalize(value: JsonElement?): JsonElement? {
        if (value == null || value is JsonNull) return value
        require(value is JsonObject) { "Ingredient nutrition must be an object." }
        val result = value.toMutableMap()
        fun missing(key: String) = result[key] == null || result[key] is JsonNull
        if (missing("servingSize")) result["servingSize"] = JsonPrimitive("")
        require((result["servingSize"] as? JsonPrimitive)?.isString == true) { "Nutrition serving size must be text." }
        if (missing("calories")) result["calories"] = JsonPrimitive(0)
        val calories = result["calories"] as? JsonPrimitive
        require(calories != null && !calories.isString && calories.intOrNull?.let { it >= 0 } == true) { "Nutrition calories must be a nonnegative whole number." }
        decimalKeys.forEach { key ->
            if (missing(key)) result[key] = JsonPrimitive(0.0)
            val primitive = result[key] as? JsonPrimitive
            require(primitive != null && !primitive.isString && primitive.doubleOrNull?.let { it.isFinite() && it >= 0 } == true) { "Nutrition $key must be a finite nonnegative number." }
        }
        return JsonObject(result)
    }
}
