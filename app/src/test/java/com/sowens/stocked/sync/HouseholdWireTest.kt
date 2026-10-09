package com.sowens.stocked.sync
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*
class HouseholdWireTest {
 private fun doc(quantity:Int,name:String="Milk")=buildJsonObject{put("inventory",buildJsonArray{add(buildJsonObject{put("id","a");put("name",name);put("quantity",quantity)})})}
 @Test fun acknowledgedPushRecoversWithoutResendingOrReapplying() {
  val sent=doc(3);val old=doc(5)
  val journal=buildJsonObject { put("pending",HouseholdWire.batch(old,sent,1));put("snapshot",sent);put("baseline",old);put("code","fixture") }
  val acknowledged=HouseholdWire.acknowledgeJournal(journal)
  assertNull(acknowledged["pending"]);assertNull(acknowledged["snapshot"])
  val restored=Json.parseToJsonElement(acknowledged.toString()).jsonObject
  assertEquals(sent,HouseholdWire.reconciliationBase(restored))
  assertEquals(2,HouseholdWire.rows(HouseholdWire.merge(HouseholdWire.reconciliationBase(restored),sent,doc(2)),"inventory").getValue("a").getValue("quantity").jsonPrimitive.int)
 }
 @Test fun decimalEncodedBaselineUsesTheSameQuantityDelta() {
  val base=buildJsonObject { put("inventory",buildJsonArray { add(buildJsonObject { put("id","a");put("name","Milk");put("quantity",2.0) }) }) }
  val batch=HouseholdWire.batch(base,doc(1),8)
  assertEquals(-1,batch.getValue("quantityOperations").jsonArray.single().jsonObject.getValue("delta").jsonPrimitive.int)
 }
 @Test fun quantityUpdateUsesBaselineAndDurableKeys(){val batch=HouseholdWire.batch(doc(5),doc(3),8);assertEquals(-2,batch.getValue("quantityOperations").jsonArray.single().jsonObject.getValue("delta").jsonPrimitive.int);assertEquals(5,batch.getValue("inventory").jsonArray.single().jsonObject.getValue("quantity").jsonPrimitive.int);assertEquals(batch,Json.parseToJsonElement(batch.toString()))}
 @Test fun acknowledgeDoesNotReapplyAlreadySentQuantity(){val merged=HouseholdWire.merge(doc(3),doc(3),doc(2));assertEquals(2,HouseholdWire.rows(merged,"inventory").getValue("a").getValue("quantity").jsonPrimitive.int)}
 @Test fun editsMadeDuringFlightAreRetained(){val merged=HouseholdWire.merge(doc(3),doc(3,"Local edit"),doc(2));assertEquals("Local edit",HouseholdWire.rows(merged,"inventory").getValue("a").getValue("name").jsonPrimitive.content)}
 @Test fun inFlightConsumptionRebasesOnConcurrentServerConsumption() {
  val (merged,baseline)=HouseholdWire.reconcile(doc(3),doc(2),doc(2))
  assertEquals(1,HouseholdWire.rows(merged,"inventory").getValue("a").getValue("quantity").jsonPrimitive.int)
  val next=HouseholdWire.batch(baseline,merged,2)
  assertEquals(-1,next.getValue("quantityOperations").jsonArray.single().jsonObject.getValue("delta").jsonPrimitive.int)
 }
 @Test fun metadataEditDoesNotUndoRemoteConsumption() {
  val (merged,baseline)=HouseholdWire.reconcile(doc(5),doc(5,"Local name"),doc(4))
  val row=HouseholdWire.rows(merged,"inventory").getValue("a")
  assertEquals("Local name",row.getValue("name").jsonPrimitive.content)
  assertEquals(4,row.getValue("quantity").jsonPrimitive.int)
  assertTrue(HouseholdWire.batch(baseline,merged,2).getValue("quantityOperations").jsonArray.isEmpty())
 }
 @Test(expected=IllegalArgumentException::class) fun impossibleConcurrentConsumptionDoesNotSilentlyClamp() {
  HouseholdWire.reconcile(doc(3),doc(0),doc(1))
 }
 @Test fun unchangedRecordHonorsServerTombstone(){val remote=buildJsonObject{put("inventory",JsonArray(emptyList()));put("invDeleted",buildJsonArray{add("a")})};assertTrue(HouseholdWire.rows(HouseholdWire.merge(doc(5),doc(5),remote),"inventory").isEmpty())}
 @Test fun batchLimitKeepsUnsentRecordsInNextDiff(){val base=buildJsonObject{put("inventory",JsonArray(emptyList()))};val current=buildJsonObject{put("inventory",buildJsonArray{repeat(201){i->add(buildJsonObject{put("id","item-$i");put("name","Milk");put("quantity",1)})}})};val first=HouseholdWire.batch(base,current,1);assertEquals(200,first.getValue("operations").jsonArray.size);val snapshot=HouseholdWire.sentSnapshot(base,current,first);assertEquals(1,HouseholdWire.batch(snapshot,current,2).getValue("operations").jsonArray.size)}
 @Test fun membershipJournalDoesNotExport(){val state=com.sowens.stocked.data.KitchenState(extra=buildJsonObject{put("_androidHousehold",buildJsonObject{put("code","PRIVATE")});put("iosSetting",true)});val exported=com.sowens.stocked.data.BackupCodec.export(state);assertFalse(exported.contains("PRIVATE"));assertTrue(exported.contains("iosSetting"))}
 @Test fun offlineDeleteSurvivesRemotePull(){val empty=buildJsonObject{put("inventory",JsonArray(emptyList()))};val merged=HouseholdWire.merge(doc(5),empty,doc(7));assertTrue(HouseholdWire.rows(merged,"inventory").isEmpty());assertEquals("delete",HouseholdWire.batch(doc(5),empty,1).getValue("operations").jsonArray.single().jsonObject.getValue("operationType").jsonPrimitive.content)}
 @Test fun androidZonesUseReleasedIOSWireKey(){
  for(zone in listOf("Fridge","Freezer","Pantry","Staples")) {
   val state=com.sowens.stocked.data.KitchenState(inventory=listOf(com.sowens.stocked.data.InventoryItem(name="Milk",storageCategory=zone)))
   val row=HouseholdWire.document(state).getValue("inventory").jsonArray.single().jsonObject
   assertEquals(zone,row.getValue("zone").jsonPrimitive.content);assertFalse(row.containsKey("storageCategory"))
  }
 }
 @Test fun iosZoneImportsWithoutChangingStorageOrUnknownMetadata(){
  val id="00000000-0000-0000-0000-000000000001"
  val remote=buildJsonObject{put("inventory",buildJsonArray{add(buildJsonObject{put("id",id);put("name","Milk");put("quantity",2);put("zone","Fridge");put("futureField","preserved")})})}
  val kitchen=HouseholdWire.kitchen(remote,com.sowens.stocked.data.KitchenState())
  assertEquals("Fridge",kitchen.inventory.single().storageCategory)
  val roundTrip=HouseholdWire.document(kitchen).getValue("inventory").jsonArray.single().jsonObject
  assertEquals("Fridge",roundTrip.getValue("zone").jsonPrimitive.content);assertEquals("preserved",roundTrip.getValue("futureField").jsonPrimitive.content)
 }
 @Test fun androidRecipesAndMealsSupplyRequiredIOSCodableFields(){
  val state=com.sowens.stocked.data.KitchenState(userRecipes=listOf(com.sowens.stocked.data.Recipe(title="Soup",ingredients=listOf(com.sowens.stocked.data.Ingredient(name="Beans")),instructions=listOf("Cook"),updatedAt=1780000000000.0)),planned=listOf(com.sowens.stocked.data.PlannedMeal(title="Soup")))
  val wire=HouseholdWire.document(state)
  val recipe=wire.getValue("userRecipes").jsonArray.single().jsonObject
  assertEquals("Medium",recipe.getValue("difficulty").jsonPrimitive.content)
  assertEquals("unspecified",recipe.getValue("dishRole").jsonPrimitive.content)
  assertEquals(801692800.0,recipe.getValue("dateCreated").jsonPrimitive.double,0.01)
  assertFalse(recipe.getValue("ingredients").jsonArray.single().jsonObject.getValue("isOptional").jsonPrimitive.boolean)
  val meal=wire.getValue("plannedMeals").jsonArray.single().jsonObject
  assertFalse(meal.getValue("isBuilding").jsonPrimitive.boolean);assertEquals("none",meal.getValue("cookAheadStatus").jsonPrimitive.content)
 }
 @Test fun byteBoundedBatchKeepsUnsentRowsForNextBatch(){
  val base=buildJsonObject{put("userRecipes",JsonArray(emptyList()))}
  val current=buildJsonObject{put("userRecipes",buildJsonArray{repeat(3){i->add(buildJsonObject{put("id","recipe-$i");put("title","Soup");put("notes","🍲".repeat(200000))})}})}
  val batch=HouseholdWire.batch(base,current,1)
  assertEquals(2,batch.getValue("operations").jsonArray.size)
  assertTrue(batch.toString().toByteArray(Charsets.UTF_8).size<=HouseholdWire.MAX_BATCH_BYTES)
  val snapshot=HouseholdWire.sentSnapshot(base,current,batch)
  assertEquals(1,HouseholdWire.batch(snapshot,current,2).getValue("operations").jsonArray.size)
  assertEquals(batch,Json.parseToJsonElement(batch.toString()))
 }
 @Test fun oversizedSingleRecordFailsBeforeFreezingOrLosingData(){
  val empty=JsonObject(emptyMap());val large=buildJsonObject{put("userRecipes",buildJsonArray{add(buildJsonObject{put("id","huge");put("title","Soup");put("notes","x".repeat(HouseholdWire.MAX_BATCH_BYTES))})})}
  val error=runCatching{HouseholdWire.batch(empty,large,1)}.exceptionOrNull()
  assertTrue(error is IllegalArgumentException);assertTrue(error!!.message!!.contains("Local data has been retained"))
  assertEquals(1,HouseholdWire.rows(large,"userRecipes").size)
 }
 private val inv="6F1C2B9A-0D3E-4C5B-9A7F-1E2D3C4B5A60";private val gro="7A2D3C4B-1E0F-4A5B-8C9D-2F3E4D5C6B71"
 private val rec="8B3E4D5C-2F1A-4B6C-9DAE-3A4F5E6D7C82";private val meal="9C4F5E6D-3A2B-4C7D-8EBF-4B5A6F7E8D93"
 /** Rows exactly as iOS HouseholdSync.inventoryDict/groceryDict and Swift JSONEncoder write them. */
 private fun iosHousehold()=buildJsonObject{
  put("revision",7);put("invDeleted",JsonArray(emptyList()))
  put("inventory",buildJsonArray{add(buildJsonObject{put("id",inv);put("name","Milk");put("quantity",2);put("zone","Fridge");put("level",1);put("brand","");put("updatedAt",1780000000123L);put("lastWriterID","ios-a");put("recordRevision",3);putJsonObject("fieldRevisions"){put("name",3)};put("serverCheckpoint",7)})})
  put("grocery",buildJsonArray{add(buildJsonObject{put("id",gro);put("name","Eggs");put("quantity",12);put("isChecked",false);put("recipeSource","");put("addedByName","Sam");put("updatedAt",1780000000000L);put("lastWriterID","ios-a");put("assignedTo","");put("sizeText","")})})
  put("userRecipes",buildJsonArray{add(buildJsonObject{put("id",rec);put("title","Soup");put("description","");put("cookTime","");put("prepTime","");put("servings",4);put("difficulty","Medium");put("cuisine","");put("tags",JsonArray(emptyList()))
   put("ingredients",buildJsonArray{add(buildJsonObject{put("id",meal.replace('9','1'));put("name","Beans");put("amount","1 can");put("isOptional",false)})})
   put("instructions",buildJsonArray{add("Simmer")});put("notes","");put("isFavorited",false);put("dateCreated",801692800.123456);put("cookCount",1);put("lastCooked",801700000.5);put("updatedAt",1780000000500L);put("lastWriterID","ios-a");put("dishRole","unspecified")})})
  put("plannedMeals",buildJsonArray{add(buildJsonObject{put("id",meal);put("dayIndex",2);put("title","Soup");put("servings",4);put("ingredients",buildJsonArray{add("Beans")});put("mealType","Dinner");put("isCooked",false);put("isBuilding",false);put("updatedAt",1780000000000L);put("lastWriterID","ios-a");put("cookAheadStatus","none")})})
 }
 @Test fun iosShapedHouseholdSyncsWithZeroChanges(){
  val remote=iosHousehold()
  val stored=com.sowens.stocked.data.BackupCodec.json.let{it.decodeFromString(com.sowens.stocked.data.KitchenState.serializer(),it.encodeToString(com.sowens.stocked.data.KitchenState.serializer(),HouseholdWire.kitchen(remote,com.sowens.stocked.data.KitchenState())))}
  val local=HouseholdWire.document(stored)
  // The raw shapes genuinely differ (containerType default, 1 vs 1.0, 1.78E12): a raw diff pushed every row.
  assertNotEquals(HouseholdWire.rows(remote,"inventory"),HouseholdWire.rows(local,"inventory"))
  assertFalse(HouseholdWire.changed(remote,local))
  assertTrue(HouseholdWire.batch(remote,local,7).getValue("operations").jsonArray.isEmpty())
  val (merged,baseline)=HouseholdWire.reconcile(remote,local,remote)
  assertEquals(stored,HouseholdWire.kitchen(merged,stored))
  assertFalse(HouseholdWire.changed(baseline,local))
  // Swift's fractional Date survives the whole-second backup export on the household wire.
  assertEquals(801692800.123456,local.getValue("userRecipes").jsonArray.single().jsonObject.getValue("dateCreated").jsonPrimitive.double,0.0)
 }
 @Test fun iosEditKeepsAndroidOnlyFieldsWithoutEchoPush(){
  val item=com.sowens.stocked.data.InventoryItem(id=inv,name="Milk",quantity=2,containerType="bottle",sizeAmount=1.0,sizeUnit="gal",storageCategory="Fridge",expirationDate="2026-10-20",brand="Acme",price=3.49,barcode="0123456789",updatedAt=1780000000000.0,lastWriterID="android")
  val state=com.sowens.stocked.data.KitchenState(inventory=listOf(item),grocery=listOf(com.sowens.stocked.data.GroceryItem(id=gro,name="Eggs",quantity=12,recipeSource="Soup",recipeId=rec,updatedAt=1780000000000.0)))
  val base=HouseholdWire.document(state)
  val remote=buildJsonObject{
   put("inventory",buildJsonArray{add(buildJsonObject{put("id",inv);put("name","Whole milk");put("quantity",3);put("zone","Fridge");put("level",0.5);put("brand","Acme");put("updatedAt",1780000900000L);put("lastWriterID","ios-a");put("recordRevision",4)})})
   put("grocery",buildJsonArray{add(buildJsonObject{put("id",gro);put("name","Eggs");put("quantity",6);put("isChecked",true);put("recipeSource","Soup");put("addedByName","");put("updatedAt",1780000900000L);put("lastWriterID","ios-a");put("assignedTo","");put("sizeText","")})})
  }
  val (merged,baseline)=HouseholdWire.reconcile(base,base,remote)
  val next=HouseholdWire.kitchen(merged,state);val milk=next.inventory.single()
  assertEquals("Whole milk",milk.name);assertEquals(3,milk.quantity);assertEquals(0.5,milk.level,0.0)
  assertEquals("2026-10-20",milk.expirationDate);assertEquals(3.49,milk.price!!,0.0);assertEquals("0123456789",milk.barcode)
  assertEquals("bottle",milk.containerType);assertEquals(1.0,milk.sizeAmount!!,0.0);assertEquals("gal",milk.sizeUnit)
  assertEquals(rec,next.grocery.single().recipeId);assertTrue(next.grocery.single().isChecked);assertEquals(6,next.grocery.single().quantity)
  assertTrue(HouseholdWire.batch(baseline,HouseholdWire.document(next),9).getValue("operations").jsonArray.isEmpty())
 }
 @Test fun androidPeerClearingAnAndroidOnlyFieldIsNotResurrected(){
  val state=com.sowens.stocked.data.KitchenState(inventory=listOf(com.sowens.stocked.data.InventoryItem(id=inv,name="Milk",expirationDate="2026-10-20")))
  val base=HouseholdWire.document(state)
  val peer=HouseholdWire.document(state.copy(inventory=listOf(state.inventory.single().copy(expirationDate=null,updatedAt=5.0))))
  assertNull(HouseholdWire.kitchen(HouseholdWire.merge(base,base,peer),state).inventory.single().expirationDate)
 }
 private fun recipeRow(id:String,title:String,servings:Int)=buildJsonObject{put("id",id);put("title",title);put("servings",servings);put("updatedAt",1780000000000L);put("lastWriterID","ios-a")}
 @Test fun malformedRowIsQuarantinedNotDeletedWhileValidRowsConverge(){
  fun household(vararg recipes:JsonObject)=buildJsonObject{put("userRecipes",JsonArray(recipes.toList()))}
  val bad=recipeRow(meal,"Broken",0);val remote=household(recipeRow(rec,"Soup",4),bad)
  val first=HouseholdWire.decode(remote,com.sowens.stocked.data.KitchenState())
  assertEquals(listOf(rec),first.state.userRecipes.map{it.id})
  assertEquals(bad,HouseholdWire.rows(first.quarantine,"userRecipes").getValue(meal))
  val local=HouseholdWire.withQuarantine(HouseholdWire.document(first.state),first.quarantine)
  assertTrue(HouseholdWire.batch(remote,local,1).getValue("operations").jsonArray.isEmpty())
  val (merged,baseline)=HouseholdWire.reconcile(remote,local,household(recipeRow(rec,"Better soup",4),bad))
  val second=HouseholdWire.decode(merged,first.state)
  assertEquals("Better soup",second.state.userRecipes.single().title)
  assertTrue(HouseholdWire.rows(second.quarantine,"userRecipes").containsKey(meal))
  val edited=second.state.copy(userRecipes=second.state.userRecipes.map{it.copy(title="Local soup")})
  val ops=HouseholdWire.batch(baseline,HouseholdWire.withQuarantine(HouseholdWire.document(edited),second.quarantine),2).getValue("operations").jsonArray.map{it.jsonObject}
  assertEquals(listOf(rec to "update"),ops.map{it.getValue("entityId").jsonPrimitive.content to it.getValue("operationType").jsonPrimitive.content})
  val unchanged=HouseholdWire.withQuarantine(HouseholdWire.document(second.state),second.quarantine)
  val repaired=HouseholdWire.decode(HouseholdWire.merge(baseline,unchanged,household(recipeRow(rec,"Better soup",4),recipeRow(meal,"Broken",2))),second.state)
  assertEquals(2,repaired.state.userRecipes.size);assertTrue(repaired.quarantine.isEmpty())
 }
}
