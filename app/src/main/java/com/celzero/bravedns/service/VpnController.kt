/*
 * Copyright 2018 Jigsaw Operations LLC
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.service

import Logger
import Logger.LOG_TAG_VPN
import android.content.Context
import android.content.Intent
import android.net.Network
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Client
import com.celzero.firestack.backend.DNSTransport
import com.celzero.firestack.backend.NetStat
import com.celzero.firestack.backend.Proxy
import com.celzero.firestack.backend.RDNS
import com.celzero.firestack.backend.RouterStats
import com.celzero.firestack.backend.RpnEntitlement
import com.celzero.firestack.backend.RpnServers
import com.celzero.firestack.intra.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.Socket

object VpnController : KoinComponent {

    @Volatile private var braveVpnService: BraveVPNService? = null
    private var connectionState: BraveVPNService.State? = null
    private val persistentState by inject<PersistentState>()
    private var states: Channel<BraveVPNService.State?>? = null
    @Volatile private var protocol: Pair<Boolean, Boolean> = Pair(false, false)
    private const val URL4 = "IPv4"
    private const val URL6 = "IPv6"

    // usually same as vpnScope from BraveVPNService
    var externalScope: CoroutineScope? = null
        private set

    @Volatile private var vpnStartElapsedTime: Long = SystemClock.elapsedRealtime()

    // FIXME: Publish VpnState through this live-data to relieve direct access
    // into VpnController's state(), isOn(), hasTunnel() etc.
    var connectionStatus: MutableLiveData<BraveVPNService.State?> = MutableLiveData()

    @Volatile private var isLastConnectionEch: Boolean = false

    // TODO: make clients listen on create, start, stop, destroy from vpn-service
    fun onVpnCreated(b: BraveVPNService) {
        braveVpnService = b
        externalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        states = Channel(Channel.CONFLATED) // drop unconsumed states

        // store app start time, used in HomeScreenBottomSheet
        vpnStartElapsedTime = SystemClock.elapsedRealtime()

        externalScope!!.launch {
            states!!.consumeEach { state ->
                // transition from paused connection state only on NEW/NULL
                when (state) {
                    null -> {
                        updateState(null)
                    }
                    BraveVPNService.State.NEW -> {
                        updateState(state)
                    }
                    else -> {
                        // do not update if in paused-state unless state is new / null
                        if (!isAppPaused()) {
                            updateState(state)
                        }
                    }
                }
            }
        }
    }

    fun onVpnDestroyed() {
        braveVpnService = null
        try {
            states?.cancel()
        } catch (_: Exception) {}
        states = null
        vpnStartElapsedTime = SystemClock.elapsedRealtime()
        try {
            externalScope?.cancel("VPNController - onVpnDestroyed")
        } catch (_: Exception) {}
        externalScope = null
    }

    @Suppress("DEPRECATION")
    fun uptimeMs(): Long {
        val b = braveVpnService
        val start = vpnStartElapsedTime
        val t = SystemClock.elapsedRealtime() - start

        return if (b?.hasTunnel() == true) {
            t
        } else {
            -1L * t
        }
    }

    fun onConnectionStateChanged(state: BraveVPNService.State?) {
        val s = states
        externalScope?.launch { s?.send(state) }
    }

    fun onEchUpdate(isEch: Boolean) {
        isLastConnectionEch = isEch
    }

    private fun updateState(state: BraveVPNService.State?) {
        connectionState = state
        connectionStatus.postValue(state)
    }

    fun start(context: Context, autoAttempt: Boolean = false) {
        val b = braveVpnService
        // if the tunnel has the go-adapter then there's nothing to do
        if (b?.hasTunnel() == true) {
            Logger.w(LOG_TAG_VPN, "braveVPNService is already on, resending vpn enabled state")
            return
        }
        // below check is to avoid multiple calls to start the vpn when always-on is enabled
        // case: after a device reboot, vpn?.isAlwaysOnEnabled() may return false even though
        // always-on is actually enabled; this causes the VPN to start twice and fails doing so.
        // one approach is to store the always-on state in persistent state and check it here.
        // another is to check whether the vpn is already running.
        // todo: see whether changing the persistent state is really necessary.
        if (b != null && autoAttempt) {
            Logger.i(LOG_TAG_VPN, "vpn service already running, no need to start")
            return
        }
        try {
            // else: resend/send the start-command to the vpn service which handles both false-start
            // and actual-start scenarios just fine; ref: isNewVpn bool in vpnService.onStartCommand
            val startServiceIntent = Intent(context, BraveVPNService::class.java)

            // ContextCompat will take care of calling the proper service based on the API version.
            // before Android O, context.startService(intent) should be invoked.
            // on or after Android O, context.startForegroundService(intent) should be invoked.
            ContextCompat.startForegroundService(context, startServiceIntent)

            onConnectionStateChanged(connectionState)
            Logger.i(LOG_TAG_VPN, "VPNController; Start(sync) executed")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_VPN, "VPNController; Start(sync) failed, ${e.message}")
        }
    }

    fun stop(reason: String, context: Context, userInitiated: Boolean = true) {
        Logger.i(LOG_TAG_VPN, "VPN Controller stop with context: $context")
        connectionState = null
        onConnectionStateChanged(null)
        braveVpnService?.signalStopService(reason, userInitiated)
    }

    @Suppress("DEPRECATION")
    fun state(): VpnState {
        val requested: Boolean = persistentState.getVpnEnabled()
        val b = braveVpnService
        val cs = connectionState
        val ech = isLastConnectionEch
        val on = b?.hasTunnel() == true
        return VpnState(requested, on, cs, ech)
    }

    @Deprecated(message = "use hasTunnel() instead", replaceWith = ReplaceWith("hasTunnel()"))
    fun isOn(): Boolean {
        return hasTunnel()
    }

    suspend fun refresh() {
        braveVpnService?.refreshResolvers()
    }

    fun hasTunnel(): Boolean {
        return braveVpnService?.hasTunnel() == true
    }

    fun hasStarted(): Boolean {
        val cs = connectionState
        return cs == BraveVPNService.State.WORKING ||
            cs == BraveVPNService.State.FAILING
    }

    fun isAppPaused(): Boolean {
        return connectionState == BraveVPNService.State.PAUSED
    }

    fun isVpnLockdown(): Boolean {
        return Utilities.isVpnLockdownEnabled(braveVpnService)
    }

    fun isAlwaysOn(context: Context): Boolean {
        return Utilities.isAlwaysOnEnabled(context, braveVpnService)
    }

    fun pauseApp() {
        braveVpnService?.let {
            onConnectionStateChanged(BraveVPNService.State.PAUSED)
            it.pauseApp()
        }
    }

    fun resumeApp() {
        braveVpnService?.let {
            onConnectionStateChanged(BraveVPNService.State.NEW)
            it.resumeApp()
        }
    }

    fun getPauseCountDownObserver(): MutableLiveData<Long>? {
        return braveVpnService?.getPauseCountDownObserver()
    }

    fun increasePauseDuration(durationMs: Long) {
        braveVpnService?.increasePauseDuration(durationMs)
    }

    fun decreasePauseDuration(durationMs: Long) {
        braveVpnService?.decreasePauseDuration(durationMs)
    }

    suspend fun getProxyStatusById(id: String): Pair<Int?, String> {
        return braveVpnService?.getProxyStatusById(id) ?: Pair(null, "vpn service not available")
    }

    suspend fun getProxyStats(id: String): RouterStats? {
        return braveVpnService?.getProxyStats(id)
    }

    suspend fun getWireGuardStats(id: String): WireguardManager.WgStats? {
        return braveVpnService?.getWireGuardStats(id)
    }

    suspend fun getRpnStats(id: String): RpnProxyManager.RpnStats? {
        return braveVpnService?.getRpnStats(id)
    }

    suspend fun getRpnAddlInfo(id: String): RpnProxyManager.ActiveRpnAddlInfo? {
        return braveVpnService?.getRpnAddlInfo(id)
    }

    suspend fun getSupportedIpVersion(id: String): Pair<Boolean, Boolean> {
        return braveVpnService?.getSupportedIpVersion(id) ?: Pair(false, false)
    }

    suspend fun isSplitTunnelProxy(id: String, pair: Pair<Boolean, Boolean>): Boolean {
        return braveVpnService?.isSplitTunnelProxy(id, pair) ?: false
    }

    suspend fun p50(id: String): Long {
        return braveVpnService?.p50(id) ?: -1L
    }

    fun getRegionLiveData(): LiveData<String> {
        return braveVpnService?.getRegionLiveData() ?: MutableLiveData()
    }

    fun protocols(): String {
        val p = protocol
        val ipv4 = p.first
        val ipv6 = p.second
        return if (ipv4 && ipv6) {
            "$URL4, $URL6"
        } else if (ipv6) {
            URL6
        } else if (ipv4) {
            URL4
        } else {
            // if both are false, then return based on the stallOnNoNetwork value
            if (!persistentState.stallOnNoNetwork) {
                "$URL4, $URL6"
            } else {
                "-"
            }
        }
    }

    fun updateProtocol(proto: Pair<Boolean, Boolean>) {
        val finalProto =
            if (!proto.first && !proto.second) {
                val failOpen = !persistentState.stallOnNoNetwork
                Logger.i(LOG_TAG_VPN, "both v4 and v6 false, setting $failOpen")
                Pair(failOpen, failOpen)
            } else {
                proto
            }
        protocol = finalProto
    }

    fun mtu(): Int {
        return braveVpnService?.tunMtu() ?: 0
    }

    fun underlyingSsid(): String? {
        val b = braveVpnService ?: return ""
        return b.underlyingNetworks?.activeSsid ?: b.underlyingNetworks?.ipv4Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid ?: b.underlyingNetworks?.ipv6Net?.firstOrNull { !it.ssid.isNullOrEmpty() }?.ssid.orEmpty()
    }

    fun netType(): String {
        // using firewall_status_unknown from strings.xml as a place holder to show network
        // type as Unknown.
        val b = braveVpnService
        var t = b?.getString(R.string.firewall_status_unknown) ?: ""
        if (b == null) {
            return t
        }

        t =
            if (b.underlyingNetworks?.isActiveNetworkMetered == true) {
                b.getString(R.string.ada_app_metered).toString()
            } else {
                // the network type is shown as unmetered even when rethink cannot determine
                // the underlying network / no underlying network
                b.getString(R.string.ada_app_unmetered).toString()
            }
        return t
    }

    suspend fun hasCid(cid: String, uid: Int): Boolean {
        return braveVpnService?.hasCid(cid, uid) ?: false
    }

    suspend fun removeWireGuardProxy(id: Int) {
        braveVpnService?.removeWireGuardProxy(id)
    }

    suspend fun addWireGuardProxy(id: String, force: Boolean = false) {
        braveVpnService?.addWireGuardProxy(id, force)
    }

    suspend fun refreshOrPauseOrResumeOrReAddProxies() {
        braveVpnService?.refreshOrPauseOrResumeOrReAddProxies()
    }

    fun closeConnectionsIfNeeded(uid: Int = INVALID_UID, reason: String) {
        braveVpnService?.closeConnectionsIfNeeded(uid, reason)
    }

    fun closeConnectionsByUidDomain(uid: Int, ipAddress: String?, reason: String) {
        braveVpnService?.closeConnectionsByUidDomain(uid, ipAddress, reason)
    }

    suspend fun getDnsStatus(id: String): Int? {
        return braveVpnService?.getDnsStatus(id)
    }

    suspend fun getRDNS(type: RethinkBlocklistManager.RethinkBlocklistType): RDNS? {
        return braveVpnService?.getRDNS(type)
    }

    fun protectSocket(socket: Socket) {
        braveVpnService?.protectSocket(socket)
    }

    suspend fun probeIpOrUrl(ip: String, useAuto: Boolean): ConnectionMonitor.ProbeResult? {
        return braveVpnService?.probeIpOrUrl(ip, useAuto)
    }

    suspend fun notifyConnectionMonitor(enforcePolicyChange: Boolean = false) {
        braveVpnService?.notifyConnectionMonitor(enforcePolicyChange)
    }

    suspend fun getSystemDns(): String {
        return braveVpnService?.getSystemDns().orEmpty()
    }

    suspend fun getNetStat(): NetStat? {
        return braveVpnService?.getNetStat()
    }

    fun writeConsoleLog(log: ConsoleLog) {
        braveVpnService?.writeConsoleLog(log)
    }

    suspend fun registerAndFetchWinConfig(prevBytes: ByteArray?, deviceId: String): ByteArray? {
        return braveVpnService?.registerAndFetchWinIfNeeded(prevBytes, deviceId)
    }

    /** Ask the tunnel to refresh the WIN proxy state and return the updated bytes. */
    suspend fun updateWin(): ByteArray? {
        return braveVpnService?.updateWin()
    }

    suspend fun onRpnOptsChange() {
        braveVpnService?.onRpnOptsChange()
    }

    suspend fun getWinExpiryTs(): Long? {
        return braveVpnService?.getWinExpiryTs()
    }

    suspend fun isWinRegistered(): Boolean {
        return braveVpnService?.isWinRegistered() ?: false
    }

    suspend fun unregisterWin(): Boolean {
        return braveVpnService?.unregisterWin() ?: false
    }

    suspend fun handleRpnProxies() {
        return braveVpnService?.handleRpnProxies() ?: Unit
    }

    suspend fun createWgHop(origin: String, hop: String): Pair<Boolean, String> {
        return (braveVpnService?.createWgHop(origin, hop) ?: Pair(false, "vpn service not available"))
    }

    suspend fun testRpnProxy(): Boolean {
        return braveVpnService?.testRpnProxy() == true
    }

    suspend fun isRpnReachable(csv: String): Boolean {
        return braveVpnService?.isRpnReachable(csv) == true
    }

    suspend fun testHop(src: String, hop: String): Pair<Boolean, String?> {
        return braveVpnService?.testHop(src, hop) ?: Pair(false, "vpn service not available")
    }

    suspend fun hopStatus(src: String, hop: String): Pair<Int?, String> {
        return braveVpnService?.hopStatus(src, hop) ?: Pair(null, "vpn service not available")
    }

    suspend fun removeHop(src: String): Pair<Boolean, String> {
        return braveVpnService?.removeHop(src) ?: Pair(false, "vpn service not available")
    }

    suspend fun getRpnProps(type: RpnProxyManager.RpnType): Pair<RpnProxyManager.RpnProps?, String?> {
        return braveVpnService?.getRpnProps(type) ?: Pair(null, null)
    }

    suspend fun getRpnLocations(type: RpnProxyManager.RpnType): Pair<RpnServers?, String?> {
        return braveVpnService?.getRpnLocations(type) ?: Pair(null, null)
    }

    suspend fun addNewWinServer(key: String): Pair<Boolean, String> {
        return braveVpnService?.addNewWinServer(key) ?: Pair(false, "vpn service not available")
    }

    suspend fun handleRpnHop(key: String, configChanged: Boolean): Pair<Boolean, String> {
        return braveVpnService?.handleRpnHop(key, configChanged) ?: Pair(false, "vpn service not available")
    }

    suspend fun removeWinServer(key: String): Pair<Boolean, String> {
        return braveVpnService?.removeWinServer(key) ?: Pair(false, "vpn service not available")
    }

    suspend fun refreshRpnProxy(id: String): Boolean {
        return braveVpnService?.refreshRpnProxy(id) ?: false
    }

    suspend fun stopRpnProxy(): Boolean {
        return braveVpnService?.stopRpnProxy() ?: false
    }

    suspend fun reconnectRpnProxy(id: String): Boolean {
        return braveVpnService?.reconnectRpnProxy(id) ?: false
    }

    suspend fun getRpnClientInfoById(id: String): Client? {
        return braveVpnService?.getRpnClientInfoById(id)
    }

    suspend fun getWgClientInfoById(id: String): Client? {
        return braveVpnService?.getWgClientInfoById(id)
    }

    suspend fun vpnStats(): String? {
        return braveVpnService?.vpnStats()
    }

    fun performConnectivityCheck(controller: Controller, id: String, addrPort: String): Boolean {
        return braveVpnService?.performConnectivityCheck(controller, id, addrPort) ?: false
    }

    fun performAutoConnectivityCheck(controller: Controller, id: String, mode: String): Boolean {
        return braveVpnService?.performAutoConnectivityCheck(controller, id, mode) ?: false
    }

    fun bindToNwForConnectivityChecks(nw: Network, pfd: Long): Boolean {
        return braveVpnService?.bindToNwForConnectivityChecks(nw, pfd) ?: false
    }

    fun protectFdForConnectivityChecks(fd: Long) {
        this.braveVpnService?.protectFdForConnectivityChecks(fd)
    }

    suspend fun getPlusResolvers(): List<String> {
        return braveVpnService?.getPlusResolvers() ?: emptyList()
    }

    suspend fun getPlusTransportById(id: String): DNSTransport? {
        return braveVpnService?.getPlusTransportById(id)
    }

    fun isUnderlyingVpnNetworkEmpty(): Boolean {
        return braveVpnService?.isUnderlyingVpnNetworkEmpty() ?: false
    }

    fun screenUnlock() {
        braveVpnService?.screenUnlock()
    }

    suspend fun initiateWgPing(proxyId: String) {
        braveVpnService?.initiateWgPing(proxyId)
    }

    suspend fun initiateRpnPing(proxyId: String) {
        braveVpnService?.initiateRpnPing(proxyId)
    }

    fun screenLock() {
        braveVpnService?.screenLock()
    }


    suspend fun getWinByKey(key: String): Proxy? {
        return braveVpnService?.getWinByKey(key)
    }

    suspend fun getWinIdentifier(): String? {
        return braveVpnService?.getWinIdentifier()
    }

    suspend fun getWinProxyId(): String? {
        return braveVpnService?.getWinProxyId()
    }

    suspend fun crashTun(type: Long) {
        braveVpnService?.crashTun(type)
    }

    suspend fun getEntitlementDetails(prevBytes: ByteArray?, deviceId: String): RpnEntitlement? {
        return braveVpnService?.getEntitlementDetails(prevBytes, deviceId)
    }
}
