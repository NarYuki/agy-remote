import SwiftUI
import UIKit

// MARK: - Colour tokens

/// The app's palette. Every token resolves per trait collection, so it follows light/dark.
enum AppColor {
    /// Builds a colour from two 0xRRGGBB values, picked by the current interface style.
    fileprivate static func pair(_ day: UInt32, _ night: UInt32) -> Color {
        let lightUI = UIColor(hexValue: day), darkUI = UIColor(hexValue: night)
        return Color(uiColor: UIColor { traits in traits.userInterfaceStyle == .dark ? darkUI : lightUI })
    }

    static let surface = pair(0xFFFFFF, 0x000000)
    static let surfaceLowest = pair(0xFFFFFF, 0x000000)
    static let surfaceContainerLow = pair(0xF6F9F9, 0x0B0E0F)
    static let surfaceContainer = pair(0xEEF3F3, 0x14181A)
    static let surfaceContainerHigh = pair(0xE6EDED, 0x1B2023)
    static let surfaceContainerHighest = pair(0xD9E2E2, 0x262C30)
    static let sidebarActivePill = pair(0xE3ECEC, 0x12171A)
    static let userBubble = pair(0xE6EDED, 0x182024)

    static let onSurface = pair(0x1A1F20, 0xEEF2F1)
    static let onSurfaceVariant = pair(0x5A6668, 0x98A3A6)
    static let onSurfaceMuted = pair(0x6F7A7C, 0x6F7A7D)
    static let outlineVariant = pair(0xC2CBCB, 0x343C40)

    static let primary = pair(0x0A66D8, 0x4C9BFF)
    static let onPrimary = pair(0xFFFFFF, 0xFFFFFF)
    static let accentDot = pair(0x0A66D8, 0x4C9BFF)
    static let accentFill = pair(0x111111, 0xF2F2F2)
    static let onAccentFill = pair(0xFFFFFF, 0x0B0B0C)
    static let accentFillMuted = pair(0xC9CDD1, 0x3A3D42)
    static let stopButtonFill = pair(0xDDE5E5, 0x262C30)
    static let accentRing = pair(0x9AA0A6, 0x5F6368)

    static let connectedGreen = pair(0x15803D, 0x6EE7A0)
    static let warningAmber = pair(0xB45309, 0xFBBF4A)
    static let errorRed = pair(0xB42318, 0xFDA29B)

    // MARK: Brand

    private static let mintHex: UInt32 = 0x1AD1B8
    private static let skyHex: UInt32 = 0x38A8FA
    private static let violetHex: UInt32 = 0x8F6BFF
    private static let roseHex: UInt32 = 0xFF709E

    /// Raw in dark mode; in light mode every channel moves 30 % toward white.
    private static func brand(_ hex: UInt32) -> Color {
        let (r, g, b) = rgb(hex)
        let wash = { (c: Double) in c + (1 - c) * 0.3 }
        let lightUI = UIColor(red: wash(r), green: wash(g), blue: wash(b), alpha: 1)
        let darkUI = UIColor(red: r, green: g, blue: b, alpha: 1)
        return Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? darkUI : lightUI })
    }

    static let brandMint = brand(mintHex)
    static let brandSky = brand(skyHex)
    static let brandViolet = brand(violetHex)
    static let brandRose = brand(roseHex)
    static let brandRamp: [Color] = [brandMint, brandSky, brandViolet, brandRose]

    /// Unwashed in both themes (the brand mark).
    static let brandMintRaw = Color(uiColor: UIColor(hexValue: mintHex))
    static let brandVioletRaw = Color(uiColor: UIColor(hexValue: violetHex))

    /// The raw ramp as 0–1 channel triples, for code that blends between stops.
    static let rampRGB: [(Double, Double, Double)] = [mintHex, skyHex, violetHex, roseHex].map(rgb)

    /// 0xRRGGBB split into 0–1 channels.
    static func rgb(_ hex: UInt32) -> (Double, Double, Double) {
        (Double((hex >> 16) & 0xFF) / 255, Double((hex >> 8) & 0xFF) / 255, Double(hex & 0xFF) / 255)
    }
}

private extension UIColor {
    convenience init(hexValue: UInt32) {
        let c = AppColor.rgb(hexValue)
        self.init(red: c.0, green: c.1, blue: c.2, alpha: 1)
    }
}

