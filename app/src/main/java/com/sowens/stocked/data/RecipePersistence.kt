package com.sowens.stocked.data
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Creation dates become durable once and cannot drift when a recipe is edited. */
object RecipePersistence {
 fun prepare(incoming:Recipe,stored:Recipe?,timestamp:Double):Recipe {
  require(timestamp.isFinite() && timestamp>=0 && timestamp<=253402300799000.0)
  val existingDate=incoming.extra["dateCreated"]?.takeUnless{it is JsonNull} ?: stored?.extra?.get("dateCreated")?.takeUnless{it is JsonNull}
  val created=existingDate ?: JsonPrimitive(Instant.ofEpochMilli((stored?.updatedAt?.takeIf{it.isFinite() && it>0} ?: timestamp).toLong()).truncatedTo(ChronoUnit.SECONDS).toString())
  return incoming.copy(title=incoming.title.trim(),updatedAt=timestamp,extra=JsonObject(incoming.extra+("dateCreated" to created)))
 }
}
