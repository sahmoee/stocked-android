@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.sowens.stocked.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CookModeScreen(snapshot:CookSnapshot,store:CookSessionStore,completeCook:suspend(String,String)->Unit,close:()->Unit) {
    val scope=rememberCoroutineScope(); var error by remember { mutableStateOf<String?>(null) }; var discarding by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notes by remember(snapshot.completionToken) { mutableStateOf(snapshot.notes) }
    val finalNotes by rememberUpdatedState(notes)
    val persistenceError by store.error.collectAsStateWithLifecycle()
    DisposableEffect(snapshot.completionToken) { onDispose { store.flushNotes(snapshot.completionToken,finalNotes) } }
    fun change(operation:suspend ()->Unit) { if(busy) return; busy=true; scope.launch { try { operation(); error=null } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Progress could not be saved." } finally { busy=false } } }
    LaunchedEffect(notes,snapshot.completionToken) { delay(500); if(notes!=store.state.value?.notes) try { store.update(snapshot.completionToken) { current -> current.copy(notes=notes) } } catch(e:CancellationException) { throw e } catch(e:Exception) { error=e.message ?: "Notes could not be saved." } }
    fun pauseAndClose() { change { store.update(snapshot.completionToken) { it.copy(notes=notes,status=if(it.status=="completed") "completed" else "paused") }; close() } }
    BackHandler(onBack=::pauseAndClose)
    val recipe=snapshot.recipe
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)) {
        item { TextButton(enabled=!busy,onClick=::pauseAndClose) { Text(if(snapshot.status=="completed") "Back to recipes" else "Pause & return") }; EditorialHero("Your cooking workspace",recipe.title,"${snapshot.servings} servings · Progress is saved on this device.",mealArtwork()) }
        item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { listOf("Before you start","Cook","Finish & serve").forEachIndexed { index,label -> FilterChip(enabled=!busy,selected=snapshot.stage==index,onClick={ change { store.update(snapshot.completionToken) { it.copy(stage=index,notes=notes) } } },label={ Text(label) }) } }; if(busy) LinearProgressIndicator(Modifier.fillMaxWidth()); (error ?: persistenceError)?.let { Text(it,color=MaterialTheme.colorScheme.error) } }
        when(snapshot.stage) {
            0 -> {
                item { Text("Gather your ingredients",style=MaterialTheme.typography.titleLarge); Text("Check items as you prepare them. This does not deduct food from inventory.",style=MaterialTheme.typography.bodySmall) }
                itemsIndexed(recipe.ingredients) { index,ingredient -> val amount=RecipeQuantities.scale(ingredient.amount,snapshot.servings.toDouble()/recipe.servings)
                    StockedCard { Row(Modifier.padding(12.dp)) { Checkbox(enabled=!busy,checked=index in snapshot.checkedIngredients,onCheckedChange={ checked -> change { store.update(snapshot.completionToken) { it.copy(checkedIngredients=if(checked) it.checkedIngredients+index else it.checkedIngredients-index) } } },modifier=Modifier.semantics { contentDescription="Prepared ${ingredient.name}" }); Column(Modifier.weight(1f)) { Text("${amount.text} ${ingredient.name}"); if(!amount.scaled && ingredient.amount.isNotBlank() && snapshot.servings!=recipe.servings) Text("Adjust this amount manually",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error) } } }
                }
                item { Button(enabled=!busy,onClick={ change { store.update(snapshot.completionToken) { it.copy(stage=1,notes=notes) } } }) { Text("Ready to cook") } }
            }
            1 -> {
                if(recipe.instructions.isEmpty()) item { EmptyState("Add your cooking steps","This recipe has no instructions. Return to its editor before using guided cooking.") }
                else {
                    val index=snapshot.currentStep.coerceIn(recipe.instructions.indices)
                    item { Text("Step ${index+1} of ${recipe.instructions.size}",style=MaterialTheme.typography.titleLarge); LinearProgressIndicator(progress={ snapshot.completedSteps.size.toFloat()/recipe.instructions.size },modifier=Modifier.fillMaxWidth()) }
                    item { StockedCard(fill=StockedPalette.oat()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) { Text(recipe.instructions[index],style=MaterialTheme.typography.bodyLarge); Row { Checkbox(index in snapshot.completedSteps,{ checked -> change { store.update(snapshot.completionToken) { it.copy(completedSteps=if(checked) it.completedSteps+index else it.completedSteps-index) } } },enabled=!busy,modifier=Modifier.semantics { contentDescription="Step ${index+1} completed" }); Text("Step completed",Modifier.padding(top=12.dp)) } } } }
                    item { FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) { OutlinedButton(enabled=!busy && index>0,onClick={ change { store.update(snapshot.completionToken) { it.copy(currentStep=index-1) } } }) { Text("Previous") }; Button(enabled=!busy && index<recipe.instructions.lastIndex,onClick={ change { store.update(snapshot.completionToken) { it.copy(currentStep=index+1) } } }) { Text("Next step") }; TextButton(enabled=!busy,onClick={ change { store.update(snapshot.completionToken) { it.copy(stage=2,notes=notes) } } }) { Text("Finish & serve") } } }
                }
                item { CookingTimer(recipe.id,recipe.title) }
            }
            2 -> {
                item { StockedCard(fill=StockedPalette.garden()) { Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) { Text(if(snapshot.status=="completed") "Nicely done." else "Ready for the table?",style=MaterialTheme.typography.headlineSmall); Text("${snapshot.completedSteps.size} of ${recipe.instructions.size} steps checked off."); Text("Record the containers you used from the recipe's ingredients dialog. Completing this workspace never guesses pantry quantities.") } } }
                item { Field("Cooking notes",notes,{ notes=it },lines=3,enabled=!busy); OutlinedButton(enabled=!busy,onClick={ change { store.update(snapshot.completionToken) { it.copy(notes=notes) } } }) { Text("Save notes") } }
                item { if(snapshot.status!="completed") Button(enabled=!busy,onClick={ change { store.complete(snapshot.completionToken,notes) { current -> completeCook(current.recipe.id,current.completionToken) } } }) { Text("Finish cooking & record") } }
            }
        }
        item { TextButton(enabled=!busy,onClick={ discarding=true }) { Text("Discard this workspace") } }
    }
    if(discarding) AlertDialog(onDismissRequest={ discarding=false },title={ Text("Discard cooking progress?") },text={ Text("Your recipe and pantry stay unchanged. This removes only this workspace and its notes.") },confirmButton={ TextButton(enabled=!busy,onClick={ change { store.discard(snapshot.completionToken); close() } }) { Text("Discard") } },dismissButton={ TextButton(enabled=!busy,onClick={ discarding=false }) { Text("Cancel") } })
}
