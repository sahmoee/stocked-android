package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class BackupInteroperabilityTest {
 @Test fun newAndroidBackupSuppliesSwiftRequiredFieldsAndISODate(){
  val state=KitchenState(userRecipes=listOf(Recipe(title="Soup",updatedAt=1780000000000.0,ingredients=listOf(Ingredient(name="Beans")))),planned=listOf(PlannedMeal(title="Soup")))
  val root=Json.parseToJsonElement(BackupCodec.export(state)).jsonObject
  val recipe=root.getValue("userRecipes").jsonArray.single().jsonObject
  assertEquals("Medium",recipe.getValue("difficulty").jsonPrimitive.content);assertEquals("unspecified",recipe.getValue("dishRole").jsonPrimitive.content)
  assertEquals("2026-05-28T20:26:40Z",recipe.getValue("dateCreated").jsonPrimitive.content)
  assertFalse(recipe.getValue("ingredients").jsonArray.single().jsonObject.getValue("isOptional").jsonPrimitive.boolean)
  val meal=root.getValue("planned").jsonArray.single().jsonObject
  assertFalse(meal.getValue("isBuilding").jsonPrimitive.boolean);assertEquals("none",meal.getValue("cookAheadStatus").jsonPrimitive.content)
 }
 @Test fun iosRecipeFixtureRoundTripsDatesAndUnmodeledFields(){
  val fixture="""{"schemaVersion":1,"exportedAt":"2026-10-08T12:00:00Z","userRecipes":[{"id":"00000000-0000-0000-0000-000000000001","title":"Soup","description":"","cookTime":"5m","prepTime":"2m","servings":2,"difficulty":"Easy","cuisine":"","tags":[],"ingredients":[{"id":"00000000-0000-0000-0000-000000000002","name":"Beans","amount":"1 cup","isOptional":true,"prep":"rinsed"}],"instructions":["Cook"],"notes":"","isFavorited":false,"dateCreated":"2025-01-02T03:04:05Z","cookCount":2,"lastCooked":"2026-01-02T03:04:05Z","updatedAt":1000,"lastWriterID":"ios","dishRole":"main"}],"planned":[{"id":"00000000-0000-0000-0000-000000000003","title":"Soup","dayIndex":1,"servings":2,"ingredients":["Beans"],"mealType":"Dinner","isCooked":false,"isBuilding":true,"updatedAt":1000,"lastWriterID":"ios","cookAheadStatus":"stored"}],"staples":["Rice"]}"""
  val imported=BackupCodec.preview(fixture,KitchenState()).incoming
  val root=Json.parseToJsonElement(BackupCodec.export(imported)).jsonObject
  val recipe=root.getValue("userRecipes").jsonArray.single().jsonObject
  assertEquals("2025-01-02T03:04:05Z",recipe.getValue("dateCreated").jsonPrimitive.content);assertEquals("Easy",recipe.getValue("difficulty").jsonPrimitive.content);assertEquals("main",recipe.getValue("dishRole").jsonPrimitive.content)
  assertTrue(recipe.getValue("ingredients").jsonArray.single().jsonObject.getValue("isOptional").jsonPrimitive.boolean)
  assertEquals("stored",root.getValue("planned").jsonArray.single().jsonObject.getValue("cookAheadStatus").jsonPrimitive.content)
  assertEquals("Rice",root.getValue("staples").jsonArray.single().jsonPrimitive.content)
 }
 @Test fun householdNumericRecipeDatesExportAsPublicISO(){
  val recipe=Recipe(title="Soup",extra=buildJsonObject{put("dateCreated",0.0);put("lastCooked",801692800.0)})
  val row=Json.parseToJsonElement(BackupCodec.export(KitchenState(userRecipes=listOf(recipe)))).jsonObject.getValue("userRecipes").jsonArray.single().jsonObject
  assertEquals("2001-01-01T00:00:00Z",row.getValue("dateCreated").jsonPrimitive.content);assertEquals("2026-05-28T20:26:40Z",row.getValue("lastCooked").jsonPrimitive.content)
 }
}