// MARK: - Motion

extension Animation {
    private static func springy(_ r: Double, _ d: Double) -> Animation { .spring(response: r, dampingFraction: d) }

    static var listPress: Animation { springy(0.22, 0.88) }
    static var pill: Animation { springy(0.28, 0.84) }
    static var inputMorph: Animation { springy(0.30, 0.86) }
    static var drawerClose: Animation { springy(0.26, 1.0) }
    static var drawerOpen: Animation { springy(0.34, 0.92) }
    static var melt: Animation { .smooth(duration: 0.5) }
}

/// The hidden half of the melt transition: faded out, blurred, a touch smaller.
private struct Melted: ViewModifier {
    var amount: Double
    func body(content: Content) -> some View {
        let k = CGFloat(amount)
        return content.scaleEffect(1 - k * 0.06).blur(radius: k * 8).opacity(1 - Double(k))
    }
}

extension AnyTransition {
    static var melt: AnyTransition {
        let gone = Melted(amount: 1), shown = Melted(amount: 0)
        return .modifier(active: gone, identity: shown)
    }
}

// MARK: - Type

enum AppFont {
    private static func sys(_ pt: CGFloat, _ w: Font.Weight = .regular) -> Font { Font.system(size: pt, weight: w) }

    static let body = sys(16.5)
    static let sidebarItem = sys(16.5)
    static let secondary = sys(14.5)
    static let small = sys(12.5)
    static let label = sys(14, .medium)
    static let sidebarBrand = sys(22)
    static let headerBrand = sys(17.5, .medium)
    static let headerModel = sys(17.5)
    static let code = Font.system(size: 13, design: .monospaced)

    static let bodyLarge = body
    static let bodyMedium = secondary
    static let labelLarge = label
    static let labelSmall = small
    static let codeBlock = code
}

enum AppMetrics {
    static let inputPlateMaxWidth: CGFloat = 660
    static let headerButtonSize: CGFloat = 42
    static let headerHeight: CGFloat = 46
    static let inputPlateHeight: CGFloat = 58
    static let sidebarHeaderButtonSize: CGFloat = 44
    static let inputPlateIdleMargin: CGFloat = 24, inputPlateFocusedMargin: CGFloat = 14
    static let inputPlateBottomMargin: CGFloat = 10
    static let userBubbleCornerRadius: CGFloat = 24
}

/// Shorthand used by the app's own views: font size/weight/design plus a foreground colour.
extension View {
    func agyText(_ pt: CGFloat, _ w: Font.Weight = .regular, mono: Bool = false, _ tone: Color? = nil) -> some View {
        self.font(Font.system(size: pt, weight: w, design: mono ? .monospaced : .default))
            .foregroundStyle(tone.map(AnyShapeStyle.init) ?? AnyShapeStyle(.foreground))
    }

    /// Liquid Glass in `shape` (interactive unless told otherwise), optionally tied to a morph id.
    @ViewBuilder
    func agyGlass<S: Shape>(_ shape: S, still: Bool = false, morph: String? = nil, in space: Namespace.ID? = nil) -> some View {
        let material: Glass = still ? .regular : .regular.interactive()
        if let morph, let space {
            glassEffect(material, in: shape).glassEffectID(morph, in: space)
        } else {
            glassEffect(material, in: shape)
        }
    }

    /// A fixed box that is hit-testable everywhere inside `shape`.
    func agyBox<S: Shape>(_ w: CGFloat, _ h: CGFloat, hit shape: S = Rectangle()) -> some View {
        frame(width: w, height: h).contentShape(shape)
    }
}

@MainActor func agyResignFirstResponder() {
    UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
}

// MARK: - Appearance preference

enum ThemePreference: String, CaseIterable, Identifiable {
    case system, dark, light

    var id: String { rawValue }

    var title: LocalizedStringKey {
        [ThemePreference.dark: "Dark", .light: "Light"][self] ?? "System"
    }

    var colorScheme: ColorScheme? {
        self == .system ? nil : (self == .dark ? ColorScheme.dark : ColorScheme.light)
    }
}

// MARK: - Haptics

@MainActor
enum Haptics {
    private static let lightTap = UIImpactFeedbackGenerator(style: .light)
    private static let ticker = UISelectionFeedbackGenerator()
    private static let notifier = UINotificationFeedbackGenerator()

