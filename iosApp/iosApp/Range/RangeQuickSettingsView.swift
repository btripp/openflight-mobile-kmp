// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// Plan F8f: the range's quick settings, Android's `RangeQuickSettingsContent`: Show (live, the
/// current session's newest 5/10/20 or all of it, every session, plus the club filter), Trail (the
/// eleven styles with swatches drawn by the shared `ShotTrail`, keep last, landing effect), View
/// (theme, Fixed/Follow, reset view) and Numbers (total + roll, units). Every control sends one shared
/// `DrivingRangeEvent`; the view model persists it through the key Settings › Practice uses, so the
/// scene (and Settings) follow at once.
///
/// On an iPhone it's a sheet over the scene (swipe down or tap outside to close); on an iPad a side
/// panel beside the scene (`isPanel`), with a Done button.
struct RangeQuickSettingsView: View {
    let state: DrivingRangeUiState
    let send: (DrivingRangeEvent) -> Void
    var isPanel = false
    var onClose: () -> Void = {}

    private var browse: RangeBrowseState { state.browse }
    private var camera: RangeCameraState { state.camera }
    private var trail: RangeTrailState { camera.trail }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Range settings")
                    .font(.ofDisplay(.title3))
                    .foregroundStyle(Theme.cream)
                Spacer()
                if isPanel {
                    Button("Done", action: onClose)
                        .font(.of(.body, weight: .semibold))
                        .foregroundStyle(Theme.gold)
                        .frame(minWidth: 44, minHeight: 44)
                        .accessibilityIdentifier(RangeTestTags.shared.QUICK_SETTINGS_CLOSE)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, isPanel ? 12 : 20)

            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    showSection
                    trailSection
                    viewSection
                    numbersSection
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 12)
            }
        }
        .background(Theme.bgCard.ignoresSafeArea())
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier(RangeTestTags.shared.QUICK_SETTINGS_PANEL)
    }

    // MARK: Show

    @ViewBuilder
    private var showSection: some View {
        Eyebrow("SHOW")
        FlowChips {
            ForEach(RangeShowSetting.entries, id: \.self) { show in
                choice(show.pickerLabel, selected: browse.show == show, id: RangeTestTags.shared.quickShow(show: show)) {
                    send(DrivingRangeEventSetShow(show: show))
                }
            }
        }
        // Plan F8f: whose shots, on this device only (hidden without a roster).
        let profileChoices = browse.profiles.choices
        if !profileChoices.isEmpty {
            note("Viewing profile")
            FlowChips {
                ForEach(profileChoices, id: \.profile.storageValue) { option in
                    choice(
                        option.label,
                        selected: option.selected,
                        id: RangeTestTags.shared.quickViewingProfile(profile: option.profile)
                    ) {
                        send(DrivingRangeEventSetViewingProfile(profile: option.profile))
                    }
                }
            }
        }
        if let overlay = browse.mode as? RangeModeOverlay {
            FlowChips {
                choice("All clubs", selected: overlay.club == nil, id: RangeTestTags.shared.quickClub(club: nil)) {
                    send(DrivingRangeEventSetOverlayClub(club: nil))
                }
                ForEach(browse.overlayClubs, id: \.self) { club in
                    choice(Units.clubLabel(club), selected: overlay.club == club, id: RangeTestTags.shared.quickClub(club: club)) {
                        send(DrivingRangeEventSetOverlayClub(club: club))
                    }
                }
            }
        } else {
            note("Pick a show option other than Live to filter by club.")
        }
    }

    // MARK: Trail

    @ViewBuilder
    private var trailSection: some View {
        Eyebrow("TRAIL")
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 96), spacing: 8)], spacing: 8) {
            ForEach(ShotTrailStyle.entries, id: \.self) { style in
                trailStyleButton(style)
            }
        }
        note("Keep last shots")
        FlowChips {
            ForEach(SettingsRepositoryKt.SHOT_TRAIL_KEEP_OPTIONS.map { $0.int32Value }, id: \.self) { count in
                choice(
                    RangeSettingLabelsKt.shotTrailKeepLabel(count: count),
                    selected: trail.keepLast == count,
                    id: RangeTestTags.shared.quickKeepLast(count: count)
                ) {
                    send(DrivingRangeEventSetTrailKeepLast(count: count))
                }
            }
        }
        note("Landing effect")
        FlowChips {
            ForEach(LandingEffect.entries, id: \.self) { effect in
                choice(
                    effect.pickerLabel,
                    selected: trail.landingEffect == effect,
                    id: RangeTestTags.shared.quickLandingEffect(effect: effect)
                ) {
                    send(DrivingRangeEventSetLandingEffect(effect: effect))
                }
            }
        }
    }

    private func trailStyleButton(_ style: ShotTrailStyle) -> some View {
        let selected = trail.style == style
        return Button {
            send(DrivingRangeEventSetTrailStyle(style: style))
        } label: {
            VStack(spacing: 4) {
                ShotTrailSwatchView(style: style, theme: camera.theme)
                    .frame(height: 44)
                Text(style.pickerLabel)
                    .font(.of(.caption, weight: .semibold))
                    .foregroundStyle(selected ? Theme.gold : Theme.cream)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .padding(4)
            .frame(minHeight: 44)
            .overlay {
                RoundedRectangle(cornerRadius: 10)
                    .stroke(selected ? Theme.gold : Theme.cream.opacity(0.12), lineWidth: selected ? 2 : 1)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(style.pickerLabel) trail")
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier(RangeTestTags.shared.quickTrail(style: style))
    }

    // MARK: View

    @ViewBuilder
    private var viewSection: some View {
        Eyebrow("VIEW")
        FlowChips {
            ForEach(RangeThemeSetting.entries, id: \.self) { theme in
                choice(theme.pickerLabel, selected: camera.theme.setting == theme, id: RangeTestTags.shared.quickTheme(theme: theme)) {
                    send(DrivingRangeEventSetTheme(theme: theme))
                }
            }
        }
        FlowChips {
            ForEach(RangeCameraMode.entries, id: \.self) { mode in
                choice(
                    mode.pickerLabel,
                    selected: state.cameraMode == mode,
                    id: RangeTestTags.shared.quickCamera(mode: mode),
                    enabled: !state.cameraModeLocked
                ) {
                    send(DrivingRangeEventSetCameraMode(mode: mode))
                }
            }
            RangeBarButton(
                title: "Reset view",
                identifier: RangeTestTags.shared.QUICK_RESET_VIEW,
                isEnabled: browse.userTransformed
            ) {
                send(DrivingRangeEventResetView.shared)
            }
        }
        if state.cameraModeLocked {
            note("Reduced motion keeps the camera fixed.")
        }
    }

    // MARK: Numbers

    @ViewBuilder
    private var numbersSection: some View {
        Eyebrow("NUMBERS")
        Toggle(isOn: Binding(
            get: { camera.numbers.showTotal },
            set: { send(DrivingRangeEventSetShowTotal(show: $0)) }
        )) {
            VStack(alignment: .leading, spacing: 2) {
                Text("Show total + roll (est.)")
                    .font(.of(.body, weight: .semibold))
                    .foregroundStyle(Theme.cream)
                Text("Estimated from carry, launch and spin")
                    .font(.of(.caption))
                    .foregroundStyle(Theme.creamDim)
            }
        }
        .tint(Theme.gold)
        .frame(minHeight: 44)
        .accessibilityIdentifier(RangeTestTags.shared.QUICK_SHOW_TOTAL)
        FlowChips {
            ForEach(UnitSystem.entries, id: \.self) { units in
                choice(
                    RangeSettingLabelsKt.unitSystemLabel(units: units),
                    selected: camera.numbers.units == units,
                    id: RangeTestTags.shared.quickUnits(units: units)
                ) {
                    send(DrivingRangeEventSetUnits(units: units))
                }
            }
        }
    }

    // MARK: Pieces

    private func choice(
        _ label: String,
        selected: Bool,
        id: String,
        enabled: Bool = true,
        action: @escaping () -> Void
    ) -> some View {
        ChipButton(label: label, isSelected: selected, isEnabled: enabled, minTouchTarget: true, action: action)
            .accessibilityIdentifier(id)
    }

    private func note(_ text: String) -> some View {
        Text(text)
            .font(.of(.caption))
            .foregroundStyle(Theme.creamDim)
    }
}

/// Chips that wrap onto more lines when they don't fit, like Android's `FlowRow`.
private struct FlowChips<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        FlowLayout(spacing: 8) { content }
    }
}

