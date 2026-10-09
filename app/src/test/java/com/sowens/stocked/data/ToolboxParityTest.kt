package com.sowens.stocked.data

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class ToolboxParityTest {
 @Test fun wholeWordMatchingNeverConfusesHamAndGraham() {
  assertFalse(ToolboxParity.mentions("Graham crackers","ham"))
  assertTrue(ToolboxParity.mentions("Black beans","bean"))
  assertTrue(ToolboxParity.mentions("Peanut butter","peanut butter"))
 }
 @Test fun regionalTranslationIsWholeWordLongestFirstAndSinglePass() {
  assertEquals("Eggplant and Heavy cream",ToolboxParity.translate("Aubergine and double cream",toUS=true))
  assertEquals("Graham cracker base",ToolboxParity.translate("Digestive biscuit base",toUS=true))
  assertEquals("Grilled cheese",ToolboxParity.translate("Grilled cheese",toUS=true))
  assertEquals("Courgette",ToolboxParity.translate("Zucchini",toUS=false))
  assertEquals("Cookie",ToolboxParity.translate(ToolboxParity.translate("Cookie",false),true))
 }
 @Test fun ovenTableFindsNearestStandardStep() {
  assertEquals(180,ToolboxParity.nearestOven(356).celsius)
  assertEquals("9",ToolboxParity.nearestOven(1000).gasMark)
 }
 @Test fun thawUsesExactWeightUnitsAndConservativeMinimums() {
  assertNull(ToolboxParity.pounds(InventoryItem(name="Stock",sizeAmount=1.0,sizeUnit="gal")))
  assertNull(ToolboxParity.pounds(InventoryItem(name="Roast")))
  assertEquals(2.0,ToolboxParity.pounds(InventoryItem(name="Roast",quantity=3,sizeAmount=32.0,sizeUnit="oz"))!!,1e-9)
  assertEquals(4.0,ToolboxParity.thawHours(0.1,ToolboxParity.ThawMethod.FRIDGE),1e-9)
  assertEquals(10.0,ToolboxParity.thawHours(2.0,ToolboxParity.ThawMethod.FRIDGE),1e-9)
  assertEquals(LocalDateTime.of(2026,10,9,8,0),ToolboxParity.takeOutAt(LocalDateTime.of(2026,10,9,18,0),10.0))
  assertTrue(runCatching{ToolboxParity.thawHours(Double.NaN,ToolboxParity.ThawMethod.MICROWAVE)}.isFailure)
 }
 @Test fun costSplitSharesSumExactlyAndSettleDeterministically() {
  val people=listOf("Ana","Ben","Cy")
  val bill=ToolboxParity.Expense("Groceries",ToolboxParity.parseCents("100.00"),"Ana")
  assertEquals(10000L,ToolboxParity.shares(bill,people).values.sum())
  val balances=ToolboxParity.balances(listOf(bill),people)
  assertEquals(0L,balances.values.sum())
  val settle=ToolboxParity.settlements(listOf(bill),people)
  assertEquals(10000L-ToolboxParity.shares(bill,people).getValue("Ana"),settle.sumOf{it.amountCents})
  assertTrue(settle.all{it.to=="Ana"})
  assertTrue(runCatching{ToolboxParity.parseCents("1.005")}.isFailure)
  assertTrue(runCatching{ToolboxParity.parseCents("-3")}.isFailure)
  assertEquals("-0.05",ToolboxParity.money(-5))
 }
 @Test fun readinessListsUnknownFoodsInsteadOfInventingCalories() {
  val items=listOf(InventoryItem(name="Rice",quantity=2,storageCategory="Pantry"),InventoryItem(name="Mystery tin",storageCategory="Pantry"),InventoryItem(name="Water",quantity=4,sizeAmount=1.0,sizeUnit="gal",storageCategory="Staples"),InventoryItem(name="Milk",storageCategory="Fridge"))
  val result=ToolboxParity.readiness(items,2,LocalDate.of(2026,10,9))
  assertEquals(3200.0,result.knownCalories,1e-9)
  assertEquals(listOf("Mystery tin"),result.unknownFoods)
  assertEquals(4*3.78541,result.waterLiters,1e-6)
  assertEquals(3200.0/3600.0,result.days,1e-9)
 }
 @Test fun rouletteHonoursFiltersAndAllergens() {
  val pasta=Recipe(title="Pasta",cuisine="Italian",ingredients=listOf(Ingredient(name="Spaghetti pasta")))
  val curry=Recipe(title="Curry",cuisine="Thai",isFavorited=true,ingredients=listOf(Ingredient(name="Coconut milk")))
  val stew=Recipe(title="Stew",cuisine="Italian",isFavorited=true,ingredients=listOf(Ingredient(name="Beans")))
  val candidates=ToolboxParity.rouletteCandidates(listOf(pasta,curry,stew),null,false,setOf("Gluten"))
  assertEquals(listOf(curry,stew),candidates)
  assertEquals(listOf(stew),ToolboxParity.rouletteCandidates(listOf(pasta,curry,stew),"italian",true,emptySet()))
  assertNull(ToolboxParity.spin(emptyList()))
  assertEquals(stew,ToolboxParity.spin(candidates,Random(1),avoidId=curry.id))
 }
 @Test fun allergenReviewMatchesKnownAndCustomTerms() {
  val recipe=Recipe(title="Pesto",ingredients=listOf(Ingredient(name="Pine nuts"),Ingredient(name="Parmesan cheese"),Ingredient(name="Basil")))
  val hits=ToolboxParity.allergenMatches(recipe,setOf("Tree nuts","Dairy","Basil"))
  assertEquals(setOf("Tree nuts","Dairy","Basil"),hits.map{it.first}.toSet())
  assertTrue(ToolboxParity.allergenMatches(Recipe(title="Toast",ingredients=listOf(Ingredient(name="Graham crackers"))),setOf("ham")).isEmpty())
 }
 @Test fun referenceSearchSeasonalAndSubstitutions() {
  assertEquals("Eggs",ToolboxParity.searchReference(ToolboxParity.shelfLife,"egg"){it.food}.single().food)
  assertEquals(ToolboxParity.storageTips.size,ToolboxParity.searchReference(ToolboxParity.storageTips,"  "){it.food}.size)
  val october=ToolboxParity.seasonalProduce(10,listOf(InventoryItem(name="Honeycrisp apple"),InventoryItem(name="Pear",quantity=0)))
  assertTrue(october.first{it.first=="Apples"}.second); assertFalse(october.first{it.first=="Pears"}.second)
  assertEquals("¾ the amount",ToolboxParity.substitutesFor("unsalted butter").first().ratio)
  assertEquals("white sugar + 1 tbsp molasses",ToolboxParity.substitutesFor("Brown sugar").single().substitute)
  assertTrue(ToolboxParity.substitutesFor("ham").isEmpty())
 }
 @Test fun savedBillsKeepOriginalParticipantsWhenPeopleDraftChanges() {
  val bill=ToolboxParity.recordedExpense("Lunch",1000,"Ana",listOf("Ana","Ben"))
  val balances=ToolboxParity.balances(listOf(bill),listOf("Ana","Cy"))
  assertEquals(-500L,balances.getValue("Ben")); assertEquals(0L,balances.getValue("Cy"))
  assertTrue(runCatching{ToolboxParity.recordedExpense("Lunch",1000,"Ana",listOf("Ana","Ben\u001Eextra"))}.isFailure)
 }
 @Test fun readinessWaterUsesRemainingFillAndRejectsUnknownFill() {
  fun water(fill:Double)=InventoryItem(name="Water",quantity=2,sizeAmount=1.0,sizeUnit="l",level=fill)
  assertEquals(0.5,ToolboxParity.readiness(listOf(water(0.25)),1).waterLiters,1e-9)
  assertEquals(0.0,ToolboxParity.readiness(listOf(water(0.0)),1).waterLiters,1e-9)
  assertEquals(0.0,ToolboxParity.readiness(listOf(water(Double.NaN)),1).waterLiters,1e-9)
 }

}