    static func impact() {
        lightTap.impactOccurred()
        lightTap.prepare()
    }
    static func select() {
        ticker.selectionChanged()
        ticker.prepare()
    }
    static func success() { notify(.success) }
    static func warning() { notify(.warning) }
    private static func notify(_ kind: UINotificationFeedbackGenerator.FeedbackType) {
        notifier.notificationOccurred(kind)
        notifier.prepare()
    }
}

// MARK: - Glyphs

struct MenuIcon: View {
    var body: some View {
        VStack(alignment: HorizontalAlignment.leading, spacing: 5) {
            ForEach([17, 10.5] as [CGFloat], id: \.self) { len in
                Capsule().size(width: len, height: 1.9).fill(color)
                    .frame(width: len, height: 1.9)
            }
        }
        .frame(width: 17, height: 9, alignment: .leading)
        .accessibilityHidden(true)
    }

    var color: Color = AppColor.onSurface
}

struct StopIcon: View {
    var body: some View {
        Color.clear
            .frame(width: 11.5, height: 11.5)
            .background(color, in: .rect(cornerRadius: 3, style: .continuous))
            .accessibilityHidden(true)
    }

    var color: Color = AppColor.onSurface
}

struct ComposeIcon: View {
    var body: some View {
        let side = size
        return Image(systemName: "square.and.pencil")
            .agyText(side * 0.92, .regular, color)
            .accessibilityHidden(true)
            .frame(width: side, height: side)
    }

    var size: CGFloat = 18
    var color: Color = AppColor.onSurface
}

/// One half of the ">_" mark: the chevron, or (with `cursor`) the underscore bar.
struct BrandStroke: Shape {
    var cursor: Bool

    func path(in rect: CGRect) -> Path {
        let u = min(rect.width, rect.height)
        let origin = CGPoint(x: rect.midX - u / 2, y: rect.midY - u / 2)
        let unit: [(CGFloat, CGFloat)] = cursor
            ? [(0.56, 0.76), (0.90, 0.76)]
            : [(0.12, 0.24), (0.44, 0.50), (0.12, 0.76)]
        return Path { p in
            p.addLines(unit.map { CGPoint(x: origin.x + $0.0 * u, y: origin.y + $0.1 * u) })
        }
    }
}

struct BrandMarkView: View {
    var body: some View {
        let style = StrokeStyle(lineWidth: max(1.7, 0.14 * size), lineCap: .round, lineJoin: .round)
        let fill = paint
        return BrandStroke(cursor: false).stroke(fill, style: style)
            .overlay { BrandStroke(cursor: true).stroke(fill, style: style).opacity(cursorOpacity) }
            .accessibilityHidden(true)
            .frame(width: size, height: size)
    }

    var size: CGFloat = 20
    var color: Color?
    var cursorOpacity: Double = 1

    private var paint: AnyShapeStyle {
        guard let color else {
            let ends = [AppColor.brandMintRaw, AppColor.brandVioletRaw]
            return AnyShapeStyle(LinearGradient(colors: ends, startPoint: UnitPoint.leading, endPoint: UnitPoint.trailing))
        }
        return AnyShapeStyle(color)
    }
}

// MARK: - Quota and context formatting

enum QuotaFormat {
    /// The limit's window in the UI language, for the windows agy reports; anything else as agy names it.
    static func windowName(_ limit: QuotaLimit) -> String {
        switch limit.window {
        case "daily", "day": String(localized: "daily", comment: "Quota window, as in 'Pro: 14% of daily limit left'")
        case "weekly", "week": String(localized: "weekly", comment: "Quota window, as in 'Pro: 14% of weekly limit left'")
        case "five_hours": String(localized: "5-hour", comment: "Quota window, as in 'Pro: 14% of 5-hour limit left'")
        default: limit.label.lowercased()
        }
    }

    static func localePercent(_ value: Double) -> String {
        let pf = NumberFormatter()
        pf.numberStyle = .percent
        pf.maximumFractionDigits = 0
        pf.locale = .current
        return pf.string(from: NSNumber(value: value / 100)) ?? "\(Int(value))%"
    }

