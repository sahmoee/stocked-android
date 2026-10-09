package com.sowens.stocked.data

import java.time.LocalDate
import java.util.Locale
import kotlin.math.*

/** Deterministic local tools; no provider requests and no silent kitchen mutations. */
object KitchenTools {
 data class PantryValue(val knownValue:Double,val pricedRows:Int,val unknownRows:Int,val byZone:Map<String,Double>)
 fun pantryValue(items:List<InventoryItem>):PantryValue {
  val priced=items.filter{it.price!=null && it.price.isFinite() && it.price>=0 && it.quantity>=0 && it.level.isFinite() && it.level in 0.0..1.0 && (it.price*it.quantity*it.level).isFinite() && it.price*it.quantity*it.level<=1e12}
  val zones=priced.groupBy{it.storageCategory}.mapValues{(_,rows)->rows.sumOf{it.price!!*it.quantity*it.level}}
  return PantryValue(zones.values.sum(),priced.size,items.size-priced.size,zones)
 }
 fun expiring(items:List<InventoryItem>,today:LocalDate=LocalDate.now(),days:Long=7):List<InventoryItem> = items.filter { item -> item.quantity>0 && item.expirationDate?.let{runCatching{LocalDate.parse(it)<=today.plusDays(days)}.getOrDefault(false)}==true }.sortedBy{it.expirationDate}
 fun lowFill(items:List<InventoryItem>):List<InventoryItem> = items.filter{it.quantity==0 || it.level<=0.25}.sortedWith(compareBy<InventoryItem>{it.level}.thenBy{it.name})
 fun duplicates(items:List<InventoryItem>):List<List<InventoryItem>> = items.groupBy{listOf(KitchenRules.key(it.name),it.storageCategory,KitchenRules.key(it.containerType),it.sizeAmount?.toString().orEmpty(),KitchenRules.key(it.sizeUnit.orEmpty()))}.values.filter{it.size>1}
 fun snapshot(items:List<InventoryItem>):String = "Stocked pantry snapshot\n"+items.sortedWith(compareBy<InventoryItem>{it.storageCategory}.thenBy{it.name}).joinToString("\n"){"${it.name} · ${it.quantity} ${it.containerType} · ${it.storageCategory}"+(it.expirationDate?.let{date->" · expires $date"} ?: "")}
 private val factors=mapOf("g" to ("mass" to 1.0),"kg" to ("mass" to 1000.0),"oz" to ("mass" to 28.3495),"lb" to ("mass" to 453.592),"ml" to ("volume" to 1.0),"l" to ("volume" to 1000.0),"fl oz" to ("volume" to 29.5735),"cup" to ("volume" to 236.588),"tsp" to ("volume" to 4.92892),"tbsp" to ("volume" to 14.7868),"pt" to ("volume" to 473.176),"qt" to ("volume" to 946.353),"gal" to ("volume" to 3785.41))
 val units=factors.keys.toList()+listOf("°C","°F")
 fun convert(value:Double,from:String,to:String):Double {
  require(value.isFinite()){"Enter a finite amount."}
  if(from in listOf("°C","°F") && to in listOf("°C","°F")) {
   val c=if(from=="°F")(value-32)*5/9 else value
   require(c>=-273.15){"Temperature is below absolute zero."}
   return if(to=="°F")c*9/5+32 else c
  }
  require(value>=0){"Amount cannot be negative."}
  val a=factors[from] ?: error("Choose a known unit.");val b=factors[to] ?: error("Choose a known unit.")
  require(a.first==b.first){"Mass and volume need ingredient-specific density; use matching units."}
  return (value*(a.second/b.second)).also{require(it.isFinite() && abs(it)<=1e12){"Result is too large."}}
 }
 data class Field(val key:String,val label:String,val initial:Double,val zero:Boolean=false,val whole:Boolean=false,val maximum:Double=1e9)
 data class Calculator(val id:String,val title:String,val note:String,val fields:List<Field>)
 data class Result(val summary:String,val rows:List<Pair<String,Double>>)
 private fun f(key:String,label:String,value:Double,zero:Boolean=false,whole:Boolean=false,maximum:Double=1e9)=Field(key,label,value,zero,whole,maximum)
 val calculators=listOf(
  Calculator("unitPrice","Compare unit prices","Use the same currency and quantity unit for both packages.",listOf(f("aPrice","Package A price",4.5,true),f("aSize","Package A size",500.0),f("bPrice","Package B price",6.0,true),f("bSize","Package B size",750.0))),
  Calculator("packages","Buy enough packages","Matching quantity units. A zero price means cost is unknown.",listOf(f("need","Amount needed",1200.0),f("size","Amount per package",500.0),f("price","Price per package",3.5,true))),
  Calculator("pans","Scale rectangular pans","Use inside dimensions and intended batter depth in the same unit. Baking time is not inferred.",listOf(f("aLength","Original length",9.0),f("aWidth","Original width",9.0),f("aDepth","Original batter depth",2.0),f("bLength","New length",10.0),f("bWidth","New width",10.0),f("bDepth","New batter depth",2.0))),
  Calculator("baker","Baker’s percentages","Weights in grams, including starter flour and water. Flour is 100%.",listOf(f("flour","Total flour (g)",500.0),f("water","Water (g)",325.0,true),f("salt","Salt (g)",10.0,true),f("yeast","Yeast (g)",5.0,true),f("fat","Fat (g)",20.0,true),f("sugar","Sugar (g)",15.0,true))),
  Calculator("hydration","Adjust dough hydration","Adds water or flour; never removes mixed dough. Include starter weight.",listOf(f("flour","Current flour (g)",500.0),f("water","Current water (g)",300.0,true),f("target","Target hydration (%)",70.0,maximum=300.0))),
  Calculator("ratio","Scale an ingredient ratio","Use matching units; no cooking time or preservation safety is inferred.",listOf(f("base","Original base ingredient",300.0),f("other","Original second ingredient",200.0,true),f("newBase","New base ingredient",450.0))),
  Calculator("yield","Allow for trimming","Your estimated loss; buying amount rounds up to the next gram.",listOf(f("need","Usable amount needed (g)",800.0),f("loss","Estimated trim loss (%)",20.0,true,maximum=99.9))),
  Calculator("portions","Pack portions","Use finished cooked weight. A smaller remainder gets its own container.",listOf(f("batch","Cooked batch (g)",1800.0),f("portion","Weight per serving (g)",250.0),f("perBox","Servings per container",2.0,whole=true))),
  Calculator("batches","Plan batch timing","Includes round turnaround; preparation and preheating are separate.",listOf(f("items","Items to make",40.0,whole=true),f("capacity","Items per batch",12.0,whole=true),f("parallel","Batches at once",2.0,whole=true),f("minutes","Minutes per round",18.0),f("turnaround","Minutes between rounds",3.0,true))),
  Calculator("offers","Check a shopping offer","Percentage discount, fixed coupon, then tax. Store rules may differ.",listOf(f("price","Shelf total before tax",45.0,true),f("discount","Discount (%)",20.0,true,maximum=100.0),f("coupon","Coupon after discount",5.0,true),f("tax","Tax (%)",8.25,true,maximum=100.0)))
 )
 private fun up(value:Double):Double {val nearest=round(value);return if(abs(value-nearest)<=1e-10*max(1.0,abs(value)))nearest else ceil(value)}
 fun calculate(id:String,input:Map<String,Double>):Result {
  val tool=calculators.single{it.id==id}
  tool.fields.forEach { field -> val value=input[field.key] ?: error("Enter ${field.label}.");require(value.isFinite() && value<=field.maximum && (if(field.zero)value>=0 else value>0) && (!field.whole||value==floor(value))){"${field.label} needs a ${if(field.whole)"whole " else ""}${if(field.zero)"nonnegative" else "positive"} number up to ${field.maximum}."} }
  fun n(key:String)=input.getValue(key)
  val result=when(id) {
   "unitPrice"->{val a=n("aPrice")/n("aSize");val b=n("bPrice")/n("bSize");Result(if(abs(a-b)<1e-12)"Unit prices match." else "Package ${if(a<b)"A" else "B"} costs less per unit.",listOf("A price per unit" to a,"B price per unit" to b))}
   "packages"->{val packs=up(n("need")/n("size"));Result("Buy ${format(packs)} whole packages.",listOf("Packages" to packs,"Amount left over" to packs*n("size")-n("need"))+if(n("price")>0)listOf("Estimated cost" to packs*n("price"))else emptyList())}
   "pans"->{val factor=n("bLength")*n("bWidth")*n("bDepth")/(n("aLength")*n("aWidth")*n("aDepth"));Result("Multiply ingredient amounts by ${format(factor)}.",listOf("Recipe multiplier" to factor))}
   "baker"->Result("Flour is 100%.",listOf("Flour (%)" to 100.0)+listOf("water","salt","yeast","fat","sugar").map{it.replaceFirstChar(Char::uppercase)+" (%)" to n(it)/n("flour")*100}+listOf("Total dough (g)" to tool.fields.sumOf{n(it.key)}))
   "hydration"->{val water=max(0.0,n("flour")*n("target")/100-n("water"));val flour=max(0.0,n("water")*100/n("target")-n("flour"));Result("Add water for a higher target, or flour for a lower target.",listOf("Current hydration (%)" to n("water")/n("flour")*100,"Water to add (g)" to water,"Flour to add (g)" to flour))}
   "ratio"->Result("Preserve the ingredient proportion.",listOf("New second ingredient" to n("other")*n("newBase")/n("base"),"Multiplier" to n("newBase")/n("base")))
   "yield"->{val buy=up(n("need")/(1-n("loss")/100));Result("Buy approximately ${format(buy)} g before trimming.",listOf("Buying weight (g)" to buy,"Estimated trim (g)" to buy-n("need")))}
   "portions"->{val full=floor(n("batch")/n("portion"));val rem=(n("batch")-full*n("portion")).coerceAtLeast(0.0);Result("Pack full servings first.",listOf("Full servings" to full,"Smaller remainder (g)" to rem,"Containers" to ceil(full/n("perBox"))+(if(rem>1e-9)1 else 0)))}
   "batches"->{val batches=ceil(n("items")/n("capacity"));val rounds=ceil(batches/n("parallel"));Result("Planning estimate; follow recipe doneness guidance.",listOf("Batches" to batches,"Rounds" to rounds,"Elapsed minutes" to rounds*n("minutes")+max(0.0,rounds-1)*n("turnaround")))}
   else->{val sub=max(0.0,n("price")*(1-n("discount")/100)-n("coupon"));Result("Estimated checkout total.",listOf("Discounted subtotal" to sub,"Tax" to sub*n("tax")/100,"Total" to sub*(1+n("tax")/100)))}
  }
  require(result.rows.all{it.second.isFinite() && abs(it.second)<=1e12}){"Result is too large."};return result
 }
 fun format(value:Double):String=String.format(Locale.ROOT,"%.4f",value).trimEnd('0').trimEnd('.')
}
