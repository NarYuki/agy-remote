// SPDX-License-Identifier: AGPL-3.0-only
import SwiftUI

/// The phone app's brand colours, for a dark wrist.
enum WatchStyle {
    static let mint = Color(red: 0x1A / 255, green: 0xD1 / 255, blue: 0xB8 / 255)
    static let sky = Color(red: 0x38 / 255, green: 0xA8 / 255, blue: 0xFA / 255)
    static let violet = Color(red: 0x8F / 255, green: 0x6B / 255, blue: 0xFF / 255)
    static let amber = Color(red: 0xFB / 255, green: 0xBF / 255, blue: 0x4A / 255)
    static let online = Color(red: 0x6E / 255, green: 0xE7 / 255, blue: 0xA0 / 255)
    static let userBubble = Color(red: 0x18 / 255, green: 0x20 / 255, blue: 0x24 / 255)

    static let brand = LinearGradient(colors: [mint, sky, violet], startPoint: .leading, endPoint: .trailing)
    static let ring = AngularGradient(colors: [violet, sky, mint, violet], center: .center)
    static func page(_ tone: Color) -> LinearGradient {
        LinearGradient(colors: [tone.opacity(0.34), .black], startPoint: .top, endPoint: .bottom)
    }
    static let backdrop = LinearGradient(colors: [violet.opacity(0.28), .black], startPoint: .top, endPoint: .bottom)
}

/// A spinning comet ring in the brand gradient, shown next to running chats.
struct RunningMark: View {
    var size: CGFloat = 14

    var body: some View {
        TimelineView(.animation) { clock in
            let turn = clock.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.1) / 1.1
            Circle()
                .trim(from: 0.08, to: 1)
                .stroke(AngularGradient(colors: [WatchStyle.violet.opacity(0), WatchStyle.violet, WatchStyle.sky, WatchStyle.mint],
                                        center: .center),
                        style: StrokeStyle(lineWidth: size / 6, lineCap: .round))
                .rotationEffect(.degrees(turn * 360))
        }
        .frame(width: size, height: size)
        .accessibilityLabel(Text("Running"))
    }
}

/// The `>_` prompt glyph, the app's motif, in SF Mono.
struct PromptGlyph: View {
    var size: CGFloat
    var gradient = true

    var body: some View {
        Text(verbatim: ">_")
            .font(.system(size: size, weight: .bold, design: .monospaced))
            .foregroundStyle(gradient ? AnyShapeStyle(WatchStyle.brand) : AnyShapeStyle(.primary))
            .accessibilityHidden(true)
    }
}

/// The `>_` prompt inside a ring that turns in the brand gradient while agy works.
struct PromptRing: View {
    var spinning: Bool

    var body: some View {
        GeometryReader { box in
            let side = min(box.size.width, box.size.height)
            ring(side)
        }
        .aspectRatio(1, contentMode: .fit)
        .accessibilityLabel(spinning ? Text("Running") : Text("Idle"))
    }

    private func ring(_ side: CGFloat) -> some View {
        let line = max(2, side * 0.09)
        return TimelineView(.animation(paused: !spinning)) { clock in
            let t = clock.date.timeIntervalSinceReferenceDate
            ZStack {
                Circle()
                    .stroke(Color.white.opacity(0.1), lineWidth: line)
                Circle()
                    .trim(from: 0, to: spinning ? 0.72 : 1)
                    .stroke(spinning ? AnyShapeStyle(WatchStyle.ring) : AnyShapeStyle(Color.white.opacity(0.25)),
                            style: StrokeStyle(lineWidth: line, lineCap: .round))
                    .rotationEffect(.degrees(spinning ? (t.truncatingRemainder(dividingBy: 1.4) / 1.4) * 360 : -90))
                    .padding(line / 2)
                if side >= 30 { PromptGlyph(size: side * 0.36, gradient: spinning) }
            }
            .frame(width: side, height: side)
        }
    }
}

extension View {
    /// Capsule chrome for the app's compact controls (chips, reply bar, Stop, secondary actions).
    func pill(_ fill: Color, height: CGFloat = 28, inset: CGFloat = 11) -> some View {
        padding(.horizontal, inset)
            .frame(minHeight: height)
            .background(fill, in: Capsule())
    }
}
