import SwiftUI
import simd

/// Small animated "working" mark. Seven shapes; the user picks one in Settings (or random).
struct ThinkingIndicatorView: View {
    var style: ThinkingIndicatorStyle? = nil
    var size: CGFloat = 16

    static let concrete = ThinkingIndicatorStyle.allCases.filter { $0 != .random }

    @AppStorage("agy.pref.thinkingStyle") private var preference = ThinkingIndicatorStyle.random.rawValue
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @State private var rolled: ThinkingIndicatorStyle?
    @State private var startedAt = Date.now

    private var resolvedShape: ThinkingIndicatorStyle {
        let chosen = style ?? ThinkingIndicatorStyle(rawValue: preference) ?? .random
        guard chosen == .random else { return chosen }
        return rolled ?? .ring
    }

    var body: some View {
        let shape = resolvedShape
        let dark = scheme == .dark
        let side = size
        TimelineView(.animation(minimumInterval: 1 / 30.0, paused: scenePhase != .active)) { tick in
            let elapsed = tick.date.timeIntervalSince(startedAt)
            Canvas { gc, area in
                let square = CGRect(origin: .zero, size: CGSize(width: side, height: side))
                    .offsetBy(dx: 0.5 * (area.width - side), dy: 0.5 * (area.height - side))
                var pen = Pen(box: square, time: elapsed, dark: dark, gc: gc)
                pen.draw(shape)
            }
        }
        .chatSize(24, 24)
        .onAppear {
            startedAt = .now
            rolled = Self.concrete.randomElement()
        }
        .accessibilityElement()
        .accessibilityLabel(Text("Processing"))
    }
}

/// One frame of drawing. Fractions of the drawing square are written as unit coordinates.
private struct Pen {
    let box: CGRect
    let time: Double
    let dark: Bool
    var gc: GraphicsContext

    private var s: CGFloat { box.width }
    private var weight: CGFloat { max(2, s * 0.15) }
    private var line: StrokeStyle { .init(lineWidth: weight, lineCap: .round, lineJoin: .round) }
    private var trackInk: GraphicsContext.Shading { .color(AppColor.onSurface.opacity(dark ? 0.14 : 0.08)) }

    mutating func draw(_ shape: ThinkingIndicatorStyle) {
        switch shape {
        case .ring, .random: ring()
        case .prompt: prompt()
        case .braille: braille()
        case .infinity: figureEight()
        case .brackets: brackets()
        case .mobius: mobius()
        case .dots: dots()
        }
    }

    // MARK: colour

    private static let tailRGB = SIMD3<Double>(0.56, 0.42, 1.0)
    private static let midRGB = SIMD3<Double>(0.22, 0.66, 0.98)
    private static let headRGB = SIMD3<Double>(0.10, 0.82, 0.72)

    /// Comet colour at position `u` (0 tail … 1 head), quantised to 1/48 steps.
    static func ink(_ u: Double) -> GraphicsContext.Shading {
        let q = (u.clamped01 * 48).rounded() / 48
        let rgb: SIMD3<Double> = q <= 0.6
            ? tailRGB + (midRGB - tailRGB) * (q / 0.6)
            : midRGB + (headRGB - midRGB) * ((q - 0.6) / 0.4)
        return .color(Color(red: rgb.x, green: rgb.y, blue: rgb.z).opacity(min(1, 1.15 * q + 0.08)))
    }

    // MARK: helpers

    private func at(_ ux: CGFloat, _ uy: CGFloat) -> CGPoint {
        CGPoint(x: box.minX + ux * s, y: box.minY + uy * s)
    }

    private func polyline(_ points: [CGPoint]) -> Path {
        Path { p in p.addLines(points) }
    }

    private func disc(_ centre: CGPoint, _ radius: CGFloat) -> Path {
        Path(ellipseIn: CGRect(origin: centre, size: .zero).insetBy(dx: -radius, dy: -radius))
    }

    /// 24 stroked pieces from the tail (faint) to the head (bright).
    private mutating func comet(_ path: Path, front head: Double, span length: Double, loop closed: Bool) {
        let count: Double = 24
        for k in stride(from: 1.0, through: count, by: 1) {
            var lo = head - length * (1 - (k - 1) / count)
            var hi = head - length * (1 - k / count)
            if closed {
                lo = lo.wrapped01
                hi = hi.wrapped01
                if hi < lo { hi = 1 }
            } else {
                lo = lo.clamped01
                hi = hi.clamped01
                guard hi > lo else { continue }
            }
            gc.stroke(path.trimmedPath(from: lo, to: hi), with: Self.ink(k / count), style: line)
        }
    }

    // MARK: shapes

