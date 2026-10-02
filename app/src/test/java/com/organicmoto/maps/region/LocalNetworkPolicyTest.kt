package com.organicmoto.maps.region

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkPolicyTest {

    @Test
    fun acceptsLoopbackAndLocalNames() {
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://localhost:8080"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://127.0.0.1:8080"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://maps.local:8080"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://[::1]:8080"))
    }

    @Test
    fun acceptsPrivateIpv4Ranges() {
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://10.0.0.5"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://172.16.5.5:9000"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://172.31.255.254"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://192.168.1.20"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://169.254.10.10"))
    }

    @Test
    fun acceptsUniqueLocalAndLinkLocalIpv6() {
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://[fd00::1]:8080"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://[fe80::1]"))
        assertTrue(LocalNetworkPolicy.isLocalAddress("http://[fc00:1234::5]"))
    }

    @Test
    fun rejectsPublicAddresses() {
        assertFalse(LocalNetworkPolicy.isLocalAddress("http://example.com"))
        assertFalse(LocalNetworkPolicy.isLocalAddress("http://8.8.8.8"))
        assertFalse(LocalNetworkPolicy.isLocalAddress("http://172.32.0.1"))
        assertFalse(LocalNetworkPolicy.isLocalAddress("http://11.0.0.1"))
        assertFalse(LocalNetworkPolicy.isLocalAddress("http://[2001:db8::1]"))
        assertFalse(LocalNetworkPolicy.isLocalAddress(""))
        assertFalse(LocalNetworkPolicy.isLocalAddress("not a url"))
    }
}
