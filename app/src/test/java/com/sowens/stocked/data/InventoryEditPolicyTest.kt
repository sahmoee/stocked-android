package com.sowens.stocked.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InventoryEditPolicyTest {
    @Test fun editsKeepConcurrentFieldsAndUnknownMetadata() {
        val original = InventoryItem(id="one", name="Milk", quantity=1, extra=JsonObject(mapOf("subZone" to JsonPrimitive("Door"))))
        val live = original.copy(quantity=3, extra=JsonObject(mapOf("subZone" to JsonPrimitive("Shelf"), "futureField" to JsonPrimitive(7))))
        val draft = original.copy(name="Oat milk", extra=JsonObject(original.extra + ("customCategory" to JsonPrimitive("Drinks"))))
        val result = InventoryEditPolicy.merge(original, live, draft)
        assertEquals("Oat milk", result.name)
        assertEquals(3, result.quantity)
        assertEquals("Shelf", result.extra["subZone"]?.jsonPrimitive?.content)
        assertEquals(7, result.extra["futureField"]?.jsonPrimitive?.int)
        assertEquals("Drinks", result.extra["customCategory"]?.jsonPrimitive?.content)
    }
    @Test fun explicitMetadataRemovalWinsWithoutDeletingOtherFields() {
        val original = InventoryItem(id="one",name="Rice",extra=JsonObject(mapOf("parQuantity" to JsonPrimitive(2))))
        val live = original.copy(extra=JsonObject(original.extra + ("nutrition" to JsonObject(emptyMap()))))
        val result = InventoryEditPolicy.merge(original,live,original.copy(extra=JsonObject(emptyMap())))
        assertFalse(result.extra.containsKey("parQuantity"))
        assertTrue(result.extra.containsKey("nutrition"))
    }
}
