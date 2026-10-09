package com.sowens.stocked.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sowens.stocked.data.*
import java.time.LocalDate
import java.text.NumberFormat

@Composable
fun KitchenToolsScreen(state:KitchenState,onClose:()->Unit,addGrocery:(GroceryItem)->Unit={}) {
 var selected by rememberSaveable{mutableStateOf("summary")}
 Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
  TextButton(onClick=onClose){Text("Back")}
  Text("Kitchen tools",style=MaterialTheme.typography.headlineLarge)
  Text("Useful calculations and a clear view of your pantry. Everything here works offline.")
  ToolMenu(selected,{selected=it},listOf("summary" to "Pantry overview","expiry" to "Expiry calendar","low" to "Low fill report","duplicates" to "Duplicate finder","snapshot" to "Pantry snapshot","convert" to "Unit converter")+parityTools+KitchenTools.calculators.map{it.id to it.title})
  when(selected) {
   "summary"->PantryOverview(state)
   "expiry"->{
    Text("Expiry calendar",style=MaterialTheme.typography.titleLarge)
    Text("Dates are the ones recorded in your kitchen, not a guarantee of food safety.")
    val rows=remember(state.inventory){state.inventory.filter{it.expirationDate!=null && it.quantity>0}.sortedBy{it.expirationDate}}
    if(rows.isEmpty())Text("No expiration dates recorded. Add dates from Inventory.")
    PagedReport(rows) { item ->
     val date=item.expirationDate!!
     val parsed=runCatching{LocalDate.parse(date)}.getOrNull()
     Text(date+(if(parsed!=null&&parsed<LocalDate.now())" · past recorded date"else ""),style=MaterialTheme.typography.titleMedium)
     Text("${item.name} · ${item.quantity} ${item.containerType}")
    }
    Text("${state.inventory.count{it.expirationDate==null}} items have no expiration date.")
   }
   "low"->{Text("Low fill report",style=MaterialTheme.typography.titleLarge);Text("Items at 25% fill or below, including empty stock. This does not infer a reorder target; adding to groceries combines with a matching unchecked row.");val rows=remember(state.inventory){KitchenTools.lowFill(state.inventory)};if(rows.isEmpty())Text("No low-fill items.");PagedReport(rows){item->Text("${item.name} · ${item.quantity} ${item.containerType} · ${KitchenTools.format(item.level*100)}% fill");TextButton(onClick={addGrocery(GroceryItem(name=item.name.trim(),sizeText=item.sizeAmount?.let{size->"${KitchenTools.format(size)} ${item.sizeUnit.orEmpty()}".trim()}.orEmpty()))}){Text("Add ${item.name} to groceries")}}}
   "duplicates"->{Text("Duplicate finder",style=MaterialTheme.typography.titleLarge);Text("Exact normalized names, zone and package sizes only. These are candidates to review in Inventory; nothing is merged automatically.");val groups=remember(state.inventory){KitchenTools.duplicates(state.inventory)};if(groups.isEmpty())Text("No exact duplicate candidates.");Text("${groups.size} duplicate groups");val candidates=remember(groups){groups.flatMap{group->group.map{item->item to group.size}}};PagedReport(candidates){(item,count)->Text("${item.name} · $count matching records",style=MaterialTheme.typography.titleMedium);Text("${item.quantity} ${item.containerType} · ${item.storageCategory}"+(item.sizeAmount?.let{value->" · ${KitchenTools.format(value)} ${item.sizeUnit.orEmpty()}"} ?: ""));HorizontalDivider()}}
   "snapshot"->PantrySnapshot(state)
   "convert"->ConversionTool()
   else->if(!showParityTool(selected,state))KitchenTools.calculators.find{it.id==selected}?.let{tool->key(tool.id){CalculatorPanel(tool)}}
  }
 }
}
@Composable private fun ToolMenu(value:String,change:(String)->Unit,options:List<Pair<String,String>>) {
 var expanded by remember{mutableStateOf(false)}
 Box {
  OutlinedButton(onClick={expanded=true},modifier=Modifier.fillMaxWidth()){Text(options.find{it.first==value}?.second ?: "Choose a tool")}
  DropdownMenu(expanded,onDismissRequest={expanded=false}){options.forEach{(id,title)->DropdownMenuItem(text={Text(title)},onClick={change(id);expanded=false})}}
 }
}
@Composable private fun PantryOverview(state:KitchenState) {
 val value=remember(state.inventory){KitchenTools.pantryValue(state.inventory)}
 val money=remember{NumberFormat.getCurrencyInstance()}
 Text("Pantry value",style=MaterialTheme.typography.titleLarge)
 Text(money.format(value.knownValue),style=MaterialTheme.typography.headlineMedium)
 Text("Estimate: recorded price × package quantity × fill level. ${value.pricedRows} rows have a price; ${value.unknownRows} are excluded because price is unknown or outside the safe calculation range.")
 value.byZone.forEach{(zone,total)->Text("$zone · ${money.format(total)}")}
 HorizontalDivider()
 Text("${state.inventory.size} inventory rows · ${state.grocery.count{!it.isChecked}} groceries still to buy")
 Text("${KitchenTools.expiring(state.inventory).size} items have recorded expiration dates within seven days or earlier.")
 Text("${state.userRecipes.size} saved recipes · ${state.planned.count{!it.isCooked}} planned meals still to cook")
}
private const val REPORT_PAGE_SIZE=25
@Composable private fun <T> PagedReport(rows:List<T>,render:@Composable (T)->Unit) {
 var requested by rememberSaveable{mutableStateOf(0)}
 val lastPage=((rows.size-1)/REPORT_PAGE_SIZE).coerceAtLeast(0)
 val page=requested.coerceIn(0,lastPage)
 val first=page*REPORT_PAGE_SIZE
 val end=minOf(first+REPORT_PAGE_SIZE,rows.size)
 if(rows.isNotEmpty()) {
  Text("Showing ${first+1}–$end of ${rows.size} rows")
  rows.subList(first,end).forEach{render(it)}
  Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
   OutlinedButton(onClick={requested=page-1},enabled=page>0,modifier=Modifier.weight(1f)){Text("Previous")}
   OutlinedButton(onClick={requested=page+1},enabled=page<lastPage,modifier=Modifier.weight(1f)){Text("Next")}
  }
 }
}
@Composable private fun PantrySnapshot(state:KitchenState) {
 val context=LocalContext.current
 val rows=remember(state.inventory){state.inventory.sortedWith(compareBy<InventoryItem>{it.storageCategory}.thenBy{it.name})}
 var requested by rememberSaveable{mutableStateOf(0)}
 val lastPage=((rows.size-1)/REPORT_PAGE_SIZE).coerceAtLeast(0)
 val page=requested.coerceIn(0,lastPage)
 val first=page*REPORT_PAGE_SIZE
 val end=minOf(first+REPORT_PAGE_SIZE,rows.size)
 val snapshot=remember(rows,page){KitchenTools.snapshot(rows.subList(first,end))}
 Text("Pantry snapshot",style=MaterialTheme.typography.titleLarge)
 Text("Review this page before sharing. It contains item names, quantities, zones and recorded expiration dates. Sharing sends only the displayed page.")
 Text(if(rows.isEmpty())"Your pantry is empty." else "Showing ${first+1}–$end of ${rows.size} items")
 Text(snapshot)
 Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
  OutlinedButton(onClick={requested=page-1},enabled=page>0,modifier=Modifier.weight(1f)){Text("Previous")}
  OutlinedButton(onClick={requested=page+1},enabled=page<lastPage,modifier=Modifier.weight(1f)){Text("Next")}
 }
 OutlinedButton(onClick={val intent=Intent(Intent.ACTION_SEND).apply{type="text/plain";putExtra(Intent.EXTRA_TEXT,snapshot)};context.startActivity(Intent.createChooser(intent,"Share pantry snapshot page"))},enabled=rows.isNotEmpty()){Text("Share this page (${end-first} items)")}
}
@Composable private fun ConversionTool() {
 var value by rememberSaveable{mutableStateOf("1")};var from by rememberSaveable{mutableStateOf("cup")};var to by rememberSaveable{mutableStateOf("ml")}
 var result by rememberSaveable{mutableStateOf<String?>(null)}
 Text("Unit converter",style=MaterialTheme.typography.titleLarge)
 Text("US customary cups and spoons. Volume and mass stay separate; no ingredient density is guessed.")
 Field("Amount",value,{value=it;result=null},true)
 Text("From");ToolMenu(from,{from=it;result=null},KitchenTools.units.map{it to it})
 Text("To");ToolMenu(to,{to=it;result=null},KitchenTools.units.map{it to it})
 Button(onClick={result=runCatching{val parsed=value.toDoubleOrNull() ?: error("Enter a number.");"${KitchenTools.format(KitchenTools.convert(parsed,from,to))} $to"}.getOrElse{it.message ?: "Check the units and amount."}}){Text("Convert")}
 result?.let{Text(it,style=MaterialTheme.typography.titleMedium)}
}
@Composable private fun CalculatorPanel(tool:KitchenTools.Calculator) {
 var values by rememberSaveable{mutableStateOf(tool.fields.associate{it.key to KitchenTools.format(it.initial)})}
 var answer by rememberSaveable{mutableStateOf<String?>(null)}
 Text(tool.title,style=MaterialTheme.typography.titleLarge)
 Text(tool.note)
 tool.fields.forEach{field->Field(field.label,values[field.key].orEmpty(),{text->values=values+(field.key to text);answer=null},true)}
 Button(onClick={answer=runCatching{
  val parsed=tool.fields.associate{field->field.key to (values[field.key]?.toDoubleOrNull() ?: error("Enter ${field.label}."))}
  val result=KitchenTools.calculate(tool.id,parsed)
  result.summary+"\n"+result.rows.joinToString("\n"){"${it.first}: ${KitchenTools.format(it.second)}"}
 }.getOrElse{it.message ?: "Check your values."}}){Text("Calculate")}
 answer?.let{Text(it,style=MaterialTheme.typography.bodyLarge)}
}
