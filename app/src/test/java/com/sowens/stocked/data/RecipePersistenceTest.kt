package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class RecipePersistenceTest {
 @Test fun dateCreatedSurvivesStaleEditorAndCookCompletion(){
  val original=Recipe(title="Soup")
  val stored=RecipePersistence.prepare(original,null,1780000000000.0)
  val edited=RecipePersistence.prepare(original.copy(title="Soup edited"),stored,1780009000000.0)
  assertEquals(stored.extra["dateCreated"],edited.extra["dateCreated"])
  val cooked=LocalRecipeCompletion.apply(KitchenState(userRecipes=listOf(edited)),edited.id,"00000000-0000-0000-0000-000000000099",1780010000000.0)
  assertEquals(stored.extra["dateCreated"],cooked.userRecipes.single().extra["dateCreated"])
 }
 @Test fun importedDateAndUnknownMetadataRemain(){
  val recipe=Recipe(title="Soup",extra=buildJsonObject{put("dateCreated","2025-01-02T03:04:05Z");put("futureField",true)})
  val saved=RecipePersistence.prepare(recipe,null,1780000000000.0)
  assertEquals(recipe.extra,saved.extra)
 }
}
