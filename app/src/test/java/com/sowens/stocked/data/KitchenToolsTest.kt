package com.sowens.stocked.data
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate
class KitchenToolsTest {
 private fun calculate(id:String,vararg values:Pair<String,Double>)=KitchenTools.calculate(id,KitchenTools.calculators.single{it.id==id}.fields.associate{it.key to it.initial}+values.toMap())
 @Test fun unitFamiliesAndTemperatures(){assertEquals(236.588,KitchenTools.convert(1.0,"cup","ml"),0.001);assertEquals(100.0,KitchenTools.convert(212.0,"°F","°C"),0.001);assertTrue(runCatching{KitchenTools.convert(1.0,"cup","g")}.isFailure);assertTrue(runCatching{KitchenTools.convert(-274.0,"°C","°F")}.isFailure)}
 @Test fun invalidNumericInputsRejected(){assertTrue(runCatching{KitchenTools.convert(Double.NaN,"g","kg")}.isFailure);assertTrue(runCatching{calculate("packages","size" to 0.0)}.isFailure);assertTrue(runCatching{calculate("batches","items" to 1.5)}.isFailure)}
 @Test fun packageRoundingDoesNotDropPartialPack(){assertEquals(3.0,calculate("packages").rows.first().second,0.0);assertEquals(2.0,calculate("packages","need" to 0.3,"size" to 0.15).rows.first().second,0.0)}
 @Test fun pantryUnknownsRemainUnknown(){val summary=KitchenTools.pantryValue(listOf(InventoryItem(name="Milk",quantity=2,price=3.0,level=0.5),InventoryItem(name="Unknown")));assertEquals(3.0,summary.knownValue,0.0);assertEquals(1,summary.unknownRows)}
 @Test fun expiryAndDuplicatesConservative(){val a=InventoryItem(name="Milk",expirationDate="2026-10-09");val b=a.copy(id="another",name=" MILK ");val c=a.copy(id="third",storageCategory="Fridge");assertEquals(1,KitchenTools.duplicates(listOf(a,b,c)).size);assertEquals(1,KitchenTools.expiring(listOf(a,InventoryItem(name="Unknown")),LocalDate.parse("2026-10-08")).size)}
 @Test fun calculatorsHaveExpectedDefaultResults(){assertEquals(1.2345679,calculate("pans").rows.first().second,0.000001);assertEquals(65.0,calculate("baker").rows[1].second,0.0);assertEquals(50.0,calculate("hydration").rows.toMap().getValue("Water to add (g)"),0.0);assertEquals(300.0,calculate("ratio").rows.first().second,0.0);assertEquals(1000.0,calculate("yield").rows.first().second,0.0);assertEquals(39.0,calculate("batches").rows.last().second,0.0);assertEquals(33.5575,calculate("offers").rows.last().second,0.00001)}
 @Test fun remainderHasOwnContainer(){val result=calculate("portions");assertEquals(7.0,result.rows[0].second,0.0);assertEquals(50.0,result.rows[1].second,0.0);assertEquals(5.0,result.rows[2].second,0.0)}
 @Test fun couponCannotMakeNegativePrice(){assertEquals(0.0,calculate("offers","coupon" to 999.0).rows.last().second,0.0)}
 @Test fun snapshotExcludesPrivateProvenance(){val snapshot=KitchenTools.snapshot(listOf(InventoryItem(name="Milk",brand="Private metadata",barcode="12345")));assertTrue(snapshot.contains("Milk"));assertFalse(snapshot.contains("Private metadata"));assertFalse(snapshot.contains("12345"))}
}