    private static let spanFormatter: DateComponentsFormatter = {
        let dc = DateComponentsFormatter()
        dc.allowedUnits = [.minute, .hour, .day]
        dc.maximumUnitCount = 2
        dc.unitsStyle = .abbreviated
        return dc
    }()

    static func resets(_ date: Date?) -> String {
        switch date.map({ $0.timeIntervalSinceNow }) {
        case nil:
            return ""
        case let wait? where wait <= 0:
            return String(localized: "now")
        case let wait?:
            let span = spanFormatter.string(from: Swift.max(60, wait)) ?? ""
            let phrase = String(localized: "in \(span)", comment: "When a limit resets, e.g. 'in 5d 3h'")
            return phrase
        }
    }

    static func limitTint(_ remaining: Double) -> Color {
        if remaining >= 25 { return AppColor.primary }
        return remaining >= 10 ? AppColor.warningAmber : AppColor.errorRed
    }
}

enum ContextFormat {
    private static let warnAbove = 0.75, alarmAbove = 0.9

    static func tint(_ fraction: Double) -> Color {
        guard fraction > warnAbove else { return AppColor.primary }
        return fraction > alarmAbove ? AppColor.errorRed : AppColor.warningAmber
    }

    static func short(_ n: Int) -> String {
        let v = Double(n)
        if n >= 1_000_000 {
            return n.isMultiple(of: 1_000_000) ? "\(n / 1_000_000)M" : String(format: "%.1fM", v / 1e6)
        }
        if n >= 10_000 { return String(format: "%.0fK", v / 1e3) }
        if n >= 1000 { return String(format: "%.1fK", v / 1e3) }
        return String(n)
    }
}

// MARK: - Plan limit gauge

struct PlanLimitGauge: View {
    var body: some View {
        Button(action: action) { face.agyBox(44, 40) }
            .accessibilityLabel(spoken)
            .animation(.smooth, value: limit?.remaining)
            .buttonStyle(.plain)
    }

    let limit: QuotaLimit?
    let action: () -> Void

    private var left: Int? { limit.map { Int($0.remaining.rounded(.down)) } }

    @ViewBuilder private var face: some View {
        if let left {
            let tone = left < 25 ? QuotaFormat.limitTint(Double(left)) : AppColor.onSurfaceVariant
            Text(QuotaFormat.localePercent(Double(left)))
                .contentTransition(.numericText())
                .foregroundStyle(tone)
                .font(Font.system(size: 13, weight: .semibold, design: .default).monospacedDigit())
        } else {
            Image(systemName: "gauge.with.dots.needle.33percent")
                .agyText(15, .medium, AppColor.onSurfaceVariant)
        }
    }

    private var spoken: Text {
        guard let limit, let left else { return Text("Usage") }
        let owner = limit.group.isEmpty ? limit.label : limit.group
        return Text("\(owner) limit, \(left) percent left")
    }
}

// MARK: - Low quota notice

struct QuotaLowNotice: View {
    var body: some View {
        let pct = QuotaFormat.localePercent(limit.remaining)
        let who = limit.group.isEmpty ? limit.label : limit.group
        let tint = QuotaFormat.limitTint(limit.remaining)
        HStack(alignment: .center, spacing: 8) {
            Image(systemName: "gauge.with.dots.needle.33percent").agyText(12.5, .semibold, tint)
            Text("\(who): \(pct) of \(QuotaFormat.windowName(limit)) limit left")
                .agyText(13, .medium, limit.remaining > 25 ? AppColor.onSurface : tint)
            if let when = limit.resets {
                Text("· resets \(QuotaFormat.resets(when))").agyText(13, .regular, AppColor.onSurfaceVariant)
            }
        }
        .minimumScaleFactor(0.85)
        .lineLimit(1)
        .padding(EdgeInsets(top: 8, leading: 14, bottom: 8, trailing: 14))
        .glassEffect(.regular, in: .capsule)
        .opacity(1 - Swift.min(0.7, abs(shift) / 260))
        .offset(x: shift)
        .gesture(DragGesture(minimumDistance: 8).onChanged(follow).onEnded(release))
        .accessibilityAction(named: Text("Dismiss"), onDismiss)
    }

    @State private var shift: CGFloat = 0
    let limit: QuotaLimit
    let onDismiss: () -> Void

