package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class HistoryMergeTest {
 private fun row(id:Int,title:String)=buildJsonObject{put("id","00000000-0000-0000-0000-${id.toString().padStart(12,'0')}");put("title",title);put("futureField",true)}
 private fun state(vararg rows:JsonObject)=KitchenState(extra=buildJsonObject{put("pastMeals",JsonArray(rows.toList()))})
 @Test fun importsMergeHistoryAndPreservePrivateJournals(){
  val original=state(row(1,"Local")).copy(extra=JsonObject(state(row(1,"Local")).extra+("_androidHousehold" to JsonPrimitive("private"))+("_androidCookCompletions" to JsonPrimitive("private cook"))))
  val imported=state(row(1,"Imported edit"),row(2,"Added"))
  val merged=BackupCodec.merge(original,imported,false)
  val history=merged.extra.getValue("pastMeals").jsonArray
  assertEquals(2,history.size);assertEquals("Local",history.first().jsonObject.getValue("title").jsonPrimitive.content)
  assertEquals(original.extra["_androidHousehold"],merged.extra["_androidHousehold"]);assertEquals(original.extra["_androidCookCompletions"],merged.extra["_androidCookCompletions"])
  val replaced=BackupCodec.merge(original,imported,true)
  assertEquals("Imported edit",replaced.extra.getValue("pastMeals").jsonArray.first().jsonObject.getValue("title").jsonPrimitive.content)
 }
 @Test fun overLimitHistoryFailsWithoutChangingOriginal(){
  val original=state(row(1,"Local"));val huge=KitchenState(extra=buildJsonObject{put("pastMeals",JsonArray((1..10001).map{row(it,"Cook")}))})
  assertTrue(runCatching{BackupCodec.merge(original,huge,false)}.isFailure)
  assertEquals(1,original.extra.getValue("pastMeals").jsonArray.size)
 }
}
