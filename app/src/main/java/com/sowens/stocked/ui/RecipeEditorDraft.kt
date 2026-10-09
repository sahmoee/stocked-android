package com.sowens.stocked.ui

import com.sowens.stocked.data.*
import kotlinx.serialization.json.*
import java.net.URI

fun JsonObject.text(key:String):String = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

/** Editable known metadata is merged into, never substituted for, the imported payload. */
data class IngredientDraft(
    val original:Ingredient,
    val name:String=original.name, val amount:String=original.amount,
    val brand:String=original.extra.text("brand"), val notes:String=original.extra.text("notes"),
    val quantity:String=original.extra.text("quantity"), val unit:String=original.extra.text("unit"), val prep:String=original.extra.text("prep"),
    val optional:Boolean=(original.extra["isOptional"] as? JsonPrimitive)?.booleanOrNull ?: false,
    val nutrition:Map<String,String> = RecipeEditorDraft.nutritionKeys.associateWith { key -> (original.extra["nutrition"] as? JsonObject)?.text(key).orEmpty() }
)

object RecipeEditorDraft {
    val nutritionKeys=listOf("servingSize","calories","totalFat","saturatedFat","transFat","cholesterol","sodium","totalCarbs","dietaryFiber","totalSugars","addedSugars","protein","vitaminD","calcium","iron","potassium")
    val nutritionLabels=mapOf("servingSize" to "Serving size", "calories" to "Calories (kcal)","totalFat" to "Total fat (g)","saturatedFat" to "Saturated fat (g)","transFat" to "Trans fat (g)","cholesterol" to "Cholesterol (mg)","sodium" to "Sodium (mg)","totalCarbs" to "Carbohydrates (g)","dietaryFiber" to "Dietary fiber (g)","totalSugars" to "Total sugars (g)","addedSugars" to "Added sugars (g)","protein" to "Protein (g)","vitaminD" to "Vitamin D (mcg)","calcium" to "Calcium (mg)","iron" to "Iron (mg)","potassium" to "Potassium (mg)")
    fun publicWebURL(raw:String):Boolean = raw.isBlank() || runCatching { val uri=URI(raw.trim()); uri.scheme?.lowercase() in listOf("https","http") && !uri.host.isNullOrBlank() && uri.userInfo==null }.getOrDefault(false)
    private fun patchText(target:MutableMap<String,JsonElement>,original:JsonObject,key:String,value:String) {
        if(value==original.text(key)) return
        if(value.isBlank()) target.remove(key) else target[key]=JsonPrimitive(value.trim())
    }
    fun ingredient(draft:IngredientDraft):Ingredient {
        require(draft.name.isNotBlank() && draft.name.length<=500) { "Every ingredient needs a name up to 500 characters." }
        require(draft.amount.length<=500) { "Ingredient amount is too long." }
        val extra=draft.original.extra.toMutableMap()
        listOf("brand" to draft.brand,"notes" to draft.notes,"unit" to draft.unit,"prep" to draft.prep).forEach { (key,value) -> patchText(extra,draft.original.extra,key,value) }
        if(draft.quantity!=draft.original.extra.text("quantity")) {
            if(draft.quantity.isBlank()) extra.remove("quantity") else {
                val quantity=draft.quantity.toDoubleOrNull(); require(quantity!=null && quantity.isFinite() && quantity>0) { "Structured ingredient quantities must be positive." }; extra["quantity"]=JsonPrimitive(quantity)
            }
        }
        val originalOptional=(draft.original.extra["isOptional"] as? JsonPrimitive)?.booleanOrNull ?: false
        if(draft.optional!=originalOptional) extra["isOptional"]=JsonPrimitive(draft.optional)
        val originalNutrition=draft.original.extra["nutrition"] as? JsonObject ?: JsonObject(emptyMap())
        val nutrition=originalNutrition.toMutableMap()
        nutritionKeys.forEach { key -> val raw=draft.nutrition[key].orEmpty(); if(raw!=originalNutrition.text(key)) {
            if(raw.isBlank()) nutrition.remove(key)
            else if(key=="servingSize") nutrition[key]=JsonPrimitive(raw.trim())
            else if(key=="calories") { val number=raw.toIntOrNull(); require(number!=null && number>=0) { "Calories must be a nonnegative whole number." }; nutrition[key]=JsonPrimitive(number) }
            else { val number=raw.toDoubleOrNull(); require(number!=null && number.isFinite() && number>=0) { "Nutrition values must be finite, nonnegative numbers." }; nutrition[key]=JsonPrimitive(number) }
        } }
        if(nutrition!=originalNutrition) { if(nutrition.isEmpty()) extra.remove("nutrition") else extra["nutrition"]=JsonObject(nutrition) }
        return draft.original.copy(name=draft.name.trim(),amount=draft.amount.trim(),extra=JsonObject(extra))
    }
    fun recipeMetadata(original:Recipe,difficulty:String,dishRole:String,author:String,photoCredit:String,categories:String):JsonObject {
        val extra=original.extra.toMutableMap()
        listOf("difficulty" to difficulty,"dishRole" to dishRole,"author" to author,"imageAttribution" to photoCredit).forEach { (key,value) -> patchText(extra,original.extra,key,value) }
        val originalCategories=(original.extra["categories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(", ").orEmpty()
        if(categories!=originalCategories) { val values=categories.split(',').map { it.trim() }.filter { it.isNotEmpty() }; if(values.isEmpty()) extra.remove("categories") else extra["categories"]=JsonArray(values.map(::JsonPrimitive)) }
        return JsonObject(extra)
    }
}
