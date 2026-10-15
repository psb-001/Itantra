package com.bjp.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Primary transport: Bluetooth Classic RFCOMM.
 *
 * WHY THIS IS PRIMARY (not Wi-Fi Direct):
 *  1. The payload is 50–250 bytes; we need ~1 kbit/s and RFCOMM gives ~2 Mbit/s.
 *     Wi-Fi Direct's bandwidth advantage is worthless for this problem.
 *  2. `BluetoothSocket` has been stable for 15 years. `WifiP2pManager` behaves differently
 *     across vendors — that is how live demos fail.
 *  3. Pairing provides sender authentication for free, which matters for an app that plays
 *     emergency alerts at full volume. Wi-Fi Direct has no equivalent.
 *  4. Wi-Fi Direct needs ACCESS_FINE_LOCATION on Android 10+ just to *discover* peers.
 *
 * FRAMING: RFCOMM is a byte stream with no message boundaries, so every packet is sent as a
 * 4-byte big-endian length followed by that many bytes. Without this, two messages sent close
 * together arrive glued into one.
 *
 * PERMISSIONS: on API 31+ (Android 12) the old BLUETOOTH / BLUETOOTH_ADMIN permissions are
 * ignored — you need BLUETOOTH_CONNECT and BLUETOOTH_SCAN. Getting this wrong is a classic
 * silent failure: no crash, no connection.
 */
