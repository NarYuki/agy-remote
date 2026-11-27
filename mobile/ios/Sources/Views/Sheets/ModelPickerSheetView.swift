import SwiftUI

struct ModelPickerSheetView: View {
    /// How many models sit in the first section before "More models".
    private let featuredCount = 4
    @Bindable var viewModel: RemoteWorkspaceViewModel
    @Environment(\.dismiss) private var closeSheet

    private var featured: [AgyBaseModel] {
        var list = Array(viewModel.baseModels.prefix(featuredCount))
        if let chosen = viewModel.selectedBaseModel, !list.contains(where: { $0.id == chosen.id }) {
            list.append(chosen)
        }
        return list
    }

    private var others: [AgyBaseModel] {
        let shown = Set(featured.map(\.id))
        return viewModel.baseModels.filter { !shown.contains($0.id) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(featured) { model in modelButton(model) }
                }
                if !viewModel.availableEffortsForSelectedModel.isEmpty {
                    Section {
                        NavigationLink {
                            EffortPage(viewModel: viewModel)
                        } label: {
                            LabeledContent("Effort", value: viewModel.selectedEffortOption?.displayName ?? "")
                        }
                    }
                }
                let rest = others
                if !rest.isEmpty {
                    Section {
                        NavigationLink("More models") {
                            List {
                                Section {
                                    ForEach(rest) { model in modelButton(model) }
                                }
                            }
                            .inlineTitle("More models")
                        }
                    }
                }
            }
            .inlineTitle("Select model")
            .closeToolbar { closeSheet() }
        }
    }

    private func modelButton(_ model: AgyBaseModel) -> some View {
        Button {
            viewModel.selectBaseModel(model)
            closeSheet()
        } label: {
            CheckRow(title: model.name, checked: viewModel.selectedBaseModel?.id == model.id)
                .gap(.vertical, 2)
        }
        .plainTap()
    }
}

private struct CheckRow: View {
    let title: String
    let checked: Bool

    var body: some View {
        HStack {
            Text(verbatim: title)
                .typeset(17, ink: AppColor.onSurface)
            Spacer()
            if checked {
                Image.sf("checkmark")
                    .typeset(16, .semibold, ink: AppColor.primary)
            }
        }
        .contentShape(.rect)
    }
}

/// Effort levels for the selected model; picking one keeps the page open.
private struct EffortPage: View {
    let viewModel: RemoteWorkspaceViewModel

    var body: some View {
        List {
            Section {
                ForEach(viewModel.availableEffortsForSelectedModel) { option in
                    Button {
                        viewModel.selectEffort(option)
                    } label: {
                        CheckRow(title: option.displayName,
                                 checked: viewModel.selectedEffortOption?.value == option.value)
                    }
                    .plainTap()
                }
            } footer: {
                Text.caption("Higher effort means more thorough responses, but takes longer and uses your limits faster.")
            }
        }
        .inlineTitle("Effort")
    }
}
