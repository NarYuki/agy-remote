import SwiftUI

extension View {
    /// Hooks up the notification explainer sheet. Two copies exist (root and Settings);
    /// whichever matches `showSettings` is the one that presents.
    func notificationExplainer(_ viewModel: RemoteWorkspaceViewModel, insideSettings: Bool = false) -> some View {
        modifier(ExplainerPresenter(viewModel: viewModel, insideSettings: insideSettings))
    }
}

private struct ExplainerPresenter: ViewModifier {
    let viewModel: RemoteWorkspaceViewModel
    let insideSettings: Bool

    func body(content: Content) -> some View {
        let vm = viewModel
        let mine = insideSettings
        let gate = Binding<Bool>(
            get: { vm.showNotificationExplainer && (vm.showSettings == mine) },
            set: { open in if open == false { vm.showNotificationExplainer = false } }
        )
        content.sheet(isPresented: gate) {
            NotificationExplainerView(viewModel: vm).presentationDetents(Set([PresentationDetent.large]))
        }
    }
}

private struct ExplainerFact: Identifiable {
    let symbol: String
    let heading: LocalizedStringKey
    let detail: LocalizedStringKey
    var id: String { symbol }
}

private struct RouteStop: Identifiable {
    let symbol: String
    let caption: LocalizedStringKey
    var lit = false
    var id: String { symbol }
}

struct NotificationExplainerView: View {
    @Environment(\.dismiss) private var closeSheet
    @Bindable var viewModel: RemoteWorkspaceViewModel

    private let facts: [ExplainerFact] = [
        ExplainerFact(symbol: "lock", heading: "Only this phone can read them",
                      detail: "Your computer locks each notification with a key that only this phone holds. It's unlocked on the phone itself."),
        ExplainerFact(symbol: "eye.slash", heading: "What the relay sees",
                      detail: "This phone's push address, an ID for your computer, the time, and a locked message that is always the same size. Never the chat name or what was said."),
        ExplainerFact(symbol: "power", heading: "Off means no contact",
                      detail: "Until you turn notifications on, your computer never registers with the relay or talks to it. Turning them off removes this phone again."),
        ExplainerFact(symbol: "chevron.left.forwardslash.chevron.right", heading: "Open source",
                      detail: "The relay's code is public (AGPL-3.0), and you can run your own."),
    ]

    private let stops: [RouteStop] = [
        RouteStop(symbol: "desktopcomputer", caption: "Computer"),
        RouteStop(symbol: "server.rack", caption: "Relay"),
        RouteStop(symbol: "antenna.radiowaves.left.and.right", caption: "Apple"),
        RouteStop(symbol: "iphone", caption: "iPhone", lit: true),
    ]

    var body: some View {
        let alreadyOn = viewModel.notificationsEnabled
        Down(0) {
            ScrollView(.vertical, showsIndicators: false) { story.gap(.bottom, 20) }
                .scrollBounceBehavior(ScrollBounceBehavior.basedOnSize)
            primaryButton(alreadyOn)
            if !alreadyOn {
                Button(action: { closeSheet() }) {
                    Text("Not Now")
                        .typeset(16, .medium, ink: AppColor.onSurfaceVariant)
                        .frame(minHeight: 44)
                        .stretch()
                }
                .gap(.top, 4)
            }
        }
        .gap(.bottom, 12)
        .gap(.horizontal, 28)
        .frame(maxWidth: 520)
        .stretch()
    }

    private var story: some View {
        Lead(0) {
            Image.sf("bell.badge")
                .typeset(34, ink: AppColor.onSurface)
                .padding(EdgeInsets(top: 44, leading: 0, bottom: 18, trailing: 0))
            Text("Notifications")
                .tracking(-0.6)
                .typeset(32, .bold, ink: AppColor.onSurface)
                .gap(.bottom, 8)
            Text("Find out when the agent finishes, fails, or needs your approval, even with the app closed.")
                .fixedSize(horizontal: false, vertical: true)
                .typeset(16, ink: AppColor.onSurfaceVariant)
                .gap(.bottom, 30)
            routeDiagram.gap(.bottom, 28)
            Lead(22) {
                ForEach(facts) { factRow($0) }
            }
        }
    }

    private func primaryButton(_ alreadyOn: Bool) -> some View {
        Button {
            guard !alreadyOn else { return closeSheet() }
            let vm = viewModel
            Task { await vm.confirmNotifications() }
        } label: {
            Text(alreadyOn ? "Done" : "Turn On Notifications")
                .typeset(17, .semibold, ink: AppColor.onAccentFill)
                .frame(minHeight: 54)
                .stretch()
                .background(AppColor.accentFill, in: Capsule(style: .continuous))
        }
        .plainTap()
    }

    private var routeDiagram: some View {
        Down(14) {
            Across(0, top: true) {
                ForEach(stops) { stopView($0) }
            }
            .background(alignment: .top) { DashedRoute() }
            Label {
                Text("Locked until it reaches your iPhone").fontSized(13, .medium)
            } icon: {
                Image.sf("lock.fill").fontSized(11)
            }
            .labelStyle(TightLabel())
            .ink(AppColor.primary)
        }
    }

    private func stopView(_ stop: RouteStop) -> some View {
        let ring = stop.lit ? AppColor.primary : AppColor.outlineVariant
        return Down(8) {
            ZStack {
                Circle().fill(AppColor.surface)
                Circle().strokeBorder(ring, lineWidth: stop.lit ? 1.5 : 1)
                Image.sf(stop.symbol).typeset(17, .medium, ink: AppColor.onSurface)
            }
            .frame(width: 44, height: 44)
            Text(stop.caption)
                .oneLine()
                .typeset(12, .medium, ink: AppColor.onSurfaceVariant)
        }
        .stretch()
    }

    private func factRow(_ fact: ExplainerFact) -> some View {
        Across(16, top: true) {
            Image.sf(fact.symbol)
                .frame(width: 28)
                .typeset(18, .medium, ink: AppColor.primary)
            Lead(3) {
                Text(fact.heading).typeset(16, .semibold, ink: AppColor.onSurface)
                Text(fact.detail)
                    .fixedSize(horizontal: false, vertical: true)
                    .typeset(14.5, ink: AppColor.onSurfaceVariant)
            }
        }
    }
}

/// Icon and title six points apart.
private struct TightLabel: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        Across(6) { configuration.icon; configuration.title }
    }
}

/// Dashed line through the circle centres of the first and last of four columns.
private struct DashedRoute: View {
    var body: some View {
        GeometryReader { box in
            let span = box.size.width
            let dashes = StrokeStyle(lineWidth: 1, dash: [3, 4])
            Path { line in
                line.move(to: .init(x: span * 0.125, y: 22))
                line.addLine(to: .init(x: span * 0.875, y: 22))
            }
            .stroke(style: dashes)
            .ink(AppColor.onSurfaceVariant.opacity(0.35))
        }
    }
}
