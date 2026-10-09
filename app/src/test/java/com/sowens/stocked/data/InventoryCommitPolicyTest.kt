package com.sowens.stocked.data
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
class InventoryCommitPolicyTest {
 @Test fun commitUsesLatestQuantityAndRevisionInsteadOfEditorSnapshot(){
  val baseline=InventoryItem(name="Beans",quantity=5,extra=buildJsonObject{put("recordRevision",2);put("photo","old")})
  val live=baseline.copy(quantity=3,level=0.5,updatedAt=9000.0,lastWriterID="ios",extra=buildJsonObject{put("recordRevision",3);put("photo","new");put("futureField",true)})
  val state=KitchenState(inventory=listOf(live),extra=buildJsonObject{put("privateSetting",true)})
  val saved=InventoryCommitPolicy.apply(state,baseline.copy(name="Renamed beans"),baseline,10000.0)
  val row=saved.inventory.single()
  assertEquals("Renamed beans",row.name);assertEquals(3,row.quantity);assertEquals(0.5,row.level,0.0);assertEquals(live.extra,row.extra);assertEquals("ios",row.lastWriterID);assertEquals(10000.0,row.updatedAt,0.0);assertEquals(state.extra,saved.extra)
 }
 @Test fun deletedEditedItemCannotBeResurrected(){
  val original=InventoryItem(name="Beans");val state=KitchenState()
  val error=runCatching{InventoryCommitPolicy.apply(state,original.copy(name="Renamed"),original,10000.0)}.exceptionOrNull()
  assertTrue(error is IllegalArgumentException);assertTrue(error!!.message!!.contains("not been recreated"));assertTrue(state.inventory.isEmpty())
 }
 @Test fun creationIsExplicitAndCannotOverwriteAnotherRow(){
  val item=InventoryItem(name="Beans");val once=InventoryCommitPolicy.apply(KitchenState(),item,null,10000.0)
  assertEquals(1,once.inventory.size);assertTrue(runCatching{InventoryCommitPolicy.apply(once,item.copy(quantity=99),null,11000.0)}.isFailure)
 }
 @Test fun unchangedEditorDoesNotWriteOrIncrementVersions(){
  val baseline=InventoryItem(name="Beans");val live=baseline.copy(quantity=2,updatedAt=9000.0)
  val state=KitchenState(inventory=listOf(live));assertSame(state,InventoryCommitPolicy.apply(state,baseline,baseline,10000.0))
 }
}
