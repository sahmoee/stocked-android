@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.sowens.stocked.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.sowens.stocked.data.*
import java.time.LocalDate
import kotlinx.serialization.json.*

@Composable
fun InventoryScreen(state: KitchenState, save: (InventoryItem, InventoryItem?) -> Unit, delete: (String) -> Unit, consume: (String, Int) -> Unit, initialQuery: String = "") {
    var zone by remember { mutableStateOf("All") }
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var editing by remember { mutableStateOf<InventoryItem?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<InventoryItem?>(null) }
    val filtered = state.inventory.filter { it.name.contains(query, true) && (zone=="All" || it.storageCategory==zone) }.sortedBy { it.expirationDate ?: "9999" }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=16.dp),contentPadding=PaddingValues(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        EditorialHero("Your kitchen, at a glance", "Good food, all in reach.", "Browse, stay organized, and know what you have.", produceArtwork())
        FlowRow(horizontalArrangement=Arrangement.spacedBy(4.dp)) { listOf("All","Fridge","Freezer","Pantry","Staples").forEach { value -> FilterChip(selected=zone==value,onClick={ zone=value },label={ Text(value) }) } }
        Field("Search inventory", query, { query = it })
        Button(onClick = { creating = true; editing = InventoryItem(name = "") }) { Text("Add item") }
        if (filtered.isEmpty()) EmptyState("Your pantry starts here", "Add food, its storage zone and expiry date. Search results appear here too.")
        } }
            items(filtered, key = { it.id }) { item ->
                StockedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text("${item.quantity} ${item.containerType} · ${item.storageCategory}")
                    Text("Fill: ${(item.level * 100).toInt()}%" + (item.sizeAmount?.let { " · $it ${item.sizeUnit.orEmpty()} per container" } ?: ""))
                    listOf("customCategory" to "Category", "subZone" to "Spot", "storePurchasedAt" to "Purchased at", "parQuantity" to "Minimum stock").forEach { (key, label) ->
                        (item.extra[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { Text("$label: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                    item.price?.let { Text("Price per container: $it") }
                    item.expirationDate?.let { Text("Expires $it", color = if (runCatching { LocalDate.parse(it).isBefore(LocalDate.now()) }.getOrDefault(false)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { creating = false; editing = item }) { Text("Edit") }
                        TextButton(onClick = { consume(item.id, 1) }, enabled = item.quantity > 0) { Text("Use one") }
                        TextButton(onClick = { deleting = item }) { Text("Delete") }
                    }
                } }
            }
    }
    editing?.let { original ->
        val live = state.inventory.firstOrNull { it.id == original.id }
        InventoryEditor(original, creating || live != null, { draft ->
            val current = state.inventory.firstOrNull { it.id == original.id }
            if (creating || current != null) {
                save(draft, if (creating) null else original)
                editing = null
            }
        }, { editing = null })
    }
    deleting?.let { item -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${item.name}?") }, text = { Text("This removes the inventory entry.") }, confirmButton = { TextButton(onClick = { delete(item.id); deleting = null }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun InventoryEditor(item: InventoryItem, available: Boolean, save: (InventoryItem) -> Unit, dismiss: () -> Unit) {
    var name by remember(item.id) { mutableStateOf(item.name) }
    var quantity by remember { mutableStateOf(item.quantity.toString()) }
    var container by remember { mutableStateOf(item.containerType) }
    var zone by remember { mutableStateOf(item.storageCategory) }
    var expiry by remember { mutableStateOf(item.expirationDate.orEmpty()) }
    var brand by remember { mutableStateOf(item.brand.orEmpty()) }
    var size by remember { mutableStateOf(item.sizeAmount?.toString().orEmpty()) }
    var unit by remember { mutableStateOf(item.sizeUnit.orEmpty()) }
    var price by remember { mutableStateOf(item.price?.toString().orEmpty()) }
    var barcode by remember { mutableStateOf(item.barcode.orEmpty()) }
    var level by remember { mutableStateOf(item.level.toFloat().takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f) }
    fun originalText(key: String) = (item.extra[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    var category by remember { mutableStateOf(originalText("customCategory")) }
    var spot by remember { mutableStateOf(originalText("subZone")) }
    var store by remember { mutableStateOf(originalText("storePurchasedAt")) }
    var par by remember { mutableStateOf(originalText("parQuantity")) }
    var leftover by remember { mutableStateOf((item.extra["isLeftover"] as? JsonPrimitive)?.booleanOrNull ?: false) }
    var meal by remember { mutableStateOf(originalText("leftoverMeal")) }
    var stash by remember { mutableStateOf((item.extra["hasStash"] as? JsonPrimitive)?.booleanOrNull ?: false) }
    fun metadata(): JsonObject {
        val values = item.extra.toMutableMap()
        fun text(key: String, value: String) {
            if (value != originalText(key)) { if (value.isBlank()) values.remove(key) else values[key] = JsonPrimitive(value.trim()) }
        }
        text("customCategory", category); text("subZone", spot); text("storePurchasedAt", store); text("leftoverMeal", meal)
        if (par != originalText("parQuantity")) {
            if (par.isBlank() || par.toIntOrNull() == 0) values.remove("parQuantity") else values["parQuantity"] = JsonPrimitive(par.toInt())
        }
        if (leftover != ((item.extra["isLeftover"] as? JsonPrimitive)?.booleanOrNull ?: false)) values["isLeftover"] = JsonPrimitive(leftover)
        if (stash != ((item.extra["hasStash"] as? JsonPrimitive)?.booleanOrNull ?: false)) values["hasStash"] = JsonPrimitive(stash)
        return JsonObject(values)
    }
    val valid = available && container.isNotBlank() && listOf(category, spot, store, meal).all { it.length <= 500 } && (par.isBlank() || par.toIntOrNull() in 0..100000) && (price.isBlank() || (price.toDoubleOrNull()?.let { it.isFinite() && it >= 0 } == true)) && name.isNotBlank() && quantity.toIntOrNull() in 0..100000 && (expiry.isBlank() || runCatching { LocalDate.parse(expiry) }.isSuccess) && (size.isBlank() || (size.toDoubleOrNull()?.let { it.isFinite() && it > 0 } == true))
    EditorDialog("Inventory item", valid, { save(item.copy(name = name.trim(), quantity = quantity.toInt(), containerType = container.trim(), storageCategory = zone, expirationDate = expiry.ifBlank { null }, brand = brand.ifBlank { null }, sizeAmount = size.toDoubleOrNull(), sizeUnit = unit.ifBlank { null }, price = price.toDoubleOrNull(), barcode = barcode.ifBlank { null }, level = level.toDouble(), extra = metadata())) }, dismiss) {
        Field("Name", name, { name = it }); Field("Quantity", quantity, { quantity = it }, true)
        Field("Container", container, { container = it })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("item", "package", "bag", "box", "can", "bottle", "jar", "carton", "case").forEach { value -> FilterChip(selected = container == value, onClick = { container = value }, label = { Text(value) }) } }
        Text("Storage zone")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("Pantry", "Fridge", "Freezer", "Staples").forEach { value -> FilterChip(selected = zone == value, onClick = { zone = value }, label = { Text(value) }) } }
        Field("Expiry YYYY-MM-DD (optional)", expiry, { expiry = it }); Field("Brand (optional)", brand, { brand = it })
        Field("Size (optional)", size, { size = it }, true); Field("Size unit (g, ml…)", unit, { unit = it })
        Field("Category (Snacks, Baking…)", category, { category = it })
        Field("Spot in $zone (shelf, door…)", spot, { spot = it })
        Field("Minimum stock (optional)", par, { par = it }, true)
        Text("Minimum stock is saved for iOS compatibility; Android does not automatically reorder.", style = MaterialTheme.typography.bodySmall)
        Field("Purchased at (optional)", store, { store = it })
        Row { Checkbox(checked = leftover, onCheckedChange = { leftover = it }, modifier = Modifier.semantics { contentDescription = "Mark as leftover" }); Text("Leftover") }
        if (leftover) Field("Leftover meal (optional)", meal, { meal = it })
        Row { Checkbox(checked = stash, onCheckedChange = { stash = it }, modifier = Modifier.semantics { contentDescription = "Has extra stock in stash" }); Text("Extra stock in stash") }
        Field("Price per container (optional)", price, { price = it }, true); Field("Barcode (optional)", barcode, { barcode = it })
        Text("Fill level: ${(level * 100).toInt()}%")
        Slider(value = level, onValueChange = { level = it }, modifier = Modifier.semantics { contentDescription = "Container fill level" })
        if (!available) Text("This item was removed. Close the editor to refresh.", color = MaterialTheme.colorScheme.error)
        if (!valid && available) Text("Enter a name and container, whole quantity/minimum stock, valid date, positive size and non-negative price.", color = MaterialTheme.colorScheme.error)
    }
}
