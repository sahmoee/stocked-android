package com.sowens.stocked.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sowens.stocked.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate

object CookHubRules {
    fun missing(recipe:Recipe,inventory:List<InventoryItem>):List<Ingredient> = recipe.ingredients.filter { ingredient ->
        (ingredient.extra["isOptional"] as? kotlinx.serialization.json.JsonPrimitive)?.content != "true" &&
        inventory.none { it.quantity>0 && it.level>0 && KitchenRules.key(it.name)==KitchenRules.key(ingredient.name) }
    }
    fun expiring(inventory:List<InventoryItem>,today:LocalDate):List<InventoryItem> = inventory.filter { it.quantity>0 && it.expirationDate?.let { raw -> runCatching { LocalDate.parse(raw) in today..today.plusDays(7) }.getOrDefault(false) }==true }.sortedBy { it.expirationDate }
}

@Composable
fun CookHubScreen(state:KitchenState,pendingMeal:Boolean,openRecipe:(String)->Unit,openKitchen:()->Unit,completeCook:suspend (String,String)->Unit,cookLater:@Composable ()->Unit) {
    var destination by rememberSaveable { mutableStateOf("hub") }
    val store=CookSessionStore.get(LocalContext.current)
    val session by store.state.collectAsStateWithLifecycle()
    val persistenceError by store.error.collectAsStateWithLifecycle()
    var cooking by remember { mutableStateOf(false) }; var busy by remember { mutableStateOf(false) }; var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    val scope=rememberCoroutineScope()
    LaunchedEffect(pendingMeal) { if(pendingMeal) destination="later" }
    LaunchedEffect(store) { try { store.initialize() } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message } }
    if(cooking && session!=null) { CookModeScreen(session!!,store,completeCook) { cooking=false }; return }
    BackHandler(enabled=destination!="hub") { destination="hub" }
    if(destination=="later") {
        Column(Modifier.fillMaxSize()) { TextButton(onClick={ destination="hub" }) { Text("Back to Cook") }; Box(Modifier.weight(1f)) { cookLater() } }
        return
    }
    val active=session?.takeIf { it.status in listOf("active","paused") }
    val ranked=state.userRecipes.filter { it.ingredients.isNotEmpty() }.sortedWith(compareBy<Recipe> { CookHubRules.missing(it,state.inventory).size }.thenBy { it.title.lowercase() })
    val ready=ranked.filter { CookHubRules.missing(it,state.inventory).isEmpty() }
    val almost=ranked.filter { CookHubRules.missing(it,state.inventory).size in 1..2 }
    val expiring=CookHubRules.expiring(state.inventory,LocalDate.now())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
        item { if(destination=="now") TextButton(onClick={ destination="hub" }) { Text("Back to Cook") }; EditorialHero(if(destination=="now") "Cook Now" else "Make something good","What’s on the menu?","Good food starts with what you have. Cook something now, or plan the week ahead.",mealArtwork()) }
        item { (error ?: persistenceError)?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
        active?.let { saved -> item { StockedCard(fill=StockedPalette.garden()) { Column(Modifier.padding(16.dp)) {
            Text("Resume ${saved.recipe.title}",style=MaterialTheme.typography.titleLarge)
            Text("${saved.completedSteps.size} of ${saved.recipe.instructions.size} steps done · ${if(saved.status=="paused") "Paused" else "In progress"}")
            Row { Button(enabled=!busy,onClick={ if(!busy) { busy=true; scope.launch { try { store.resume(saved.completionToken); cooking=true; error=null } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message } finally { busy=false } } } }) { Text("Resume cooking") }; TextButton(enabled=!busy,onClick={ discard=true }) { Text("Discard workspace") } }
        } } } }
        if(destination=="hub") {
            item { CookPath("Cook Now","Solve tonight with what you already have.","See what’s makeable, almost-ready, and worth using up.",StockedPalette.garden()) { destination="now" } }
            item { CookPath("Cook Later","Plan it. Shop for it. Prep it. Cook it.","Build the week, create the list, and stay ahead.",StockedPalette.oat()) { destination="later" } }
        } else {
            item { Text("Ready from your kitchen",style=MaterialTheme.typography.titleLarge); Text("Matches use ingredient names and available containers. Check amounts, freshness and dietary requirements before cooking.",style=MaterialTheme.typography.bodySmall) }
            if(ready.isEmpty()) item { EmptyState("No complete pantry matches yet","Add ingredients to your kitchen or save a recipe to see matches here.") }
            items(ready.take(12),key={ "ready-${it.id}" }) { recipe -> CookRecipeCard(recipe,"All required ingredient names match") { openRecipe(recipe.id) } }
            if(almost.isNotEmpty()) item { Text("Almost ready",style=MaterialTheme.typography.titleLarge) }
            items(almost.take(12),key={ "almost-${it.id}" }) { recipe -> CookRecipeCard(recipe,"Missing: ${CookHubRules.missing(recipe,state.inventory).joinToString { it.name }}") { openRecipe(recipe.id) } }
            item { StockedCard(fill=StockedPalette.peach()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Use something up",style=MaterialTheme.typography.titleLarge)
                if(expiring.isEmpty()) Text("No items have a recorded expiry in the next seven days.") else expiring.take(6).forEach { Text("${it.name} · ${it.expirationDate}") }
                TextButton(onClick=openKitchen) { Text("Review your kitchen") }
            } } }
        }
    }
    if(discard && active!=null) AlertDialog(onDismissRequest={ discard=false },title={ Text("Discard cooking workspace?") },text={ Text("Saved progress for ${active.recipe.title} will be removed. Your recipe and pantry stay unchanged.") },confirmButton={ TextButton(enabled=!busy,onClick={ if(!busy) { busy=true; scope.launch { try { store.discard(active.completionToken); discard=false; error=null } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message } finally { busy=false } } } }) { Text("Discard") } },dismissButton={ TextButton(onClick={ discard=false }) { Text("Keep") } })
}
@Composable private fun CookPath(title:String,detail:String,subtitle:String,fill:androidx.compose.ui.graphics.Color,open:()->Unit) {
    StockedCard(fill=fill,onClick=open) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(title,style=MaterialTheme.typography.headlineMedium); Text(detail,style=MaterialTheme.typography.titleMedium); Text(subtitle,style=MaterialTheme.typography.bodySmall) } }
}
@Composable private fun CookRecipeCard(recipe:Recipe,detail:String,open:()->Unit) {
    StockedCard(onClick=open) { Column(Modifier.padding(16.dp)) { Text(recipe.title,style=MaterialTheme.typography.titleLarge); Text(detail); Text("${recipe.servings} servings") } }
}
