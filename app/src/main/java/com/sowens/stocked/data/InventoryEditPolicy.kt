package com.sowens.stocked.data

import kotlinx.serialization.json.JsonObject

/** Keep concurrent changes to fields the open editor did not change. */
object InventoryEditPolicy {
    fun merge(initial: InventoryItem, live: InventoryItem, draft: InventoryItem): InventoryItem {
        require(initial.id == live.id && live.id == draft.id)
        fun <T> field(before: T, current: T, edited: T): T = if (before == edited) current else edited
        val extras = live.extra.toMutableMap()
        (initial.extra.keys + draft.extra.keys).forEach { key ->
            if (initial.extra[key] != draft.extra[key]) {
                draft.extra[key]?.let { extras[key] = it } ?: extras.remove(key)
            }
        }
        return live.copy(
            name=field(initial.name,live.name,draft.name), quantity=field(initial.quantity,live.quantity,draft.quantity),
            containerType=field(initial.containerType,live.containerType,draft.containerType),
            storageCategory=field(initial.storageCategory,live.storageCategory,draft.storageCategory),
            expirationDate=field(initial.expirationDate,live.expirationDate,draft.expirationDate),
            brand=field(initial.brand,live.brand,draft.brand), sizeAmount=field(initial.sizeAmount,live.sizeAmount,draft.sizeAmount),
            sizeUnit=field(initial.sizeUnit,live.sizeUnit,draft.sizeUnit), price=field(initial.price,live.price,draft.price),
            barcode=field(initial.barcode,live.barcode,draft.barcode), level=field(initial.level,live.level,draft.level),
            extra=JsonObject(extras)
        ).also(KitchenRules::validate)
    }
}
