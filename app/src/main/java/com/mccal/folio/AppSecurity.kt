package com.mccal.folio

import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.UUID

data class AppSecurityKey(val packageName: String, val userSerial: Long)
enum class AppSecurityMode { LOCKED, HIDDEN }

/** Folio's launch/display policy, independent of the older cosmetic hidden-app setting. */
internal object AppSecurity {
    private var context: Context? = null
    private var rules by mutableStateOf<Map<AppSecurityKey, AppSecurityMode>>(emptyMap())
    private var unreadable by mutableStateOf(false)
    private var folderUser by mutableStateOf<Long?>(null)
    val revision = MutableStateFlow(0)
    private val prompts = mutableSetOf<CancellationSignal>()
    private val setupDialogs = mutableSetOf<android.app.AlertDialog>()
    private val requests = mutableMapOf<String, Pair<String, () -> Unit>>()
    @Volatile private var authenticationGeneration = 0
    val authenticationEpoch: Int get() = authenticationGeneration
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == "state") read(prefs)
    }

    fun initialize(value: Context) {
        if (context != null) return
        context = value.applicationContext
        val prefs = value.getSharedPreferences("launcher", Context.MODE_PRIVATE)
        read(prefs)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        ContextCompat.registerReceiver(value.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF || intent?.getStringExtra("reason") == "homekey") lock()
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun read(prefs: SharedPreferences) {
        runCatching {
            val state = JSONObject(prefs.getString("state", "{}") ?: "{}")
            decodeAppSecurity(if (state.has("appSecurity")) state.getJSONArray("appSecurity") else null)
        }
            .onSuccess { rules = it; unreadable = false }
            .onFailure { unreadable = true }
        revision.update { it + 1 }
        IslandListenerService.refreshPrivacy()
    }

    fun key(app: AppEntry) = AppSecurityKey(app.packageName, app.userSerial)
    fun key(packageName: String, user: UserHandle): AppSecurityKey? =
        runCatching { context?.getSystemService(UserManager::class.java)?.getSerialNumberForUser(user) }.getOrNull()?.takeIf { it >= 0 }
            ?.let { AppSecurityKey(packageName, it) }
    fun isHidden(app: AppEntry, policy: Map<AppSecurityKey, AppSecurityMode> = rules) =
        unreadable || policy[key(app)] == AppSecurityMode.HIDDEN
    fun isProtected(app: AppEntry, policy: Map<AppSecurityKey, AppSecurityMode> = rules) =
        unreadable || key(app) in policy
    fun isDisplayable(app: AppEntry, policy: Map<AppSecurityKey, AppSecurityMode> = rules) =
        !isHidden(app, policy) && (!app.isShortcut || !isProtected(app, policy))

    fun isProtected(packageName: String, user: UserHandle? = null): Boolean {
        if (unreadable) return true
        val serial = user?.let { key(packageName, it)?.userSerial }
        return rules.keys.any { it.packageName == packageName && (serial == null || it.userSerial == serial) }
    }

    fun isHidden(packageName: String, user: UserHandle? = null): Boolean {
        if (unreadable) return true
        val serial = user?.let { key(packageName, it)?.userSerial }
        return rules.any { (key, mode) -> mode == AppSecurityMode.HIDDEN && key.packageName == packageName &&
            (serial == null || key.userSerial == serial) }
    }

    fun badgeVisible(packageName: String, user: UserHandle): Boolean {
        if (unreadable) return false
        val serial = key(packageName, user)?.userSerial ?: return false
        return rules[AppSecurityKey(packageName, serial)] != AppSecurityMode.HIDDEN || hasFolderAccess(serial)
    }

    fun openFolder(userSerial: Long) { folderUser = userSerial; revision.update { it + 1 } }
    fun closeFolder() { if (folderUser != null) { folderUser = null; revision.update { it + 1 } } }
    fun hasFolderAccess(userSerial: Long) = folderUser == userSerial && !unreadable
    fun lock() {
        closeFolder()
        authenticationGeneration++
        prompts.toList().forEach { it.cancel() }
        setupDialogs.toList().forEach { it.dismiss() }
        requests.clear()
        revision.update { it + 1 }
    }

    fun authenticate(activity: Activity, title: String, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        initialize(activity)
        if (activity.isFinishing || activity.isDestroyed) { onFailure(); return }
        if (!activity.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
            val dialog = android.app.AlertDialog.Builder(activity).setTitle(R.string.security_setup_title)
                .setMessage(R.string.security_setup_message)
                .setPositiveButton(R.string.security_setup_action) { _, _ ->
                    runCatching { activity.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }.setNegativeButton(R.string.cancel, null).create()
            val owner = activity as? LifecycleOwner
            val observer = object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) { dialog.dismiss() }
            }
            dialog.setOnDismissListener {
                setupDialogs.remove(dialog)
                owner?.lifecycle?.removeObserver(observer)
                onFailure()
            }
            setupDialogs += dialog
            owner?.lifecycle?.addObserver(observer)
            runCatching { dialog.show() }.onFailure {
                setupDialogs.remove(dialog)
                owner?.lifecycle?.removeObserver(observer)
                onFailure()
            }
            return
        }
        val generation = authenticationGeneration
        val cancellation = CancellationSignal()
        var completed = false
        val owner = activity as? LifecycleOwner
        lateinit var observer: DefaultLifecycleObserver
        fun complete(success: Boolean) {
            if (completed) return
            completed = true
            prompts.remove(cancellation)
            owner?.lifecycle?.removeObserver(observer)
            if (success && generation == authenticationGeneration && !activity.isFinishing && !activity.isDestroyed) onSuccess()
            else onFailure()
        }
        observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { cancellation.cancel(); complete(false) }
        }
        owner?.lifecycle?.addObserver(observer)
        prompts += cancellation
        runCatching {
            BiometricPrompt.Builder(activity).setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build().authenticate(cancellation, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = complete(true)
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = complete(false)
                })
        }.onFailure { complete(false) }
    }

    fun run(context: Context, packageName: String, user: UserHandle? = null, action: () -> Unit) {
        initialize(context)
        val serial = user?.let { key(packageName, it)?.userSerial }
        val folderAuthorized = serial != null && hasFolderAccess(serial) &&
            rules[AppSecurityKey(packageName, serial)] == AppSecurityMode.HIDDEN
        val open = { try { action() } finally { closeFolder() } }
        if (!isProtected(packageName, user) || folderAuthorized) { open(); return }
        val activity = activity(context)
        if (activity != null) authenticate(activity, context.getString(R.string.security_auth_title), open)
        else {
            val id = UUID.randomUUID().toString()
            requests[id] = context.getString(R.string.security_auth_title) to open
            runCatching { context.startActivity(Intent(context, AppAuthenticationActivity::class.java)
                .putExtra("request", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { requests.remove(id) }
        }
    }

    /** Resolve shared-data viewers too; an ambiguous chooser with a protected candidate authenticates first. */
    fun startActivity(context: Context, intent: Intent, options: android.os.Bundle? = null, onStarted: () -> Unit = {}) {
        initialize(context)
        val targets = context.packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        val explicitPackage = intent.component?.packageName ?: intent.`package` ?: intent.selector?.component?.packageName ?: intent.selector?.`package`
        val protectedPackage = explicitPackage?.takeIf { isProtected(it, android.os.Process.myUserHandle()) }
            ?: targets.firstOrNull { isProtected(it.activityInfo.packageName, android.os.Process.myUserHandle()) }?.activityInfo?.packageName
        val open = { context.startActivity(intent, options); onStarted() }
        if (protectedPackage == null) { closeFolder(); open() }
        else run(context, protectedPackage, android.os.Process.myUserHandle()) { runCatching { open() } }
    }

    private fun activity(context: Context): Activity? = when (context) {
        is Activity -> context
        is ContextWrapper -> context.baseContext.takeIf { it !== context }?.let(::activity)
        else -> null
    }

    internal fun takeRequest(id: String?) = id?.let(requests::remove)
}

/** Non-exported authentication host for user actions from Folio's services. Requests never survive process death. */
class AppAuthenticationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val request = AppSecurity.takeRequest(intent.getStringExtra("request")) ?: run { finish(); return }
        AppSecurity.authenticate(this, request.first, {
            request.second()
            finish()
        }, { finish() })
    }
}
