@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.sowens.stocked.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sowens.stocked.data.*
import kotlinx.serialization.json.*

@Composable
fun RecipeEditor(recipe:Recipe,save:(Recipe)->Unit,dismiss:()->Unit) {
    var title by remember(recipe.id) { mutableStateOf(recipe.title) }
    var description by remember { mutableStateOf(recipe.description) }; var servings by remember { mutableStateOf(recipe.servings.toString()) }
    val ingredients=remember(recipe.id) { mutableStateListOf<IngredientDraft>().also { list -> list.addAll(recipe.ingredients.map { IngredientDraft(it) }) } }
    var steps by remember { mutableStateOf(recipe.instructions.joinToString("\n")) }; var prep by remember { mutableStateOf(recipe.prepTime) }; var cook by remember { mutableStateOf(recipe.cookTime) }
    var notes by remember { mutableStateOf(recipe.notes) }; var cuisine by remember { mutableStateOf(recipe.cuisine) }; var tags by remember { mutableStateOf(recipe.tags.joinToString(", ")) }
    var source by remember { mutableStateOf(recipe.sourceURL.orEmpty()) }; var publisher by remember { mutableStateOf(recipe.sourceName.orEmpty()) }; var license by remember { mutableStateOf(recipe.license.orEmpty()) }
    var image by remember { mutableStateOf(recipe.imageURL.orEmpty()) }; var author by remember { mutableStateOf(recipe.extra.text("author")) }; var photoCredit by remember { mutableStateOf(recipe.extra.text("imageAttribution")) }
    var difficulty by remember { mutableStateOf(recipe.extra.text("difficulty").ifBlank { "Medium" }) }; var role by remember { mutableStateOf(recipe.extra.text("dishRole").ifBlank { "unspecified" }) }
    var categories by remember { mutableStateOf((recipe.extra["categories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }?.joinToString(", ").orEmpty()) }
    val validation=runCatching {
        require(title.isNotBlank() && title.length<=500) { "Enter a recipe title up to 500 characters." }; require(steps.lines().count { it.isNotBlank() }<=1000) { "Use up to 1,000 instruction steps." }; require(servings.toIntOrNull() in 1..1000) { "Choose between 1 and 1,000 servings." }
        require(RecipeEditorDraft.publicWebURL(source) && RecipeEditorDraft.publicWebURL(image)) { "Source and image links must be HTTP or HTTPS web addresses without credentials." }
        ingredients.map(RecipeEditorDraft::ingredient)
    }
    EditorDialog("Recipe",validation.isSuccess,{
        save(recipe.copy(title=title.trim(),description=description,servings=servings.toInt(),prepTime=prep,cookTime=cook,
            ingredients=ingredients.map(RecipeEditorDraft::ingredient),instructions=steps.lines().map { it.trim() }.filter { it.isNotBlank() },notes=notes,cuisine=cuisine,
            tags=tags.split(',').map { it.trim() }.filter { it.isNotBlank() },sourceURL=source.trim().ifBlank { null },sourceName=publisher.trim().ifBlank { null },license=license.trim().ifBlank { null },imageURL=image.trim().ifBlank { null },
            extra=RecipeEditorDraft.recipeMetadata(recipe,difficulty,role,author,photoCredit,categories)))
    },dismiss) {
        Text("Recipe details",style=MaterialTheme.typography.titleMedium)
        Field("Title",title,{ title=it }); Field("Description",description,{ description=it },lines=2); Field("Cuisine",cuisine,{ cuisine=it })
        Text("Timing & servings",style=MaterialTheme.typography.titleMedium)
        Field("Servings",servings,{ servings=it },true); Field("Prep time",prep,{ prep=it }); Field("Cook time",cook,{ cook=it })
        Text("Difficulty",style=MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { (listOf("Easy","Medium","Hard")+difficulty).distinct().forEach { value -> FilterChip(difficulty==value,{ difficulty=value },label={ Text(value) }) } }
        Text("Dish role",style=MaterialTheme.typography.labelLarge)
        val roles=linkedMapOf("unspecified" to "Recipe","entree" to "Entrée","side" to "Side","component" to "Component","fullMeal" to "Full meal"); if(role !in roles) roles[role]=role
        FlowRow(horizontalArrangement=Arrangement.spacedBy(6.dp)) { roles.forEach { (value,label) -> FilterChip(role==value,{ role=value },label={ Text(label) }) } }
        Text("Ingredients",style=MaterialTheme.typography.titleMedium)
        if(ingredients.isEmpty()) Text("No ingredients yet — add a row below.")
        ingredients.forEachIndexed { index,draft -> key(draft.original.id) { IngredientEditorRow(draft,{ ingredients[index]=it },{ ingredients.removeAt(index) }) } }
        OutlinedButton(onClick={ ingredients.add(IngredientDraft(Ingredient(name=""))) },enabled=ingredients.size<1000) { Text("Add ingredient") }
        Text("Instructions & notes",style=MaterialTheme.typography.titleMedium)
        Field("Instructions — one step per line",steps,{ steps=it },lines=4); Field("Private notes",notes,{ notes=it },lines=2)
        Field("Tags (comma separated)",tags,{ tags=it }); Field("Categories (comma separated)",categories,{ categories=it })
        Text("Original source & credits",style=MaterialTheme.typography.titleMedium)
        Field("Original recipe website",source,{ source=it }); Field("Publisher / source name",publisher,{ publisher=it }); Field("Recipe author",author,{ author=it }); Field("Source license, if supplied",license,{ license=it })
        Field("Web image link (optional)",image,{ image=it }); Field("Photo credit",photoCredit,{ photoCredit=it })
        Text("Keep the creator’s credit and license with the recipe. Saving here stays in your kitchen; it does not publish or grant rights to redistribute it.",style=MaterialTheme.typography.bodySmall)
        if("imageData" in recipe.extra) Text("The original embedded photo is preserved alongside this web link.",style=MaterialTheme.typography.bodySmall)
        validation.exceptionOrNull()?.message?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun IngredientEditorRow(draft:IngredientDraft,change:(IngredientDraft)->Unit,remove:()->Unit) {
    var details by remember(draft.original.id) { mutableStateOf(false) }; var nutrition by remember(draft.original.id) { mutableStateOf(false) }
    StockedCard { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Field("Ingredient name",draft.name,{ change(draft.copy(name=it)) }); Field("Amount (for example ½ cup)",draft.amount,{ change(draft.copy(amount=it)) })
        Row { Checkbox(draft.optional,{ change(draft.copy(optional=it)) }); Text("Optional ingredient",Modifier.padding(top=12.dp)) }
        FlowRow { TextButton(onClick={ details=!details }) { Text(if(details) "Less detail" else "Brand, prep & quantity") }; TextButton(onClick={ nutrition=!nutrition }) { Text(if(nutrition) "Hide nutrition" else "Nutrition") }; TextButton(onClick=remove) { Text("Remove") } }
        if(details) {
            Field("Brand",draft.brand,{ change(draft.copy(brand=it)) }); Field("Preparation (minced, sliced…)",draft.prep,{ change(draft.copy(prep=it)) }); Field("Ingredient notes",draft.notes,{ change(draft.copy(notes=it)) })
            Field("Structured quantity (optional)",draft.quantity,{ change(draft.copy(quantity=it)) },true); Field("Structured unit (cup, g…)",draft.unit,{ change(draft.copy(unit=it)) })
            Text("The displayed amount stays the source of truth. Keep the optional structured quantity and unit consistent with it.",style=MaterialTheme.typography.bodySmall)
        }
        if(nutrition) {
            Text("Ingredient nutrition",style=MaterialTheme.typography.titleSmall)
            Text("Enter values for the ingredient amount used in the recipe. No nutrition is estimated automatically.",style=MaterialTheme.typography.bodySmall)
            RecipeEditorDraft.nutritionKeys.forEach { key -> Field(RecipeEditorDraft.nutritionLabels.getValue(key),draft.nutrition[key].orEmpty(),{ change(draft.copy(nutrition=draft.nutrition+(key to it))) },key!="servingSize") }
        }
    } }
}
