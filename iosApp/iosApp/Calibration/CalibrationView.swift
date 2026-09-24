// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI
import UIKit

/// The calibration screen, ported from the reference `RadarCalibrationView.swift`. The layout and
/// copy are the reference's; the state is the shared `CalibrationViewModel`'s `CalibrationUiState`
/// instead of `PhoneOrientationMonitor`/`BluetoothManager`/`RadarCalibrationClient`, so both
/// platforms render the same state and send the same events (Android's `CalibrationScreen.kt`).
struct CalibrationView: View {
    @StateObject private var host = ViewModelHost(KoinHelper().calibrationViewModel())
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        CalibrationContent(state: host.state, send: host.send)
            .navigationTitle("Calibrate TI Radar")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                        .accessibilityIdentifier("calibration.done")
                }
            }
            // The reference keeps the screen awake while `PhoneOrientationMonitor` samples
            // (PhoneOrientation.swift). The shared VM samples for its whole lifetime while the
            // sensor is available, so this mirrors Android's `KeepScreenOn(sensor is Sampling)`.
            .onChange(of: isSampling(host.state), initial: true) { _, sampling in
                UIApplication.shared.isIdleTimerDisabled = sampling
            }
            .onDisappear { UIApplication.shared.isIdleTimerDisabled = false }
    }

    private func isSampling(_ state: CalibrationUiState) -> Bool {
        state.sensor is SensorUiStateSampling
    }
}

/// The stateless calibration screen: renders a `CalibrationUiState` and reports intents through
/// `send`.
struct CalibrationContent: View {
    let state: CalibrationUiState
    let send: (CalibrationEvent) -> Void

    var body: some View {
        ZStack {
            Theme.background.ignoresSafeArea()

            ScrollView {
                VStack(spacing: 18) {
                    instructions
                    transportCard
                    measurementCard
                    submissionCard
                }
                .padding(20)
            }
        }
        .foregroundStyle(Theme.cream)
        .preferredColorScheme(.dark)
    }

    // MARK: Instructions

    private var instructions: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label("Measure the radar face", systemImage: "iphone.gen3.radiowaves.left.and.right")
                .font(.headline)
                .foregroundStyle(Theme.success)
            Text(
                "Remove the case. Hold the phone upright in portrait with its back flat against a " +
                    "straight reference surface parallel to the TI antenna face. Keep the screen facing the target."
            )
            Text("Avoid the camera bump and keep both the radar and phone still while the two-second sample fills.")
                .foregroundStyle(Theme.creamDim)
            Text("This calibrates gravity-referenced mount tilt. It does not change target-line azimuth.")
                .font(.footnote.weight(.semibold))
                .foregroundStyle(Theme.warning)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
        .background(Theme.cream.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
    }

    // MARK: Transport

    private var hostBinding: Binding<String> {
        Binding(
            get: { state.host },
            set: { send(CalibrationEventHostEdited(text: $0)) }
        )
    }

    @ViewBuilder
    private var transportCard: some View {
        if state.transport == .wifi {
            hostField
        } else {
            bluetoothField
        }
    }

