package com.sowens.stocked.data

import kotlinx.serialization.json.JsonObject

/** Merge the editor's explicit changes at the durable commit boundary. */
object RecipeCommitPolicy {
 private fun extras(before:JsonObject,current:JsonObject,draft:JsonObject,protectedKeys:Set<String> = emptySet()):JsonObject {
  val result=current.toMutableMap()
  (before.keys+draft.keys).forEach{key->if(key !in protectedKeys && before[key]!=draft[key]){draft[key]?.let{result[key]=it} ?: result.remove(key)}}
  return JsonObject(result)
 }
 private fun ingredients(before:List<Ingredient>,current:List<Ingredient>,draft:List<Ingredient>):List<Ingredient> {
  require(draft.map{it.id}.distinct().size==draft.size){"Ingredient IDs are duplicated. Reopen the recipe."}
  val baseline=before.associateBy{it.id};val edited=draft.associateBy{it.id};val result=current.associateBy{it.id}.toMutableMap()
  // Only a deletion of an ID actually present when the editor opened is intentional.
  (baseline.keys-edited.keys).forEach{result.remove(it)}
  draft.forEach { row ->
   val original=baseline[row.id];val live=result[row.id]
   if(original==null){require(live==null || live==row){"Another ingredient now uses this ID. Reopen the recipe."};if(live==null)result[row.id]=row}
   else if(live==null){require(row==original){"An ingredient was deleted while you were editing. Reopen the recipe; it has not been recreated."}}
   else {
    result[row.id]=live.copy(name=if(original.name==row.name)live.name else row.name,amount=if(original.amount==row.amount)live.amount else row.amount,extra=extras(original.extra,live.extra,row.extra))
   }
  }
  val reordered=draft.map{it.id}!=before.map{it.id}
  return if(reordered) (draft.mapNotNull{result.remove(it.id)}+result.values) else result.values.toList()
 }
 fun apply(state:KitchenState,draft:Recipe,baseline:Recipe?,timestamp:Double):KitchenState {
  require(timestamp.isFinite() && timestamp>=0 && timestamp<=253402300799000.0)
  require(draft.ingredients.map{it.id}.distinct().size==draft.ingredients.size){"Ingredient IDs are duplicated. Reopen the recipe."}
  val live=state.userRecipes.find{it.id==draft.id}
  if(baseline==null){require(live==null){"This recipe already exists. Reopen it to edit; no changes were saved."};val created=RecipePersistence.prepare(draft,null,timestamp).also(KitchenRules::validate);return state.copy(userRecipes=state.userRecipes+created)}
  require(baseline.id==draft.id){"Recipe identity changed. Reopen the recipe."}
  require(live!=null){"This recipe was deleted while you were editing. It has not been recreated."}
  fun <T> field(before:T,current:T,edited:T):T=if(before==edited)current else edited
  val merged=live.copy(
   title=field(baseline.title,live.title,draft.title).trim(),description=field(baseline.description,live.description,draft.description),
   servings=field(baseline.servings,live.servings,draft.servings),prepTime=field(baseline.prepTime,live.prepTime,draft.prepTime),cookTime=field(baseline.cookTime,live.cookTime,draft.cookTime),
   ingredients=ingredients(baseline.ingredients,live.ingredients,draft.ingredients),instructions=field(baseline.instructions,live.instructions,draft.instructions),
   tags=field(baseline.tags,live.tags,draft.tags),cuisine=field(baseline.cuisine,live.cuisine,draft.cuisine),notes=field(baseline.notes,live.notes,draft.notes),
   sourceURL=field(baseline.sourceURL,live.sourceURL,draft.sourceURL),sourceName=field(baseline.sourceName,live.sourceName,draft.sourceName),license=field(baseline.license,live.license,draft.license),
   imageURL=field(baseline.imageURL,live.imageURL,draft.imageURL),isFavorited=field(baseline.isFavorited,live.isFavorited,draft.isFavorited),
   extra=extras(baseline.extra,live.extra,draft.extra,setOf("lastCooked","dateCreated"))
  ).also(KitchenRules::validate)
  if(merged==live)return state
  val committed=RecipePersistence.prepare(merged,live,timestamp)
  return state.copy(userRecipes=state.userRecipes.map{if(it.id==draft.id)committed else it})
 }
}
