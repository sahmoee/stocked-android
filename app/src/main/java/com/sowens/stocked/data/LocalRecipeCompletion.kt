package com.sowens.stocked.data

import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Atomic idempotency marker, recipe metrics and compatible meal-history preparation. */
object LocalRecipeCompletion {
 const val JOURNAL="_androidCookCompletions"
 fun apply(state:KitchenState,recipeId:String,completionToken:String,timestamp:Double,zone:ZoneId=ZoneId.systemDefault()):KitchenState {
  val token=runCatching{UUID.fromString(completionToken).toString()}.getOrElse{throw IllegalArgumentException("Cooking completion token is invalid.")}
  require(timestamp.isFinite() && timestamp>=0 && timestamp<=253402300799000.0){"Cooking completion date is invalid."}
  val recipe=state.userRecipes.find{it.id==recipeId} ?: error("Recipe no longer exists. Your kitchen has not changed.")
  val ledger=state.extra[JOURNAL] as? JsonObject ?: JsonObject(emptyMap())
  val previous=ledger[token] as? JsonObject
  if(previous!=null){require(previous["recipeId"]?.jsonPrimitive?.content==recipeId){"Cooking token belongs to another recipe."};return state}
  val historyElement=state.extra["pastMeals"]
  require(historyElement==null || historyElement is JsonArray){"Meal history needs review before recording this cook."}
  val history=(historyElement as? JsonArray).orEmpty()
  history.firstOrNull{(it as? JsonObject)?.get("id")?.jsonPrimitive?.content.equals(token,ignoreCase=true)}?.let{row->
   require(row.jsonObject["recipeId"]?.jsonPrimitive?.content==recipeId){"Cooking token belongs to another recipe."};return state
  }
  require(ledger.size<10000 && history.size<10000){"Export and archive meal history before recording additional cooks."}
  require(recipe.cookCount<Int.MAX_VALUE){"Recipe cooking count is too large."}
  val completed=Instant.ofEpochMilli(timestamp.toLong()).truncatedTo(ChronoUnit.SECONDS)
  val updated=recipe.copy(cookCount=recipe.cookCount+1,updatedAt=timestamp,extra=JsonObject(recipe.extra+("lastCooked" to JsonPrimitive(completed.toString()))))
  val entry=buildJsonObject{put("id",token);put("title",recipe.title);put("date",completed.atZone(zone).toLocalDate().toString());put("recipeId",recipeId);put("rating",0);put("thumbUp",true);put("notes","")}
  val marker=buildJsonObject{put("recipeId",recipeId);put("completedAt",completed.toString())}
  return state.copy(userRecipes=state.userRecipes.map{if(it.id==recipeId)updated else it},extra=JsonObject(state.extra+("pastMeals" to JsonArray(history+entry))+(JOURNAL to JsonObject(ledger+(token to marker)))))
 }
}
