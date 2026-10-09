package com.sowens.stocked.data

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.random.Random

/**
 * Offline ports of Stocked iOS Kitchen Toolbox logic (RegionalFoodData, ThawPlanner, CostSplitting,
 * EmergencyPantry, ToolboxCooking roulette, DietaryProfile, ToolboxReference, IngredientIntel).
 * Matching is whole-word so "ham" never matches "graham"; unknown values stay unknown.
 */
object ToolboxParity {
 private fun words(text:String)=KitchenRules.key(text).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()
 /** Whole-word phrase match tolerant of simple plurals ("bean" matches "black beans"). */
 fun mentions(text:String,phrase:String):Boolean {
  val p=words(phrase); if(p.isEmpty())return false
  return Regex("(^| )${Regex.escape(p)}(s|es)?( |$)").containsMatchIn(words(text))
 }

 // Regional vocabulary and oven temperatures (iOS RegionalFoodData.swift)
 data class Alias(val us:String,val uk:String,val note:String="")
 val aliases=listOf(
  Alias("Cilantro","Coriander (fresh)","UK 'coriander' alone usually means the seed — check whether the recipe wants leaves or ground."),
  Alias("Eggplant","Aubergine"),Alias("Zucchini","Courgette"),Alias("Arugula","Rocket"),
  Alias("All-purpose flour","Plain flour","UK plain flour is slightly lower in protein; for bread use strong/bread flour."),
  Alias("Heavy cream","Double cream","Double cream is richer (~48% vs 36%). Whips faster — don't over-beat."),
  Alias("Half and half","Single cream","Not identical; single cream is a bit richer."),
  Alias("Powdered sugar","Icing sugar"),Alias("Superfine sugar","Caster sugar"),
  Alias("Molasses","Black treacle","Treacle is slightly more bitter."),
  Alias("Corn starch","Cornflour","UK 'cornflour' is starch, NOT the US cornmeal-style flour."),
  Alias("Scallion","Spring onion"),Alias("Bell pepper","Capsicum","Australia uses capsicum."),
  Alias("Ground beef","Beef mince"),Alias("Shrimp","Prawns","Technically different animals; cook the same."),
  Alias("Beet","Beetroot"),Alias("Snow pea","Mangetout"),Alias("Baking soda","Bicarbonate of soda"),
  Alias("Broil","Grill","UK 'grill' = US broiler. UK 'barbecue' = US grill. This one causes real accidents."),
  Alias("Skillet","Frying pan"),Alias("Cookie","Biscuit","And a US biscuit is closest to a UK scone."),
  Alias("Oatmeal","Porridge oats"),Alias("Canned","Tinned"),Alias("Confectioners glaze","Water icing"),
  Alias("Graham cracker","Digestive biscuit","The standard swap for cheesecake bases."),
  Alias("Jelly","Jam","UK 'jelly' is US 'Jell-O'."),Alias("Rutabaga","Swede"),
  Alias("Cotton candy","Candy floss"),Alias("Baking sheet","Baking tray")
 )
 fun searchAliases(query:String):List<Alias> { val q=query.trim().lowercase(Locale.ROOT); return if(q.isEmpty())aliases else aliases.filter{it.us.lowercase(Locale.ROOT).contains(q)||it.uk.lowercase(Locale.ROOT).contains(q)} }
 /** Single-pass, whole-word, longest-first translation so replacements are never re-translated. */
 fun translate(line:String,toUS:Boolean):String {
  val pairs=aliases.map{if(toUS)it.uk to it.us else it.us to it.uk}.sortedByDescending{it.first.length}
  val pattern=Regex("(?i)(?<![\\p{L}\\p{N}])("+pairs.joinToString("|"){Regex.escape(it.first)}+")(?![\\p{L}\\p{N}])")
  return pattern.replace(line){match->pairs.first{it.first.equals(match.value,ignoreCase=true)}.second}
 }
 data class OvenTemp(val fahrenheit:Int,val celsius:Int,val fanCelsius:Int,val gasMark:String,val description:String)
 val ovenTemps=listOf(OvenTemp(275,140,120,"1","Very slow"),OvenTemp(300,150,130,"2","Slow"),OvenTemp(325,165,145,"3","Moderately slow"),OvenTemp(350,180,160,"4","Moderate — most baking"),OvenTemp(375,190,170,"5","Moderately hot"),OvenTemp(400,200,180,"6","Hot — roasting"),OvenTemp(425,220,200,"7","Hot"),OvenTemp(450,230,210,"8","Very hot"),OvenTemp(475,245,225,"9","Very hot — pizza"))
 fun nearestOven(fahrenheit:Int):OvenTemp=ovenTemps.minBy{abs(it.fahrenheit-fahrenheit)}

