package com.sowens.stocked.ui

import com.sowens.stocked.data.KitchenRules
import java.util.UUID

object CookSnapshotRules {
    fun validate(snapshot:CookSnapshot) {
        KitchenRules.validate(snapshot.recipe)
        require(snapshot.servings in 1..1000 && snapshot.stage in 0..2) { "Saved cooking servings or stage is invalid." }
        require(snapshot.status in listOf("active","paused","completed")) { "Saved cooking status is invalid." }
        require(snapshot.currentStep==0 && snapshot.recipe.instructions.isEmpty() || snapshot.currentStep in snapshot.recipe.instructions.indices) { "Saved cooking step is invalid." }
        require(snapshot.checkedIngredients.all { it in snapshot.recipe.ingredients.indices } && snapshot.completedSteps.all { it in snapshot.recipe.instructions.indices }) { "Saved cooking checkoffs are invalid." }
        require(snapshot.notes.length<=100000 && snapshot.startedAt>=0 && snapshot.savedAt>=0) { "Saved cooking notes or timestamps are invalid." }
        require(runCatching { UUID.fromString(snapshot.completionToken).toString()==snapshot.completionToken.lowercase() }.getOrDefault(false)) { "Saved cooking completion token is invalid." }
    }
    fun expected(current:CookSnapshot?,token:String):CookSnapshot {
        check(current!=null && current.completionToken==token) { "The cooking workspace changed. Open the current workspace before making changes." }
        return current
    }
    fun allowStart(current:CookSnapshot?,expectedPreviousToken:String?) {
        if(expectedPreviousToken!=null) expected(current,expectedPreviousToken)
        else check(current==null || current.status=="completed") { "An active cooking workspace needs replacement confirmation." }
    }
}
