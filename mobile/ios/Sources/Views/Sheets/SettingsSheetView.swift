import SwiftUI

// MARK: - Handoff from Settings to the next sheet

/// One pending change per view model; the newest request replaces an older one.
@MainActor
private var settingsHandoffs: [ObjectIdentifier: @MainActor (RemoteWorkspaceViewModel) -> Void] = [:]

extension RemoteWorkspaceViewModel {
    /// Runs `change` after Settings has fully closed, or right away when Settings is not open.
    func presentAfterSettings(_ change: @escaping @MainActor (RemoteWorkspaceViewModel) -> Void) {
        if !showSettings { return change(self) }
        settingsHandoffs[ObjectIdentifier(self)] = change
        showSettings = false
    }

    /// Called from the Settings sheet's on-dismiss.
    func runSettingsHandoff() {
        let key = ObjectIdentifier(self)
        guard let pending = settingsHandoffs[key] else { return }
        settingsHandoffs[key] = nil
        pending(self)
    }
}

// MARK: - Root

enum AppLinks {
    static let sourceRepo = URL(string: "https://github.com/NarYuki/agy-remote")!
    static let privacyPolicy = URL(string: "https://n4t.su/agy/privacy")!
}

struct SettingsSheetView: View {
    enum Page: Hashable { case computer, notifications, appearance, licenses, reset }

    @State private var stack: [Page] = []
    @Environment(\.dismiss) private var closeSheet
    @Environment(\.openURL) private var openURL
    @Bindable var viewModel: RemoteWorkspaceViewModel

    static var versionText: String {
        let (v, b) = (RemoteWorkspaceViewModel.appVersion, RemoteWorkspaceViewModel.buildNumber)
        return b.isEmpty ? v : v + " (" + b + ")"
    }

    static func name(of theme: ThemePreference) -> String {
        let key: String.LocalizationValue = switch theme {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
        return String(localized: key)
    }

    var body: some View {
        NavigationStack(path: $stack) {
            List {
                if let device = viewModel.selectedDevice {
                    Section { NavigationLink(value: Page.computer) { ComputerCard(device: device) } }
                }
                preferenceLinks
                if viewModel.baseModels.count + viewModel.modes.count > 0 { chatSection }
                aboutSection
                Section {
                    link(.reset, SettingsRow("arrow.counterclockwise", "Reset App", destructive: true))
                }
            }
            .navigationDestination(for: Page.self, destination: destination)
            .inlineTitle("Settings")
            .doneToolbar { closeSheet() }
        }
    }

    private func link(_ page: Page, _ row: SettingsRow) -> some View {
        NavigationLink(value: page) { row }
    }

    private var preferenceLinks: some View {
        let on = viewModel.notificationsEnabled
        return Section {
            link(.notifications, SettingsRow("bell", "Notifications",
                                             value: String(localized: on ? "On" : "Off")))
            link(.appearance, SettingsRow("circle.lefthalf.filled", "Appearance",
                                          value: Self.name(of: viewModel.theme)))
        }
    }

    private var chatSection: some View {
        Section {
            if viewModel.baseModels.count > 0 {
                Button(action: openModelPicker) {
                    SettingsRow("cpu", "Model", value: viewModel.selectedBaseModel?.name, chevron: true)
                }
                .plainTap()
            }
            if viewModel.modes.count > 0 { modePicker }
        } header: {
            Text.caption("Chat")
        } footer: {
            let blurb = viewModel.selectedMode?.description ?? ""
            if !blurb.isEmpty { Text(verbatim: blurb) }
        }
    }

    private var modePicker: some View {
        let vm = viewModel
        let choice = Binding<String>(get: { vm.selectedModeId }) { picked in
            guard let mode = vm.modes.first(where: { $0.id == picked }) else { return }
            vm.selectMode(mode)
        }
        return Picker(selection: choice) {
            ForEach(vm.modes, id: \.id) { mode in Text(verbatim: mode.name).tag(mode.id) }
        } label: {
            SettingsRow("dial.medium", "Mode")
        }
        .tint(AppColor.onSurfaceVariant)
        .pickerStyle(MenuPickerStyle())
    }

    private var aboutSection: some View {
        Section(content: {
            SettingsRow("info.circle", "Version", value: Self.versionText)
            link(.licenses, SettingsRow("doc.text", "License", value: "AGPL-3.0"))
            Button { openURL(AppLinks.sourceRepo) } label: {
                SettingsRow("", "Source Code", value: String(localized: "GitHub"), asset: "GitHubMark")
            }
            .buttonStyle(.plain)
            Button { openURL(AppLinks.privacyPolicy) } label: { SettingsRow("hand.raised", "Privacy Policy") }
                .buttonStyle(.plain)
        }, header: { Text.caption("About") })
    }

    private func openModelPicker() {
        viewModel.presentAfterSettings { vm in vm.showModelPicker = true }
    }

    @ViewBuilder private func destination(_ page: Page) -> some View {
        switch page {
        case .computer: ComputerDetailView(viewModel: viewModel)
        case .notifications: NotificationSettingsView(viewModel: viewModel)
        case .appearance: AppearanceSettingsView(viewModel: viewModel)
        case .licenses: LicensesView()
        case .reset: ResetPage(viewModel: viewModel)
        }
    }
}

// MARK: - Row

struct SettingsRow: View {
    let symbol: String
    let title: LocalizedStringKey
    var value: String? = nil
    var chevron: Bool = false
    var destructive: Bool = false
    /// Template image asset used instead of `symbol` when set.
    var asset: String? = nil

