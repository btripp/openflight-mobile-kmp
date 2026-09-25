// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.prop
import kotlin.test.Test

/**
 * Plan R8d's endpoint policy: cleartext only on the local network, https anywhere, and no
 * credentials, foreign schemes or malformed input. Cases follow the plan's list: public cleartext,
 * credentials, malformed input, unsupported schemes, IPv6 forms, bare hosts with and without ports.
 */
class EndpointPolicyTest {
    private fun allowed(input: String): Endpoint {
        val decision = EndpointPolicy.evaluate(input)
        assertThat(decision, name = input).isInstanceOf(EndpointDecision.Allowed::class)
        return (decision as EndpointDecision.Allowed).endpoint
    }

    private fun assertAllowedUrl(
        input: String,
        expected: String,
    ) {
        assertThat(allowed(input).url("/api/club"), name = input).isEqualTo(expected)
    }

    private fun assertCleartextRefused(input: String) {
        assertThat(EndpointPolicy.evaluate(input), name = input)
            .isInstanceOf(EndpointDecision.CleartextToPublicHost::class)
    }

    private fun assertMalformed(input: String) {
        assertThat(EndpointPolicy.evaluate(input), name = input).isInstanceOf(EndpointDecision.Malformed::class)
    }

    // Local cleartext

    @Test
    fun bareMdnsHostGetsHttpAndTheDefaultPort() {
        assertAllowedUrl("raspberrypi.local", "http://raspberrypi.local:8080/api/club")
    }

    @Test
    fun bareHostWithAPortKeepsIt() {
        assertAllowedUrl("raspberrypi.local:9000", "http://raspberrypi.local:9000/api/club")
    }

    @Test
    fun mdnsNamesAreCaseInsensitiveAndMayEndInADot() {
        assertAllowedUrl("PI.Local.", "http://pi.local:8080/api/club")
    }

    @Test
    fun loopbackNamesAndAddressesAreLocal() {
        assertAllowedUrl("localhost", "http://localhost:8080/api/club")
        assertAllowedUrl("127.0.0.1:8091", "http://127.0.0.1:8091/api/club")
        assertAllowedUrl("127.255.255.254", "http://127.255.255.254:8080/api/club")
    }

    @Test
    fun rfc1918RangesAreLocalAtTheirEdges() {
        assertAllowedUrl("10.0.0.1", "http://10.0.0.1:8080/api/club")
        assertAllowedUrl("10.255.255.255", "http://10.255.255.255:8080/api/club")
        assertAllowedUrl("172.16.0.1", "http://172.16.0.1:8080/api/club")
        assertAllowedUrl("172.31.255.255", "http://172.31.255.255:8080/api/club")
        assertAllowedUrl("192.168.4.1:8080", "http://192.168.4.1:8080/api/club")
        assertAllowedUrl("http://192.168.1.100:8080/", "http://192.168.1.100:8080/api/club")
    }

    @Test
    fun ipv4LinkLocalIsLocal() {
        assertAllowedUrl("169.254.10.20", "http://169.254.10.20:8080/api/club")
    }

    @Test
    fun addressesJustOutsideThePrivateRangesArePublic() {
        assertCleartextRefused("172.15.255.255")
        assertCleartextRefused("172.32.0.1")
        assertCleartextRefused("192.169.0.1")
        assertCleartextRefused("169.255.0.1")
        assertCleartextRefused("11.0.0.1")
        assertCleartextRefused("0.0.0.0")
    }

    // Public cleartext

    @Test
    fun publicIpOverHttpIsRefusedWithAReasonNamingTheHost() {
        val decision = EndpointPolicy.evaluate("http://8.8.8.8")
        assertThat(decision).isEqualTo(EndpointDecision.CleartextToPublicHost("8.8.8.8"))
        assertThat(EndpointPolicy.rejectionReason("http://8.8.8.8"))
            .isEqualTo("http:// only works for a Pi on your local network. Use https:// to reach 8.8.8.8.")
    }

