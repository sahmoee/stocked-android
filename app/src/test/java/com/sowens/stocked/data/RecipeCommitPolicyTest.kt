package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class RecipeCommitPolicyTest {
 @Test fun staleEditorPreservesCookMetricsAndUnchangedLiveFields(){
  val before=Recipe(title="Soup",cookCount=1,extra=buildJsonObject{put("lastCooked","2025-01-01T00:00:00Z");put("dateCreated","2024-01-01T00:00:00Z");put("difficulty","Easy")})
  val live=before.copy(cookCount=2,isFavorited=true,notes="Remote note",lastWriterID="ios",extra=buildJsonObject{put("lastCooked","2026-01-01T00:00:00Z");put("dateCreated","2024-01-01T00:00:00Z");put("difficulty","Hard");put("futureField",true)})
  val result=RecipeCommitPolicy.apply(KitchenState(userRecipes=listOf(live)),before.copy(title="New title"),before,1780000000000.0).userRecipes.single()
  assertEquals("New title",result.title);assertEquals(2,result.cookCount);assertTrue(result.isFavorited);assertEquals("Remote note",result.notes);assertEquals(live.extra,result.extra);assertEquals("ios",result.lastWriterID)
 }
 @Test fun ingredientEditsMergeByIdAndPreserveConcurrentAdditionsDeletions(){
  val one=Ingredient(name="Beans",amount="1 cup",extra=buildJsonObject{put("nutrition","old")});val two=Ingredient(name="Salt",amount="1 tsp");val three=Ingredient(name="Pepper",amount="1 tsp")
  val before=Recipe(title="Soup",ingredients=listOf(one,two))
  val live=before.copy(ingredients=listOf(one.copy(amount="2 cups",extra=buildJsonObject{put("nutrition","new");put("futureField",true)}),three))
  val edited=before.copy(ingredients=listOf(one.copy(name="Black beans"),two))
  val result=RecipeCommitPolicy.apply(KitchenState(userRecipes=listOf(live)),edited,before,1780000000000.0).userRecipes.single().ingredients
  assertEquals(listOf(one.id,three.id),result.map{it.id});assertEquals("Black beans",result.first().name);assertEquals("2 cups",result.first().amount);assertEquals(live.ingredients.first().extra,result.first().extra)
 }
 @Test fun explicitBaselineIngredientDeletionWinsButDoesNotDeleteConcurrentNewId(){
  val one=Ingredient(name="Beans");val two=Ingredient(name="Salt");val fresh=Ingredient(name="Pepper");val before=Recipe(title="Soup",ingredients=listOf(one,two));val live=before.copy(ingredients=listOf(one.copy(amount="2 cups"),two,fresh))
  val result=RecipeCommitPolicy.apply(KitchenState(userRecipes=listOf(live)),before.copy(ingredients=listOf(two)),before,1780000000000.0).userRecipes.single().ingredients
  assertEquals(listOf(two.id,fresh.id),result.map{it.id})
 }
 @Test fun editedDeletedIngredientOrRecipeIsNeverResurrected(){
  val ingredient=Ingredient(name="Beans");val before=Recipe(title="Soup",ingredients=listOf(ingredient));val live=before.copy(ingredients=emptyList());val draft=before.copy(ingredients=listOf(ingredient.copy(name="Black beans")))
  assertTrue(runCatching{RecipeCommitPolicy.apply(KitchenState(userRecipes=listOf(live)),draft,before,1780000000000.0)}.isFailure)
  assertTrue(runCatching{RecipeCommitPolicy.apply(KitchenState(),before,before,1780000000000.0)}.isFailure)
 }
 @Test fun newIdCollisionRejectedAndUnchangedDraftIsNoOp(){
  val recipe=Recipe(title="Soup");val state=KitchenState(userRecipes=listOf(recipe));assertTrue(runCatching{RecipeCommitPolicy.apply(state,recipe,null,1780000000000.0)}.isFailure)
  assertSame(state,RecipeCommitPolicy.apply(state,recipe,recipe,1780000000000.0))
 }
}