    var body: some View {
        let tone: Color = destructive ? Color.red : AppColor.onSurface
        Across(12) {
            ZStack {
                RoundedRectangle(cornerRadius: 7, style: .continuous).fill(AppColor.onSurface.opacity(0.08))
                if let asset {
                    Image(asset).renderingMode(.template).resizable().scaledToFit().frame(width: 15, height: 15)
                } else {
                    Image.sf(symbol).fontSized(14, .medium)
                }
            }
            .frame(width: 29, height: 29)
            .ink(tone)
            Text(title).ink(tone)
            Spacer(minLength: 8)
            if let value, value.isEmpty == false {
                Text(verbatim: value).oneLine().ink(AppColor.onSurfaceVariant)
            }
            if chevron {
                Image.sf("chevron.right").typeset(13, .semibold, ink: AppColor.onSurfaceVariant.opacity(0.6))
            }
        }
        .contentShape(.rect)
    }
}

extension SettingsRow {
    /// Shorthand: symbol and title without labels.
    init(_ symbol: String, _ title: LocalizedStringKey, value: String? = nil, chevron: Bool = false, destructive: Bool = false, asset: String? = nil) {
        self.asset = asset
        self.symbol = symbol
        self.title = title
        self.value = value
        self.chevron = chevron
        self.destructive = destructive
    }
}

// MARK: - Computer

extension RemoteDevice.Status {
    var tint: Color {
        if self == .connected { return AppColor.connectedGreen }
        return self == .standby ? AppColor.warningAmber : AppColor.onSurfaceMuted
    }
}

struct ComputerStatusText: View {
    let device: RemoteDevice?

    var body: some View {
        if let device, device.status == .connected {
            Text("Online · \(device.latencyMs) ms").ink(AppColor.connectedGreen)
        } else if device?.status == .standby {
            Text("Connecting…").ink(AppColor.warningAmber)
        } else {
            Text("Offline").ink(AppColor.onSurfaceVariant)
        }
    }
}

private struct ComputerCard: View {
    let device: RemoteDevice

    var body: some View {
        let ring: Color = switch device.status {
        case .offline: AppColor.accentRing.opacity(0.85)
        case .standby: AppColor.warningAmber
        case .connected: AppColor.connectedGreen
        }
        Across(14) {
            Image.sf(device.deviceIconName)
                .typeset(20, .medium, ink: AppColor.onSurface)
                .frame(width: 52, height: 52)
                .overlay(Circle().stroke(ring, lineWidth: 2))
            Lead(3) {
                Across(6) {
                    Text(verbatim: device.name).oneLine().typeset(18, .semibold, ink: AppColor.onSurface)
                    if device.isDemo { DemoBadge() }
                }
                ComputerStatusText(device: device).fontSized(14)
            }
        }
        .gap(.vertical, 6)
    }
}

struct ComputerDetailView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    static func statusName(_ status: RemoteDevice.Status) -> String {
        let key: String.LocalizationValue = switch status {
        case .connected: "Online"
        case .standby: "Connecting…"
        case .offline: "Offline"
        }
        return String(localized: key)
    }

    var body: some View {
        if let device = viewModel.selectedDevice {
            List {
                identitySection(device)
                Section(content: {
                    LabeledContent("Status", value: Self.statusName(device.status))
                    if !device.transport.isEmpty { LabeledContent("Transport", value: device.transportLabel) }
                    LabeledContent("Latency") { Text("\(device.latencyMs) ms") }
                }, header: { Text.caption("Connection") })
                relaySection
                Section {
                    Button("Switch or Add Computer…", action: switchComputer)
                }
            }
            .inlineTitle(Text(verbatim: device.name))
        }
    }

    private func identitySection(_ device: RemoteDevice) -> some View {
        Section {
            fact("Name", device.name)
            fact("Hardware", device.hardwareModel)
            fact("agy", device.agentVersion)
            LabeledContent("agy-connector") {
                if device.connectorVersion.isEmpty { Text("Unknown") } else { Text(verbatim: device.connectorVersion) }
            }
        } footer: {
            versionFooter
        }
    }

    /// A labelled value, left out when the value is empty.
    @ViewBuilder private func fact(_ label: LocalizedStringKey, _ text: String) -> some View {
        if text.isEmpty == false { LabeledContent(label) { Text(verbatim: text) } }
    }

    @ViewBuilder private var versionFooter: some View {
        let note = viewModel.connectorUpdateNote ?? ""
        if viewModel.versionIssue == .updateConnector {
            Text("This app needs agy-connector \(RemoteDevice.minimumConnector) or newer. On this computer, run agy-connector update, then restart it.")
        } else if note.isEmpty == false {
            Text("Update available: \(note). agy-connector installs it automatically unless automatic updates are off.")
        }
    }