 // Thaw planning (iOS ThawPlanner.swift); weight comes only from exact units.
 enum class ThawMethod(val label:String,val guidance:String) {
  FRIDGE("Fridge","Safest. Plan ahead — roughly 5 hours per pound."),
  COLD_WATER("Cold water","Faster, but change the water every 30 min and cook immediately."),
  MICROWAVE("Microwave","Fastest, but cook it right away — edges begin to cook.")
 }
 fun thawHours(pounds:Double,method:ThawMethod):Double {
  require(pounds.isFinite() && pounds>0 && pounds<=500){"Enter a weight between 0 and 500 lb."}
  val lb=max(0.25,pounds)
  return when(method){ThawMethod.FRIDGE->max(4.0,lb*5);ThawMethod.COLD_WATER->max(0.5,lb*0.5);ThawMethod.MICROWAVE->max(0.1,lb*0.1)}
 }
 /** Weight of one package; null when the recorded size has no weight unit, so the UI asks instead of guessing. */
 fun pounds(item:InventoryItem):Double? {
  val amount=item.sizeAmount?.takeIf{it.isFinite() && it>0} ?: return null
  val each=when(item.sizeUnit?.trim()?.lowercase(Locale.ROOT)) { "lb","lbs","pound","pounds"->amount;"oz","ounce","ounces"->amount/16;"kg","kilogram","kilograms"->amount*2.20462;"g","gram","grams"->amount/453.592;else->return null }
 return each
 }
 fun takeOutAt(readyBy:LocalDateTime,hours:Double):LocalDateTime=readyBy.minusMinutes(ceil(hours*60).toLong())
 fun readableHours(hours:Double):String=when{hours<1->"${ceil(hours*60).toInt()} min";hours<24->"${KitchenTools.format(Math.round(hours*10)/10.0)} hr";else->"${KitchenTools.format(Math.round(hours/2.4)/10.0)} days"}
 fun frozen(items:List<InventoryItem>)=items.filter{it.storageCategory=="Freezer" && it.quantity>0}.sortedBy{KitchenRules.key(it.name)}

 // Cost splitting (iOS CostSplitting.swift) in exact cents: shares always sum to the bill.
 data class Expense(val label:String,val amountCents:Long,val paidBy:String,val sharedWith:List<String> = emptyList())
 data class Settlement(val from:String,val to:String,val amountCents:Long)
 /** Freeze a bill's participants; editing the people draft must not redistribute saved bills. */
 fun recordedExpense(label:String,amountCents:Long,paidBy:String,people:List<String>):Expense {
  val names=people.map{it.trim()}.filter{it.isNotEmpty()}.distinct()
  require(names.size in 2..20 && paidBy in names){"Choose a payer from 2–20 people."}
  require(names.none{n->n.any{it.isISOControl()}}){"Names cannot contain control characters."}
  require(amountCents in 1..100000000){"Enter a positive amount up to 1,000,000."}
  return Expense(label,amountCents,paidBy,names)
 }
 fun parseCents(text:String):Long {
  val value=text.trim().toBigDecimalOrNull() ?: error("Enter an amount such as 12.50.")
  require(value.signum()>0 && value.scale()<=2 && value<=java.math.BigDecimal("1000000")){"Amount must be positive, at most 1,000,000 and use whole cents."}
  return value.movePointRight(2).longValueExact()
 }
 fun shares(expense:Expense,people:List<String>):Map<String,Long> {
  val participants=(expense.sharedWith.ifEmpty{people}.ifEmpty{listOf(expense.paidBy)}).distinct().sorted()
  val base=expense.amountCents/participants.size;val extra=expense.amountCents%participants.size
  return participants.mapIndexed{index,name->name to base+if(index<extra)1 else 0}.toMap()
 }
 fun balances(expenses:List<Expense>,people:List<String>):Map<String,Long> {
  val net=people.associateWith{0L}.toMutableMap()
  expenses.forEach{e->net[e.paidBy]=(net[e.paidBy] ?: 0)+e.amountCents;shares(e,people).forEach{(name,share)->net[name]=(net[name] ?: 0)-share}}
  return net
 }
 fun settlements(expenses:List<Expense>,people:List<String>):List<Settlement> {
  val net=balances(expenses,people).toMutableMap();val out=mutableListOf<Settlement>()
  while(true) {
   val debtor=net.entries.filter{it.value<0}.minWithOrNull(compareBy<Map.Entry<String,Long>>{it.value}.thenBy{it.key}) ?: break
   val creditor=net.entries.filter{it.value>0}.maxWithOrNull(compareBy<Map.Entry<String,Long>>{it.value}.thenByDescending{it.key}) ?: break
   val amount=minOf(-debtor.value,creditor.value);val d=debtor.key;val c=creditor.key
   out+=Settlement(d,c,amount);net[d]=net.getValue(d)+amount;net[c]=net.getValue(c)-amount
  }
  return out
 }
 fun money(cents:Long):String=String.format(Locale.ROOT,"%s%d.%02d",if(cents<0)"-" else "",abs(cents)/100,abs(cents)%100)