    private func follow(_ v: DragGesture.Value) {
        guard abs(v.translation.width) > abs(v.translation.height) else { return }
        shift = v.translation.width
    }

    private func release(_ v: DragGesture.Value) {
        let far = abs(v.translation.width) > 90
        let flung = abs(v.predictedEndTranslation.width) > 180
        guard far || flung else {
            let back = Animation.spring(response: 0.3, dampingFraction: 0.8)
            withAnimation(back) { shift = 0 }
            return
        }
        let dx = v.translation.width == 0 ? v.predictedEndTranslation.width : v.translation.width
        Haptics.impact()
        let away = Animation.easeOut(duration: 0.2)
        withAnimation(away) { shift = dx < 0 ? -500 : 500 }
        let finish = onDismiss
        Task { @MainActor in
            try? await Task.sleep(for: .milliseconds(180))
            finish()
        }
    }
}

// MARK: - Running indicator

struct RunningActivityIcon: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var start = Date.now
    var size: CGFloat = 15
    var tint: Color = AppColor.accentDot

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 30.0, paused: scenePhase != .active)) { ctx in
            let t = ctx.date.timeIntervalSince(start)
            frame(at: t, pulse: 0.72 + 0.28 * sin(3.8 * t))
        }
        .frame(width: size, height: size)
        .onAppear { start = .now }
        .accessibilityElement()
        .accessibilityLabel(Text("Running"))
    }

    private func frame(at t: Double, pulse: Double) -> some View {
        let spin = Angle(degrees: (240 * t).truncatingRemainder(dividingBy: 360))
        let dot = 0.3 * size
        let arc = StrokeStyle(lineWidth: 1.8, lineCap: .round)
        return ZStack {
            Circle().trim(from: 0.1, to: 0.72).stroke(style: arc)
                .foregroundStyle(tint.opacity(pulse))
                .rotationEffect(spin)
            Circle().foregroundStyle(tint.opacity(0.9))
                .scaleEffect(0.6 + 0.25 * (1 + pulse))
                .frame(width: dot, height: dot)
        }
    }
}

// MARK: - Fading content under bars

struct BarEdgeFade: ViewModifier {
    func body(content: Content) -> some View {
        content.mask(GeometryReader(content: ramp).ignoresSafeArea())
    }

    var topEdge: CGFloat
    var bottomEdge: CGFloat?
    var fade: CGFloat = 22

    private func ramp(_ proxy: GeometryProxy) -> LinearGradient {
        let area = proxy.frame(in: .global)
        let span = Swift.max(1, area.height)
        let pos = { (y: CGFloat) -> CGFloat in Swift.min(1, Swift.max(0, (y - area.minY) / span)) }
        let floorY = bottomEdge ?? area.maxY + fade
        let tiny: CGFloat = 0.0001
        let p0 = pos(topEdge - 0.6 * fade)
        let p1 = Swift.max(pos(topEdge + 0.4 * fade), p0 + tiny)
        let p2 = Swift.max(p1, Swift.min(0.9999, pos(floorY - 0.4 * fade)))
        let p3 = Swift.max(pos(floorY + 0.6 * fade), p2 + tiny)
        let dim = Color.black.opacity(0.35)
        return LinearGradient(stops: [.init(color: dim, location: p0), .init(color: .black, location: Swift.min(1, p1)),
                                      .init(color: .black, location: Swift.min(1, p2)), .init(color: dim, location: Swift.min(1, p3))],
                              startPoint: UnitPoint.top, endPoint: UnitPoint.bottom)
    }
}

extension View {
    /// Keeps `into` equal to this view's rounded global top (`.top`) or bottom (`.bottom`) edge.
    func reportGlobalEdge(_ edge: VerticalEdge, into target: Binding<CGFloat>) -> some View {
        let probe = CGFloat.self
        return onGeometryChange(for: probe, of: { g in
            let r = g.frame(in: .global)
            return (edge == .bottom ? r.maxY : r.minY).rounded()
        }, action: { target.wrappedValue = $0 })
    }

    func barEdgeFade(topEdge: CGFloat, bottomEdge: CGFloat? = nil) -> some View {
        let fader = BarEdgeFade(topEdge: topEdge, bottomEdge: bottomEdge, fade: 22)
        return self.modifier(fader)
    }
}
