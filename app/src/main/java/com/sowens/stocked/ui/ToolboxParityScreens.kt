package com.sowens.stocked.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sowens.stocked.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Offline Android equivalents of iOS Kitchen Toolbox screens; logic lives in [ToolboxParity]. */
val parityTools = listOf("regional" to "Regional names & oven", "thaw" to "Thaw planner", "split" to "Split costs", "readiness" to "Emergency readiness", "roulette" to "Recipe roulette", "dietary" to "Dietary profile", "substitutions" to "Substitutions", "shelfLife" to "Shelf life lookup", "storage" to "Storage tips", "seasonal" to "Seasonal produce")

@Composable
fun showParityTool(id: String, state: KitchenState): Boolean {
    when (id) {
        "regional" -> RegionalTool()
        "thaw" -> ThawTool(state)
        "split" -> SplitCostsTool()
        "readiness" -> ReadinessTool(state)
        "roulette" -> RouletteTool(state)
        "dietary" -> DietaryProfileTool()
        "substitutions" -> SubstitutionsTool()
        "shelfLife" -> ShelfLifeTool()
        "storage" -> StorageTipsTool()
        "seasonal" -> SeasonalTool(state)
        else -> return false
    }
    return true
}

@Composable private fun Heading(title: String, note: String) { Text(title, style = MaterialTheme.typography.titleLarge); Text(note) }

@Composable private fun RegionalTool() {
    var query by rememberSaveable { mutableStateOf("") }
    var line by rememberSaveable { mutableStateOf("") }
    var toUS by rememberSaveable { mutableStateOf(true) }
    var fahrenheit by rememberSaveable { mutableStateOf("350") }
    Heading("Regional names & oven", "US and UK/Australian ingredient names, a recipe-line translator and standard oven steps. Fan ovens usually run 20 °C lower.")
    Field("Translate an ingredient line", line, { line = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = toUS, onClick = { toUS = true }, label = { Text("To US terms") }); FilterChip(selected = !toUS, onClick = { toUS = false }, label = { Text("To UK terms") }) }
    if (line.isNotBlank()) Text(ToolboxParity.translate(line, toUS), style = MaterialTheme.typography.titleMedium)
    HorizontalDivider()
    Field("Oven temperature (°F)", fahrenheit, { fahrenheit = it }, true)
    fahrenheit.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..1000.0 }?.let { f ->
        val step = ToolboxParity.nearestOven(f.toInt())
        Text("${KitchenTools.format(KitchenTools.convert(f, "°F", "°C"))} °C exact · nearest step ${step.fahrenheit} °F = ${step.celsius} °C (fan ${step.fanCelsius} °C) · gas mark ${step.gasMark} · ${step.description}")
    } ?: Text("Enter a temperature between 0 and 1000 °F.")
    HorizontalDivider()
    Field("Search names", query, { query = it })
    ToolboxParity.searchAliases(query).forEach { alias -> Text("${alias.us} (US) · ${alias.uk} (UK)", style = MaterialTheme.typography.titleMedium); if (alias.note.isNotBlank()) Text(alias.note, style = MaterialTheme.typography.bodySmall) }
}