    private var relaySection: some View {
        let vm = viewModel
        let relay = Binding<Bool>(get: { vm.relayAllowed }) { enabled in
            // The choice is global: apply it to every paired computer.
            vm.devices = vm.devices.map { entry in
                var copy = entry
                copy.relayAllowed = enabled
                return copy
            }
            vm.setRelayAllowed(enabled)
        }
        return Section(content: {
            Toggle("Use Relay When Needed", isOn: relay)
        }, header: { Text.caption("Relay") }, footer: {
            Text.caption("When your phone can't reach this computer directly (for example on some mobile or office networks), the connection can go through a public relay run by n0, the maker of the networking library. It stays end-to-end encrypted. Off until you turn it on.")
        })
    }

    private func switchComputer() {
        viewModel.presentAfterSettings { vm in vm.showComputerPicker = true }
    }
}

// MARK: - Appearance

struct AppearanceSettingsView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel
    /// Declaration order is the display order: ring … random.
    private let styles = ThinkingIndicatorStyle.allCases

    var body: some View {
        List {
            Section(content: {
                Picker(selection: $viewModel.theme) {
                    ForEach([ThemePreference.system, .dark, .light], id: \.self) { option in
                        Text(LocalizedStringKey(SettingsSheetView.name(of: option))).tag(option)
                    }
                } label: {
                    Text("Theme")
                }
                .labelsHidden()
                .pickerStyle(InlinePickerStyle())
            }, header: { Text.caption("Theme") })
            Section(content: {
                ForEach(styles) { styleRow($0) }
            }, header: {
                Text.caption("Thinking Indicator")
            }, footer: {
                Text.caption("Random picks a different shape each time the agent starts thinking.")
            })
        }
        .inlineTitle("Appearance")
    }

    private func styleRow(_ style: ThinkingIndicatorStyle) -> some View {
        let chosen = viewModel.thinkingStyle == style
        return Button {
            Haptics.select()
            viewModel.thinkingStyle = style
        } label: {
            Across(14) {
                ThinkingIndicatorView(style: style)
                    .frame(minWidth: 28, maxWidth: 28)
                Text(style.title)
                    .ink(AppColor.onSurface)
                Spacer()
                Image.sf("checkmark")
                    .typeset(14, .semibold, ink: AppColor.primary)
                    .opacity(chosen ? 1 : 0)
            }
            .contentShape(.rect)
        }
        .plainTap()
    }
}

// MARK: - Notifications

struct NotificationSettingsView: View {
    @Bindable var viewModel: RemoteWorkspaceViewModel

    private let kinds: [(kind: NotificationKind, label: LocalizedStringKey)] = [
        (.finished, "Finished"),
        (.failed, "Failed"),
        (.approval, "Needs Approval"),
        (.cliReleased, "agy CLI session released"),
    ]

    var body: some View {
        let vm = viewModel
        List {
            Section(content: {
                Toggle("Allow Notifications", isOn: Binding(get: { vm.notificationsEnabled }, set: { on in vm.requestNotifications(on) }))
            }, footer: {
                Button(action: { vm.showNotificationExplainer = true }) {
                    Text("How notifications work").font(.footnote)
                }
            })
            if vm.notificationsEnabled {
                Section(content: {
                    ForEach(kinds, id: \.kind) { item in
                        Toggle(item.label, isOn: Binding(
                            get: { vm.notificationKinds.contains(item.kind) },
                            set: { vm.setNotificationKind(item.kind, $0) }))
                    }
                }, header: {
                    Text.caption("Notify Me When")
                }, footer: {
                    Text.caption("Nothing is shown for the chat you have open. Approval notifications can be answered with Allow or Deny without opening the app.")
                })
            }
        }
        .inlineTitle("Notifications")
        .animation(Animation.default, value: vm.notificationsEnabled)
        .notificationExplainer(vm, insideSettings: true)
    }
}

// MARK: - Reset

private struct ResetPage: View {
    let viewModel: RemoteWorkspaceViewModel
    @State private var asking = false

    var body: some View {
        List {
            Section(content: {
                Button(role: .destructive, action: { asking = true }) { Text("Reset AGY Remote") }
            }, footer: {
                Text.caption("All paired computers, cached chats and settings are removed from this device. Nothing on your computers is deleted. You can pair again any time.")
            })
        }
        .inlineTitle("Reset")
        .confirmationDialog(Text("Reset AGY Remote?"), isPresented: $asking, titleVisibility: Visibility.visible) {
            Button(role: .cancel, action: {}) { Text("Cancel") }
            Button(role: .destructive, action: wipe) { Text("Reset") }
        }
    }

    private func wipe() {
        Haptics.warning()
        viewModel.resetApp()
    }
}

/// Marks the simulated computer from the demo pairing code.
struct DemoBadge: View {
    var body: some View {
        Text("Demo")
            .typeset(11, .semibold, ink: AppColor.primary)
            .padding(EdgeInsets(top: 2, leading: 7, bottom: 2, trailing: 7))
            .background(AppColor.primary.opacity(0.14), in: .capsule)
    }
}