    private mutating func ring() {
        let loop = Path(ellipseIn: box.insetBy(dx: weight * 0.5, dy: weight * 0.5))
        gc.stroke(loop, with: trackInk, style: line)
        let wave = sin(time * 2.4)
        let head = (time * 0.83 + 0.11 * sin(time * 2.4 - 0.8)).wrapped01
        comet(loop, front: head, span: 0.475 + 0.225 * wave, loop: true)
    }

    private mutating func prompt() {
        let chevron = polyline([at(0.08, 0.2), at(0.44, 0.5), at(0.08, 0.8)])
        let cursor = polyline([at(0.56, 0.8), at(0.94, 0.8)])
        gc.stroke(chevron, with: trackInk, style: line)
        gc.stroke(cursor, with: trackInk, style: line)

        let phase = time.truncatingRemainder(dividingBy: 1.6) / 1.6
        let sweep = min(1, phase / 0.55)
        comet(chevron, front: 1.6 * sweep * sweep, span: 0.6, loop: false)
        let blink = phase >= 0.5 ? (1 - cos(8 * .pi * (phase - 0.5))) / 2 : 0
        let mint = AppColor.brandMint.opacity(0.25 + blink * 0.75)
        gc.stroke(cursor, with: .color(mint), style: line)
    }

    private mutating func braille() {
        // Clockwise walk around a 2 × 4 cell: (column, row).
        let walk: [SIMD2<Double>] = [[0, 0], [1, 0], [1, 1], [1, 2], [1, 3], [0, 3], [0, 2], [0, 1]]
        let pitch = SIMD2<Double>(0.34, 0.27) * Double(s)
        let origin = SIMD2<Double>(Double(box.midX) - pitch.x / 2, Double(box.midY) - pitch.y * 1.5)
        let lead = time * 9
        for (k, cell) in walk.enumerated() {
            let lag = (lead - Double(k)).positiveRemainder(8)
            let glow = max(0, 1 - lag / 4.5)
            let c = origin + cell * pitch
            let dot = disc(CGPoint(x: c.x, y: c.y), s * 0.1 * (0.75 + 0.35 * glow))
            gc.fill(dot, with: trackInk)
            if glow > 0.001 { gc.fill(dot, with: Self.ink(glow)) }
        }
    }

    private mutating func figureEight() {
        let curve = Lemniscates.path(in: box, inset: weight / 2)
        gc.stroke(curve, with: trackInk, style: line)
        comet(curve, front: (time * 0.55).wrapped01, span: 0.42 + 0.08 * sin(time * 1.7), loop: true)
    }

    private mutating func brackets() {
        let spread = s * (0.03 + 0.05 * (0.5 + 0.5 * sin(time * 3)))
        let upper = box.minY + s * 0.18, lower = box.maxY - s * 0.18
        for side in [CGFloat(-1), 1] {
            let foot = box.midX + side * (s * 0.2 + spread)
            let tip = box.midX + side * (s * 0.46 + spread)
            let angle = polyline([CGPoint(x: foot, y: upper), CGPoint(x: tip, y: box.midY), CGPoint(x: foot, y: lower)])
            gc.stroke(angle, with: Self.ink(side < 0 ? 0.45 : 0.85), style: line)
        }
        for k in 0..<3 {
            let wave = 0.5 + 0.5 * sin(time * 6 - 0.9 * Double(k))
            let centre = CGPoint(x: box.midX + CGFloat(k - 1) * s * 0.17, y: box.midY - s * 0.06 * wave)
            gc.fill(disc(centre, s * 0.06 * (0.7 + 0.5 * wave)), with: Self.ink(0.35 + 0.65 * wave))
        }
    }
}