@Composable private fun ThawTool(state: KitchenState) {
    val frozen = remember(state.inventory) { ToolboxParity.frozen(state.inventory) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var weight by rememberSaveable { mutableStateOf("") }
    var method by rememberSaveable { mutableStateOf(ToolboxParity.ThawMethod.FRIDGE) }
    var ready by rememberSaveable { mutableStateOf("18:00") }
    Heading("Thaw planner", "Plans when to move frozen food so it is ready on time. Cold-water and microwave thawing need cooking straight away. Follow your food's own safety guidance.")
    if (frozen.isEmpty()) Text("No freezer items in inventory. You can still enter a weight.")
    frozen.take(30).forEach { item -> FilterChip(selected = selectedId == item.id, onClick = { selectedId = item.id; weight = ToolboxParity.pounds(item)?.let { KitchenTools.format(Math.round(it * 100) / 100.0) }.orEmpty() }, label = { Text("${item.name} · ${item.quantity} ${item.containerType}") }) }
    if (frozen.size > 30) Text("Showing the first 30 of ${frozen.size} freezer items.")
    if (selectedId != null && weight.isBlank()) Text("This item has no recorded weight. Enter the weight of one package.")
    Field("Weight per package (lb)", weight, { weight = it }, true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ToolboxParity.ThawMethod.entries.forEach { option -> FilterChip(selected = method == option, onClick = { method = option }, label = { Text(option.label) }) } }
    Text(method.guidance, style = MaterialTheme.typography.bodySmall)
    Field("Ready by tomorrow at (HH:MM, 24-hour)", ready, { ready = it })
    val result = runCatching {
        val hours = ToolboxParity.thawHours(weight.toDoubleOrNull() ?: error("Enter a weight in pounds."), method)
        val time = runCatching { LocalTime.parse(ready.trim()) }.getOrElse { error("Enter a time such as 18:00.") }
        val out = ToolboxParity.takeOutAt(LocalDateTime.of(LocalDate.now().plusDays(1), time), hours)
        "Thawing takes about ${ToolboxParity.readableHours(hours)}. Move it ${out.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))}."
    }.getOrElse { it.message ?: "Check the weight and time." }
    Text(result, style = MaterialTheme.typography.titleMedium)
}

@Composable private fun SplitCostsTool() {
    var people by rememberSaveable { mutableStateOf("") }
    var label by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var payer by rememberSaveable { mutableStateOf("") }
    var expenses by rememberSaveable { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val names = people.split(",").map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val parsed = expenses.mapNotNull { row -> row.split("\u001F").takeIf { it.size == 3 }?.let { ToolboxParity.Expense(it[0], it[1].toLong(), it[2]) } }
    Heading("Split costs", "Shares are calculated in exact cents and always add up to each bill. This calculator stays on this screen; it is not saved or synced.")
    Field("People, separated by commas", people, { people = it })
    Field("What was bought", label, { label = it })
    Field("Amount", amount, { amount = it }, true)
    Text("Paid by"); Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { names.take(6).forEach { name -> FilterChip(selected = payer == name, onClick = { payer = name }, label = { Text(name) }) } }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { error = runCatching {
        require(names.size in 2..20) { "Enter between 2 and 20 people." }
        require(payer in names) { "Choose who paid." }
        require(expenses.size < 200) { "Up to 200 bills per calculation." }
        val cents = ToolboxParity.parseCents(amount)
        expenses = expenses + listOf(label.trim().ifEmpty { "Expense" }.replace("\u001F", " ").take(120), cents.toString(), payer).joinToString("\u001F"); label = ""; amount = ""
    }.exceptionOrNull()?.message }) { Text("Add bill") }
    if (parsed.isNotEmpty()) {
        parsed.forEach { Text("${it.label} · ${ToolboxParity.money(it.amountCents)} paid by ${it.paidBy}") }
        Text("Total ${ToolboxParity.money(parsed.sumOf { it.amountCents })}", style = MaterialTheme.typography.titleMedium)
        val everyone = (names + parsed.map { it.paidBy }).distinct()
        ToolboxParity.balances(parsed, everyone).forEach { (name, cents) -> Text("$name · ${if (cents >= 0) "is owed" else "owes"} ${ToolboxParity.money(kotlin.math.abs(cents))}") }
        Text("Settle up", style = MaterialTheme.typography.titleMedium)
        val settle = ToolboxParity.settlements(parsed, everyone)
        if (settle.isEmpty()) Text("Everyone is even.")
        settle.forEach { Text("${it.from} pays ${it.to} ${ToolboxParity.money(it.amountCents)}") }
        OutlinedButton(onClick = { expenses = emptyList() }) { Text("Clear bills") }
    }
}

@Composable private fun ReadinessTool(state: KitchenState) {
    var people by rememberSaveable { mutableStateOf("2") }
    Heading("Emergency readiness", "A planning estimate from pantry and staples using conservative calories per package and 3.8 L water per person per day. Foods without a known calorie estimate are listed, not guessed.")
    Field("People in household", people, { people = it }, true)
    val count = people.toIntOrNull()
    if (count == null || count !in 1..50) { Text("Enter between 1 and 50 people."); return }
    val result = remember(state.inventory, count) { ToolboxParity.readiness(state.inventory, count) }
    Text("About ${KitchenTools.format(Math.floor(result.days * 10) / 10)} days", style = MaterialTheme.typography.headlineSmall)
    Text("Food: ${KitchenTools.format(Math.floor(result.daysOfFood * 10) / 10)} days from ${KitchenTools.format(Math.round(result.knownCalories).toDouble())} known kcal")
    Text("Water: ${KitchenTools.format(Math.floor(result.daysOfWater * 10) / 10)} days from ${KitchenTools.format(Math.round(result.waterLiters * 10) / 10.0)} L with a recorded size")
    if (result.unknownFoods.isNotEmpty()) Text("Not counted (${result.unknownFoods.size}): " + result.unknownFoods.take(20).joinToString(", "))
    if (result.expiringSoon.isNotEmpty()) Text("Rotate soon — recorded dates within 30 days: " + result.expiringSoon.take(20).joinToString(", "))
}

@Composable private fun RouletteTool(state: KitchenState) {
    val context = LocalContext.current
    val profile = remember { DietaryProfileStore.load(context) }
    var cuisine by rememberSaveable { mutableStateOf<String?>(null) }
    var favorites by rememberSaveable { mutableStateOf(false) }
    var pickedId by rememberSaveable { mutableStateOf<String?>(null) }
    val cuisines = remember(state.userRecipes) { state.userRecipes.map { it.cuisine.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }.sorted() }
    val candidates = remember(state.userRecipes, cuisine, favorites, profile) { ToolboxParity.rouletteCandidates(state.userRecipes, cuisine, favorites, profile.allergens) }
    Heading("Recipe roulette", "Picks one of your saved recipes. Recipes matching your saved allergens are left out.")
    FilterChip(selected = cuisine == null, onClick = { cuisine = null }, label = { Text("Any cuisine") })
    cuisines.take(12).forEach { option -> FilterChip(selected = cuisine == option, onClick = { cuisine = option }, label = { Text(option) }) }
    Row { Checkbox(checked = favorites, onCheckedChange = { favorites = it }); Text("Favorites only", Modifier.padding(top = 12.dp)) }
    Text("${candidates.size} eligible recipes")
    Button(onClick = { pickedId = ToolboxParity.spin(candidates, avoidId = pickedId)?.id }, enabled = candidates.isNotEmpty()) { Text("Pick a recipe") }
    if (candidates.isEmpty()) Text(if (state.userRecipes.isEmpty()) "Save a few recipes first." else "No recipes match these filters.")
    state.userRecipes.firstOrNull { it.id == pickedId }?.let { Text(it.title, style = MaterialTheme.typography.headlineSmall); Text("${it.servings} servings · ${it.ingredients.size} ingredients" + (it.cuisine.takeIf { c -> c.isNotBlank() }?.let { c -> " · $c" } ?: "")) }
}

@Composable private fun DietaryProfileTool() {
    val context = LocalContext.current
    var profile by remember { mutableStateOf(DietaryProfileStore.load(context)) }
    var custom by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(next: DietaryProfileStore.Profile) { error = runCatching { DietaryProfileStore.save(context, next); profile = DietaryProfileStore.load(context) }.exceptionOrNull()?.message }
    Heading("Dietary profile", "Saved on this device only. Recipes show a warning when an ingredient mentions a saved allergen, and roulette skips them. Keyword checks can miss hidden ingredients; always read labels.")
    Text("Dietary style", style = MaterialTheme.typography.titleMedium)
    ToolboxParity.diets.forEach { diet -> FilterChip(selected = profile.diet == diet, onClick = { update(profile.copy(diet = if (profile.diet == diet) "" else diet)) }, label = { Text(diet) }) }
    Text("Allergens to avoid", style = MaterialTheme.typography.titleMedium)
    (ToolboxParity.commonAllergens + profile.allergens.filter { saved -> ToolboxParity.commonAllergens.none { it.equals(saved, true) } }).forEach { allergen ->
        val selected = profile.allergens.any { it.equals(allergen, true) }
        FilterChip(selected = selected, onClick = { update(profile.copy(allergens = if (selected) profile.allergens.filterNot { it.equals(allergen, true) }.toSet() else profile.allergens + allergen)) }, label = { Text(allergen) }, modifier = Modifier.semantics { contentDescription = "$allergen, ${if (selected) "avoided" else "not avoided"}" })
    }
    Field("Add another ingredient to avoid", custom, { custom = it })
    OutlinedButton(onClick = { if (custom.isNotBlank()) { update(profile.copy(allergens = profile.allergens + custom.trim())); custom = "" } }, enabled = custom.isNotBlank()) { Text("Add") }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable private fun SubstitutionsTool() {
    var ingredient by rememberSaveable { mutableStateOf("") }
    Heading("Substitutions", "Offline swaps with ratios. Results can change texture or flavor; nothing in your recipes is changed automatically.")
    Field("Ingredient", ingredient, { ingredient = it })
    val results = ToolboxParity.substitutesFor(ingredient)
    if (ingredient.isNotBlank() && results.isEmpty()) Text("No offline substitute for \"${ingredient.trim()}\". Try a common baking ingredient.")
    results.forEach { Text("${it.substitute} · ${it.ratio}", style = MaterialTheme.typography.titleMedium) }
}

@Composable private fun ShelfLifeTool() {
    var query by rememberSaveable { mutableStateOf("") }
    Heading("Shelf life lookup", "Typical ranges for quality and planning, not a safety guarantee. Use the product's own date and discard anything that smells or looks wrong.")
    Field("Search foods", query, { query = it })
    val rows = ToolboxParity.searchReference(ToolboxParity.shelfLife, query) { it.food }
    if (rows.isEmpty()) Text("No match. Try a broader name.")
    rows.forEach { row -> Text(row.food, style = MaterialTheme.typography.titleMedium); Text(listOf("Pantry" to row.pantryDays, "Fridge" to row.fridgeDays, "Freezer" to row.freezerDays).joinToString(" · ") { (zone, days) -> "$zone: ${days?.let { "$it days" } ?: "not recommended"}" }) }
}

@Composable private fun StorageTipsTool() {
    var query by rememberSaveable { mutableStateOf("") }
    Heading("Storage tips", "General storage guidance to keep food at its best.")
    Field("Search foods", query, { query = it })
    val rows = ToolboxParity.searchReference(ToolboxParity.storageTips, query) { it.food + " " + it.place }
    if (rows.isEmpty()) Text("No match. Try a broader name.")
    rows.forEach { Text("${it.food} · ${it.place}", style = MaterialTheme.typography.titleMedium); Text(it.tip) }
}

@Composable private fun SeasonalTool(state: KitchenState) {
    var month by rememberSaveable { mutableStateOf(LocalDate.now().monthValue) }
    val name = java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
    Heading("Seasonal produce", "Typical US seasons; local availability varies.")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { month = if (month == 1) 12 else month - 1 }, modifier = Modifier.semantics { contentDescription = "Previous month" }) { Text("‹") }
        Text(name, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { month = if (month == 12) 1 else month + 1 }, modifier = Modifier.semantics { contentDescription = "Next month" }) { Text("›") }
    }
    ToolboxParity.seasonalProduce(month, state.inventory).forEach { (produce, have) -> Text(produce + if (have) " · in your kitchen" else "") }
}
