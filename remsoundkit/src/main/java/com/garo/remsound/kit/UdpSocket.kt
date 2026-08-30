package com.garo.remsound.kit

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * IPv4 UDP endpoint. The whole RemSound protocol is IPv4 (matching the Windows app's AF_INET
 * sockets), so an address is stored as one [Int] holding the four octets most-significant
 * first (a.b.c.d = a shl 24 or b shl 16 or c shl 8 or d) and the port in host order.
 */
data class UdpEndpoint(val address: Int, val port: Int) {
    val addressString: String
        get() = "${(address ushr 24) and 0xFF}.${(address ushr 16) and 0xFF}." +
            "${(address ushr 8) and 0xFF}.${address and 0xFF}"

    override fun toString(): String = "$addressString:$port"

    fun toInetAddress(): InetAddress = InetAddress.getByAddress(
        byteArrayOf(
            ((address ushr 24) and 0xFF).toByte(),
            ((address ushr 16) and 0xFF).toByte(),
            ((address ushr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
        ),
    )

    companion object {
        fun fromInetAddress(inet: InetAddress, port: Int): UdpEndpoint {
            val bytes = inet.address
            val value = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
            return UdpEndpoint(value, port)
        }

        /** Parses a dotted quad without touching DNS. Null when it is not one. */
        fun parseLiteral(host: String, port: Int): UdpEndpoint? {
            val parts = host.split(".")
            if (parts.size != 4) return null
            var value = 0
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet
            }
            return UdpEndpoint(value, port)
        }

        /**
         * Resolves a hostname or dotted quad to IPv4 endpoints (DNS for relay hostnames,
         * MagicDNS names, and the like). Blocking — call off the main thread.
         */
        fun resolve(host: String, port: Int): List<UdpEndpoint> {
            parseLiteral(host, port)?.let { return listOf(it) }
            return try {
                InetAddress.getAllByName(host)
                    .filterIsInstance<Inet4Address>()
                    .map { fromInetAddress(it, port) }
                    .distinct()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

/**
 * Minimal blocking-receive UDP socket with a dedicated drain thread, mirroring the Windows
 * `NetworkListener` design: one fixed receive buffer reused across calls, packets handed up
 * as (buffer, length, remote) — the callback must copy anything it keeps.
 *
 * Unlike the Apple port there is no kernel arrival timestamp: the JDK's datagram API exposes
 * no `SO_TIMESTAMP` control message, so inter-arrival gaps are timed on this thread. That is
 * reported honestly in the diagnostics panel ("thread-timed"), because a descheduled receive
 * thread makes a burst of on-time packets read as one huge gap.
 */
class UdpSocket(
    private val onPacket: (buffer: ByteArray, length: Int, remote: UdpEndpoint) -> Unit,
    private val onDiagnostic: ((String) -> Unit)? = null,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    /** The locally bound port (useful when binding port 0). */
    var boundPort: Int = 0
        private set

    /**
     * Bind and start the receive thread. `port` 0 lets the OS pick. Broadcast permission is
     * needed to SEND broadcast announcements (discovery).
     */
    @Throws(SocketException::class)
    fun start(port: Int, enableBroadcast: Boolean = false, reuseAddress: Boolean = true) {
        stop()
        val sock = DatagramSocket(null)
        sock.reuseAddress = reuseAddress
        if (enableBroadcast) sock.broadcast = true
        // 1 MB kernel receive buffer — same rationale as the Windows receiver: ride out short
        // render-thread or scheduler stalls without the kernel dropping datagrams.
        try {
            sock.receiveBufferSize = 1024 * 1024
            sock.sendBufferSize = 1024 * 1024
        } catch (_: SocketException) {
            // Best effort; a smaller kernel buffer still works.
        }
        sock.bind(InetSocketAddress(port))
        socket = sock
        boundPort = sock.localPort

        val receiveThread = Thread({ receiveLoop(sock) }, "RemSound.UDPReceive")
        // Network drain feeds the audio path; raise priority above default UI work.
        receiveThread.priority = Thread.MAX_PRIORITY
        receiveThread.isDaemon = true
        receiveThread.start()
        thread = receiveThread
        onDiagnostic?.invoke("UDP socket bound to :$boundPort")
    }

    fun stop() {
        val sock = socket
        socket = null
        sock?.close() // unblocks the receive() in the receive thread
        thread = null
    }

    /** Fire-and-forget UDP send. Returns true when the datagram was handed to the kernel. */
    fun send(data: ByteArray, length: Int, to: UdpEndpoint): Boolean {
        val sock = socket ?: return false
        return try {
            sock.send(DatagramPacket(data, length, to.toInetAddress(), to.port))
            true
        } catch (_: Exception) {
            false
        }
    }

    fun send(data: ByteArray, to: UdpEndpoint): Boolean = send(data, data.size, to)

    private fun receiveLoop(sock: DatagramSocket) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        while (true) {
            try {
                packet.setData(buffer, 0, buffer.size)
                sock.receive(packet)
            } catch (_: Exception) {
                break // socket closed (stop()) or fatal error — exit the thread
            }
            val source = packet.address
            if (source !is Inet4Address) continue
            onPacket(buffer, packet.length, UdpEndpoint.fromInetAddress(source, packet.port))
        }
    }
}

/**
 * Local IPv4 interface enumeration — used for subnet broadcast addresses (discovery) and
 * self-identification.
 */
object NetworkInterfaces {
    /**
     * Subnet-directed broadcast addresses of all up, non-loopback IPv4 interfaces, plus the
     * limited broadcast 255.255.255.255.
     */
    fun broadcastAddresses(port: Int): List<UdpEndpoint> {
        val result = linkedSetOf(UdpEndpoint(-1, port)) // 255.255.255.255
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                if (!nic.isUp || nic.isLoopback) continue
                for (address in nic.interfaceAddresses) {
                    val broadcast = address.broadcast ?: continue
                    if (broadcast !is Inet4Address) continue
                    result.add(UdpEndpoint.fromInetAddress(broadcast, port))
                }
            }
        } catch (_: Exception) {
            // Enumeration can fail transiently on a network change; the limited broadcast
            // above still goes out.
        }
        return result.toList()
    }

    /**
     * Local (non-loopback) IPv4 addresses — used to ignore our own discovery announcements
     * echoed back by the network.
     */
    fun localAddresses(): Set<Int> {
        val result = mutableSetOf<Int>()
        try {
            for (nic in NetworkInterface.getNetworkInterfaces()) {
                for (address in nic.inetAddresses) {
                    if (address is Inet4Address) {
                        result.add(UdpEndpoint.fromInetAddress(address, 0).address)
                    }
                }
            }
        } catch (_: Exception) {
            // Same as above — best effort.
        }
        return result
    }
}
