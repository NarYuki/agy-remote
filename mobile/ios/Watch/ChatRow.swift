// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

struct ChatRow: View {
    let chat: WatchSnapshot.Chat
    @ScaledMetric(relativeTo: .headline) private var glyph: CGFloat = 18
    @ScaledMetric(relativeTo: .headline) private var titleSize: CGFloat = 15
    @ScaledMetric(relativeTo: .caption) private var noteSize: CGFloat = 12

    /// The glyph sits a little inside its 18 pt slot.
    private var mark: CGFloat { glyph - 3 }

    var body: some View {
        HStack(alignment: .center, spacing: 9) {
            Group {
                if chat.isRunning {
                    RunningMark(size: mark)
                } else {
                    Image(systemName: "checkmark.circle.fill")
                        .resizable()
                        .frame(width: mark, height: mark)
                        .foregroundStyle(.white.opacity(0.35))
                }
            }
            .frame(width: glyph, height: glyph)
            VStack(alignment: .leading, spacing: 1) {
                Text(chat.title.isEmpty ? String(localized: "Untitled chat") : chat.title)
                    .font(.system(size: titleSize, weight: .semibold))
                    .lineLimit(2)
                Text(note)
                    .font(.system(size: noteSize))
                    .foregroundStyle(chat.isRunning ? AnyShapeStyle(WatchStyle.sky) : AnyShapeStyle(.secondary))
                    .lineLimit(1)
            }
        }
        .padding(.vertical, 3)
    }

    private var note: String {
        if chat.isRunning { return chat.status.isEmpty ? String(localized: "Working") : chat.status }
        return chat.folder
    }
}

struct ComputerPicker: View {
    let link: PhoneLink
    let done: () -> Void

    var body: some View {
        List(link.snapshot?.computers ?? []) { computer in
            Button {
                Buzz.tap()
                Task {
                    await link.ask(.selectComputer(id: computer.id))
                    done()
                }
            } label: {
                HStack {
                    Circle().fill(computer.online ? WatchStyle.online : .gray).frame(width: 7, height: 7)
                    Text(computer.name)
                    Spacer()
                    if computer.id == link.snapshot?.computer?.id {
                        Image(systemName: "checkmark").foregroundStyle(WatchStyle.sky)
                    }
                }
            }
        }
        .navigationTitle(Text("Computers"))
    }
}
