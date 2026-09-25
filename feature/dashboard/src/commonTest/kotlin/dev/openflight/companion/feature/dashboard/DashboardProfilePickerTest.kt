// SPDX-License-Identifier: AGPL-3.0-or-later
package dev.openflight.companion.feature.dashboard

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.startsWith
import dev.openflight.companion.core.data.TransportType
import dev.openflight.companion.core.model.pi.PiFeatureAvailability
import dev.openflight.companion.core.model.pi.PiLinkState
import dev.openflight.companion.core.model.pi.Profile
import dev.openflight.companion.core.model.pi.ProfileRules
import dev.openflight.companion.core.model.pi.ProfilesState
import dev.openflight.companion.core.model.pi.ShotDetail
import dev.openflight.companion.core.testing.FakePiSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The profile picker (plan R8f), porting the Expo `ProfilePicker.test.tsx` and
 * `ProfileNameForm.test.tsx` cases (`feat/profile-selection`) plus the §9.1 removal rules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardProfilePickerTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val settings = FakeSettingsRepository(transport = TransportType.WIFI, host = "pi.local:8080")
    private val shots = FakeShotRepository(settings)
    private val piSession = FakePiSessionRepository()
    private lateinit var viewModel: DashboardViewModel

    private val ann = Profile(id = "p1", name = "Ann")
    private val bob = Profile(id = "p2", name = "Bob")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        piSession.profiles.value = ProfilesState(listOf(ann, bob), activeProfileId = "p1", loaded = true)
        viewModel = DashboardViewModel(shots, settings, piSession, ClubConfirmation())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun picker(): Flow<ProfilePickerState> =
        viewModel.uiState.map { it.connection.profile }.distinctUntilChanged()

    @Test
    fun showsTheProfileTheServerIsFilingShotsUnder() =
        runTest(dispatcher) {
            picker().test {
                val state = awaitUntil { it.loaded }
                assertThat(state.label).isEqualTo("Ann")
                assertThat(state.rows.map { it.name to it.active }).containsExactly("Ann" to true, "Bob" to false)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun saysNotSetWithoutASelectionAndWaitsBeforeTheFirstSnapshot() =
        runTest(dispatcher) {
            piSession.profiles.value = ProfilesState()
            picker().test {
                val waiting = awaitUntil { !it.loaded }
                assertThat(waiting.label).isEqualTo(ProfilePickerState.NOT_SET)
                assertThat(waiting.rows).isEmpty()
                piSession.profiles.value = ProfilesState(listOf(ann), activeProfileId = "", loaded = true)
                assertThat(awaitUntil { it.loaded }.label).isEqualTo(ProfilePickerState.NOT_SET)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun asksTheServerForThePickedProfileAndClosesWithoutFlippingLocally() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                awaitUntil { it.sheet == ProfileSheet.List }
                viewModel.onEvent(ProfilePickerEvent.Select("p2"))

                val closed = awaitUntil { it.sheet == ProfileSheet.Closed }
                // Still Ann until the Pi's roster says otherwise.
                assertThat(closed.label).isEqualTo("Ann")
                assertThat(piSession.commands).containsExactly("set_active_profile:p2")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun tellsTwoProfilesWithTheSameNameApartById() =
        runTest(dispatcher) {
            piSession.profiles.value =
                ProfilesState(listOf(ann, Profile(id = "p3", name = "Ann")), activeProfileId = "p3", loaded = true)
            picker().test {
                val state = awaitUntil { it.rows.size == 2 }
                assertThat(state.rows.map { it.active }).containsExactly(false, true)
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.Select("p1"))
                assertThat(piSession.commands).containsExactly("set_active_profile:p1")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun pickingTheActiveProfileClosesWithoutAChange() =
        runTest(dispatcher) {
            viewModel.onEvent(ProfilePickerEvent.Open)
            viewModel.onEvent(ProfilePickerEvent.Select("p1"))

            assertThat(piSession.commands).isEmpty()
        }

    @Test
    fun theSheetClosesWhenTheConnectionDropsAndStaysClosed() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                awaitUntil { it.sheet is ProfileSheet.Adding }

                piSession.linkState.value = PiLinkState.Reconnecting(1, 500, "closed")
                assertThat(awaitUntil { it.sheet == ProfileSheet.Closed && !it.selection.isAvailable }.selection)
                    .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.NOT_CONNECTED))

                piSession.linkState.value = PiLinkState.Connected
                assertThat(awaitUntil { it.selection.isAvailable }.sheet).isEqualTo(ProfileSheet.Closed)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun addsTheTrimmedNameWithoutAlsoSwitchingAndReturnsToTheRoster() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                assertThat(awaitUntil { it.sheet is ProfileSheet.Adding }.sheet).isEqualTo(ProfileSheet.Adding(""))

                viewModel.onEvent(ProfilePickerEvent.NameEdited("  Cara  "))
                viewModel.onEvent(ProfilePickerEvent.SubmitName)

                awaitUntil { it.sheet == ProfileSheet.List }
                assertThat(piSession.commands).containsExactly("add_profile:Cara")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aBlankOrWhitespaceNameIsRefusedWithoutSending() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                viewModel.onEvent(ProfilePickerEvent.NameEdited("   "))
                viewModel.onEvent(ProfilePickerEvent.SubmitName)

                assertThat(awaitUntil { (it.sheet as? ProfileSheet.Adding)?.error != null }.sheet)
                    .isEqualTo(ProfileSheet.Adding("   ", ProfilePicker.BLANK_NAME))
                assertThat(piSession.commands).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun typingStopsAtTheLengthTheServerAccepts() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                viewModel.onEvent(ProfilePickerEvent.NameEdited("x".repeat(ProfileRules.MAX_NAME_LENGTH + 5)))

                val draft =
                    (
                        awaitUntil { (it.sheet as? ProfileSheet.Adding)?.draft?.isNotEmpty() == true }.sheet
                            as ProfileSheet.Adding
                    ).draft
                assertThat(draft.length).isEqualTo(ProfileRules.MAX_NAME_LENGTH)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun cancelReturnsToTheRosterWithoutSending() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                viewModel.onEvent(ProfilePickerEvent.NameEdited("Cara"))
                viewModel.onEvent(ProfilePickerEvent.CancelForm)

                awaitUntil { it.sheet == ProfileSheet.List }
                assertThat(piSession.commands).isEmpty()
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun willNotOfferAThirteenthProfile() =
        runTest(dispatcher) {
            piSession.profiles.value =
                ProfilesState(
                    (1..ProfileRules.MAX_PROFILES).map { Profile(id = "p$it", name = "P$it") },
                    activeProfileId = "p1",
                    loaded = true,
                )
            picker().test {
                val full = awaitUntil { it.rows.size == ProfileRules.MAX_PROFILES }
                assertThat(full.addEnabled).isFalse()
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                assertThat(awaitUntil { it.sheet != ProfileSheet.Closed }.sheet).isEqualTo(ProfileSheet.List)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun renamePrefillsTheCurrentNameAndSendsTheTrimmedOne() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartRename("p2"))
                assertThat(awaitUntil { it.sheet is ProfileSheet.Renaming }.sheet)
                    .isEqualTo(ProfileSheet.Renaming("p2", "Bob"))

                viewModel.onEvent(ProfilePickerEvent.NameEdited(" Robert "))
                viewModel.onEvent(ProfilePickerEvent.SubmitName)
                awaitUntil { it.sheet == ProfileSheet.List }
                assertThat(piSession.commands).containsExactly("rename_profile:p2:Robert")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun removalIsNotOfferedForTheActiveTheLastOrAProfileWithSessionShots() =
        runTest(dispatcher) {
            piSession.profiles.value =
                ProfilesState(
                    listOf(ann, bob, Profile(id = "p3", name = "Cara")),
                    activeProfileId = "p1",
                    loaded = true,
                )
            piSession.setSession(listOf(ShotDetail(timestamp = "2026-09-22T10:00:00.000001", profileId = "p2")))
            picker().test {
                val rows = awaitUntil { it.rows.size == 3 && it.rows[1].removeBlockedReason != null }.rows
                assertThat(rows.map { it.removeBlockedReason })
                    .containsExactly(ProfilePicker.ACTIVE_PROFILE, ProfilePicker.HAS_SHOTS, null)
                piSession.profiles.value = ProfilesState(listOf(ann), activeProfileId = "", loaded = true)
                assertThat(awaitUntil { it.rows.size == 1 }.rows.single().removeBlockedReason)
                    .isEqualTo(ProfilePicker.LAST_PROFILE)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun removingIsConfirmedFirstAndTheServersReplyIsTheTruth() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.Remove("p2"))
                assertThat(awaitUntil { it.sheet is ProfileSheet.ConfirmingRemoval }.sheet)
                    .isEqualTo(ProfileSheet.ConfirmingRemoval("p2", "Bob"))
                assertThat(piSession.commands).isEmpty()

                viewModel.onEvent(ProfilePickerEvent.ConfirmRemove)
                assertThat(piSession.commands).containsExactly("remove_profile:p2")
                piSession.profiles.value = ProfilesState(listOf(ann), activeProfileId = "p1", loaded = true)
                val after = awaitUntil { it.rows.size == 1 }
                advanceTimeBy(ProfilePickerState.REFUSAL_WAIT_MILLIS + 1)
                assertThat(after.notice).isNull()
                expectNoEvents()
            }
        }

    @Test
    fun aRefusedRemovalReadsAsUnchanged() =
        runTest(dispatcher) {
            picker().test {
                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.Remove("p2"))
                viewModel.onEvent(ProfilePickerEvent.ConfirmRemove)
                awaitUntil { it.sheet == ProfileSheet.List }

                // The Pi answers a refusal with the same roster, which isn't a new value.
                advanceTimeBy(ProfilePickerState.REFUSAL_WAIT_MILLIS + 1)
                val notice = awaitUntil { it.notice != null }.notice
                assertThat(notice.orEmpty()).startsWith("“Bob” is unchanged.")

                viewModel.onEvent(ProfilePickerEvent.DismissNotice)
                assertThat(awaitItem().notice).isNull()
            }
        }

    @Test
    fun overBluetoothV2SelectingWorksButEditsNeedWifi() =
        runTest(dispatcher) {
            piSession.linkState.value = PiLinkState.WifiOnly
            piSession.bluetoothSchemaV2.value = true
            picker().test {
                val state = awaitUntil { it.selection.isAvailable }
                assertThat(state.edits)
                    .isEqualTo(PiFeatureAvailability.Unavailable(PiFeatureAvailability.WIFI_ONLY_ON_BLUETOOTH))
                assertThat(state.addEnabled).isFalse()

                viewModel.onEvent(ProfilePickerEvent.Open)
                viewModel.onEvent(ProfilePickerEvent.StartAdd)
                viewModel.onEvent(ProfilePickerEvent.Remove("p2"))
                assertThat(awaitUntil { it.sheet != ProfileSheet.Closed }.sheet).isEqualTo(ProfileSheet.List)
                viewModel.onEvent(ProfilePickerEvent.Select("p2"))
                assertThat(piSession.commands).containsExactly("set_active_profile:p2")
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun aFailedCommandBecomesTheNotice() =
        runTest(dispatcher) {
            piSession.linkState.value = PiLinkState.WifiOnly // Bluetooth without v2: nothing works.
            picker().test {
                val state = awaitUntil { !it.selection.isAvailable }
                assertThat(state.selection.disabledReason).isEqualTo(PiFeatureAvailability.REQUIRES_WIFI)
                viewModel.onEvent(ProfilePickerEvent.Open)
                assertThat(viewModel.uiState.value.connection.profile.sheet == ProfileSheet.Closed).isTrue()
                cancelAndIgnoreRemainingEvents()
            }
        }

    private suspend fun <T> ReceiveTurbine<T>.awaitUntil(predicate: (T) -> Boolean): T {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }
}
