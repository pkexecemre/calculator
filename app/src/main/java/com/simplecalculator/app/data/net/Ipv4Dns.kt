package com.simplecalculator.app.data.net

import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns

/**
 * The media CDN declines signed URLs when the connection arrives over IPv6 in
 * some networks, so resolve A records first and only fall back to AAAA.
 */
class Ipv4Dns(private val delegate: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val v4 = all.filterIsInstance<Inet4Address>()
        return if (v4.isNotEmpty()) v4 else all
    }
}
