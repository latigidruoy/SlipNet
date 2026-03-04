package app.slipnet.tunnel

import app.slipnet.util.AppLog as Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * A known DoH server with name, URL, and pre-resolved IP addresses (IPv4 + IPv6).
 * Pre-resolved IPs bypass ISP DNS when the app is excluded from VPN via addDisallowedApplication.
 */
data class DohServer(
    val name: String,
    val url: String,
    val ips: List<String> = emptyList()
)

/**
 * Complete list of known DoH servers.
 * IPs sourced from Intra app (Jigsaw/Google) + additional providers.
 */
val DOH_SERVERS = listOf(
    // --- Major global providers ---
    DohServer(
        "Google", "https://dns.google/dns-query",
        listOf("8.8.8.8", "8.8.4.4", "2001:4860:4860::8888", "2001:4860:4860::8844")
    ),
    DohServer(
        "Cloudflare", "https://cloudflare-dns.com/dns-query",
        listOf("1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001")
    ),
    DohServer(
        "Cloudflare 1.1.1.1", "https://1.1.1.1/dns-query",
        listOf("1.1.1.1")
    ),
    DohServer(
        "Quad9", "https://dns.quad9.net/dns-query",
        listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::fe:9")
    ),
    DohServer(
        "OpenDNS", "https://doh.opendns.com/dns-query",
        listOf("146.112.41.2", "2620:119:fc::2")
    ),
    DohServer(
        "CleanBrowsing", "https://doh.cleanbrowsing.org/doh/security-filter/",
        listOf("185.228.168.9", "185.228.169.9", "2a0d:2a00:1::2", "2a0d:2a00:2::2")
    ),
    DohServer(
        "Canadian Shield", "https://private.canadianshield.cira.ca/dns-query",
        listOf("149.112.121.10", "149.112.122.10", "2620:10a:80bb::10", "2620:10a:80bc::10")
    ),
    // --- Privacy-focused ---
    DohServer(
        "Mullvad", "https://base.dns.mullvad.net/dns-query",
        listOf("194.242.2.2", "2a07:e340::2")
    ),
    DohServer(
        "Applied Privacy", "https://doh.applied-privacy.net/query"
    ),
    DohServer(
        "Digitale Gesellschaft", "https://dns.digitale-gesellschaft.ch/dns-query",
        listOf("185.95.218.42", "185.95.218.43", "2a05:fc84::42", "2a05:fc84::43")
    ),
    DohServer(
        "DNS.SB", "https://doh.dns.sb/dns-query",
        listOf("185.222.222.222", "45.11.45.11")
    ),
    DohServer(
        "42l Association", "https://doh.42l.fr/dns-query",
        listOf("45.155.171.163", "2a09:6382:4000:3:45:155:171:163")
    ),
    // --- Regional ---
    DohServer(
        "Andrews & Arnold", "https://dns.aa.net.uk/dns-query",
        listOf("217.169.20.22", "217.169.20.23", "2001:8b0::2022", "2001:8b0::2023")
    ),
    DohServer("IIJ Japan", "https://public.dns.iij.jp/dns-query"),
    // --- Additional ---
    DohServer(
        "DNS for Family", "https://dns-doh.dnsforfamily.com/dns-query",
        listOf("78.47.64.161")
    ),
    DohServer("Rethink DNS", "https://sky.rethinkdns.com/dns-query"),
    DohServer("JoinDNS4EU", "https://unfiltered.joindns4.eu/dns-query"),
    // --- Ad-blocking / filtering variants ---
    DohServer(
        "AdGuard DNS", "https://dns.adguard.com/dns-query",
        listOf("94.140.14.14", "94.140.15.15")
    ),
    DohServer(
        "AdGuard Unfiltered", "https://unfiltered.adguard-dns.com/dns-query",
        listOf("94.140.14.140", "94.140.14.141")
    ),
    DohServer(
        "Cloudflare Security", "https://security.cloudflare-dns.com/dns-query",
        listOf("1.1.1.2", "1.0.0.2")
    ),
    DohServer(
        "Cloudflare Family", "https://family.cloudflare-dns.com/dns-query",
        listOf("1.1.1.3", "1.0.0.3")
    ),
    DohServer(
        "CleanBrowsing Family", "https://doh.cleanbrowsing.org/doh/family-filter/",
        listOf("185.228.168.168", "185.228.169.168")
    ),
    DohServer(
        "DNS4EU Protective", "https://protective.joindns4.eu/dns-query",
        listOf("86.54.11.1", "86.54.11.201")
    ),
    // --- Additional global providers ---
    DohServer(
        "Cisco Umbrella", "https://doh.umbrella.com/dns-query",
        listOf("208.67.222.222", "208.67.220.220")
    ),
    DohServer(
        "Mozilla DNS", "https://mozilla.cloudflare-dns.com/dns-query",
        listOf("104.16.248.249", "104.16.249.249")
    ),
    DohServer(
        "Mullvad DoH", "https://doh.mullvad.net/dns-query",
        listOf("194.242.2.2", "194.242.2.3")
    ),
    DohServer(
        "AliDNS", "https://dns.alidns.com/dns-query",
        listOf("223.5.5.5", "223.6.6.6")
    ),
    DohServer(
        "Control D", "https://freedns.controld.com/p0",
        listOf("76.76.2.0", "76.76.10.0")
    ),
    DohServer(
        "UncensoredDNS", "https://unicast.uncensoreddns.org/dns-query",
        listOf("91.239.100.100", "89.233.43.71")
    ),
    DohServer(
        "ComSS", "https://dns.comss.one/dns-query",
        listOf("95.217.205.213")
    ),
)

