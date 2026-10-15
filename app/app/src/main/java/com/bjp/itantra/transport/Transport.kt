package com.bjp.itantra.transport

/**
 * The link between the two phones. One contract, two implementations.
 *
 * The PS allows "Wi-Fi/Bluetooth", so this is an interface rather than a hardcoded choice.
 * That means we build Bluetooth first (stable API, vendor-independent) and add Wi-Fi Direct
 * later without touching a single line of speech code.
 *
 * Payload reality check: one sentence is 50–250 bytes. We need roughly 1 kbit/s.
 * Bluetooth Classic gives ~2 Mbit/s, Wi-Fi Direct ~250 Mbit/s. Both are thousands of times
 * more than required, so the choice is decided by *reliability*, not bandwidth.
 */
/** A device you could connect to, for showing in the UI. */
data class PeerInfo(
    /** Stable identifier — a MAC address for Bluetooth, a device address for Wi-Fi Direct. */
    val id: String,
    /** Human-readable name, falling back to the id when the OS gives us nothing. */
    val name: String,
    /** True when the OS already trusts this device (Bluetooth: bonded). */
    val paired: Boolean,
    /** True for the peer we are currently connected to, if any. */
    val connected: Boolean = false,
)

interface Transport {

    /** Human-readable name for the UI. */
    val name: String

    /** True when a peer is connected and [send] will work. */
    val isConnected: Boolean

    /** Name of the connected peer, or null. */
    val connectedPeerName: String?

    /**
     * Devices we could connect to. Bluetooth returns bonded devices; Wi-Fi Direct returns
     * discovered peers. Empty when the transport has nothing to offer or lacks permission.
     */
    fun availablePeers(): List<PeerInfo>

    /**
     * Start listening for an incoming connection. Call on the LISTENER phone.
     * Returns immediately; connection arrives via [onMessage] / [onStateChange].
     */
    fun startListening()

    /**
     * Connect to the peer found by [startListening]. Call on the SPEAKER phone.
     * @param peerId when supplied, connect to exactly this peer; otherwise try them all.
     */
    fun connectToPeer(peerId: String? = null)

    /**
     * Send one framed message.
     *
     * @return true when the packet was handed to a connected socket; false when it was NOT sent
     *         (no link, or the write failed). Callers must buffer and retry — see [Outbox]. The
     *         old signature returned Unit and dropped silently, so the speaker's screen said
     *         "Sent" while the other phone heard nothing.
     */
    fun send(payload: ByteArray): Boolean

    /** Stop everything and release sockets. Safe to call twice. */
    fun stop()

    // --- callbacks, set before starting ---
    var onMessage: ((ByteArray) -> Unit)?
    var onStateChange: ((State, String) -> Unit)?

    enum class State { IDLE, DISCOVERING, CONNECTING, CONNECTED, ERROR }
}

/**
 * Picks a transport at runtime. Both are compiled in, so a judge can watch the fallback work
 * live rather than take our word for it.
 */
enum class TransportKind(val displayName: String) {
    BLUETOOTH("Bluetooth Classic (RFCOMM)"),
    WIFI_DIRECT("Wi-Fi Direct");

    /**
     * Build this transport.
     *
     * A CONTEXT IS MANDATORY. An earlier version took no argument, so both transports were
     * constructed with `appContext = null`. That produced the worst possible failure mode:
     *
     *   - Bluetooth: `adapter()` returned null, so the app reported "Bluetooth unavailable"
     *     even on a phone with Bluetooth switched on and working. Every connect attempt failed
     *     with no way to tell why.
     *   - Wi-Fi Direct: `ensureManager()` hit `appContext ?: return false` and returned
     *     **silently** — no error, no log, nothing happened at all.
     *
     * Passing the application context is what makes either transport able to reach the system
     * services it needs. Do not remove this parameter.
     */
    fun create(context: android.content.Context): Transport {
        val app = context.applicationContext
        return when (this) {
            BLUETOOTH -> BluetoothRfcommTransport(app)
            WIFI_DIRECT -> WifiDirectTransport(app)
        }
    }
}
