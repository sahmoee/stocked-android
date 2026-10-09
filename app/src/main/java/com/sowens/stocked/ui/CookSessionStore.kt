package com.sowens.stocked.ui

import android.content.Context
import android.util.AtomicFile
import com.sowens.stocked.data.Recipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable data class CookSnapshot(
    val recipe: Recipe, val servings: Int, val stage: Int=0, val currentStep: Int=0,
    val checkedIngredients: Set<Int> = emptySet(), val completedSteps: Set<Int> = emptySet(),
    val notes: String="", val status: String="active", val completionToken: String=UUID.randomUUID().toString(),
    val startedAt: Long=System.currentTimeMillis(), val savedAt: Long=System.currentTimeMillis()
)

/** One active local cooking workspace, saved atomically before publishing progress. */
class CookSessionStore private constructor(context: Context) {
    private val file=AtomicFile(File(context.filesDir,"cook-session-v1.json"))
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private val lock=Mutex(); private var initialized=false
    private val mutable=MutableStateFlow<CookSnapshot?>(null)
    val state=mutable.asStateFlow()
    private val writer=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val mutableError=MutableStateFlow<String?>(null)
    val error=mutableError.asStateFlow()
    suspend fun initialize()=withContext(Dispatchers.IO) { lock.withLock {
        if(initialized) return@withLock
        if(file.baseFile.exists() || File(file.baseFile.path+".bak").exists()) {
            val bytes=file.openRead().use { input -> val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192); while(true) { val count=input.read(buffer); if(count<0) break; require(output.size()+count<=2*1024*1024) { "Saved cooking workspace is too large." }; output.write(buffer,0,count) }; output.toByteArray() }
            val snapshot=json.decodeFromString<CookSnapshot>(bytes.toString(Charsets.UTF_8))
            CookSnapshotRules.validate(snapshot)
            mutable.value=snapshot
        }
        initialized=true
    } }
    private fun persist(next:CookSnapshot?) {
        if(next==null) { file.delete(); check(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) { "Cooking workspace could not be discarded." }; mutable.value=null; return }
        val stamped=next.copy(savedAt=System.currentTimeMillis())
        CookSnapshotRules.validate(stamped)
        val bytes=json.encodeToString(stamped).toByteArray(Charsets.UTF_8)
        require(bytes.size<=2*1024*1024) { "Cooking workspace exceeds the save limit." }
        val output=file.startWrite()
        try { output.write(bytes); file.finishWrite(output); mutable.value=stamped; mutableError.value=null }
        catch(error:Exception) { file.failWrite(output); throw error }
    }
    private suspend fun write(transform:(CookSnapshot?)->CookSnapshot?)=withContext(Dispatchers.IO) { lock.withLock {
        check(initialized) { "Cooking workspace is still loading." }
        persist(transform(mutable.value))
    } }
    suspend fun start(recipe:Recipe,servings:Int,expectedPreviousToken:String?=null)=write { current ->
        CookSnapshotRules.allowStart(current,expectedPreviousToken)
        CookSnapshot(recipe,servings)
    }
    suspend fun update(expectedToken:String,change:(CookSnapshot)->CookSnapshot)=write { current -> change(CookSnapshotRules.expected(current,expectedToken)) }
    suspend fun resume(expectedToken:String)=update(expectedToken) { check(it.status in listOf("active","paused")) { "This cooking workspace is already complete." }; it.copy(status="active") }
    suspend fun discard(expectedToken:String)=write { current -> CookSnapshotRules.expected(current,expectedToken); null }
    /** Keep the active workspace locked across domain recording and snapshot acknowledgement. */
    suspend fun complete(expectedToken:String,notes:String,record:suspend(CookSnapshot)->Unit)=withContext(Dispatchers.IO) { lock.withLock {
        check(initialized) { "Cooking workspace is still loading." }
        val current=CookSnapshotRules.expected(mutable.value,expectedToken)
        if(current.status=="completed") return@withLock
        val finished=current.copy(notes=notes,status="completed",stage=2)
        CookSnapshotRules.validate(finished)
        // Once recording begins, finish its local acknowledgement even if its screen closes.
        // Process death between files remains retry-safe through the same persisted domain token.
        withContext(NonCancellable) { record(current); persist(finished) }
    } }
    /** Flush a disappearing screen's final notes without tying the write to its canceled scope. */
    fun flushNotes(expectedToken:String,notes:String) { writer.launch {
        try { lock.withLock { val current=mutable.value; if(initialized && current?.completionToken==expectedToken && current.notes!=notes) { persist(current.copy(notes=notes)); mutableError.value=null } } }
        catch(error:CancellationException) { throw error }
        catch(error:Exception) { mutableError.value=error.message ?: "Final cooking notes could not be saved." }
    } }
    companion object {
        @Volatile private var instance:CookSessionStore?=null
        fun get(context:Context):CookSessionStore = instance ?: synchronized(this) { instance ?: CookSessionStore(context.applicationContext).also { instance=it } }
    }
}