 // Emergency readiness (iOS EmergencyPantry.swift); unmatched foods are listed, never invented.
 const val CALORIES_PER_PERSON_DAY=1800.0
 const val LITERS_PER_PERSON_DAY=3.8
 private val calorieTable=listOf("peanut butter" to 2800.0,"powdered milk" to 1500.0,"rice" to 1600.0,"bean" to 1200.0,"lentil" to 1300.0,"pasta" to 1600.0,"flour" to 1600.0,"oat" to 1500.0,"cereal" to 1100.0,"sugar" to 1900.0,"oil" to 4000.0,"nut" to 2600.0,"soup" to 300.0,"tuna" to 200.0,"chicken" to 400.0,"bar" to 250.0,"cracker" to 500.0,"jerky" to 400.0,"honey" to 1300.0,"tortilla" to 900.0,"bread" to 1200.0,"corn" to 400.0,"tomato" to 200.0,"sauce" to 300.0,"canned" to 400.0)
 data class Readiness(val people:Int,val knownCalories:Double,val waterLiters:Double,val daysOfFood:Double,val daysOfWater:Double,val unknownFoods:List<String>,val expiringSoon:List<String>) { val days get()=minOf(daysOfFood,daysOfWater) }
 private fun liters(item:InventoryItem):Double? {
  val amount=item.sizeAmount?.takeIf{it.isFinite() && it>0} ?: return null
  val each=when(item.sizeUnit?.trim()?.lowercase(Locale.ROOT)){"l","liter","liters","litre","litres"->amount;"ml"->amount/1000;"gal","gallon","gallons"->amount*3.78541;"fl oz"->amount*0.0295735;else->return null}
  val remaining=item.level.takeIf { it.isFinite() }?.coerceIn(0.0,1.0) ?: 0.0
  return each*max(0,item.quantity)*remaining
 }
 fun readiness(items:List<InventoryItem>,people:Int,today:java.time.LocalDate=java.time.LocalDate.now()):Readiness {
  require(people in 1..50){"Household size must be between 1 and 50."}
  val stable=items.filter{(it.storageCategory=="Pantry"||it.storageCategory=="Staples") && it.quantity>0}
  val water=stable.filter{mentions(it.name,"water")}
  val food=stable-water.toSet()
  var calories=0.0;val unknown=mutableListOf<String>()
  food.forEach{item->val hit=calorieTable.firstOrNull{mentions(item.name,it.first)};if(hit==null)unknown+=item.name else calories+=hit.second*item.quantity*item.level.coerceIn(0.0,1.0)}
  val waterL=water.sumOf{liters(it) ?: 0.0};water.filter{liters(it)==null}.forEach{unknown+=it.name+" (size unknown)"}
  return Readiness(people,calories,waterL,calories/(CALORIES_PER_PERSON_DAY*people),waterL/(LITERS_PER_PERSON_DAY*people),unknown,KitchenTools.expiring(stable,today,30).map{it.name})
 }

