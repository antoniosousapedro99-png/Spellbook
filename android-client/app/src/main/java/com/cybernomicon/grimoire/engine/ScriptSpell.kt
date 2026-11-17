package com.cybernomicon.grimoire.engine

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.WrappedException
import java.net.InetSocketAddress
import java.net.Socket

data class ScriptResult(
    val status: String,          // success | denied | error | halted
    val output: String,
    val logs: List<String>,
    val findingsAdded: Int,
    val error: String?,
)

/**
 * The spell API exposed to user scripts. Every network primitive asserts
 * authorization first, so a script can only touch authorized scopes — the same
 * boundary daemons and spells obey. These are plain blocking calls (the runner
 * executes on a background thread).
 */
class SpellApi(private val auth: Authorization, private val store: EngineStore) {
    val logs = mutableListOf<String>()
    var findingsAdded = 0; private set
    var probes = 0; private set
    val hosts = HashSet<String>()

    fun log(m: String) { if (logs.size < 500) logs.add(m) }
    fun authorized(host: String): Boolean = auth.isAuthorized(host)

    /** Expand an authorized scope to host addresses (throws if unauthorized). */
    fun expand(scope: String): Array<String> = auth.expand(scope).toTypedArray()

    fun connect(host: String, port: Int, timeoutMs: Int): Boolean {
        auth.assertAuthorized(host); probes++; hosts.add(host)
        return try {
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) { false }
    }

    fun banner(host: String, port: Int, timeoutMs: Int): String? {
        auth.assertAuthorized(host); probes++; hosts.add(host)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                val b = ByteArray(256)
                val n = s.getInputStream().read(b)
                if (n > 0) String(b, 0, n, Charsets.ISO_8859_1).trim() else null
            }
        } catch (e: Exception) { null }
    }

    fun addFinding(kind: String, title: String, detail: String, severity: String): String {
        val f = store.addFinding(FindingRow(kind = kind, severity = severity,
            title = title, detail = detail, daemon = "forge"))
        findingsAdded++
        return f.id
    }

    fun listDevices(): Array<String> =
        store.listDevices().map { it.address }.toTypedArray()
}

private const val PRELUDE = """
function log(m){ __api.log(String(m)); }
function authorized(h){ return __api.authorized(String(h)); }
function connect(h,p,t){ return __api.connect(String(h),(p|0),(t||1000)); }
function banner(h,p,t){ var b=__api.banner(String(h),(p|0),(t||2000)); return b===null?null:String(b); }
function addFinding(k,t,d,s){ return __api.addFinding(String(k),String(t),(d||''),(s||'info')); }
function expand(s){ var a=__api.expand(String(s)); var r=[]; for(var i=0;i<a.length;i++) r.push(String(a[i])); return r; }
function devices(){ var a=__api.listDevices(); var r=[]; for(var i=0;i<a.length;i++) r.push(String(a[i])); return r; }
"""

/** Runs a user spell script under a sandbox (interpreted, instruction-count
 *  timeout). Blocking — call from a background thread. */
object ScriptRunner {
    private class Sandbox(val deadline: Long) : ContextFactory() {
        override fun makeContext(): Context {
            val cx = super.makeContext()
            cx.optimizationLevel = -1                 // required on Android
            cx.instructionObserverThreshold = 10_000
            cx.languageVersion = Context.VERSION_ES6
            cx.maximumInterpreterStackDepth = 1_000
            return cx
        }
        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.currentTimeMillis() > deadline)
                throw Error("spell exceeded its time limit")
        }
    }

    fun run(source: String, host: String?, api: SpellApi,
            timeoutMs: Long = 15_000): ScriptResult {
        val factory = Sandbox(System.currentTimeMillis() + timeoutMs)
        val cx = factory.enterContext()
        return try {
            val scope = cx.initStandardObjects()
            ScriptableObject.putProperty(scope, "__api", Context.javaToJS(api, scope))
            ScriptableObject.putProperty(scope, "host", host ?: "")
            ScriptableObject.putProperty(scope, "scope", host ?: "")
            val result = cx.evaluateString(scope, PRELUDE + "\n" + source, "spell", 1, null)
            val out = if (result == null || result is org.mozilla.javascript.Undefined)
                "" else Context.toString(result)
            ScriptResult("success", out, api.logs, api.findingsAdded, null)
        } catch (e: WrappedException) {
            val w = e.wrappedException
            if (w is AuthorizationError)
                ScriptResult("denied", "", api.logs, api.findingsAdded, w.message)
            else ScriptResult("error", "", api.logs, api.findingsAdded,
                w.message ?: e.message)
        } catch (e: RhinoException) {
            ScriptResult("error", "", api.logs, api.findingsAdded, e.message)
        } catch (e: Throwable) {
            ScriptResult("error", "", api.logs, api.findingsAdded, e.message)
        } finally {
            Context.exit()
        }
    }
}
