// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.core.network

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import kotlin.test.Test

/** Ported from `WiFiShotClientTests`' `endpointURL`/`streamURL` cases (plan §0.2, Step 4 task 1). */
class EndpointUrlTest {
    @Test
    fun bareHostGetsTheDefaultSchemePortAndPath() {
        assertThat(EndpointUrl.build(host = "raspberrypi.local", path = "/api/shots/stream"))
            .isEqualTo("http://raspberrypi.local:8080/api/shots/stream")
    }

    @Test
    fun explicitPortIsPreserved() {
        assertThat(EndpointUrl.build(host = "10.0.0.10:9000", path = "/api/shots/stream"))
            .isEqualTo("http://10.0.0.10:9000/api/shots/stream")
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertThat(EndpointUrl.build(host = "  10.0.0.10  ", path = "/api/shots/stream"))
            .isEqualTo("http://10.0.0.10:8080/api/shots/stream")
    }

    @Test
    fun pastedUrlPathAndQueryAreReplaced() {
        assertThat(EndpointUrl.build(host = "http://pi.local:8080/display?x=1", path = "/api/shots/stream"))
            .isEqualTo("http://pi.local:8080/api/shots/stream")
    }

    @Test
    fun httpsHostDoesNotGainTheHttpDefaultPort() {
        assertThat(EndpointUrl.build(host = "https://pi.example.com", path = "/api/shots/stream"))
            .isEqualTo("https://pi.example.com/api/shots/stream")
    }

    @Test
    fun anExplicitPortOnHttpsIsStillPreserved() {
        assertThat(EndpointUrl.build(host = "https://pi.example.com:9443", path = "/api/club"))
            .isEqualTo("https://pi.example.com:9443/api/club")
    }

    @Test
    fun aPathWithoutALeadingSlashIsNormalized() {
        assertThat(EndpointUrl.build(host = "pi.local", path = "api/club"))
            .isEqualTo("http://pi.local:8080/api/club")
    }

    @Test
    fun emptyHostIsRejected() {
        assertThat(EndpointUrl.build(host = "", path = "/api/shots/stream")).isNull()
    }

    @Test
    fun blankHostIsRejected() {
        assertThat(EndpointUrl.build(host = "   ", path = "/api/shots/stream")).isNull()
    }

    @Test
    fun anUnsupportedSchemeIsRejected() {
        assertThat(EndpointUrl.build(host = "ftp://pi.local", path = "/api/shots/stream")).isNull()
    }

    @Test
    fun aSchemeWithNoAuthorityIsRejected() {
        assertThat(EndpointUrl.build(host = "http://", path = "/api/shots/stream")).isNull()
    }

    @Test
    fun aNonNumericPortIsRejected() {
        assertThat(EndpointUrl.build(host = "pi.local:abc", path = "/api/shots/stream")).isNull()
    }
}