    @Test
    fun publicNamesOverHttpAreRefusedWithOrWithoutAScheme() {
        assertCleartextRefused("example.com")
        assertCleartextRefused("example.com:8080")
        assertCleartextRefused("http://pi.example.com/api")
    }

    @Test
    fun singleLabelNamesAreNotAssumedLocal() {
        // DNS may resolve a bare name anywhere, so only .local names count as the LAN.
        assertCleartextRefused("raspberrypi")
        assertCleartextRefused("raspberrypi:8080")
    }

    @Test
    fun namesThatOnlyLookLocalAreRefused() {
        assertCleartextRefused("local")
        assertCleartextRefused("pi.local.example.com")
        assertCleartextRefused("192.168.1.1.nip.io")
    }

    @Test
    fun ambiguousNumericHostsAreNotTreatedAsPrivateAddresses() {
        // inet_aton reads these as 192.168.1.1 or octal; the policy treats them as names instead.
        assertCleartextRefused("3232235777")
        assertCleartextRefused("192.168.1")
        assertCleartextRefused("0300.0250.1.1")
        assertCleartextRefused("192.168.001.001")
    }

    // HTTPS

    @Test
    fun httpsIsAllowedForAnyHostWithoutAnInventedPort() {
        assertAllowedUrl("https://pi.example.com", "https://pi.example.com/api/club")
        assertAllowedUrl("https://8.8.8.8:8443", "https://8.8.8.8:8443/api/club")
        assertAllowedUrl("HTTPS://Pi.Example.com", "https://pi.example.com/api/club")
    }

    @Test
    fun httpsToALanHostIsAllowed() {
        assertAllowedUrl("https://raspberrypi.local:8080", "https://raspberrypi.local:8080/api/club")
    }

    // Credentials

    @Test
    fun credentialsAreRefusedOnEveryScheme() {
        assertThat(EndpointPolicy.evaluate("http://user:pass@192.168.1.5:8080"))
            .isEqualTo(EndpointDecision.Credentials)
        assertThat(EndpointPolicy.evaluate("https://user@pi.example.com")).isEqualTo(EndpointDecision.Credentials)
        assertThat(EndpointPolicy.evaluate("admin:secret@pi.local")).isEqualTo(EndpointDecision.Credentials)
    }

    @Test
    fun anAtSignAfterThePathIsNotACredential() {
        assertAllowedUrl("http://pi.local:8080/some@path", "http://pi.local:8080/api/club")
    }

    // Schemes

    @Test
    fun otherSchemesAreRefusedByName() {
        assertThat(EndpointPolicy.evaluate("ftp://pi.local")).isEqualTo(EndpointDecision.UnsupportedScheme("ftp"))
        assertThat(EndpointPolicy.evaluate("ws://pi.local")).isEqualTo(EndpointDecision.UnsupportedScheme("ws"))
        assertThat(EndpointPolicy.evaluate("file:///etc/passwd"))
            .isEqualTo(EndpointDecision.UnsupportedScheme("file"))
    }

    // Malformed

    @Test
    fun blankInputAsksForAnAddress() {
        assertThat(EndpointPolicy.evaluate("")).isEqualTo(EndpointDecision.Blank)
        assertThat(EndpointPolicy.evaluate("   ")).isEqualTo(EndpointDecision.Blank)
        assertThat(EndpointDecision.Blank.reason).isEqualTo("Enter the address of your OpenFlight Pi.")
    }

    @Test
    fun malformedInputIsRefusedWithTheTrimmedInput() {
        assertThat(EndpointPolicy.evaluate(" pi local ")).isEqualTo(EndpointDecision.Malformed("pi local"))
        assertThat(EndpointPolicy.rejectionReason("pi local")).isEqualTo("\"pi local\" is not a valid address.")
    }