 // Recipe roulette (iOS ToolboxCooking.swift) honouring the saved allergen profile.
 fun rouletteCandidates(recipes:List<Recipe>,cuisine:String?,favoritesOnly:Boolean,avoid:Set<String>):List<Recipe> = recipes.filter{r->(cuisine==null||r.cuisine.trim().equals(cuisine,true)) && (!favoritesOnly||r.isFavorited) && allergenMatches(r,avoid).isEmpty()}
 fun spin(candidates:List<Recipe>,random:Random=Random.Default,avoidId:String?=null):Recipe? {
  if(candidates.isEmpty())return null
  val pool=candidates.filter{it.id!=avoidId}.ifEmpty{candidates}
  return pool[random.nextInt(pool.size)]
 }

 // Dietary profile and allergen review (iOS DietaryProfileView.swift); a review aid, not a label.
 val diets=listOf("Omnivore","Vegetarian","Vegan","Pescatarian","Gluten-free","Keto","Paleo")
 val commonAllergens=listOf("Peanuts","Tree nuts","Dairy","Eggs","Gluten","Soy","Shellfish","Fish","Sesame")
 private val allergenTerms=mapOf(
  "peanuts" to listOf("peanut","peanut butter","groundnut"),
  "tree nuts" to listOf("almond","walnut","pecan","cashew","pistachio","hazelnut","macadamia","brazil nut","pine nut"),
  "dairy" to listOf("milk","butter","cheese","cream","yogurt","yoghurt","whey","casein","ghee","buttermilk"),
  "eggs" to listOf("egg","egg yolk","egg white","mayonnaise"),
  "gluten" to listOf("wheat","flour","bread","pasta","barley","rye","couscous","semolina","breadcrumb","noodle","spelt"),
  "soy" to listOf("soy","soya","tofu","edamame","tempeh","miso","soy sauce"),
  "shellfish" to listOf("shrimp","prawn","crab","lobster","scallop","clam","mussel","oyster","crayfish"),
  "fish" to listOf("fish","salmon","tuna","cod","anchovy","sardine","trout","halibut","tilapia","fish sauce"),
  "sesame" to listOf("sesame","tahini")
 )
 /** Possible allergen hits as allergen → ingredient line. Custom allergens match their own words. */
 fun allergenMatches(recipe:Recipe,avoid:Set<String>):List<Pair<String,String>> {
  val lines=recipe.ingredients.map{it.name+" "+it.amount}
  return avoid.flatMap{allergen->val terms=allergenTerms[allergen.lowercase(Locale.ROOT).trim()] ?: listOf(allergen);lines.filter{line->terms.any{mentions(line,it)}}.map{allergen to it.trim()}}.distinct()
 }

