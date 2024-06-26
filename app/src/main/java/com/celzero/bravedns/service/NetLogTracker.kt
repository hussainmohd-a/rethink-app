/*
 * Copyright 2022 RethinkDNS and its authors
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

import android.content.Context
import backend.DNSSummary
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.ConnTrackerMetaData
import com.celzero.bravedns.data.ConnectionSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerRepository
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogRepository
import com.celzero.bravedns.database.RethinkLog
import com.celzero.bravedns.database.RethinkLogRepository
import com.celzero.bravedns.util.NetLogBatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.Calendar

class NetLogTracker
internal constructor(
    context: Context,
    connectionTrackerRepository: ConnectionTrackerRepository,
    rethinkLogRepository: RethinkLogRepository,
    dnsLogRepository: DnsLogRepository,
    private val persistentState: PersistentState
) : KoinComponent {

    private val dnsLatencyTracker by inject<QueryTracker>()

    private var scope: CoroutineScope? = null

    private var dnsdb: DnsLogTracker = DnsLogTracker(dnsLogRepository, persistentState, context)
    private var ipdb: IPTracker =
        IPTracker(connectionTrackerRepository, rethinkLogRepository, context)

    private var dnsBatcher: NetLogBatcher<DnsLog, Nothing>? = null
    private var ipBatcher: NetLogBatcher<ConnectionTracker, ConnectionSummary>? = null
    private var rrBatcher :NetLogBatcher<RethinkLog, ConnectionSummary>? = null

    // a single thread to run sig and batch co-routines in;
    // to avoid use of mutex/semaphores over shared-state
    // looper is never closed / cancelled and is always active
    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private val looper = newSingleThreadContext("nlbLooper")

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun restart(s: CoroutineScope) {
        this.scope = s

        // create new batchers on every new scope as their lifecycle is tied to the scope
        val b1 = NetLogBatcher<DnsLog, Nothing>("dns", looper, dnsdb::insertBatch)
        val b2 = NetLogBatcher<ConnectionTracker, ConnectionSummary>("ip", looper, ipdb::insertBatch, ipdb::updateBatch)
        val b3 = NetLogBatcher<RethinkLog, ConnectionSummary>("rr", looper, ipdb::insertRethinkBatch, ipdb::updateRethinkBatch)

        b1.begin(s)
        b2.begin(s)
        b3.begin(s)
        this.dnsBatcher = b1
        this.ipBatcher = b2
        this.rrBatcher = b3
    }

    fun writeIpLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeIpLog") {
            val connTracker = ipdb.makeConnectionTracker(info)
            ipBatcher?.add(connTracker)
        }
    }

    fun writeRethinkLog(info: ConnTrackerMetaData) {
        if (!persistentState.logsEnabled) return

        io("writeRethinkLog") {
            val rlog = ipdb.makeRethinkLogs(info) ?: return@io
            rrBatcher?.add(rlog)
        }
    }

    fun updateIpSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("updateIpSmm") {
            val s =
                if (DEBUG && summary.targetIp?.isNotEmpty() == true) {
                    ipdb.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            ipBatcher?.update(s)
        }
    }

    fun updateRethinkSummary(summary: ConnectionSummary) {
        if (!persistentState.logsEnabled) return

        io("updateRethinkSmm") {
            val s =
                if (DEBUG && summary.targetIp?.isNotEmpty() == true) {
                    ipdb.makeSummaryWithTarget(summary)
                } else {
                    summary
                }

            rrBatcher?.update(s)
        }
    }

    // now, this method is doing multiple things which should be removed.
    // fixme: should intend to only write the logs to database.
    fun processDnsLog(summary: DNSSummary) {
        val transaction = dnsdb.processOnResponse(summary)

        transaction.responseCalendar = Calendar.getInstance()
        // refresh latency from GoVpnAdapter
        io("refreshDnsLatency") { dnsLatencyTracker.refreshLatencyIfNeeded(transaction) }

        // TODO: This method should be part of BraveVPNService
        dnsdb.updateVpnConnectionState(transaction)

        if (!persistentState.logsEnabled) return

        val dnsLog = dnsdb.makeDnsLogObj(transaction)
        io("writeDnsLog") { dnsBatcher?.add(dnsLog) }
    }

    private fun io(s: String, f: suspend () -> Unit) =
        scope?.launch(CoroutineName(s) + Dispatchers.IO) { f() }
}
