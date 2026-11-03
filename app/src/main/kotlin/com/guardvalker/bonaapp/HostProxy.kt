package com.guardvalker.bonaapp

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Proxy HTTP CONNECT local (solo loopback) que resuelve [host] directo a [ip] y deja el resto de los
 * nombres al DNS del sistema. El TLS sigue yendo de punta a punta con el certificado real del host.
 */
class HostProxy(private val host: String, private val ip: String) {
    fun start(): Int {
        val server = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "host-proxy") {
            while (true) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { handle(c) }; runCatching { c.close() } }
            }
        }
        return server.localPort
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = i.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(c: Socket) {
        val cin = c.getInputStream(); val cout = c.getOutputStream()
        val first = readLine(cin) ?: return
        while (true) { val l = readLine(cin) ?: return; if (l.isEmpty()) break }
        val parts = first.split(' ')
        if (parts.size < 2 || parts[0] != "CONNECT") {
            cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); return
        }
        val h = parts[1].substringBeforeLast(':')
        val port = parts[1].substringAfterLast(':').toIntOrNull() ?: 443
        val remote = Socket()
        try {
            val addr = if (h.equals(host, ignoreCase = true)) InetAddress.getByName(ip) else InetAddress.getByName(h)
            remote.connect(InetSocketAddress(addr, port), 15000)
        } catch (e: Exception) {
            cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray()); remote.close(); return
        }
        cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()); cout.flush()
        val t = thread(isDaemon = true) { runCatching { pipe(remote.getInputStream(), cout) }; runCatching { c.close() } }
        runCatching { pipe(cin, remote.getOutputStream()) }
        runCatching { remote.close() }; t.join(1000)
    }

    private fun pipe(i: InputStream, o: OutputStream) {
        val buf = ByteArray(16384)
        while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); o.flush() }
    }
}