 // Reference tables (iOS ToolboxReference.swift); general guidance, not a safety guarantee.
 data class ShelfLife(val food:String,val pantryDays:Int?,val fridgeDays:Int?,val freezerDays:Int?)
 val shelfLife=listOf(ShelfLife("Milk (opened)",null,7,90),ShelfLife("Eggs",null,35,null),ShelfLife("Butter",2,90,270),ShelfLife("Hard cheese",null,42,180),ShelfLife("Soft cheese",null,14,null),ShelfLife("Yogurt",null,14,60),ShelfLife("Chicken (raw)",null,2,270),ShelfLife("Ground beef (raw)",null,2,120),ShelfLife("Steak (raw)",null,4,270),ShelfLife("Pork (raw)",null,4,180),ShelfLife("Fish (raw)",null,1,180),ShelfLife("Bacon (opened)",null,7,30),ShelfLife("Deli meat (opened)",null,4,60),ShelfLife("Cooked leftovers",null,4,90),ShelfLife("Bread",5,null,90),ShelfLife("Tortillas",7,30,180),ShelfLife("Apples",7,42,null),ShelfLife("Bananas",5,null,60),ShelfLife("Berries",1,5,270),ShelfLife("Lettuce",null,7,null),ShelfLife("Tomatoes",5,10,null),ShelfLife("Potatoes",30,null,null),ShelfLife("Onions",40,null,null),ShelfLife("Garlic",120,null,null),ShelfLife("Carrots",null,28,270),ShelfLife("Rice (dry)",720,null,null),ShelfLife("Pasta (dry)",720,null,null),ShelfLife("Canned goods",720,null,null),ShelfLife("Peanut butter (opened)",90,180,null),ShelfLife("Ketchup (opened)",30,180,null),ShelfLife("Salsa (opened)",null,14,null),ShelfLife("Orange juice (opened)",null,8,null))
 data class StorageTip(val food:String,val place:String,val tip:String)
 val storageTips=listOf(StorageTip("Tomatoes","Counter","Never refrigerate — the cold turns them mealy and kills flavor. Stem side down on the counter."),StorageTip("Potatoes","Pantry","Cool, dark, and dry. Keep away from onions — together they both spoil faster."),StorageTip("Onions","Pantry","Cool and dry with airflow. Once cut, refrigerate in a sealed container."),StorageTip("Bananas","Counter","Separate from other fruit — they release ethylene that ripens everything nearby."),StorageTip("Bread","Counter / Freezer","Counter for a few days, freezer for longer. The fridge actually stales bread faster."),StorageTip("Berries","Fridge","Don't wash until you eat them — moisture speeds mold."),StorageTip("Herbs (soft)","Fridge","Cilantro, parsley: trim stems, stand in a jar of water, loosely cover with a bag."),StorageTip("Herbs (woody)","Fridge","Rosemary, thyme: wrap in a barely-damp paper towel inside a bag in the crisper."),StorageTip("Lettuce & greens","Fridge","Wash, dry very well, store with a paper towel to absorb moisture."),StorageTip("Avocados","Counter → Fridge","Ripen on the counter, then refrigerate to hold them at peak for several extra days."),StorageTip("Apples","Fridge","Crisper drawer — they last weeks refrigerated versus days on the counter."),StorageTip("Citrus","Fridge","Fine on the counter for a week; the crisper drawer roughly doubles that."),StorageTip("Garlic","Pantry","Whole heads in a cool, dark, airy spot. Refrigeration makes it sprout."),StorageTip("Mushrooms","Fridge","Paper bag, not plastic — they need to breathe."),StorageTip("Cheese","Fridge","Wrap in parchment or wax paper, then loosely in plastic."),StorageTip("Eggs","Fridge","In their carton, on a shelf — not the door, where temperature swings most."),StorageTip("Milk","Fridge","Back of the fridge where it's coldest, never the door."),StorageTip("Butter","Fridge / Counter","A few days in a covered dish on the counter is fine for spreading; the rest stays refrigerated."),StorageTip("Coffee","Pantry","Airtight, opaque, room temperature."),StorageTip("Olive oil","Pantry","Cool and dark, away from the stove — heat and light turn it rancid."),StorageTip("Honey","Pantry","Room temperature. If it crystallizes, warm the jar in water."),StorageTip("Nuts","Freezer","Their oils go rancid at room temperature within months."),StorageTip("Flour","Pantry / Freezer","Airtight container. Whole-grain flours belong in the freezer."),StorageTip("Brown sugar","Pantry","Airtight with a slice of bread or a terracotta disk to keep it soft."),StorageTip("Ginger","Freezer","Freeze it whole and grate from frozen."),StorageTip("Celery","Fridge","Wrap in foil, not plastic — it stays crisp for weeks."),StorageTip("Carrots","Fridge","Remove the green tops, store in the crisper."),StorageTip("Cucumbers","Fridge front","They dislike deep cold — the warmer front of the fridge, wrapped."),StorageTip("Peppers","Fridge","Whole and dry in the crisper. Cut peppers go in a sealed container."),StorageTip("Meat (raw)","Fridge bottom","Bottom shelf so drips can't touch anything below. Freeze if not cooking within 2 days."),StorageTip("Fish (raw)","Fridge bottom","Coldest shelf, on ice if possible, and cook within a day of buying."),StorageTip("Rice (cooked)","Fridge","Cool quickly and refrigerate within an hour; eat within 3–4 days."),StorageTip("Leftovers","Fridge","Shallow containers cool faster. Label with the date — 3–4 days is the rule."))
 fun <T> searchReference(rows:List<T>,query:String,text:(T)->String):List<T> { val q=words(query);return if(q.isEmpty())rows else rows.filter{words(text(it)).contains(q)} }
 private val seasonal=mapOf(1 to listOf("Oranges","Grapefruit","Kale","Brussels sprouts","Cabbage","Leeks","Sweet potatoes","Turnips"),2 to listOf("Oranges","Grapefruit","Kale","Cauliflower","Broccoli","Carrots","Beets","Lemons"),3 to listOf("Asparagus","Artichokes","Spinach","Peas","Radishes","Spring onions","Strawberries","Lettuce"),4 to listOf("Asparagus","Artichokes","Peas","Radishes","Rhubarb","Spinach","Strawberries","Spring greens"),5 to listOf("Strawberries","Asparagus","Peas","Rhubarb","Cherries","Apricots","Lettuce","New potatoes"),6 to listOf("Cherries","Strawberries","Blueberries","Zucchini","Tomatoes","Corn","Peaches","Green beans"),7 to listOf("Tomatoes","Corn","Peaches","Blueberries","Blackberries","Zucchini","Cucumbers","Melons","Bell peppers"),8 to listOf("Tomatoes","Corn","Peaches","Melons","Eggplant","Okra","Figs","Plums","Bell peppers"),9 to listOf("Apples","Pears","Grapes","Tomatoes","Winter squash","Pumpkins","Figs","Sweet potatoes"),10 to listOf("Apples","Pears","Pumpkins","Winter squash","Cranberries","Brussels sprouts","Cauliflower","Sweet potatoes"),11 to listOf("Cranberries","Pumpkins","Winter squash","Brussels sprouts","Sweet potatoes","Pears","Pomegranates","Kale"),12 to listOf("Oranges","Grapefruit","Pomegranates","Kale","Brussels sprouts","Winter squash","Sweet potatoes","Pears"))
 /** US-seasonal produce for a month with whether a matching item is in stock. */
 fun seasonalProduce(month:Int,inventory:List<InventoryItem>):List<Pair<String,Boolean>> {
  require(month in 1..12){"Choose a month."}
  val stocked=inventory.filter{it.quantity>0}.map{words(it.name)}
  return seasonal.getValue(month).map{produce->val p=words(produce);val forms=setOf(p,p.removeSuffix("s"),p.removeSuffix("es")).filter{it.isNotEmpty()};produce to stocked.any{name->forms.any{mentions(name,it)}}}
 }

