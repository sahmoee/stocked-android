package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class LocalRecipeCompletionTest {
 private val token="00000000-0000-0000-0000-000000000099"
 @Test fun replayAfterDurableRoundTripDoesNotCountTwiceOrConsumeStock(){
  val recipe=Recipe(title="Soup",cookCount=2,extra=buildJsonObject{put("futureField","keep")})
  val original=KitchenState(userRecipes=listOf(recipe),inventory=listOf(InventoryItem(name="Beans",quantity=5)))
  val once=LocalRecipeCompletion.apply(original,recipe.id,token,1780000000000.0)
  val restored=BackupCodec.json.decodeFromJsonElement<KitchenState>(BackupCodec.json.encodeToJsonElement(once))
  val twice=LocalRecipeCompletion.apply(restored,recipe.id,token,1780000009999.0)
  assertEquals(once,twice);assertEquals(3,twice.userRecipes.single().cookCount);assertEquals(original.inventory,twice.inventory)
  assertEquals("keep",twice.userRecipes.single().extra.getValue("futureField").jsonPrimitive.content)
  assertEquals(1,twice.extra.getValue("pastMeals").jsonArray.size)
 }
 @Test fun exportedHistoryPreservesCompletionIdempotencyWithoutPrivateJournal(){
  val recipe=Recipe(title="Soup");val cooked=LocalRecipeCompletion.apply(KitchenState(userRecipes=listOf(recipe)),recipe.id,token,1780000000000.0)
  val text=BackupCodec.export(cooked);assertFalse(text.contains(LocalRecipeCompletion.JOURNAL))
  val imported=BackupCodec.preview(text,KitchenState()).incoming
  assertEquals(imported,LocalRecipeCompletion.apply(imported,recipe.id,token,1780000001111.0))
 }
 @Test fun reusedTokenForDifferentRecipeFailsWithoutMutation(){
  val one=Recipe(title="Soup");val two=Recipe(title="Salad");val cooked=LocalRecipeCompletion.apply(KitchenState(userRecipes=listOf(one,two)),one.id,token,1780000000000.0)
  assertTrue(runCatching{LocalRecipeCompletion.apply(cooked,two.id,token,1780000000000.0)}.isFailure);assertEquals(0,cooked.userRecipes.last().cookCount)
 }
}
