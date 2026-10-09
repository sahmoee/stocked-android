package com.sowens.stocked.data

/** Prepared under the repository storage mutex; no stale UI snapshot can resurrect a deleted row. */
object InventoryCommitPolicy {
 fun apply(state:KitchenState,draft:InventoryItem,baseline:InventoryItem?,timestamp:Double):KitchenState {
  require(timestamp.isFinite() && timestamp>=0 && timestamp<=253402300799000.0){"Inventory edit date is invalid."}
  val live=state.inventory.find{it.id==draft.id}
  if(baseline==null) {
   require(live==null){"This inventory item already exists. Reopen it to edit; no changes were saved."}
   val created=draft.copy(name=draft.name.trim(),updatedAt=timestamp).also(KitchenRules::validate)
   return state.copy(inventory=state.inventory+created)
  }
  require(baseline.id==draft.id){"Inventory edit identity changed. Reopen the item."}
  require(live!=null){"This inventory item was deleted while you were editing. It has not been recreated."}
  val merged=InventoryEditPolicy.merge(baseline,live,draft).let{it.copy(name=it.name.trim())}.also(KitchenRules::validate)
  if(merged==live)return state
  val committed=merged.copy(updatedAt=timestamp)
  return state.copy(inventory=state.inventory.map{if(it.id==draft.id)committed else it})
 }
}