/**
 * Lightweight UDP-to-DoH DNS proxy.
 *
 * Listens on a local UDP port and forwards DNS queries to a DoH server
 * via HTTPS, then returns the responses back over UDP.
 *
 * Note: DNSTT's Go library supports DoH natively (via https:// prefix),
 * so this proxy is typically not needed for DNSTT+DoH. It remains available
 * as a fallback or for other use cases.
 *
 * Lifecycle: start before consumer, stop after consumer.
 */
object DnsDoHProxy {
    private const val TAG = "DnsDoHProxy"
    private const val MAX_DNS_PACKET = 4096

    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    @Volatile
    private var httpClient: OkHttpClient? = null
    private var dohUrl: String = ""

    /** Pre-resolved IP map from [DOH_SERVERS]. */
    private val serverIpMap: Map<String, List<String>> by lazy {
        DOH_SERVERS
            .filter { it.ips.isNotEmpty() }
            .associate { server ->
                try { java.net.URL(server.url).host } catch (_: Exception) { "" } to server.ips
            }
            .filterKeys { it.isNotEmpty() }
    }

    /**
     * Start the UDP-to-DoH proxy.
     * @return the local UDP port the proxy is listening on, or -1 on failure.
     */
    fun start(dohUrl: String): Int {
        stop()

        this.dohUrl = dohUrl

        httpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    val preResolved = serverIpMap[hostname]?.mapNotNull { ip ->
                        try { InetAddress.getByName(ip) } catch (_: Exception) { null }
                    } ?: emptyList()
                    val systemResolved = try {
                        Dns.SYSTEM.lookup(hostname)
                    } catch (_: Exception) { emptyList() }
                    val combined = (preResolved + systemResolved).distinctBy { it.hostAddress }
                    if (combined.isNotEmpty()) return combined
                    throw java.net.UnknownHostException("No addresses for $hostname")
                }
            })
            .build()

        val pool = ThreadPoolExecutor(
            4, 32, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(256)
        )
        pool.allowCoreThreadTimeOut(true)
        executor = pool

        return try {
            val ds = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            socket = ds
            val port = ds.localPort
            running.set(true)

            thread = Thread({
                Log.i(TAG, "UDP-to-DoH proxy started on 127.0.0.1:$port -> $dohUrl")
                val buf = ByteArray(MAX_DNS_PACKET)
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        ds.receive(packet)
                        val query = buf.copyOfRange(0, packet.length)
                        val clientAddr = packet.address
                        val clientPort = packet.port

                        executor?.execute {
                            try {
                                val response = forwardViaDoH(query)
                                if (response != null) {
                                    val resp = DatagramPacket(response, response.size, clientAddr, clientPort)
                                    ds.send(resp)
                                }
                            } catch (e: Exception) {
                                if (running.get()) {
                                    Log.w(TAG, "DoH forward error: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get()) {
                            Log.w(TAG, "Receive error: ${e.message}")
                        }
                    }
                }
            }, "DnsDoHProxy").apply {
                isDaemon = true
                start()
            }

            Log.i(TAG, "Started on port $port for $dohUrl")
            port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start: ${e.message}")
            stop()
            -1
        }
    }

    fun stop() {
        running.set(false)
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        thread?.interrupt()
        thread = null
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        try {
            httpClient?.connectionPool?.evictAll()
            httpClient?.dispatcher?.executorService?.shutdown()
        } catch (_: Exception) {}
        httpClient = null
        Log.d(TAG, "Stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun forwardViaDoH(query: ByteArray): ByteArray? {
        val client = httpClient ?: return null
        val body = query.toRequestBody("application/dns-message".toMediaType())
        val request = Request.Builder()
            .url(dohUrl)
            .post(body)
            .header("Accept", "application/dns-message")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                return response.body?.bytes()
            } else {
                Log.w(TAG, "DoH HTTP ${response.code}")
                return null
            }
        }
    }
}
