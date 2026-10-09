package com.sowens.stocked.sync

import com.sowens.stocked.data.*
import kotlinx.serialization.json.*
import java.util.UUID
import java.time.Instant
import kotlin.math.floor

/** Pure wire transforms. Local edits win on overlap; server tombstones never erase unsent edits.
 * iOS, the Worker and Android serialize one record differently, so rows are compared through
 * [canonical], never as raw JSON; a raw diff would re-push every iOS row on every sync. */
object HouseholdWire {
 data class Collection(val key:String,val deleted:String,val type:String)
 /** Rows that fail Android validation stay in [quarantine], unchanged and never deleted. */
 data class Decoded(val state:KitchenState,val quarantine:JsonObject)
 val collections=listOf(Collection("inventory","invDeleted","inventoryItem"),Collection("grocery","groDeleted","groceryItem"),Collection("userRecipes","userRecipeDeleted","userRecipe"),Collection("plannedMeals","mealDeleted","plannedMeal"))
 /** Keys the shipped iOS writer sends (HouseholdSync.swift inventoryDict/groceryDict, UserRecipe and
  * PlannedMeal Codable). An absent key outside this set is unknown to that writer, not cleared. */
 private val iosKeys=mapOf(
  "inventory" to setOf("id","name","quantity","zone","level","brand","updatedAt","lastWriterID"),
  "grocery" to setOf("id","name","quantity","isChecked","recipeSource","addedByName","updatedAt","lastWriterID","assignedTo","sizeText"),
  "userRecipes" to setOf("id","title","description","cookTime","prepTime","servings","difficulty","cuisine","tags","ingredients","instructions","notes","imageData","imageURL","sourceURL","sourceName","categories","portableSource","collectionSavedByUser","author","license","imageAttribution","isFavorited","dateCreated","cookCount","lastCooked","updatedAt","lastWriterID","dishRole"),
  "plannedMeals" to setOf("id","dayIndex","title","servings","ingredients","mealType","isCooked","isBuilding","updatedAt","lastWriterID","cookAheadStatus"))
 /** Android always encodes these (encodeDefaults); a row carrying one came from Android, whose omissions mean cleared. */
 private val androidMarkers=mapOf("inventory" to "containerType","grocery" to "recipeId")
 private val serverKeys=setOf("recordRevision","fieldRevisions","serverCheckpoint")
 private const val APPLE_EPOCH=978307200.0
 private fun quantityValue(value:JsonElement?):Int? = (value as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..100000.0 }?.toInt()
 fun rows(doc:JsonObject,key:String):Map<String,JsonObject> = (doc[key] as? JsonArray).orEmpty().mapNotNull { value ->
  val row=value as? JsonObject;val id=(row?.get("id") as? JsonPrimitive)?.takeIf{it.isString}?.content
  if(row==null || id==null)null else id to row
 }.toMap()
 /** Backup export truncates Swift Dates to whole seconds; keep iOS's fractional value for the same instant. */
 private fun appleSeconds(value:JsonElement?,original:JsonElement?):JsonPrimitive? {
  val primitive=value as? JsonPrimitive ?: return null
  if(!primitive.isString)return primitive
  val whole=Instant.parse(primitive.content).toEpochMilli()/1000.0-APPLE_EPOCH
  val exact=(original as? JsonPrimitive)?.takeIf{!it.isString && it !is JsonNull}
  return if(exact?.doubleOrNull?.let{floor(it)==whole}==true)exact else JsonPrimitive(whole)
 }
 fun document(state:KitchenState):JsonObject {
  val root=BackupCodec.json.parseToJsonElement(BackupCodec.export(state)).jsonObject.toMutableMap()
  val recipeExtras=state.userRecipes.associate{it.id to it.extra}
  root.remove("_androidHousehold")
  // Household inventory uses the shipped iOS `zone` wire key. Backup JSON uses
  // `storageCategory`; neither contract may silently change the other.
  root["inventory"] = JsonArray((root["inventory"] as? JsonArray).orEmpty().map { value ->
   val row=value.jsonObject
   JsonObject(row.filterKeys { it!="storageCategory" } + ("zone" to (row["storageCategory"] ?: row["zone"] ?: JsonPrimitive("Pantry"))))
  })
  root["userRecipes"] = JsonArray((root["userRecipes"] as? JsonArray).orEmpty().map { value ->
   val row=value.jsonObject.toMutableMap()
   val original=recipeExtras[row["id"]?.jsonPrimitive?.contentOrNull]
   row.putIfAbsent("difficulty",JsonPrimitive("Medium"))
   row.putIfAbsent("dishRole",JsonPrimitive("unspecified"))
   row["dateCreated"] = appleSeconds(row["dateCreated"],original?.get("dateCreated"))
    ?: JsonPrimitive((row["updatedAt"]?.jsonPrimitive?.doubleOrNull ?: 978307200000.0)/1000.0-APPLE_EPOCH)
   row["lastCooked"]?.let{row["lastCooked"]=appleSeconds(it,original?.get("lastCooked")) ?: it}
   row["ingredients"]=JsonArray((row["ingredients"] as? JsonArray).orEmpty().map{ingredient ->
    val fields=ingredient.jsonObject.toMutableMap();fields.putIfAbsent("isOptional",JsonPrimitive(false));JsonObject(fields)
   })
   JsonObject(row)
  })
  root["plannedMeals"]=JsonArray(((root.remove("planned") ?: JsonArray(emptyList())) as JsonArray).map { value ->
   val row=value.jsonObject.toMutableMap();row.putIfAbsent("isBuilding",JsonPrimitive(false));row.putIfAbsent("cookAheadStatus",JsonPrimitive("none"));JsonObject(row)
  })
  return JsonObject(root)
 }
 private fun prepare(key:String,row:JsonObject):JsonObject {
  val present=row.filterValues{it !is JsonNull}
  return JsonObject(if(key=="inventory")present+("storageCategory" to (present["zone"] ?: present["storageCategory"] ?: JsonPrimitive("Pantry"))) else present)
 }
 /** Decodes valid rows; a row failing Android validation is returned raw in quarantine instead of
  * failing the whole document, so valid rows still converge and the bad row is never read as deleted. */
 fun decode(doc:JsonObject,local:KitchenState):Decoded {
  val raw=collections.associate{c->c.key to rows(doc,c.key).values.toList()}
  fun parse(sections:Map<String,List<JsonObject>>)=BackupCodec.preview(JsonObject(mapOf<String,JsonElement>("schemaVersion" to JsonPrimitive(1))+collections.associate{c->
   (if(c.key=="plannedMeals")"planned" else c.key) to JsonArray(sections[c.key].orEmpty().map{prepare(c.key,it)})}).toString(),KitchenState()).incoming
  runCatching{parse(raw)}.onSuccess{return Decoded(it.copy(extra=local.extra),JsonObject(emptyMap()))}
  // ponytail: one invalid row re-parses each row alone; fine at household sizes, bisect if documents grow.
  val rejected=raw.mapValues{(key,list)->list.filter{row->runCatching{parse(mapOf(key to listOf(row)))}.isFailure}}
  val state=parse(raw.mapValues{(key,list)->list-rejected.getValue(key).toSet()})
  return Decoded(state.copy(extra=local.extra),JsonObject(rejected.filterValues{it.isNotEmpty()}.mapValues{JsonArray(it.value)}))
 }
 fun kitchen(doc:JsonObject,local:KitchenState):KitchenState=decode(doc,local).state
 /** Adds quarantined server rows back to a local document so diffs never read them as local deletions. */
 fun withQuarantine(doc:JsonObject,quarantine:JsonObject?):JsonObject {
  if(quarantine.isNullOrEmpty())return doc
  val result=doc.toMutableMap()
  collections.forEach{c->val held=rows(quarantine,c.key);val present=rows(doc,c.key)
   if(held.isNotEmpty())result[c.key]=JsonArray((doc[c.key] as? JsonArray).orEmpty()+held.filterKeys{it !in present}.values)}
  return JsonObject(result)
 }
 private fun comparable(value:JsonElement):JsonElement=when(value){
  is JsonObject->JsonObject(value.filterValues{it !is JsonNull}.mapValues{comparable(it.value)})
  is JsonArray->JsonArray(value.map(::comparable))
  is JsonPrimitive->if(value.isString)value else value.content.toDoubleOrNull()?.let{JsonPrimitive(it+0.0)} ?: value
 }
 /** Semantic row form: decoded and re-encoded through Android's model (absent defaults, zone, Swift
  * dates), numbers compared by value (`1`, `1.0`, `1.7E12`), nulls as absent, server revision metadata
  * excluded. Quarantined rows compare by their raw JSON. */
 fun canonical(doc:JsonObject):Map<String,Map<String,JsonElement>> {
  val encoded=document(decode(doc,KitchenState()).state)
  return collections.associate{c->val valid=rows(encoded,c.key)
   c.key to rows(doc,c.key).mapValues{(id,row)->comparable(JsonObject((valid[id] ?: row).filterKeys{it !in serverKeys}))}}
 }
 fun changed(base:JsonObject,current:JsonObject)=canonical(base)!=canonical(current)
 private fun overlay(key:String,remote:JsonObject,local:JsonObject):JsonObject {
  if(androidMarkers[key]?.let(remote::containsKey)==true)return remote
  var kept=local.filterKeys{it !in remote && it !in serverKeys && it !in iosKeys.getValue(key)}
  // A meal moved on iOS must not keep Android's stale calendar date.
  if(key=="plannedMeals" && comparable(remote["dayIndex"] ?: JsonNull)!=comparable(local["dayIndex"] ?: JsonNull))kept=kept-"date"
  return if(kept.isEmpty())remote else JsonObject(remote+kept)
 }
 /** Persist transport acknowledgement before decoding/merging: recovery never resends accepted operations. */
 fun acknowledgeJournal(current:JsonObject):JsonObject = JsonObject(
  current.filterKeys{it !in setOf("pending","snapshot")} + ("acceptedSnapshot" to current.getValue("snapshot")))
 fun reconciliationBase(current:JsonObject):JsonObject = current["acceptedSnapshot"]?.jsonObject ?: current["baseline"]?.jsonObject ?: JsonObject(emptyMap())
 const val MAX_BATCH_BYTES=1800*1024
 fun batch(base:JsonObject,local:JsonObject,revision:Long):JsonObject {
  val operations=mutableListOf<JsonElement>();val quantities=mutableListOf<JsonElement>();val payload=mutableMapOf<String,JsonElement>()
  // Reserve keys, separators, request ID and array framing. Every candidate is
  // measured after JSON escaping in UTF-8; character counts are not byte counts.
  var usedBytes=4096
  var full=false
  fun bytes(value:JsonElement?)=value?.toString()?.toByteArray(Charsets.UTF_8)?.size ?: 0
  val baseForm=canonical(base);val localForm=canonical(local)
  collections.forEach { c ->
   val old=rows(base,c.key);val current=rows(local,c.key);val changed=mutableListOf<JsonElement>();val deleted=mutableListOf<JsonElement>()
   val oldForm=baseForm.getValue(c.key);val currentForm=localForm.getValue(c.key)
   val tombstones=(base[c.deleted] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}.toSet()
   (old.keys+current.keys).forEach records@{ id ->
    val before=old[id];val after=current[id]
    if(oldForm[id]==currentForm[id] || full || operations.size>=200)return@records
    require(after==null || id !in tombstones){"An offline edit conflicts with a deleted household record. Export a backup and duplicate it with a new ID before syncing."}
    val opID=UUID.randomUUID().toString()
    val operation=buildJsonObject {put("operationId",opID);put("idempotencyKey",opID);put("entityId",id);put("entityType",c.type);put("operationType",if(after==null)"delete" else if(before==null)"create" else "update");put("baseServerRevision",revision);put("createdAt",System.currentTimeMillis())}
    val delta=if(before!=null && after!=null && c.key in setOf("inventory","grocery")) (quantityValue(after["quantity"]) ?: 0)-(quantityValue(before["quantity"]) ?: 0) else 0
    val quantity=if(delta!=0){val qID=UUID.randomUUID().toString();buildJsonObject{put("operationId",qID);put("idempotencyKey",qID);put("entityId",id);put("entityType",c.type);put("delta",delta);put("baseValue",before!!.getValue("quantity"));put("createdAt",System.currentTimeMillis())}}else null
    val record=if(after!=null && delta!=0)JsonObject(after+("quantity" to before!!.getValue("quantity")))else after
    val deletion=if(after==null)JsonPrimitive(id)else null
    val candidateBytes=bytes(operation)+bytes(quantity)+bytes(record)+bytes(deletion)+16
    if(usedBytes+candidateBytes>MAX_BATCH_BYTES){
     require(operations.isNotEmpty()){"One household record is too large to sync. Export a backup, then remove large images or shorten that record before retrying. Local data has been retained."}
     full=true;return@records
    }
    usedBytes+=candidateBytes;operations+=operation
    quantity?.let{quantities+=it};record?.let{changed+=it};deletion?.let{deleted+=it}
   }
   payload[c.key]=JsonArray(changed);payload[c.deleted]=JsonArray(deleted)
  }
  payload["syncProtocolVersion"]=JsonPrimitive(2);payload["requestId"]=JsonPrimitive(UUID.randomUUID().toString());payload["operations"]=JsonArray(operations);payload["quantityOperations"]=JsonArray(quantities)
  return JsonObject(payload).also{check(bytes(it)<=MAX_BATCH_BYTES){"Household batch exceeds the safe transport limit."}}
 }
 fun sentSnapshot(base:JsonObject,current:JsonObject,batch:JsonObject):JsonObject {
  val result=base.toMutableMap()
  collections.forEach{c->val values=rows(base,c.key).toMutableMap();val local=rows(current,c.key)
   batch.getValue("operations").jsonArray.map{it.jsonObject}.filter{it.getValue("entityType").jsonPrimitive.content==c.type}.forEach{op->val id=op.getValue("entityId").jsonPrimitive.content;if(local[id]==null)values.remove(id)else values[id]=local.getValue(id)}
   result[c.key]=JsonArray(values.values.toList())
  };return JsonObject(result)
 }
 /** Merged document plus the baseline to remember. Locally unchanged rows take the server copy, keeping
  * Android-only fields its writer never sent; the baseline stores that combined row so it is not echoed back. */
 fun reconcile(base:JsonObject,current:JsonObject,remote:JsonObject):Pair<JsonObject,JsonObject> {
  val merged=remote.toMutableMap();val baseline=remote.toMutableMap()
  val baseForm=canonical(base);val localForm=canonical(current);val remoteForm=canonical(remote)
  collections.forEach { c ->
   val before=baseForm.getValue(c.key);val localRows=localForm.getValue(c.key);val local=rows(current,c.key)
   val incoming=rows(remote,c.key);val server=incoming.toMutableMap();val remembered=incoming.toMutableMap()
   val tombstones=(remote[c.deleted] as? JsonArray).orEmpty().map{it.jsonPrimitive.content}.toSet()
   tombstones.forEach{server.remove(it)}
   (before.keys+local.keys).forEach{id->
    if(before[id]!=localRows[id]) {
     if(local[id]==null)server.remove(id) else {
      var edited=local.getValue(id)
      // Keep only the quantity delta made after this baseline (including a pending
      // batch's sent snapshot). A name-only edit must not restore consumed stock.
      if(c.key in setOf("inventory","grocery") && before[id]!=null && server[id]!=null) {
       fun quantity(row:JsonElement?):Long? = (row as? JsonObject)?.get("quantity")?.let { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.isFinite() && it % 1.0 == 0.0 && it in 0.0..100000.0 }?.toLong()
       val oldQuantity=quantity(before[id]);val localQuantity=quantity(localRows[id]);val remoteQuantity=quantity(remoteForm.getValue(c.key)[id])
       if(oldQuantity!=null && localQuantity!=null && remoteQuantity!=null) {
        val rebased=remoteQuantity+(localQuantity-oldQuantity)
        require(rebased in (if(c.key=="grocery")1L else 0L)..100000L) { "Concurrent household quantities conflict with the available stock. Local changes are retained; review ${edited["name"]?.jsonPrimitive?.content ?: id} before syncing." }
        edited=JsonObject(edited+("quantity" to JsonPrimitive(rebased)))
       }
      }
      server[id]=edited
     }
    }
    else if(local[id]!=null)server[id]?.let{row->overlay(c.key,row,local.getValue(id)).also{server[id]=it;remembered[id]=it}}
   }
   merged[c.key]=JsonArray(server.values.toList());baseline[c.key]=JsonArray(remembered.values.toList())
  }
  return JsonObject(merged) to JsonObject(baseline)
 }
 fun merge(base:JsonObject,current:JsonObject,remote:JsonObject):JsonObject=reconcile(base,current,remote).first
}