 // Substitutions with ratios (iOS IngredientIntel); exact name first, then whole-word phrase.
 data class Substitute(val substitute:String,val ratio:String)
 private val substitutions=mapOf("butter" to listOf(Substitute("olive oil","¾ the amount"),Substitute("coconut oil","1:1"),Substitute("applesauce","1:1")),"egg" to listOf(Substitute("flax egg (1 tbsp flax + 3 tbsp water)","per egg"),Substitute("¼ cup applesauce","per egg"),Substitute("¼ cup mashed banana","per egg")),"milk" to listOf(Substitute("almond milk","1:1"),Substitute("oat milk","1:1"),Substitute("soy milk","1:1")),"buttermilk" to listOf(Substitute("milk + 1 tbsp lemon juice","per cup")),"sugar" to listOf(Substitute("honey","¾ the amount"),Substitute("maple syrup","¾ the amount")),"brown sugar" to listOf(Substitute("white sugar + 1 tbsp molasses","per cup")),"flour" to listOf(Substitute("almond flour","1:1"),Substitute("oat flour","1:1")),"sour cream" to listOf(Substitute("greek yogurt","1:1")),"heavy cream" to listOf(Substitute("¾ cup milk + ¼ cup butter","per cup"),Substitute("coconut cream","1:1")),"yogurt" to listOf(Substitute("sour cream","1:1")),"vegetable oil" to listOf(Substitute("melted butter","1:1"),Substitute("applesauce","1:1")),"cornstarch" to listOf(Substitute("2 tbsp flour","per 1 tbsp")))
 fun substitutesFor(ingredient:String):List<Substitute> {
  val key=words(ingredient); if(key.isEmpty())return emptyList()
  substitutions[key]?.let{return it}
  return substitutions.entries.sortedByDescending{it.key.length}.firstOrNull{mentions(key,it.key)}?.value ?: emptyList()
 }
}