    @Test
    fun malformedAuthoritiesAreRefused() {
        assertMalformed("http://")
        assertMalformed("http:///api")
        assertMalformed("pi.local:")
        assertMalformed("pi.local:abc")
        assertMalformed("pi.local:0")
        assertMalformed("pi.local:65536")
        assertMalformed("pi_local")
        assertMalformed("-pi.local")
        assertMalformed("pi..local")
        assertMalformed("1http://pi.local")
    }

    @Test
    fun theLargestPortIsAccepted() {
        assertAllowedUrl("pi.local:65535", "http://pi.local:65535/api/club")
    }

    // IPv6

    @Test
    fun bracketedIpv6LoopbackGetsTheDefaultPort() {
        assertAllowedUrl("[::1]", "http://[::1]:8080/api/club")
        assertAllowedUrl("http://[::1]:9000/x", "http://[::1]:9000/api/club")
    }

    @Test
    fun unbracketedIpv6IsAHostWithoutAPort() {
        assertAllowedUrl("::1", "http://[::1]:8080/api/club")
        assertAllowedUrl("fe80::1", "http://[fe80::1]:8080/api/club")
    }

    @Test
    fun ipv6LinkLocalCoversFe80Slash10() {
        assertAllowedUrl("[fe80::abcd:1]", "http://[fe80::abcd:1]:8080/api/club")
        assertAllowedUrl("[febf::1]", "http://[febf::1]:8080/api/club")
        assertCleartextRefused("[fec0::1]")
    }

    @Test
    fun ipv6LinkLocalKeepsItsZoneEncodedForTheUrl() {
        assertAllowedUrl("[fe80::1%en0]:8080", "http://[fe80::1%25en0]:8080/api/club")
        assertAllowedUrl("[fe80::1%25wlan0]", "http://[fe80::1%25wlan0]:8080/api/club")
    }

    @Test
    fun ipv6UniqueLocalCoversFc00Slash7() {
        assertAllowedUrl("[fd12:3456:789a::1]", "http://[fd12:3456:789a::1]:8080/api/club")
        assertAllowedUrl("[fc00::1]", "http://[fc00::1]:8080/api/club")
        assertCleartextRefused("[fe00::1]")
    }

    @Test
    fun ipv6FullFormWithoutCompressionParses() {
        assertAllowedUrl(
            "[FE80:0:0:0:0:0:0:1]:8080",
            "http://[fe80:0:0:0:0:0:0:1]:8080/api/club",
        )
    }

    @Test
    fun ipv4MappedIpv6FollowsTheIpv4Rules() {
        assertAllowedUrl("[::ffff:192.168.1.10]", "http://[::ffff:192.168.1.10]:8080/api/club")
        assertCleartextRefused("[::ffff:8.8.8.8]")
    }

    @Test
    fun publicIpv6OverHttpIsRefusedButHttpsIsFine() {
        assertThat(EndpointPolicy.evaluate("http://[2001:4860:4860::8888]"))
            .isEqualTo(EndpointDecision.CleartextToPublicHost("2001:4860:4860::8888"))
        assertAllowedUrl("https://[2001:4860:4860::8888]", "https://[2001:4860:4860::8888]/api/club")
    }

    @Test
    fun malformedIpv6IsRefused() {
        assertMalformed("[::1")
        assertMalformed("[::1]x")
        assertMalformed("[::1]:")
        assertMalformed("[1::2::3]")
        assertMalformed("[12345::1]")
        assertMalformed("[1:2:3:4:5:6:7:8:9]")
        assertMalformed("[1:2:3:4:5:6:7]")
        assertMalformed("[gggg::1]")
        assertMalformed("[fe80::1%]")
    }

    @Test
    fun anAllowedEndpointExposesItsParts() {
        assertThat(allowed("192.168.4.1:8080")).isEqualTo(Endpoint(scheme = "http", host = "192.168.4.1", port = 8080))
        assertThat(allowed("https://pi.example.com")).prop(Endpoint::port).isNull()
    }
}
