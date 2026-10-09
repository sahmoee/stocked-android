package com.sowens.stocked.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class LocalCalendarDateTest {
 private val chicago=ZoneId.of("America/Chicago")
 @Test fun utcMidnightImportsAsTheLocalCalendarDay(){
  val id="00000000-0000-0000-0000-000000000001"
  val source="""{"schemaVersion":1,"inventory":[{"id":"$id","name":"Milk","expirationDate":"2026-10-09T00:00:00Z"}]}"""
  val state=BackupCodec.preview(source,KitchenState(),chicago).incoming
  assertEquals("2026-10-08",state.inventory.single().expirationDate)
 }
 @Test fun expirationDateRoundTripsThroughLocalMidnightAcrossDST(){
  val dates=listOf("2026-03-08" to "2026-03-08T06:00:00Z","2026-03-09" to "2026-03-09T05:00:00Z","2026-11-01" to "2026-11-01T05:00:00Z","2026-11-02" to "2026-11-02T06:00:00Z")
  dates.forEach{(day,expected)->
   val original=KitchenState(inventory=listOf(InventoryItem(name="Milk",expirationDate=day)))
   val exported=BackupCodec.export(original,chicago)
   val timestamp=Json.parseToJsonElement(exported).jsonObject.getValue("inventory").jsonArray.single().jsonObject.getValue("expirationDate").jsonPrimitive.content
   assertEquals(expected,timestamp)
   assertEquals(day,BackupCodec.preview(exported,KitchenState(),chicago).incoming.inventory.single().expirationDate)
  }
 }
 @Test fun numericSwiftDateUsesLocalDayRatherThanUTC(){
  val swiftSeconds=Instant.parse("2026-10-09T00:00:00Z").epochSecond-978307200
  val source="""{"schemaVersion":1,"inventory":[{"id":"00000000-0000-0000-0000-000000000001","name":"Milk","expirationDate":$swiftSeconds}]}"""
  assertEquals("2026-10-08",BackupCodec.preview(source,KitchenState(),chicago).incoming.inventory.single().expirationDate)
 }
 @Test fun eveningCookHistoryUsesLocalDayWhileLastCookedStaysAnInstant(){
  val recipe=Recipe(title="Soup");val instant=Instant.parse("2026-10-09T01:30:00Z")
  val cooked=LocalRecipeCompletion.apply(KitchenState(userRecipes=listOf(recipe)),recipe.id,"00000000-0000-0000-0000-000000000099",instant.toEpochMilli().toDouble(),chicago)
  assertEquals("2026-10-08",cooked.extra.getValue("pastMeals").jsonArray.single().jsonObject.getValue("date").jsonPrimitive.content)
  assertEquals(instant.toString(),cooked.userRecipes.single().extra.getValue("lastCooked").jsonPrimitive.content)
 }
}
