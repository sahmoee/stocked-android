@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sowens.stocked.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sowens.stocked.data.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import kotlinx.serialization.json.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
fun RecipeScreen(state: KitchenState, save: (Recipe, Recipe?) -> Unit, delete: (String) -> Unit, groceries: (String) -> Unit, consume: (String, Int) -> Unit, plan: (Recipe) -> Unit, imports: () -> Unit, initialQuery: String = "", completeCook: suspend (String,String) -> Unit, initialRecipeId:String?=null, clearInitialRecipe:()->Unit={}) {
    val context=LocalContext.current
    val cookStore=remember { CookSessionStore.get(context) }
    val session by cookStore.state.collectAsStateWithLifecycle()
    val persistenceError by cookStore.error.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var cooking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var cookError by remember { mutableStateOf<String?>(null) }
    var replaceCook by remember { mutableStateOf<Triple<Recipe,Int,String>?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    LaunchedEffect(cookStore) { try { cookStore.initialize() } catch(error:CancellationException) { throw error } catch(error:Exception) { cookError=error.message ?: "Saved cooking workspace could not be read. The file has been preserved." } }
    fun startCook(recipe:Recipe, servings:Int=recipe.servings) { if(starting) return; starting=true; scope.launch { try { val active=session; if(active?.recipe?.id==recipe.id && active.status in listOf("active","paused")) cookStore.resume(active.completionToken) else cookStore.start(recipe,servings); cooking=true; cookError=null } catch(error:CancellationException) { throw error } catch(error:Exception) { cookError=error.message } finally { starting=false } } }
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var editing by remember { mutableStateOf<Recipe?>(null) }
    var editingBaseline by remember { mutableStateOf<Recipe?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialRecipeId) { initialRecipeId?.let { selected=it; clearInitialRecipe() } }
    val results = state.userRecipes.filter { (it.title.contains(query, true) || it.ingredients.any { ingredient -> ingredient.name.contains(query, true) }) && (!favoritesOnly || it.isFavorited) }
    val recipe = state.userRecipes.firstOrNull { it.id == selected }
    if (cooking && session!=null) { CookModeScreen(session!!,cookStore,completeCook) { cooking=false } }
    else if (recipe != null) {
        RecipeDetail(recipe, state.inventory, { selected = null }, { editingBaseline=recipe; editing = recipe }, { delete(recipe.id); selected = null }, { groceries(recipe.id) }, consume, { plan(recipe) }, { save(recipe.copy(isFavorited=!recipe.isFavorited),recipe) }, { cookedServings -> if(session?.status in listOf("active","paused") && session?.recipe?.id!=recipe.id) replaceCook=Triple(recipe,cookedServings,session!!.completionToken) else startCook(recipe,cookedServings) },cookError ?: persistenceError,starting)
    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        EditorialHero("Your recipe book", "Something delicious awaits.", "Recipes you love, meals you remember, and fresh ideas for tonight.", mealArtwork())
        (cookError ?: persistenceError)?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        session?.takeIf { it.status in listOf("active","paused") }?.let { active -> StockedCard(onClick={ if(!starting) { starting=true; scope.launch { try { cookStore.resume(active.completionToken); cooking=true; cookError=null } catch(error:CancellationException) { throw error } catch(error:Exception) { cookError=error.message } finally { starting=false } } } },fill=StockedPalette.garden()) { Column(Modifier.padding(16.dp)) { Text("Resume ${active.recipe.title}",style=MaterialTheme.typography.titleMedium); Text("${active.completedSteps.size} of ${active.recipe.instructions.size} steps done · ${if(active.status=="paused") "Paused" else "In progress"}") } } }
        FilterChip(selected=favoritesOnly,onClick={ favoritesOnly=!favoritesOnly },label={ Text("Favorites · ${state.userRecipes.count { it.isFavorited }}") })
        Field("Search recipes or ingredients", query, { query = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { editingBaseline=null; editing = Recipe(title = "") }) { Text("Create recipe") }; OutlinedButton(onClick = imports) { Text("Import") } }
        if (results.isEmpty()) EmptyState("Your own recipe collection", "Save a family favourite or import a Stocked backup. Recipes stay available offline.")
        } }
            items(results, key = { it.id }) { item -> StockedCard(onClick = { selected = item.id }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(16.dp)) { Text(item.title, style = MaterialTheme.typography.titleLarge); Text("${item.servings} servings · ${item.ingredients.size} ingredients"); Text(item.description, maxLines = 2) }
            } }
    }
    replaceCook?.let { incoming -> AlertDialog(onDismissRequest={ replaceCook=null },title={ Text("Start a different cook?") },text={ Text("Your active ${session?.recipe?.title} workspace will be replaced. The recipes and pantry stay unchanged.") },confirmButton={ TextButton(enabled=!starting,onClick={ if(!starting) { starting=true; replaceCook=null; scope.launch { try { cookStore.start(incoming.first,incoming.second,incoming.third); cooking=true; cookError=null } catch(error:CancellationException) { throw error } catch(error:Exception) { cookError=error.message } finally { starting=false } } } }) { Text("Start new cook") } },dismissButton={ TextButton(onClick={ replaceCook=null }) { Text("Keep current cook") } }) }
    editing?.let { item -> RecipeEditor(item, { save(it,editingBaseline); editing = null; editingBaseline=null }, { editing = null; editingBaseline=null }) }
}

