import SwiftUI

/// Curtains of light rising from the bottom edge, behind the empty chat and the
/// setup pages. Opaque: it paints the surface colour as well, so put it directly on the page.
/// All values come from the shared `GradientFlow` driver; the display link only runs while an
/// ambient view is mounted.
struct WelcomeBackdropView: View {
    let flow: GradientFlow
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        let b = flow.backdrop
        let shader = ShaderLibrary.aurora(
            .boundingRect, .float(b.clock), .float(b.tide), .float(b.presence),
            .float(b.stirLevel), .float(b.lift), .float(b.swell),
            .float(scheme == .dark ? 1 : 0))
        Rectangle()
            .fill(AppColor.surface)
            .colorEffect(shader)
            .allowsHitTesting(false)
            .accessibilityHidden(true)
            .onAppear { flow.ambientAppeared() }
            .onDisappear { flow.ambientDisappeared() }
    }
}

/// Layered waves of brand colour hanging from the top edge while the agent works.
/// Meant for a full-screen overlay above the transcript; it never takes touches.
/// Mount it while `flow.glowVisible` is set.
struct HeaderGlowView: View {
    let flow: GradientFlow
    /// How far the transcript is scrolled up from its end, as a share of the viewport.
    var scrollAway: Double = 0
    @Environment(\.colorScheme) private var scheme
    /// Scroll position when the current turn started: dimming counts from there, so a new turn
    /// always starts at full strength.
    @State private var baseline: Double?

    var body: some View {
        GeometryReader { geo in
            let away = max(0, scrollAway - (baseline ?? 0))
            let dim = GradientFlow.scrollDimming(distance: away * geo.size.height, viewport: geo.size.height)
            let c = flow.crown
            let level = c.level * dim
            if level > 0.001 {
                Rectangle()
                    .fill(.white)
                    .colorEffect(ShaderLibrary.crown(
                        .boundingRect, .float(c.hue), .float(c.drift), .float(level),
                        .float(c.reach), .float(scheme == .dark ? 1 : 0)))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
        .onAppear { flow.ambientAppeared(); baseline = scrollAway }
        .onDisappear { flow.ambientDisappeared() }
        .onChange(of: flow.turnToken) { baseline = scrollAway }
        .onChange(of: scrollAway) { _, new in
            // Back at (or below) the baseline: drop it so dimming tracks the real position again.
            if let b = baseline, new <= b { baseline = nil }
        }
    }
}
