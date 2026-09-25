// SPDX-License-Identifier: AGPL-3.0-or-later
import Shared
import SwiftUI

/// A destructive action's outcome in the page (plan R8f, Android's `SessionActionPanel`): a
/// spinner while pending, then what happened with OK, or why it failed with Try again. VoiceOver
/// announces each change. Nothing for idle or a confirmation (that's `sessionActionDialog`).
struct SessionActionPanel: View {
    let state: SessionActionState
    let onRetry: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        Group {
            if let pending = state as? SessionActionStatePending {
                HStack(spacing: 12) {
                    ProgressView()
                    Text(pending.message)
                        .font(.of(.body))
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)
                .accessibilityIdentifier(SessionActionTestTags.shared.PENDING)
                .onAppear { announce(pending.message) }
            } else if let done = state as? SessionActionStateDone {
                VStack(alignment: .leading, spacing: 10) {
                    Label(done.message, systemImage: "checkmark.circle")
                        .font(.of(.body))
                        .fixedSize(horizontal: false, vertical: true)
                    dismissButton
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier(SessionActionTestTags.shared.DONE)
                .onAppear { announce(done.message) }
            } else if let failed = state as? SessionActionStateFailed {
                VStack(alignment: .leading, spacing: 10) {
                    // Not colour alone: the icon and the title say it failed.
                    Label(failed.title, systemImage: "exclamationmark.triangle")
                        .font(.of(.headline, weight: .semibold))
                        .foregroundStyle(Theme.warning)
                    Text(failed.message)
                        .font(.of(.subheadline))
                        .foregroundStyle(Theme.creamDim)
                        .fixedSize(horizontal: false, vertical: true)
                    HStack(spacing: 12) {
                        if failed.canRetry {
                            Button(SessionActionCopy.shared.RETRY, action: onRetry)
                                .buttonStyle(.borderedProminent)
                                .tint(Theme.gold)
                                .foregroundStyle(Theme.bgDeep)
                                .controlSize(.large)
                                .accessibilityIdentifier(SessionActionTestTags.shared.RETRY)
                        }
                        dismissButton
                    }
                }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier(SessionActionTestTags.shared.FAILED)
                .onAppear { announce("\(failed.title). \(failed.message)") }
            }
        }
    }

    private var dismissButton: some View {
        Button(SessionActionCopy.shared.DISMISS, action: onDismiss)
            .buttonStyle(.bordered)
            .tint(Theme.gold)
            .controlSize(.large)
            .accessibilityIdentifier(SessionActionTestTags.shared.DISMISS)
    }

    private func announce(_ text: String) {
        UIAccessibility.post(notification: .announcement, argument: text)
    }
}

/// Plan R8f: with Reduce Motion on, the session lists change without animating rows in and out.
private struct ReducingMotion: ViewModifier {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    func body(content: Content) -> some View {
        content.transaction { transaction in
            if reduceMotion {
                transaction.disablesAnimations = true
                transaction.animation = nil
            }
        }
    }
}

extension View {
    func reducingMotion() -> some View {
        modifier(ReducingMotion())
    }

    /// The confirmation for a `SessionActionStateConfirming` state. Dismissing it without choosing
    /// sends `onCancel`; the ViewModel ignores that once the action has started.
    func sessionActionDialog(
        _ state: SessionActionState,
        onConfirm: @escaping () -> Void,
        onCancel: @escaping () -> Void
    ) -> some View {
        let confirming = state as? SessionActionStateConfirming
        return confirmationDialog(
            confirming?.title ?? "",
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { onCancel() } }),
            titleVisibility: .visible,
            presenting: confirming
        ) { prompt in
            Button(prompt.confirmLabel, role: .destructive, action: onConfirm)
                .accessibilityIdentifier(SessionActionTestTags.shared.CONFIRM)
            Button("Cancel", role: .cancel, action: onCancel)
                .accessibilityIdentifier(SessionActionTestTags.shared.CANCEL)
        } message: { prompt in
            Text(prompt.message)
        }
    }
}