extension Pen {
    /// A Möbius strip turning in space: a lit band with one half twist, seen in perspective and
    /// drawn back to front, with a bright stretch running along it. Each facet is shaded by how
    /// it faces the light (both sides of the band are lit), with a glint where it faces it head on.
    fileprivate mutating func mobius() {
        let n = 96
        let spin = time * 0.6
        let tilt = 1.0 + 0.16 * sin(time * 0.7)
        let yaw = 0.28 * sin(time * 0.45)
        let radius = 0.30, width = 0.16, focal = 1.7
        func point(_ u: Double, _ v: Double) -> SIMD3<Double> {
            let half = u / 2
            let x = (radius + v * cos(half)) * cos(u + spin)
            let y = (radius + v * cos(half)) * sin(u + spin)
            let z = v * sin(half)
            // Tip towards the viewer (x axis), then sway side to side (y axis).
            let y1 = y * cos(tilt) - z * sin(tilt), z1 = y * sin(tilt) + z * cos(tilt)
            return SIMD3(x * cos(yaw) + z1 * sin(yaw), y1, -x * sin(yaw) + z1 * cos(yaw))
        }
        func screen(_ p: SIMD3<Double>) -> CGPoint {
            let k = focal / (focal - p.z)
            return at(0.5 + CGFloat(p.x * k) * 1.25, 0.5 + CGFloat(p.y * k) * 1.25)
        }
        let light = simd_normalize(SIMD3<Double>(-0.45, -0.6, 0.75))
        struct Facet { let corners: [SIMD3<Double>]; let u: Double; let depth: Double }
        var facets: [Facet] = []
        for i in 0..<n {
            let u0 = Double(i) / Double(n) * 2 * .pi, u1 = Double(i + 1) / Double(n) * 2 * .pi
            let c = [point(u0, -width), point(u1, -width), point(u1, width), point(u0, width)]
            facets.append(Facet(corners: c, u: Double(i) / Double(n), depth: c.map(\.z).reduce(0, +) / 4))
        }
        facets.sort { $0.depth < $1.depth }
        let head = (time * 0.35).wrapped01
        for f in facets {
            let c = f.corners
            let pts = c.map(screen)
            let path = Path { p in p.addLines(pts); p.closeSubpath() }
            let normal = simd_normalize(simd_cross(c[1] - c[0], c[3] - c[0]))
            let facing = abs(simd_dot(normal, light))
            let near = ((f.depth + 0.35) / 0.7).clamped01
            let lit = 0.22 + 0.78 * facing
            let behind = (head - f.u).wrapped01
            let glow = max(0, 1 - behind / 0.55)
            gc.fill(path, with: trackInk)
            gc.fill(path, with: Self.ink(0.3 + 0.7 * glow * (0.5 + 0.5 * near)))
            // Shade: facets turned away from the light and further back go darker.
            gc.fill(path, with: .color(.black.opacity((1 - lit) * 0.62 + (1 - near) * 0.18)))
            // Glint where the band faces the light.
            let glint = pow(facing, 14) * (0.35 + 0.4 * near)
            if glint > 0.02 { gc.fill(path, with: .color(.white.opacity(glint))) }
            // The band's two rims, so its edge reads against the twist.
            let rim = Path { p in p.move(to: pts[0]); p.addLine(to: pts[1]); p.move(to: pts[2]); p.addLine(to: pts[3]) }
            gc.stroke(rim, with: .color(.white.opacity(0.10 + 0.22 * near * facing)), lineWidth: max(0.5, s * 0.012))
        }
    }

    /// Dots that keep re-forming through eleven figures (about 46 s for the whole run). The count
    /// changes only between figures: a new dot splits off a neighbour and grows, a leaving one
    /// shrinks into a neighbour, so nothing pops in or out.
    fileprivate mutating func dots() {
        let hold = 4.2, morph = 1.3
        let figures = DotFigures.all
        let step = time / hold
        let index = Int(step.rounded(.down))
        let local = (step - step.rounded(.down)) * hold
        let blend = DotFigures.smooth((local - (hold - morph)) / morph)
        let from = figures[index % figures.count], to = figures[(index + 1) % figures.count]
        let n = max(from.count, to.count)
        for i in 0..<n {
            let a = from.place(i % from.count, from.count, time)
            let b = to.place(i % to.count, to.count, time)
            let sizeA = i < from.count ? from.size(i, time) : 0
            let sizeB = i < to.count ? to.size(i, time) : 0
            let p = a + (b - a) * blend
            let size = sizeA + (sizeB - sizeA) * blend
            guard size > 0.02 else { continue }
            let tone = 0.5 + 0.5 * sin(time * 2.6 - Double(i) * 0.75)
            gc.fill(disc(at(0.5 + CGFloat(p.x), 0.5 + CGFloat(p.y)), s * CGFloat(size)), with: Self.ink(0.35 + 0.65 * tone))
        }
    }
}

/// The figures the dots form. Positions are offsets from the centre in units of the square.
private struct DotFigure {
    let count: Int
    let place: (Int, Int, Double) -> SIMD2<Double>
    var radius: Double = 0.07
    var pulse: ((Int, Double) -> Double)? = nil

    func size(_ i: Int, _ t: Double) -> Double { radius * (pulse?(i, t) ?? 1) }
}

private enum DotFigures {
    static func smooth(_ x: Double) -> Double { let c = Swift.min(1, Swift.max(0, x)); return c * c * (3 - 2 * c) }
    static func polar(_ r: Double, _ a: Double) -> SIMD2<Double> { SIMD2(cos(a), sin(a)) * r }