/// Plan F8f: one trail style's swatch: the shared `ShotTrailSwatch` (the landed preview 7-iron,
/// written by the range's own `ShotTrail`) filled like the range's trail and fitted to the view.
/// Built once per style and theme, off the main thread.
struct ShotTrailSwatchView: View {
    let style: ShotTrailStyle
    let theme: RangeTheme

    @State private var swatch: ShotTrailSwatch<CGPathSink>?

    var body: some View {
        Canvas { context, size in
            guard let swatch else {
                context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(Theme.bgDeep))
                return
            }
            context.fill(Path(CGRect(origin: .zero, size: size)), with: .color(swatch.background.swiftUI))
            let fit = swatch.fit(width: Float(size.width), height: Float(size.height), padding: 4)
            let scale = CGFloat(fit.get(index: 0))
            context.translateBy(x: CGFloat(fit.get(index: 1)), y: CGFloat(fit.get(index: 2)))
            context.scaleBy(x: scale, y: scale)
            for layer in swatch.trail.layers where layer.visible {
                guard let path = layer.path.cgPath else { continue }
                let color = layer.paletteIndex >= 0
                    ? Theme.clubColor(Int(layer.paletteIndex)).opacity(Double(UInt32(bitPattern: layer.argb) >> 24) / 255)
                    : Color(argb: layer.argb)
                context.fill(Path(path), with: .color(color))
            }
            let radius = CGFloat(swatch.ballRadius)
            context.fill(
                Path(ellipseIn: CGRect(
                    x: CGFloat(swatch.ballX) - radius,
                    y: CGFloat(swatch.ballY) - radius,
                    width: radius * 2,
                    height: radius * 2
                )),
                with: .color(.white)
            )
        }
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .accessibilityHidden(true)
        .task(id: "\(style.storageValue)-\(theme.name)") {
            let style = style
            let theme = theme
            swatch = await Task.detached {
                ShotTrailSwatch<CGPathSink>(style: style, theme: theme, newPath: { CGPathSink() })
            }.value
        }
    }
}
