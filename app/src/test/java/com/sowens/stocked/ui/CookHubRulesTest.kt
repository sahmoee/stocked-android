package com.sowens.stocked.ui
import com.sowens.stocked.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
class CookHubRulesTest {
 @Test fun optionalIngredientsDoNotBlockButEmptyContainersCannotMatch() {
  val recipe=Recipe(title="Soup",ingredients=listOf(Ingredient(name="Carrot"),Ingredient(name="Pepper",extra=buildJsonObject { put("isOptional",true) })))
  assertTrue(CookHubRules.missing(recipe,listOf(InventoryItem(name="CARROT",quantity=1,level=1.0))).isEmpty())
  assertEquals(listOf("Carrot"),CookHubRules.missing(recipe,listOf(InventoryItem(name="Carrot",quantity=1,level=0.0))).map { it.name })
 }
 @Test fun expiryWindowExcludesPastDatesInvalidDatesAndConsumedItems() {
  val today=LocalDate.of(2026,10,8)
  val items=listOf(InventoryItem(name="Old",expirationDate="2026-10-07"),InventoryItem(name="Now",expirationDate="2026-10-08"),InventoryItem(name="Later",expirationDate="2026-10-16"),InventoryItem(name="Invalid",expirationDate="bad"),InventoryItem(name="Used",expirationDate="2026-10-09",quantity=0))
  assertEquals(listOf("Now"),CookHubRules.expiring(items,today).map { it.name })
 }
}