@Composable
private fun RecipeDetail(recipe: Recipe, pantry: List<InventoryItem>, back: () -> Unit, edit: () -> Unit, delete: () -> Unit, groceries: () -> Unit, consume: (String, Int) -> Unit, plan: () -> Unit, favorite: () -> Unit, cook: (Int) -> Unit, cookError:String?, starting:Boolean) {
    BackHandler(onBack = back)
    var servings by remember(recipe.id) { mutableStateOf(recipe.servings) }
    var showConsume by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { TextButton(onClick = back) { Text("Back to recipes") }; Text(recipe.title, style = MaterialTheme.typography.headlineMedium); Text(recipe.description) }
        item {
            val context = LocalContext.current
            val allergenHits = remember(recipe.ingredients) { ToolboxParity.allergenMatches(recipe, DietaryProfileStore.load(context).allergens) }
            if (allergenHits.isNotEmpty()) Text("Check your saved allergens: " + allergenHits.joinToString("; ") { "${it.first} — ${it.second}" } + ". Keyword review only; always check product labels.", color = MaterialTheme.colorScheme.error)
        }
        item { cookError?.let { Text(it,color=MaterialTheme.colorScheme.error) }; Button(enabled=!starting,onClick={ cook(servings) }) { Text("Start cooking") }; FlowRow { TextButton(onClick=favorite) { Text(if(recipe.isFavorited) "Remove favorite" else "Favorite") }; TextButton(onClick = edit) { Text("Edit") }; TextButton(onClick = groceries) { Text("Add to groceries") }; TextButton(onClick = plan) { Text("Plan meal") }; TextButton(onClick = { confirmDelete = true }) { Text("Delete") } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Button(onClick = { servings-- }, enabled = servings > 1, modifier = Modifier.semantics { contentDescription = "Decrease servings" }) { Text("−") }; Text("$servings servings", Modifier.padding(top = 12.dp)); Button(onClick = { servings++ }, enabled = servings < 1000, modifier = Modifier.semantics { contentDescription = "Increase servings" }) { Text("+") } }; Text("Numbers and common fractions scale for recognized units. Other amounts are marked for manual adjustment.", style = MaterialTheme.typography.bodySmall) }
        item {
            val metadata=listOf(recipe.prepTime.takeIf { it.isNotBlank() }?.let { "Prep: $it" },recipe.cookTime.takeIf { it.isNotBlank() }?.let { "Cook: $it" },recipe.cuisine.takeIf { it.isNotBlank() },recipe.extra.text("difficulty").takeIf { it.isNotBlank() }) .filterNotNull()
            if(metadata.isNotEmpty()) Text(metadata.joinToString(" · "))
            val role=mapOf("entree" to "Entrée","side" to "Side","component" to "Component","fullMeal" to "Full meal")[recipe.extra.text("dishRole")]
            role?.let { Text("Dish role: $it") }
            val categories=(recipe.extra["categories"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            if((recipe.tags+categories).isNotEmpty()) Text((recipe.tags+categories).distinct().joinToString(" · "))
        }
        item { Text("Ingredients", style = MaterialTheme.typography.titleLarge) }
        items(recipe.ingredients, key = { it.id }) { ingredient ->
            val matched = pantry.filter { it.quantity > 0 && KitchenRules.key(it.name) == KitchenRules.key(ingredient.name) }
            val amount = RecipeQuantities.scale(ingredient.amount, servings.toDouble() / recipe.servings)
            Text("${amount.text} ${ingredient.name}\n${if (matched.isEmpty()) "Not in pantry" else "In pantry: ${matched.sumOf { it.quantity }} containers"}")
            val optional=(ingredient.extra["isOptional"] as? JsonPrimitive)?.booleanOrNull == true
            if(optional) Text("Optional ingredient",style=MaterialTheme.typography.labelMedium)
            listOf("brand" to "Brand", "prep" to "Preparation", "notes" to "Notes").forEach { (key,label) -> ingredient.extra.text(key).takeIf { it.isNotBlank() }?.let { Text("$label: $it",style=MaterialTheme.typography.bodySmall) } }
            IngredientNutrition(ingredient,servings!=recipe.servings)
            if (servings != recipe.servings && ingredient.amount.isNotBlank() && !amount.scaled) Text("Adjust this amount manually",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        }
        item { Button(onClick = { showConsume = true }) { Text("Record ingredients used") }; Text("Confirm whole container counts; recipe measurements are not automatically deducted.", style = MaterialTheme.typography.bodySmall) }
        item { Text("Cooking steps", style = MaterialTheme.typography.titleLarge) }
        items(recipe.instructions.indices.toList()) { index -> Text("${index + 1}. ${recipe.instructions[index]}") }
        item { CookingTimer(recipe.id, recipe.title) }
        if (recipe.notes.isNotBlank()) item { Text("Notes", style = MaterialTheme.typography.titleMedium); Text(recipe.notes) }
        item { RecipeCredits(recipe) }
    }
    if (showConsume) ConsumptionDialog(pantry.filter { entry -> recipe.ingredients.any { KitchenRules.key(it.name) == KitchenRules.key(entry.name) } && entry.quantity > 0 }, consume, { showConsume = false })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("Delete recipe?") }, text = { Text("${recipe.title} will be removed from your collection.") }, confirmButton = { TextButton(onClick = delete) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun IngredientNutrition(ingredient:Ingredient,scaled:Boolean) {
    var expanded by remember(ingredient.id) { mutableStateOf(false) }
    val nutrition=ingredient.extra["nutrition"] as? JsonObject
    val supplied=RecipeEditorDraft.nutritionKeys.mapNotNull { key -> nutrition?.text(key)?.takeIf { it.isNotBlank() }?.let { RecipeEditorDraft.nutritionLabels.getValue(key) to it } }
    if(supplied.isEmpty()) Text("Nutrition: unknown",style=MaterialTheme.typography.bodySmall)
    else {
        TextButton(onClick={ expanded=!expanded }) { Text(if(expanded) "Hide supplied nutrition" else "Supplied nutrition") }
        if(expanded) {
            Text("Values supplied for the original ingredient amount. Missing values are unknown; they are not treated as zero.",style=MaterialTheme.typography.bodySmall)
            if(scaled) Text("Nutrition values have not been scaled to these servings.",style=MaterialTheme.typography.bodySmall)
            supplied.forEach { (label,value) -> Text("$label: $value",style=MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RecipeCredits(recipe:Recipe) {
    val context=LocalContext.current
    var error by remember(recipe.id) { mutableStateOf<String?>(null) }
    val originalURL=recipe.sourceURL?.takeIf { it.isNotBlank() }
        ?: (recipe.extra["portableSource"] as? JsonObject)?.text("originalSourceURL")?.takeIf { it.isNotBlank() }
    val author=recipe.extra.text("author"); val photo=recipe.extra.text("imageAttribution")
    if(originalURL==null && recipe.sourceName.isNullOrBlank() && author.isBlank() && photo.isBlank() && recipe.license.isNullOrBlank()) return
    fun open(raw:String) {
        try {
            require(raw.isNotBlank() && RecipeEditorDraft.publicWebURL(raw))
            context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(raw.trim())).addCategory(Intent.CATEGORY_BROWSABLE))
            error=null
        } catch(exception:Exception) { error="This source link could not be opened in a browser." }
    }
    StockedCard { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Source & credits",style=MaterialTheme.typography.titleMedium)
        if(originalURL!=null && RecipeEditorDraft.publicWebURL(originalURL)) TextButton(onClick={ open(originalURL) }) { Text(recipe.sourceName?.takeIf { it.isNotBlank() } ?: "Original recipe") }
        else recipe.sourceName?.takeIf { it.isNotBlank() }?.let { Text("Source: $it") }
        if(originalURL!=null && !RecipeEditorDraft.publicWebURL(originalURL)) Text("Source link is unavailable.",style=MaterialTheme.typography.bodySmall)
        if(author.isNotBlank()) Text("Recipe by $author")
        if(photo.isNotBlank()) Text("Photo credit: $photo")
        recipe.license?.takeIf { it.isNotBlank() }?.let { license ->
            if(RecipeEditorDraft.publicWebURL(license)) TextButton(onClick={ open(license) }) { Text("View source license") }
            else Text("Source license: $license")
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
    } }
}

@Composable
private fun ConsumptionDialog(items: List<InventoryItem>, consume: (String, Int) -> Unit, dismiss: () -> Unit) {
    val amounts = remember { mutableStateMapOf<String, String>() }
    EditorDialog("Record containers used", items.isNotEmpty() && amounts.any { (id, value) -> (value.toIntOrNull() ?: 0) > 0 } && items.all { (amounts[it.id] ?: "0").toIntOrNull()?.let { amount -> amount in 0..it.quantity } == true }, {
        items.forEach { item -> val amount = amounts[item.id]?.toIntOrNull() ?: 0; if (amount > 0) consume(item.id, amount) }; dismiss()
    }, dismiss) {
        if (items.isEmpty()) Text("No ingredients match available pantry names exactly. Update inventory first.")
        items.forEach { item -> Field("${item.name}: ${item.quantity} available", amounts[item.id] ?: "0", { amounts[item.id] = it }, true) }
    }
}

@Composable
fun CookingTimer(id: String, title: String) {
    val context = LocalContext.current
    var minutes by remember(id) { mutableStateOf("10") }
    var deadline by remember(id) { mutableStateOf(CookingTimerStore.deadline(context,id)) }
    var remaining by remember(id) { mutableStateOf(0L) }
    var timerError by remember { mutableStateOf<String?>(null) }
    var exactAllowed by remember { mutableStateOf(CookingTimerStore.canScheduleExact(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, id) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { runCatching { CookingTimerStore.restore(context) }.onFailure { timerError = it.message }; deadline = CookingTimerStore.deadline(context,id); exactAllowed = CookingTimerStore.canScheduleExact(context) } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { runCatching { CookingTimerStore.restore(context) }.onFailure { timerError = it.message } }
    LaunchedEffect(id) { runCatching { CookingTimerStore.restore(context) }.onFailure { timerError = it.message } }
    LaunchedEffect(deadline) { val end = deadline ?: return@LaunchedEffect; do { remaining = ((end - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0); delay(250) } while (remaining > 0) }
    StockedCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cooking timer", style = MaterialTheme.typography.titleMedium)
        Field("Minutes", minutes, { minutes = it }, true)
        if (deadline != null) Text(if (remaining == 0L) "Timer finished" else "${remaining / 60}:${(remaining % 60).toString().padStart(2, '0')}", style = MaterialTheme.typography.headlineMedium)
        Row { Button(onClick = {
            try { deadline = CookingTimerStore.start(context,id,title,minutes.toLong()); timerError = null
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (error: Exception) { timerError = error.message ?: "Unable to start timer" }
        }, enabled = minutes.toLongOrNull() in 1L..1440L) { Text("Start timer") }; TextButton(onClick = { runCatching { CookingTimerStore.reset(context) }.onSuccess { deadline = null; remaining = 0 }.onFailure { timerError = it.message } }) { Text("Reset") } }
        timerError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if (Build.VERSION.SDK_INT >= 31 && !exactAllowed) OutlinedButton(onClick = {
            try { context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) } catch (error: Exception) { timerError = "Exact alarm settings could not open. The timer still uses a delayed background alert." }
        }) { Text("Enable precise timer alerts") }
        Text(if(exactAllowed) "One timer runs at a time. Its deadline survives app/device restarts. Allow notifications for the completion alert." else "One timer runs at a time. Its saved deadline survives restarts, but Android may delay background alerts. Enable precise alerts and allow notifications for timely reminders.", style = MaterialTheme.typography.bodySmall)
    } }
}