    nonisolated(unsafe) static let all: [DotFigure] = [
        // A turning ring of six.
        DotFigure(count: 6, place: { i, n, t in polar(0.32, 2 * .pi * Double(i) / Double(n) + t * 1.6) }),
        // Three in a row, hopping one after another.
        DotFigure(count: 3, place: { i, _, t in SIMD2(Double(i - 1) * 0.28, 0.08 - 0.26 * abs(sin(t * 4 - Double(i) * 0.7))) },
                  radius: 0.09),
        // A 3 × 3 grid with a ripple running out from the middle.
        DotFigure(count: 9, place: { i, _, t in
            let c = SIMD2(Double(i % 3 - 1), Double(i / 3 - 1))
            let d = (c.x * c.x + c.y * c.y).squareRoot()
            return c * (0.27 + 0.05 * sin(t * 4.5 - d * 2.2))
        }, radius: 0.058),
        // A spiral arm winding round.
        DotFigure(count: 8, place: { i, _, t in polar(0.06 + 0.04 * Double(i), Double(i) * 0.85 - t * 2.4) },
                  radius: 0.05, pulse: { i, _ in 0.7 + 0.06 * Double(i) }),
        // Two pairs, each spinning while they circle each other.
        DotFigure(count: 4, place: { i, _, t in
            let pair = Double(i / 2), side = i % 2 == 0 ? 1.0 : -1.0
            return polar(0.2, t * 1.3 + pair * .pi) + polar(0.12 * side, t * 4.2)
        }, radius: 0.075),
        // One dot beating like a heart.
        DotFigure(count: 1, place: { _, _, _ in .zero }, radius: 0.16, pulse: { _, t in
            let beat = (t * 1.4).truncatingRemainder(dividingBy: 1)
            return 0.75 + 0.3 * exp(-pow((beat - 0.1) * 14, 2)) + 0.18 * exp(-pow((beat - 0.32) * 14, 2))
        }),
        // A wave of seven.
        DotFigure(count: 7, place: { i, _, t in SIMD2((Double(i) - 3) * 0.12, 0.2 * sin(t * 4.2 - Double(i) * 0.8)) },
                  radius: 0.055),
        // A turning triangle: corners and mid-points.
        DotFigure(count: 6, place: { i, _, t in
            let corner = Double(i / 2) * 2 * .pi / 3 + t * 1.1
            if i % 2 == 0 { return polar(0.34, corner) }
            return (polar(0.34, corner) + polar(0.34, corner + 2 * .pi / 3)) * 0.5
        }, radius: 0.058),
        // Five chasing round a figure-eight.
        DotFigure(count: 5, place: { i, _, t in
            let a = t * 1.8 - Double(i) * 0.42
            return SIMD2(0.36 * sin(a), 0.3 * sin(a) * cos(a))
        }, radius: 0.06, pulse: { i, _ in 1 - 0.13 * Double(i) }),
        // A cross that breathes out and in.
        DotFigure(count: 5, place: { i, _, t in
            i == 0 ? .zero : polar(0.16 + 0.14 * (0.5 + 0.5 * sin(t * 3)), Double(i) * .pi / 2 + .pi / 4)
        }, radius: 0.065),
        // Four corners swapping places round the square.
        DotFigure(count: 4, place: { i, _, t in
            let turn = smooth((t * 0.9).truncatingRemainder(dividingBy: 1) * 1.6)
            return polar(0.3, (Double(i) + (t * 0.9).rounded(.down) + turn) * .pi / 2 + .pi / 4)
        }, radius: 0.075),
    ]
}

/// The figure-eight is the same every frame for a given square, so it is built once.
private enum Lemniscates {
    nonisolated(unsafe) private static var built: [String: Path] = [:]
    private static let guardLock = NSLock()

    static func path(in box: CGRect, inset: CGFloat) -> Path {
        let key = "\(box.minX):\(box.minY):\(box.width):\(inset)"
        guardLock.lock(); defer { guardLock.unlock() }
        if let ready = built[key] { return ready }
        if built.count > 16 { built = [:] }
        let halfWidth = box.width / 2 - inset
        let halfHeight = box.width * 0.372
        let curve = Path { p in
            let samples = (0...72).map { step -> CGPoint in
                let theta = Double(step) * (2 * .pi / 72)
                return CGPoint(x: box.midX + halfWidth * sin(theta), y: box.midY + halfHeight * sin(theta) * cos(theta))
            }
            p.addLines(samples)
            p.closeSubpath()
        }
        built[key] = curve
        return curve
    }
}

private extension Double {
    var clamped01: Double { Swift.min(1, Swift.max(0, self)) }
    var wrapped01: Double { self - self.rounded(.down) }
    func positiveRemainder(_ m: Double) -> Double {
        let r = truncatingRemainder(dividingBy: m)
        return r < 0 ? r + m : r
    }
}
