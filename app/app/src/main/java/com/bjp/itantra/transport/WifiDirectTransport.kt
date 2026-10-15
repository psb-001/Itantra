package com.bjp.itantra.transport

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Fallback transport: Wi-Fi Direct (peer-to-peer).
 *
 * True P2P — no router, no hotspot, no internet. One phone becomes the group owner and hosts a
 * plain socket; the other connects to it.
 *
 * WHY IT IS THE FALLBACK, NOT THE DEFAULT:
 *  - `WifiP2pManager` is implemented inconsistently across Android vendors. Behaviour that works
 *    on a Pixel can silently fail on Xiaomi, Realme or Samsung. That is a demo risk we do not
 *    need, because Bluetooth already covers our payload with 2,000x headroom.
 *  - On Android 10+ you need ACCESS_FINE_LOCATION just to *discover* peers. Not a bug — a
 *    documented platform requirement, and a confusing one.
 *  - Group-owner negotiation adds seconds of setup before any data moves.
 *
 * It is kept because the PS explicitly allows Wi-Fi, and because having both lets us claim
 * "Wi-Fi Direct with Bluetooth fallback" honestly and demonstrate the fallback live.
 *
 * SOCKET PORT: 8988, chosen arbitrarily but fixed on both sides.
 */
class WifiDirectTransport(
    private val appContext: Context? = null,
) : Transport {

    companion object {
        private const val TAG = "iTantra/WiFiDirect"
        private const val PORT = 8988
    }

    override val name = TransportKind.WIFI_DIRECT.displayName

    @Volatile
    override var isConnected: Boolean = false
        private set

    @Volatile
    override var connectedPeerName: String? = null
        private set

    override var onMessage: ((ByteArray) -> Unit)? = null
    override var onStateChange: ((Transport.State, String) -> Unit)? = null

    /** Peers found by the last discovery run. Populated asynchronously by startListening(). */
    @Volatile
    private var discovered: List<PeerInfo> = emptyList()

    /** True while we are waiting for discovery results before issuing connect(). */
    @Volatile
    private var pendingConnect = false

    /** The specific peer we were asked for, or null for "any". */
    @Volatile
    private var wantPeerId: String? = null

    override fun availablePeers(): List<PeerInfo> = discovered

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var serverSocket: ServerSocket? = null
    private var socket: Socket? = null
    private var receiver: BroadcastReceiver? = null

    @Volatile
    private var running = false

    /** Set true when this device is the group owner (host). */
    @Volatile
    private var isGroupOwner = false

    /**
     * The host address this device last connected to as a client. Kept so a dropped socket can be
     * re-dialled without waiting for the whole group to re-form.
     */
    @Volatile
    private var lastHostAddress: String? = null

    private fun state(s: Transport.State, msg: String) {
        Log.i(TAG, "$s — $msg")
        onStateChange?.invoke(s, msg)
    }

    fun hasPermissions(): Boolean {
        val ctx = appContext ?: return false
        // Exactly ONE of these is required, depending on the API level. An earlier version
        // demanded BOTH on API 33+, but the manifest caps ACCESS_FINE_LOCATION at
        // maxSdkVersion="32", so on Android 13+ it can never be granted — the check always failed
        // and Wi-Fi Direct was structurally unreachable.
        val needed = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> emptyList()
        }
        return needed.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun ensureManager(): Boolean {
        // Fail LOUDLY. This used to be `appContext ?: return false`, which returned silently —
        // no state, no log, nothing. A transport that does nothing without saying so is the
        // hardest possible thing to debug.
        val ctx = appContext
        if (ctx == null) {
            state(Transport.State.ERROR, "no application context — transport was built wrongly")
            return false
        }
        if (manager == null) {
            manager = ctx.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            channel = manager?.initialize(ctx, Looper.getMainLooper(), null)
        }
        if (manager == null || channel == null) {
            state(Transport.State.ERROR, "Wi-Fi Direct is not supported on this device")
            return false
        }
        return true
    }

    private fun registerReceiver() {
        val ctx = appContext ?: return
        if (receiver != null) return

        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        // THE MISSING PIECE FOR WI-FI DIRECT.
                        //
                        // connectToPeer() used to call requestPeers() exactly once, immediately.
                        // requestPeers() returns only what discovery has ALREADY found, and
                        // discovery takes 10-30 s — so on a fresh start the list was almost always
                        // empty and it reported "no Wi-Fi Direct peer found" without ever really
                        // looking. Now we start discovery, and connect when the peer list arrives.
                        Log.i(TAG, "peer list changed — evaluating")
                        if (pendingConnect) {
                            val m = manager ?: return
                            m.requestPeers(channel) { list ->
                                val peers = list?.deviceList.orEmpty()
                                discovered = peers.map {
                                    PeerInfo(
                                        id = it.deviceAddress,
                                        name = it.deviceName ?: it.deviceAddress,
                                        paired = false,
                                        connected = false,
                                    )
                                }
                                Log.i(TAG, "discovered ${peers.size} peer(s)")
                                if (peers.isEmpty()) return@requestPeers
                                val chosen = if (wantPeerId != null) {
                                    peers.firstOrNull { it.deviceAddress == wantPeerId }
                                } else {
                                    peers.firstOrNull()
                                }
                                if (chosen == null) {
                                    state(Transport.State.ERROR, "peer $wantPeerId not found")
                                    pendingConnect = false
                                    return@requestPeers
                                }
                                pendingConnect = false
                                state(Transport.State.CONNECTING, "joining ${chosen.deviceName}")
                                val cfg = WifiP2pConfig().apply {
                                    deviceAddress = chosen.deviceAddress
                                }
                                m.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                                    override fun onSuccess() =
                                        state(Transport.State.CONNECTING, "invitation sent")
                                    override fun onFailure(reason: Int) =
                                        state(Transport.State.ERROR, "connect failed (reason=$reason)")
                                })
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val m = manager ?: return
                        m.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                            if (info != null && info.groupFormed) {
                                isGroupOwner = info.isGroupOwner
                                val host = info.groupOwnerAddress?.hostAddress
                                state(
                                    Transport.State.CONNECTED,
                                    "group formed — we are ${if (isGroupOwner) "owner" else "client"} at $host",
                                )
                                if (isGroupOwner) startHostSocket() else connectClientSocket(host)
                            } else {
                                isConnected = false
                                state(Transport.State.IDLE, "group dissolved")
                            }
                        }
                    }
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val enabled = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE, -1
                        ) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                        if (!enabled) state(Transport.State.ERROR, "Wi-Fi P2P is disabled")
                    }
                }
            }
        }
        receiver = r
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        // RECEIVER_NOT_EXPORTED is required on API 33+ for a receiver registered this way.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(r, filter)
        }
    }

    @SuppressLint("MissingPermission")
    override fun startListening() {
        if (!ensureManager()) return
        if (!hasPermissions()) {
            state(Transport.State.ERROR, "Wi-Fi Direct permissions not granted")
            return
        }
        running = true
        registerReceiver()
        state(Transport.State.DISCOVERING, "discovering peers")

        val m = manager ?: return
        m.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = state(Transport.State.DISCOVERING, "discovery started")
            override fun onFailure(reason: Int) =
                state(Transport.State.ERROR, "discoverPeers failed (reason=$reason)")
        })
    }

    @SuppressLint("MissingPermission")
    override fun connectToPeer(peerId: String?) {
        if (!ensureManager()) return
        if (!hasPermissions()) {
            state(Transport.State.ERROR, "Wi-Fi Direct permissions not granted")
            return
        }
        running = true
        registerReceiver()
        state(Transport.State.DISCOVERING, "discovering peers — this takes 10-30 s")

        val m = manager ?: return

        // Start discovery, then wait for WIFI_P2P_PEERS_CHANGED_ACTION to actually connect.
        // Calling requestPeers() straight away returns an empty list on a fresh start.
        wantPeerId = peerId
        pendingConnect = true
        m.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "discovery started, waiting for peers")
            }

            override fun onFailure(reason: Int) {
                pendingConnect = false
                state(Transport.State.ERROR, "discoverPeers failed (reason=$reason)")
            }
        })

        // Belt and braces: if the peers-changed broadcast never arrives, try once after 8 s.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (pendingConnect && running && !isConnected) {
                Log.i(TAG, "no peers-changed broadcast yet — requesting peers directly")
                m.requestPeers(channel) { list ->
                    discovered = list?.deviceList.orEmpty().map {
                        PeerInfo(
                            id = it.deviceAddress,
                            name = it.deviceName ?: it.deviceAddress,
                            paired = false,
                            connected = false,
                        )
                    }
                    if (discovered.isEmpty()) {
                        pendingConnect = false
                        state(
                            Transport.State.ERROR,
                            "no Wi-Fi Direct peer found. Both phones must have Wi-Fi on, and the " +
                                "other one must be in LISTENER mode.",
                        )
                    }
                }
            }
        }, 8000)
    }

    /** Group owner side: host a server socket and accept one client. */
    private fun startHostSocket() {
        thread(name = "p2p-host") {
            try {
                // Close any previous server socket first: after accept() returns, the old one is
                // still bound to the port, and re-hosting on it fails with EADDRINUSE — which
                // would make every reconnect attempt after a drop look impossible.
                runCatching { serverSocket?.close() }
                serverSocket = null
                val ss = ServerSocket(PORT)
                serverSocket = ss
                Log.i(TAG, "hosting on port $PORT")
                val client = ss.accept()
                attach(client)
            } catch (e: IOException) {
                if (running) state(Transport.State.ERROR, "host socket failed: ${e.message}")
            }
        }
    }

    /** Client side: connect to the group owner. Retries while the group settles. */
    private fun connectClientSocket(host: String?) {
        if (host == null) {
            state(Transport.State.ERROR, "group owner address unavailable")
            return
        }
        lastHostAddress = host
        thread(name = "p2p-client") {
            var attempts = 0
            while (running && attempts < 10) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, PORT), 3000)
                    attach(s)
                    return@thread
                } catch (e: IOException) {
                    attempts++
                    Log.w(TAG, "connect attempt $attempts to $host:$PORT failed: ${e.message}")
                    Thread.sleep(500)
                }
            }
            state(Transport.State.ERROR, "could not reach group owner at $host:$PORT")
        }
    }

    private fun attach(s: Socket) {
        socket = s
        isConnected = true
        connectedPeerName = s.inetAddress?.hostAddress ?: "peer"
        state(Transport.State.CONNECTED, "socket open to ${s.inetAddress?.hostAddress}")

        thread(name = "p2p-read") {
            try {
                val input = DataInputStream(s.getInputStream())
                while (running) {
                    val len = input.readInt()          // same length-prefix framing as Bluetooth
                    if (len <= 0 || len > 64 * 1024) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    onMessage?.invoke(buf)
                }
            } catch (e: IOException) {
                if (running) state(Transport.State.ERROR, "link closed: ${e.message}")
            } finally {
                isConnected = false
                connectedPeerName = null
                runCatching { socket?.close() }
                socket = null
                if (running) {
                    state(Transport.State.IDLE, "disconnected")
                    // BEST-EFFORT SELF-HEAL from the role this device already knows: the owner
                    // re-hosts on the same port (startHostSocket closes the stale socket first),
                    // the client redials the host it was just talking to. If the whole group
                    // dissolved, the connection-changed broadcast reports it and the app-level
                    // reconnect re-invites.
                    when {
                        isGroupOwner -> startHostSocket()
                        lastHostAddress != null -> connectClientSocket(lastHostAddress)
                    }
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
            val out = DataOutputStream(s.getOutputStream())
            out.writeInt(payload.size)
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
        runCatching { serverSocket?.close() }
        runCatching { socket?.close() }
        runCatching {
            receiver?.let { appContext?.unregisterReceiver(it) }
        }
        serverSocket = null
        socket = null
        receiver = null
        state(Transport.State.IDLE, "stopped")
    }
}