    private var hostField: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("OPENFLIGHT PI")
                .font(.caption.weight(.bold))
                .tracking(1.4)
                .foregroundStyle(Theme.creamDim)
            HStack(spacing: 10) {
                Image(systemName: "network")
                    .foregroundStyle(Theme.creamDim)
                TextField("raspberrypi.local:8080", text: hostBinding)
                    .textFieldStyle(.plain)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(.URL)
                    .submitLabel(.go)
                    .onSubmit { send(CalibrationEventHostSubmitted.shared) }
                    .accessibilityIdentifier(CalibrationTestTags.shared.HOST_FIELD)
            }
            .font(.callout.monospaced())
            .padding(12)
            .background(.black.opacity(0.22), in: RoundedRectangle(cornerRadius: 12))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
    }

    private var bluetoothField: some View {
        HStack(spacing: 12) {
            Image(systemName: "bluetooth")
                .foregroundStyle(state.bluetoothReady ? Theme.success : Theme.warning)
            VStack(alignment: .leading, spacing: 3) {
                Text("OPENFLIGHT BLUETOOTH")
                    .font(.caption.weight(.bold))
                    .tracking(1.4)
                Text(state.bluetoothReady ? "Connected and ready to calibrate" : "Connect to an updated OpenFlight Pi")
                    .font(.caption)
                    .foregroundStyle(Theme.creamDim)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
        .accessibilityIdentifier(CalibrationTestTags.shared.BLUETOOTH_CARD)
    }

    // MARK: Measurement

    @ViewBuilder
    private var measurementCard: some View {
        if let unavailable = state.sensor as? SensorUiStateUnavailable {
            ContentUnavailableView(
                unavailable.message,
                systemImage: "exclamationmark.triangle",
                description: Text("Calibration needs device motion, which isn't available here.")
            )
            .frame(minHeight: 180)
            .padding(18)
            .background(Theme.cream.opacity(0.08), in: RoundedRectangle(cornerRadius: 20))
            .accessibilityIdentifier(CalibrationTestTags.shared.MOTION_UNAVAILABLE)
        } else if let sampling = state.sensor as? SensorUiStateSampling {
            SamplingCard(sensor: sampling)
        }
    }

    // MARK: Submission

    private var submissionCard: some View {
        VStack(spacing: 12) {
            Button {
                send(CalibrationEventApply.shared)
            } label: {
                HStack {
                    if state.submit is SubmitUiStateSubmitting {
                        ProgressView().tint(.black)
                    } else {
                        Image(systemName: "paperplane.fill")
                    }
                    Text(state.submit is SubmitUiStateSubmitting ? "Sending…" : "Apply Calibration")
                        .fontWeight(.bold)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
            }
            .buttonStyle(.borderedProminent)
            .tint(Theme.success)
            .foregroundStyle(.black)
            .disabled(!state.applyEnabled)
            .accessibilityIdentifier(CalibrationTestTags.shared.APPLY)

            if let applied = state.submit as? SubmitUiStateApplied {
                Label(
                    "Saved TI tilt: \(ShotFormat.number(applied.result.configuredIwrTiltDeg, decimals: 2))°",
                    systemImage: "checkmark.seal.fill"
                )
                .font(.callout.weight(.semibold))
                .foregroundStyle(Theme.success)
                .accessibilityIdentifier(CalibrationTestTags.shared.APPLIED_RESULT)
                Text(responseSummary(applied.result))
                    .font(.caption)
                    .foregroundStyle(Theme.creamDim)
                    .multilineTextAlignment(.center)
            }

            if let failed = state.submit as? SubmitUiStateFailed {
                Label(failed.message, systemImage: "exclamationmark.triangle.fill")
                    .font(.callout)
                    .foregroundStyle(Theme.danger)
                    .multilineTextAlignment(.center)
                    .accessibilityIdentifier(CalibrationTestTags.shared.SUBMIT_ERROR)
            }
        }
        .padding(16)
        .background(Theme.cream.opacity(0.07), in: RoundedRectangle(cornerRadius: 18))
    }

    /// RadarCalibrationView's `responseSummary(_:)`.
    private func responseSummary(_ result: CalibrationResult) -> String {
        guard let enclosurePitch = result.enclosurePitchDeg else {
            return "The measured phone tilt is now active and will be restored after restart."
        }
        return "Measured \(ShotFormat.number(result.measuredMountTiltDeg, decimals: 2))° minus enclosure pitch " +
            "\(ShotFormat.number(enclosurePitch, decimals: 2))°."
    }
}

/// The live/stable angle readout, split out to mirror Android's `CalibrationMeasurementCard.kt`.
private struct SamplingCard: View {
    let sensor: SensorUiStateSampling

    var body: some View {
        VStack(spacing: 16) {
            if let angles = sensor.displayAngles {
                Label(
                    angles.isStableAverage ? "STABLE 2-SECOND AVERAGE" : "LIVE SENSOR READING",
                    systemImage: angles.isStableAverage ? "checkmark.circle.fill" : "waveform.path"
                )
                .font(.caption.weight(.bold))
                .tracking(1.2)
                .foregroundStyle(angles.isStableAverage ? Theme.success : Theme.info)

                HStack(spacing: 12) {
                    angleMetric(
                        title: "MOUNT TILT",
                        value: angles.mountTiltDegrees,
                        color: angles.isStableAverage ? Theme.success : Theme.info
                    )
                    .accessibilityIdentifier(CalibrationTestTags.shared.TILT_METRIC)
                    angleMetric(
                        title: "LEFT / RIGHT ROLL",
                        value: angles.rollDegrees,
                        color: rollColor(angles)
                    )
                    .accessibilityIdentifier(CalibrationTestTags.shared.ROLL_METRIC)
                }

                if let measurement = sensor.measurement {
                    Label(readinessMessage(measurement), systemImage: readinessIcon(measurement))
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(measurement.isReadyToSend ? Theme.success : Theme.warning)

                    Text(stabilityText(measurement))
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.creamDim)
                } else {
                    ProgressView(value: sensor.progress)
                        .tint(Theme.info)
                        .accessibilityIdentifier(CalibrationTestTags.shared.PROGRESS)
                    Text("Collecting calibration average: \(sensor.sampleCount) / \(minimumSampleCount) samples")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.creamDim)
                }
            } else {
                VStack(spacing: 14) {
                    Image(systemName: "gyroscope")
                        .font(.system(size: 38))
                        .foregroundStyle(Theme.success)
                    Text("Hold still while OpenFlight averages the sensors")
                        .font(.headline)
                        .multilineTextAlignment(.center)
                    ProgressView(value: sensor.progress)
                        .tint(Theme.success)
                        .accessibilityIdentifier(CalibrationTestTags.shared.PROGRESS)
                    Text("\(sensor.sampleCount) / \(minimumSampleCount) samples")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(Theme.creamDim)
                }
                .frame(maxWidth: .infinity)
                .frame(minHeight: 180)
            }
        }
        .padding(18)
        .background(Theme.cream.opacity(0.08), in: RoundedRectangle(cornerRadius: 20))
    }

    private var minimumSampleCount: Int32 { PhoneOrientationMeasurement.companion.MINIMUM_SAMPLE_COUNT }

    private func angleMetric(title: String, value: Double, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.caption2.weight(.bold))
                .foregroundStyle(Theme.creamDim)
            Text("\(ShotFormat.number(value, decimals: 2))°")
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .monospacedDigit()
                .foregroundStyle(color)
                .minimumScaleFactor(0.7)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(14)
        .background(.black.opacity(0.25), in: RoundedRectangle(cornerRadius: 14))
    }

    private func rollColor(_ angles: SensorsPhoneOrientationDisplayAngles) -> Color {
        let maxRoll = PhoneOrientationMeasurement.companion.MAXIMUM_ROLL_DEG
        guard abs(angles.rollDegrees) <= maxRoll else { return Theme.warning }
        return angles.isStableAverage ? Theme.success : Theme.info
    }

    /// RadarCalibrationView's `readinessMessage(_:)`.
    private func readinessMessage(_ measurement: PhoneOrientationMeasurement) -> String {
        let companion = PhoneOrientationMeasurement.companion
        if abs(measurement.rollDeg) > companion.MAXIMUM_ROLL_DEG {
            return "Level the radar left-to-right within 3°"
        }
        if measurement.tiltStddevDeg > companion.MAXIMUM_STANDARD_DEVIATION_DEG
            || measurement.rollStddevDeg > companion.MAXIMUM_STANDARD_DEVIATION_DEG
        {
            return "Keep the phone and radar still"
        }
        return measurement.isReadyToSend ? "Stable measurement ready" : "Adjust the radar angle"
    }

    private func readinessIcon(_ measurement: PhoneOrientationMeasurement) -> String {
        measurement.isReadyToSend ? "checkmark.circle.fill" : "exclamationmark.triangle.fill"
    }

    private func stabilityText(_ measurement: PhoneOrientationMeasurement) -> String {
        let stability = ShotFormat.number(max(measurement.tiltStddevDeg, measurement.rollStddevDeg), decimals: 2)
        return "Stability ±\(stability)° from \(measurement.sampleCount) samples"
    }
}