class BluetoothRfcommTransport(
    private val appContext: Context? = null,
) : Transport {

    companion object {
        private const val TAG = "iTantra/BT"
        private const val SPP_NAME = "iTantra"

        /**
         * How often to check that the accept loop is still alive.
         *
         * 5 s, down from 15 s: this is the worst-case delay before a dropped session's listener
         * side is accepting again, if the direct restart in the reader's finally block ever
         * fails. The check itself is two volatile reads; it costs nothing to run often.
         */
        private const val WATCHDOG_INTERVAL_MS = 5_000L

        /** Pause between connect attempts. connect() blocks for its own timeout, so keep it short. */
        private const val RETRY_PAUSE_MS = 300L

        /**
         * How long to wait after cancelDiscovery() before connecting. cancelDiscovery() is
         * asynchronous; connecting while discovery is still winding down fails instantly.
         * BlueComm — the reference implementation of the SDP fallback — uses 300 ms here too.
         */
        private const val DISCOVERY_SETTLE_MS = 300L

        /** Fixed service UUID. Both phones must use the same one. */
        private val SPP_UUID: UUID = UUID.fromString("8ce255c0-200a-11e0-ac64-0800200c9a66")
    }

    override val name = TransportKind.BLUETOOTH.displayName

    @Volatile
    override var isConnected: Boolean = false
        private set

    @Volatile
    override var connectedPeerName: String? = null
        private set

    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onStateChange: ((Transport.State, String) -> Unit)? = null

    /**
     * Devices we can connect to. For Bluetooth that means *bonded* devices only — Android will not
     * open an RFCOMM socket to an unpaired phone, and the app deliberately does not pair on the
     * user's behalf. Pairing in Android Settings is what authenticates the sender of an emergency
     * alert, so it stays a manual, visible step.
     */
    @SuppressLint("MissingPermission")
    override fun availablePeers(): List<PeerInfo> {
        val adapter = adapter() ?: return emptyList()
        if (!hasPermissions()) return emptyList()
        return try {
            (adapter.bondedDevices ?: emptySet()).map { d ->
                PeerInfo(
                    id = d.address,
                    name = d.name ?: d.address,
                    paired = true,
                    connected = d.address == connectedAddress,
                )
            }.sortedBy { it.name.lowercase() }
        } catch (e: SecurityException) {
            Log.w(TAG, "bondedDevices denied: ${e.message}")
            emptyList()
        }
    }

    @Volatile
    private var connectedAddress: String? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var socket: BluetoothSocket? = null
    private var reader: Thread? = null
    private var acceptor: Thread? = null
    private var watchdog: Thread? = null

    @Volatile
    private var running = false

    private fun state(s: Transport.State, msg: String) {
        Log.i(TAG, "$s — $msg")
        onStateChange?.invoke(s, msg)
    }

    private fun adapter(): BluetoothAdapter? =
        appContext?.getSystemService(BluetoothManager::class.java)?.adapter

    /** True when the runtime permissions this transport needs are actually granted. */
    fun hasPermissions(): Boolean {
        val ctx = appContext ?: return false
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
        return needed.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    override fun startListening() {
        if (appContext == null) {
            state(Transport.State.ERROR, "no application context — transport was built wrongly")
            return
        }
        val adapter = adapter()
        if (adapter == null) {
            state(Transport.State.ERROR, "this device reports no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            state(Transport.State.ERROR, "Bluetooth is switched off — turn it on and retry")
            return
        }
        if (!hasPermissions()) {
            state(Transport.State.ERROR, "Bluetooth permission not granted")
            return
        }

        running = true
        state(Transport.State.DISCOVERING, "LISTENING — waiting for the other phone to connect")
        startAcceptLoop()
        startWatchdog()
    }

    /** The accept loop, extracted so the watchdog can restart it if it dies. */
    @Synchronized
    private fun startAcceptLoop() {
        // Idempotent on purpose: three callers can ask for this — startListening, the watchdog,
        // and the post-session restart in the reader — and two live accept loops on one UUID is
        // a mess (the second listenUsingRfcommWithServiceRecord throws immediately and kills
        // whichever thread ran it).
        if (acceptor?.isAlive == true) return
        val adapter = adapter() ?: return
        acceptor = thread(name = "bt-accept") {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val server = adapter.listenUsingRfcommWithServiceRecord(SPP_NAME, SPP_UUID)
                serverSocket = server
                Log.i(
                    TAG,
                    "server socket open after ${android.os.SystemClock.elapsedRealtime() - t0} ms " +
                        "— ready to accept",
                )
                // Keep accepting until we get a connection or are told to stop. A single accept()
                // would leave the listener dead the moment one connect attempt failed, and the
                // speaker phone retries — so the listener must keep the door open.
                while (running && !isConnected) {
                    val accepted = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (running) Log.w(TAG, "accept threw: ${e.message}")
                        break
                    }
                    if (accepted != null) {
                        Log.i(TAG, "accepted an incoming connection")
                        val who = runCatching { accepted.remoteDevice?.name }.getOrNull()
                            ?: runCatching { accepted.remoteDevice?.address }.getOrNull()
                            ?: "unknown device"
                        attach(accepted, who)
                        break
                    }
                }
                runCatching { server.close() }
                serverSocket = null
            } catch (e: IOException) {
                if (running) Log.w(TAG, "listen failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(peerId: String?) {
        // Distinguish the two reasons we cannot proceed. A single vague "Bluetooth unavailable"
        // made a real bug (no Context) look like a hardware problem on the user's phone.
        if (appContext == null) {
            state(Transport.State.ERROR, "no application context — transport was built wrongly")
            return
        }
        val adapter = adapter()
        if (adapter == null) {
            state(Transport.State.ERROR, "this device reports no Bluetooth adapter")
            return
        }
        if (!adapter.isEnabled) {
            state(Transport.State.ERROR, "Bluetooth is switched off — turn it on and retry")
            return
        }
        if (!hasPermissions()) {
            state(Transport.State.ERROR, "Bluetooth permission not granted")
            return
        }

        val bonded: Set<BluetoothDevice> = adapter.bondedDevices ?: emptySet()
        if (bonded.isEmpty()) {
            state(
                Transport.State.ERROR,
                "no paired device — pair the two phones in Android Settings first",
            )
            return
        }

        // If the caller named a peer, use only that one. Otherwise try them all in order.
        val targets = if (peerId != null) {
            bonded.filter { it.address == peerId }.ifEmpty {
                state(Transport.State.ERROR, "device $peerId is no longer paired")
                return
            }
        } else {
            bonded.toList()
        }

        running = true
        val label = targets.firstOrNull()?.name ?: peerId ?: "${targets.size} device(s)"
        state(Transport.State.CONNECTING, "connecting to $label")

        thread(name = "bt-connect") {
            val started = android.os.SystemClock.elapsedRealtime()
            // Each attempt can block for the full RFCOMM connect timeout (up to ~12 s) when the
            // far end is not listening, so attempts are expensive. Five is plenty now that the
            // listener starts accepting before its models finish loading.
            val maxAttempts = 5
            for (attempt in 1..maxAttempts) {
                if (!running) return@thread
                for (device in targets) {
                    if (!running || isConnected) return@thread
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    try {
                        // Try every known connect method, not just the fragile SDP one.
                        val s = openSocket(device, adapter)
                        if (s == null) {
                            val took = android.os.SystemClock.elapsedRealtime() - t0
                            state(
                                Transport.State.CONNECTING,
                                "attempt $attempt/$maxAttempts to ${device.name}: all connect " +
                                    "methods failed after ${took} ms — is the app open on the " +
                                    "other phone?",
                            )
                            continue
                        }
                        val took = android.os.SystemClock.elapsedRealtime() - t0
                        Log.i(
                            TAG,
                            "CONNECTED to ${device.name} in ${took} ms " +
                                "(attempt $attempt, ${android.os.SystemClock.elapsedRealtime() - started} ms total)",
                        )
                        attach(s, device.name ?: device.address)
                        return@thread
                    } catch (e: Throwable) {
                        val took = android.os.SystemClock.elapsedRealtime() - t0
                        // Record the ACTUAL reason, not just "failed". Without the exception text a
                        // log line like "failed after 0s" is unactionable — 0 ms means the socket
                        // was refused immediately (the far end is not listening on our UUID),
                        // whereas a ~12 s failure means the far end is unreachable. Very different
                        // problems, and only the message tells them apart.
                        val why = "${e::class.simpleName}: ${e.message}"
                        Log.w(TAG, "attempt $attempt/$maxAttempts to ${device.name} failed after $took ms — $why")
                        state(
                            Transport.State.CONNECTING,
                            "attempt $attempt/${maxAttempts} to ${device.name} failed after " +
                                "${took} ms — $why",
                        )
                    }
                }
                if (attempt < maxAttempts) {
                    // No 2 s sleep here any more. connect() already blocks for its own timeout,
                    // so a long sleep on top was pure dead time — up to 10 s of the total wait.
                    // A short pause only avoids hammering the stack back-to-back.
                    try {
                        Thread.sleep(300)
                    } catch (e: InterruptedException) {
                        return@thread
                    }
                }
            }
            val total = android.os.SystemClock.elapsedRealtime() - started
            state(
                Transport.State.ERROR,
                "could not reach $label after ${total} ms. Make sure the OTHER phone has this " +
                    "app open and is showing \"LISTENING\" on its banner — if it is, tap its " +
                    "DEVICES button and connect to this phone instead.",
            )
        }
    }

    /**
     * Cancel discovery and give the stack a moment to actually stop.
     *
     * `cancelDiscovery()` is asynchronous — it returns before discovery has really stopped.
     * Starting a connect while the stack is still tearing discovery down is a documented cause of
     * instant connect failures. BlueComm (the reference implementation of the SDP fallback) sleeps
     * 300 ms here, and so do we. This is cheap insurance against a race that costs a whole retry.
     */
    @SuppressLint("MissingPermission")
    private fun settleDiscovery(adapter: BluetoothAdapter) {
        runCatching { adapter.cancelDiscovery() }
        try {
            Thread.sleep(DISCOVERY_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Build a socket to [device], trying every method that is known to work in the field.
     *
     * WHY THIS EXISTS. `createRfcommSocketToServiceRecord` performs an **SDP lookup** to discover
     * which RFCOMM channel the remote's service sits on. When that lookup fails it throws almost
     * immediately — observed as "attempt failed after 0 ms" in a real log on 21 Sep 2026 — and the
     * message is usually `Service discovery failed`. SDP is the fragile part: it can fail because
     * the remote's SDP cache is stale, because discovery was interrupted, or because the remote is
     * not advertising the service yet.
     *
     * The documented escape hatch is to **skip SDP entirely and connect straight to an RFCOMM
     * channel number** — `createRfcommSocket(int)`, reached by reflection because it is hidden API.
     * Channel 1 is the conventional default for the first SPP service.
     *
     * So we try, in order:
     *   1. secure SDP      — the normal path, works when the remote advertises properly
     *   2. insecure SDP    — same lookup, no pairing requirement
     *   3. raw channels    — bypasses SDP completely; the brute-force fallback that makes this
     *                        work on the phones where SDP misbehaves
     *
     * Returns null if every method failed; the caller then reports and retries.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, adapter: BluetoothAdapter): BluetoothSocket? {
        // 1 + 2: SDP-based.
        for ((label, factory) in listOf<Pair<String, () -> BluetoothSocket>>(
            "secure-SDP" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "insecure-SDP" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )) {
            val s = try {
                factory()
            } catch (e: Throwable) {
                Log.w(TAG, "  $label: could not build socket — ${e.message}")
                continue
            }
            try {
                settleDiscovery(adapter)
                s.connect()
                Log.i(TAG, "  $label: connected")
                return s
            } catch (e: Throwable) {
                Log.w(TAG, "  $label: ${e::class.simpleName}: ${e.message}")
                runCatching { s.close() }
            }
        }

        // 3: raw RFCOMM channel, bypassing SDP. Channel 1 first — that is what the first SPP
        // service normally lands on — then a short sweep in case the remote picked another.
        for (channel in listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)) {
            val s = try {
                device.javaClass
                    .getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, channel) as BluetoothSocket
            } catch (e: Throwable) {
                // Reflection is blocked on some OEM builds. If it fails once it will fail every
                // time, so stop the sweep rather than logging fifteen identical errors.
                Log.w(TAG, "  raw channel: reflection unavailable — ${e.message}")
                break
            }
            try {
                settleDiscovery(adapter)
                s.connect()
                Log.i(TAG, "  raw channel $channel: connected")
                return s
            } catch (e: Throwable) {
                runCatching { s.close() }
            }
        }

        return null
    }

    /** Wire up an established socket and start the read loop. */
    private fun attach(s: BluetoothSocket, peerName: String) {
        socket = s
        isConnected = true
        connectedPeerName = peerName
        connectedAddress = runCatching { s.remoteDevice?.address }.getOrNull()
        state(Transport.State.CONNECTED, "connected to $peerName")

        reader = thread(name = "bt-read") {
            try {
                val input = DataInputStream(s.inputStream)
                while (running) {
                    // length-prefixed framing
                    val len = input.readInt()
                    if (len <= 0 || len > 64 * 1024) {
                        state(Transport.State.ERROR, "bad frame length $len")
                        break
                    }
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    onMessage?.invoke(buf)
                }
            } catch (e: IOException) {
                if (running) state(Transport.State.ERROR, "link closed: ${e.message}")
            } finally {
                isConnected = false
                connectedPeerName = null
                connectedAddress = null
                if (running) {
                    state(Transport.State.IDLE, "disconnected")
                    // THE LISTENER SIDE COMES BACK IMMEDIATELY.
                    //
                    // The accept loop exits as soon as one connection is made, so before this a
                    // dropped session left the listener phone accepting nothing until the
                    // watchdog noticed — up to 15 s in which every reconnect attempt from the
                    // other phone was refused instantly. Now both sides settle back into
                    // listening the moment the session drops, which is what lets the other
                    // phone's automatic redial land on a live socket.
                    startAcceptLoop()
                }
            }
        }
    }

    @Synchronized
    override fun send(payload: ByteArray): Boolean {
        val s = socket
        if (s == null || !isConnected) {
            Log.w(TAG, "send() while not connected — not sent (${payload.size} B)")
            return false
        }
        return try {
            val out = DataOutputStream(s.outputStream)
            out.writeInt(payload.size)   // length prefix
            out.write(payload)
            out.flush()
            Log.i(TAG, "sent ${payload.size} B")
            true
        } catch (e: IOException) {
            isConnected = false
            state(Transport.State.ERROR, "send failed: ${e.message}")
            false
        }
    }

    override fun stop() {
        running = false
        isConnected = false
        connectedPeerName = null
        connectedAddress = null
        watchdog?.interrupt()
        watchdog = null
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        serverSocket = null
        socket = null
        reader = null
        acceptor = null
        state(Transport.State.IDLE, "stopped")
    }

    /**
     * Restart the accept loop if it has died.
     *
     * Bluetooth stacks do silently stop listening — a socket gets reaped, accept throws once and
     * the loop exits. When that happens the listener phone is dead until the app is restarted, and
     * the symptom on the other phone is exactly "it takes forever to connect and then never does".
     *
     * Adapted from bitchat (`BluetoothGattClientManager.kt:320-348`), which runs the same watchdog
     * over its scanner.
     */
    private fun startWatchdog() {
        if (watchdog?.isAlive == true) return
        watchdog = thread(name = "bt-watchdog") {
            while (running) {
                try {
                    Thread.sleep(WATCHDOG_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    return@thread
                }
                if (!running || isConnected) continue
                if (acceptor?.isAlive != true) {
                    Log.w(TAG, "accept loop died — restarting it")
                    runCatching { serverSocket?.close() }
                    serverSocket = null
                    startAcceptLoop()
                }
            }
        }
    }
}
