package com.sowens.stocked.data

import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

object BackupCodec {
 val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
 private const val MAX_BYTES = 10 * 1024 * 1024
 fun preview(text: String, current: KitchenState, zone:ZoneId=ZoneId.systemDefault()): ImportPreview {
  require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Backup exceeds the 10 MB import limit." }
  val root = json.parseToJsonElement(text).jsonObject
  require(!root.containsKey("sealedPayload")) { "Encrypted .stocked backups require a recovery key. Export readable JSON from iOS instead." }
  val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull ?: 1
  require(version in 1..3) { "This backup uses an unsupported schema version." }
  val warnings = mutableListOf<String>()
  fun <T> rows(primary: String, alternate: String, decode: (JsonObject)->T): List<T> {
   val value = root[primary] ?: root[alternate] ?: return emptyList()
   if (value is JsonNull) return emptyList()
   require(value is JsonArray && value.size <= 10000) { "$primary must contain at most 10,000 records." }
   return value.mapIndexed { index, row ->
    require(row is JsonObject) { "$primary row ${index+1} is invalid." }
    decode(row)
   }
  }
  fun id(obj: JsonObject): JsonObject {
   val supplied = obj["id"]?.jsonPrimitive?.contentOrNull
   if (supplied != null) { require(runCatching { UUID.fromString(supplied) }.isSuccess) { "Record ID is invalid." }; return obj }
   warnings += "A record had no ID; a new stable ID was assigned."
   return JsonObject(obj + ("id" to JsonPrimitive(UUID.randomUUID().toString())))
  }
  fun normalize(obj: JsonObject, date: Boolean=false): JsonObject {
   var result=id(obj)
   if(date) {
    val value=result["expirationDate"]
    if(value is JsonPrimitive && !value.isString) {
     val seconds=value.doubleOrNull ?: error("Invalid expiration date.")
     require(seconds.isFinite() && seconds in -62135596800.0..253402300799.0)
     result=JsonObject(result + ("expirationDate" to JsonPrimitive(Instant.ofEpochSecond((seconds+978307200).toLong()).atZone(zone).toLocalDate().toString())))
    } else if(value is JsonPrimitive && value.isString && value.content.length > 10) {
     result=JsonObject(result + ("expirationDate" to JsonPrimitive(Instant.parse(value.content).atZone(zone).toLocalDate().toString())))
    }
   }
   return result
  }
  inlineExtraCheck(root)
  val inventory=rows("inventory","inventoryItems") { original -> val obj=normalize(original,true); json.decodeFromJsonElement<InventoryItem>(obj).copy(extra=unknown<InventoryItem>(obj)).also(KitchenRules::validate) }
  val grocery=rows("grocery","groceryItems") { original -> val obj=normalize(original); json.decodeFromJsonElement<GroceryItem>(obj).copy(extra=unknown<GroceryItem>(obj)).also(KitchenRules::validate) }
  val recipes=rows("userRecipes","recipes") { original -> val obj=normalize(original); json.decodeFromJsonElement<Recipe>(obj).let { recipe -> recipe.copy(extra=unknown<Recipe>(obj), ingredients=recipe.ingredients.mapIndexed { i, ingredient -> ingredient.copy(extra=unknown<Ingredient>((obj["ingredients"] as? JsonArray)?.get(i)?.jsonObject ?: JsonObject(emptyMap()))) }) }.also(KitchenRules::validate) }
  val meals=rows("planned","plannedMeals") { original -> val obj=normalize(original); json.decodeFromJsonElement<PlannedMeal>(obj).copy(extra=unknown<PlannedMeal>(obj)).also(KitchenRules::validate) }
  fun unique(ids: List<String>) { require(ids.distinct().size == ids.size) { "Backup contains duplicate IDs. Review it before importing." } }
  unique(inventory.map{it.id}); unique(grocery.map{it.id}); unique(recipes.map{it.id}); unique(meals.map{it.id})
  val known=setOf("schemaVersion","inventory","inventoryItems","grocery","groceryItems","userRecipes","recipes","planned","plannedMeals","extra")
  val extras=((root["extra"] as? JsonObject)?.toMap().orEmpty()+root.filterKeys{it !in known}).filterKeys{it !in setOf("_androidHousehold","_androidCookCompletions")}
  val incoming=KitchenState(inventory=inventory,grocery=grocery,userRecipes=recipes,planned=meals,extra=JsonObject(extras))
  val conflicts=mutableListOf<String>()
  inventory.forEach { a->current.inventory.find{it.id==a.id}?.let{if(it!=a)conflicts += "Inventory: ${a.name}"} }
  grocery.forEach { a->current.grocery.find{it.id==a.id}?.let{if(it!=a)conflicts += "Grocery: ${a.name}"} }
  recipes.forEach { a->current.userRecipes.find{it.id==a.id}?.let{if(it!=a)conflicts += "Recipe: ${a.title}"} }
  meals.forEach { a->current.planned.find{it.id==a.id}?.let{if(it!=a)conflicts += "Meal: ${a.title}"} }
  if(extras.isNotEmpty()) warnings += "Additional iOS sections are preserved for export but are not editable in this Android build."
  return ImportPreview(incoming,conflicts,warnings.distinct())
 }
 private fun inlineExtraCheck(root:JsonObject) { require(root.size <= 100) { "Too many backup sections." } }
 private inline fun <reified T> unknown(obj: JsonObject): JsonObject {
  val keys=json.serializersModule.serializer<T>().descriptor.let { d->(0 until d.elementsCount).map{d.getElementName(it)}.toSet() }
  return JsonObject((obj["extra"] as? JsonObject)?.toMap().orEmpty()+obj.filterKeys{it !in keys})
 }
 private inline fun <reified T> wire(value:T):JsonObject {
  val obj=json.encodeToJsonElement(value).jsonObject
  return JsonObject((obj["extra"] as? JsonObject)?.toMap().orEmpty()+obj.filterKeys{it!="extra"})
 }
 private fun backupDate(value:JsonElement?,fallbackMillis:Double):JsonPrimitive {
  val primitive=value as? JsonPrimitive
  val instant=if(primitive?.isString==true)Instant.parse(primitive.content)
   else if(primitive?.doubleOrNull!=null){val seconds=primitive.double+978307200.0;require(seconds.isFinite() && seconds in -62135596800.0..253402300799.0){"Recipe date is outside the supported range."};Instant.ofEpochSecond(seconds.toLong())}
   else {require(fallbackMillis.isFinite()){ "Recipe date is invalid." };Instant.ofEpochMilli(fallbackMillis.toLong())}
  return JsonPrimitive(instant.truncatedTo(ChronoUnit.SECONDS).toString())
 }
 fun export(state:KitchenState,zone:ZoneId=ZoneId.systemDefault()):String {
  val root=state.extra.filterKeys{it !in setOf("_androidHousehold","_androidCookCompletions")}.toMutableMap()
  root["schemaVersion"]=JsonPrimitive(1)
  root["exportedAt"]=JsonPrimitive(Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
  root["inventory"]=JsonArray(state.inventory.map{item->
   val obj=wire(item)
   if(item.expirationDate==null)obj else JsonObject(obj+("expirationDate" to JsonPrimitive(LocalDate.parse(item.expirationDate).atStartOfDay(zone).toInstant().toString())))
  })
  root["grocery"]=JsonArray(state.grocery.map{wire(it)})
  root["userRecipes"]=JsonArray(state.userRecipes.map{recipe ->
   val row=wire(recipe).toMutableMap()
   row.putIfAbsent("difficulty",JsonPrimitive("Medium"));row.putIfAbsent("dishRole",JsonPrimitive("unspecified"))
   row["dateCreated"]=backupDate(row["dateCreated"],recipe.updatedAt)
   row["lastCooked"]?.let{if(it !is JsonNull)row["lastCooked"]=backupDate(it,recipe.updatedAt)}
   row["ingredients"]=JsonArray(recipe.ingredients.map{ingredient->val fields=wire(ingredient).toMutableMap();fields.putIfAbsent("isOptional",JsonPrimitive(false));NutritionWirePolicy.normalize(fields["nutrition"])?.let { fields["nutrition"]=it };JsonObject(fields)})
   JsonObject(row)
  })
  root["planned"]=JsonArray(state.planned.map{meal->val row=wire(meal).toMutableMap();row.putIfAbsent("isBuilding",JsonPrimitive(false));row.putIfAbsent("cookAheadStatus",JsonPrimitive("none"));JsonObject(row)})
  return JsonObject(root).toString()
 }
 fun merge(existing:KitchenState, incoming:KitchenState, replaceExisting:Boolean):KitchenState {
  fun <T> combine(old:List<T>, new:List<T>, id:(T)->String):List<T> {
   val result=old.associateBy(id).toMutableMap()
   new.forEach { if(replaceExisting || !result.containsKey(id(it)))result[id(it)]=it }
   return result.values.toList()
  }
  val extras=(incoming.extra.filterKeys{it !in setOf("_androidHousehold","_androidCookCompletions")}+existing.extra).toMutableMap()
  if(incoming.extra.containsKey("pastMeals")) {
   fun history(value:JsonElement?):List<JsonObject> {
    if(value==null || value is JsonNull)return emptyList()
    require(value is JsonArray && value.size<=10000){"Meal history must contain at most 10,000 records."}
    val rows=value.map{element->require(element is JsonObject){"Meal history record is invalid."};val id=element["id"]?.jsonPrimitive?.contentOrNull;require(id!=null && runCatching{UUID.fromString(id)}.isSuccess){"Meal history ID is invalid."};element}
    require(rows.map{it.getValue("id").jsonPrimitive.content.lowercase()}.distinct().size==rows.size){"Meal history contains duplicate IDs."}
    return rows
   }
   val merged=combine(history(existing.extra["pastMeals"]),history(incoming.extra["pastMeals"])){it.getValue("id").jsonPrimitive.content.lowercase()}
   require(merged.size<=10000){"Merged meal history exceeds 10,000 records. Export and archive history before importing."}
   extras["pastMeals"]=JsonArray(merged)
  }
  return existing.copy(inventory=combine(existing.inventory,incoming.inventory){it.id},grocery=combine(existing.grocery,incoming.grocery){it.id},userRecipes=combine(existing.userRecipes,incoming.userRecipes){it.id},planned=combine(existing.planned,incoming.planned){it.id},extra=JsonObject(extras))
 }
}
