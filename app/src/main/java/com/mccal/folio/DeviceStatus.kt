package com.mccal.folio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.SupplicantState
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import android.telephony.SignalStrength
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CellularStatus(val subscriptionId: Int, val level: Int? = null, val network: String? = null)

data class DeviceStatus(
    val battery: Int? = null,
    val charging: Boolean = false,
    val wifiConnected: Boolean = false,
    val wifiLevel: Int? = null,
    val cellularLevel: Int? = null,
    val airplane: Boolean = false,
    /** Ringer on silent or vibrate. */
    val silent: Boolean = false,
    val cellularSignals: List<CellularStatus> = emptyList(),
    val cellularNetwork: String? = null,
)

/** Observe only while visible; individual SIMs are available with optional phone-state access. */
class DeviceStatusMonitor(private val context: Context) : DefaultLifecycleObserver {
    private val connection = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val phone = context.getSystemService(TelephonyManager::class.java)
    private val subscriptions = context.getSystemService(SubscriptionManager::class.java)
    private val mutable = MutableStateFlow(DeviceStatus())
    val state = mutable.asStateFlow()
    private var networkRegistered = false
    private var observing = false
    private var subscriptionsRegistered = false
    private var activeDataRegistered = false
    private var activeDataId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    private val phoneCallbacks = mutableListOf<Pair<TelephonyManager, TelephonyCallback>>()
    private var phoneGeneration = 0
    private var receiverRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = updateConnection()
        override fun onLost(network: Network) = updateConnection()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = updateConnection()
    }
    private val subscriptionListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() { if (observing) refreshPhones() }
    }
    private val activeDataCallback = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
            if (observing && activeDataId != subId) {
                activeDataId = subId
                refreshPhones()
            }
        }
    }

    private fun refreshPhones() {
        // Keep this permission-dependent listener separate so basic signal updates still work without it.
        if (!activeDataRegistered) activeDataRegistered = runCatching {
            phone.registerTelephonyCallback(context.mainExecutor, activeDataCallback)
            true
        }.getOrDefault(false)
        val generation = ++phoneGeneration
        phoneCallbacks.forEach { (manager, callback) -> runCatching { manager.unregisterTelephonyCallback(callback) } }
        phoneCallbacks.clear()
        val active = runCatching { subscriptions.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex } }.getOrDefault(emptyList())
        val defaultId = activeDataId.takeIf { SubscriptionManager.isValidSubscriptionId(it) }
            ?: SubscriptionManager.getDefaultDataSubscriptionId()
        val ids = active.map { it.subscriptionId }.ifEmpty { listOf(defaultId) }
        val managers = ids.map { id -> id to if (SubscriptionManager.isValidSubscriptionId(id)) phone.createForSubscriptionId(id) else phone }
        val initial = managers.map { (id, manager) -> CellularStatus(id, runCatching { manager.signalStrength?.level }.getOrNull()) }
        val primaryId = defaultId.takeIf { it in ids } ?: ids.first()
        mutable.update { it.copy(cellularSignals = initial, cellularLevel = initial.firstOrNull { sim -> sim.subscriptionId == primaryId }?.level,
            cellularNetwork = null) }
        managers.forEach { (id, manager) ->
            fun updateSim(change: (CellularStatus) -> CellularStatus) {
                if (!observing || generation != phoneGeneration) return
                mutable.update { old ->
                    val signals = old.cellularSignals.map { if (it.subscriptionId == id) change(it) else it }
                    val primary = signals.firstOrNull { it.subscriptionId == primaryId }
                    old.copy(cellularSignals = signals, cellularLevel = primary?.level, cellularNetwork = primary?.network)
                }
            }
            val callback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
                override fun onSignalStrengthsChanged(signalStrength: SignalStrength) = updateSim { it.copy(level = signalStrength.level.coerceIn(0, 4)) }
                override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) = updateSim {
                    it.copy(network = cellularNetworkLabel(telephonyDisplayInfo))
                }
            }
            runCatching { manager.registerTelephonyCallback(context.mainExecutor, callback) }
                .onSuccess { phoneCallbacks += manager to callback }
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val charge = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                mutable.update { it.copy(battery = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null,
                    charging = charge == BatteryManager.BATTERY_STATUS_CHARGING || charge == BatteryManager.BATTERY_STATUS_FULL) }
            }
            updateConnection()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        observing = true
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION); addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION)
        })
        receiverRegistered = true
        networkRegistered = runCatching { connection.registerDefaultNetworkCallback(networkCallback); true }.getOrDefault(false)
        subscriptionsRegistered = runCatching { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionListener); true }.getOrDefault(false)
        refreshPhones()
        updateConnection()
    }

    // A runtime permission prompt can grant SIM access without stopping the Activity.
    override fun onResume(owner: LifecycleOwner) { refreshPhoneAccess() }

    fun refreshPhoneAccess() {
        if (!observing) return
        if (!subscriptionsRegistered) subscriptionsRegistered = runCatching {
            subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, subscriptionListener)
            true
        }.getOrDefault(false)
        refreshPhones()
    }

    @Suppress("DEPRECATION")
    private fun updateConnection() {
        val caps = runCatching { connection.getNetworkCapabilities(connection.activeNetwork) }.getOrNull()
        val info = (caps?.transportInfo as? WifiInfo) ?: runCatching { wifi.connectionInfo }.getOrNull()
        val connected = info?.supplicantState == SupplicantState.COMPLETED || caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val level = info?.rssi?.takeIf { connected && it > -127 }?.let { WifiManager.calculateSignalLevel(it, 5) }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val silent = runCatching { context.getSystemService(android.media.AudioManager::class.java).ringerMode != android.media.AudioManager.RINGER_MODE_NORMAL }
            .getOrDefault(false)
        mutable.update { it.copy(wifiConnected = connected, wifiLevel = level, airplane = airplane, silent = silent) }
    }

    override fun onStop(owner: LifecycleOwner) {
        observing = false
        phoneGeneration++
        if (networkRegistered) runCatching { connection.unregisterNetworkCallback(networkCallback) }
        phoneCallbacks.forEach { (manager, callback) -> runCatching { manager.unregisterTelephonyCallback(callback) } }
        phoneCallbacks.clear()
        if (activeDataRegistered) runCatching { phone.unregisterTelephonyCallback(activeDataCallback) }
        activeDataRegistered = false
        activeDataId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        if (subscriptionsRegistered) runCatching { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener) }
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        networkRegistered = false; subscriptionsRegistered = false; receiverRegistered = false
    }
}

private fun cellularNetworkLabel(info: TelephonyDisplayInfo): String? = when (info.overrideNetworkType) {
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
    TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA, TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO -> "LTE+"
    else -> when (info.networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA, TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A, TelephonyManager.NETWORK_TYPE_EVDO_B, TelephonyManager.NETWORK_TYPE_TD_SCDMA -> "3G"
        TelephonyManager.NETWORK_TYPE_GSM, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA, TelephonyManager.NETWORK_TYPE_1xRTT -> "2G"
        else -> null
    }
}
