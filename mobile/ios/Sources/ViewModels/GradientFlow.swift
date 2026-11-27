import Observation
import QuartzCore
import SwiftUI
import UIKit

/// Drives the ambient effects (empty-chat aurora and the header glow) from one display link.
/// Values are published into two small observables so each effect only re-renders for its own data.
@MainActor
@Observable
final class GradientFlow {
    enum Glow { case idle, working, answering, welcome }

    /// Values read by the aurora backdrop.
    @MainActor @Observable
    final class Backdrop {
        fileprivate(set) var tide: Float = 0
        fileprivate(set) var presence: Float = 0
        fileprivate(set) var stirLevel: Float = 0
        fileprivate(set) var lift: Float = 0
        fileprivate(set) var swell: Float = 0
        fileprivate(set) var clock: Float = 0
    }

    /// Values read by the header glow.
    @MainActor @Observable
    final class Crown {
        fileprivate(set) var level: Float = 0
        fileprivate(set) var reach: Float = 0
        fileprivate(set) var hue: Float = 0
        fileprivate(set) var drift: Float = 0
    }

    var glow: Glow = .idle {
        didSet { if glow != oldValue { glowChanged() } }
    }
    private(set) var zeroStateActive = false
    var keyboardVisible = false {
        didSet { if keyboardVisible != oldValue { quiet = 0; wake() } }
    }

    let backdrop = Backdrop()
    let crown = Crown()
    private(set) var glowVisible = false
    private(set) var turnToken = 0

    // MARK: internal state

    private static let fastRange = CAFrameRateRange(minimum: 60, maximum: 120, preferred: 120)
    private static let calmRange = CAFrameRateRange(minimum: 24, maximum: 30, preferred: 30)

    private enum Phase { case idle, processing, response, welcome }

    /// Critically damped follower.
    private struct Follower {
        var value: Double = 0
        var speed: Double = 0

        mutating func step(toward target: Double, settle: Double, dt: Double) {
            let omega = 2 * Double.pi / max(0.05, settle)
            let offset = value - target
            let push = (speed + omega * offset) * dt
            let damp = exp(-omega * dt)
            value = target + (offset + push) * damp
            speed = (speed - omega * push) * damp
            if abs(value - target) < 5e-4, abs(speed) < 5e-4 { value = target; speed = 0 }
        }
    }

    @ObservationIgnored private var phase: Phase = .idle
    @ObservationIgnored private var bloomTimer: Double = 0
    @ObservationIgnored private var kick: Double = 0
    @ObservationIgnored private var tau: Double = 0
    @ObservationIgnored private var hue: Double = 0
    @ObservationIgnored private var drift: Double = 0
    @ObservationIgnored private var clock: Double = 0
    @ObservationIgnored private var stirLevel: Double = 0
    @ObservationIgnored private var swellTimer: Double = 0
    @ObservationIgnored private var quiet: Double = 0
    @ObservationIgnored private var overlap: Double = 0
    @ObservationIgnored private var recordedZero = false

    @ObservationIgnored private var tide = Follower()
    @ObservationIgnored private var presence = Follower()
    @ObservationIgnored private var lift = Follower()
    @ObservationIgnored private var swell = Follower()
    @ObservationIgnored private var glowLevel = Follower()
    @ObservationIgnored private var reach = Follower()

    @ObservationIgnored private var link: CADisplayLink?
    @ObservationIgnored private var lastStamp: CFTimeInterval?
    @ObservationIgnored private var mounted = 0
    @ObservationIgnored private var appActive = true
    @ObservationIgnored private var fastRate: Bool?
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    init() {
        listen(UIApplication.willResignActiveNotification) { flow, _ in
            flow.appActive = false
            flow.link?.isPaused = true
        }
        listen(UIApplication.didBecomeActiveNotification) { flow, _ in
            flow.appActive = true
            flow.wake()
        }
        listen(UIResponder.keyboardWillChangeFrameNotification) { flow, frame in
            guard let frame else { return }
            flow.overlap = max(0, Double(flow.screenHeight - frame.minY))
            flow.keyboardVisible = flow.overlap > 48
        }
        listen(UIResponder.keyboardWillHideNotification) { flow, _ in
            flow.overlap = 0
            flow.keyboardVisible = false
        }
    }

    /// Runs `handle` on the main actor for each `name` notification, passing the keyboard end frame when there is one.
    private func listen(_ name: Notification.Name, _ handle: @escaping @MainActor (GradientFlow, CGRect?) -> Void) {
        let center = NotificationCenter.default
        let token = center.addObserver(forName: name, object: nil, queue: OperationQueue.main) { [weak self] n in
            let info = n.userInfo ?? [:]
            let end = info[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect
            MainActor.assumeIsolated {
                if let self { handle(self, end) }
            }
        }
        observers.append(token)
    }

    private var screenHeight: CGFloat {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let h = scene?.screen.bounds.height ?? 0
        return h > 0 ? h : 874
    }

    // MARK: public API

    func ambientAppeared() {
        mounted += 1
        wake()
    }

    func ambientDisappeared() {
        mounted = max(0, mounted - 1)
        if mounted == 0 { link?.isPaused = true }
    }

    func setZeroStateActive(_ active: Bool, triggerPulse: Bool) {
        if !recordedZero {
            recordedZero = true
            zeroStateActive = active
            quiet = 0
            if active && triggerPulse {
                startSwell()
            } else {
                tide = Follower(value: active ? 0.34 : 0)
                presence = Follower(value: active ? 0.85 : 0)
                publish(advanceClocks: false)
            }
            wake()
            return
        }
        guard active != zeroStateActive || (active && triggerPulse) else { return }
        zeroStateActive = active
        quiet = 0
        if active && triggerPulse { startSwell() }
        wake()
    }

    func stir() {
        stirLevel = min(1, stirLevel + 0.3)
        quiet = 0
        if phase == .processing { applyKick(0.6) }
        wake()
    }

    static func scrollDimming(distance: CGFloat, viewport: CGFloat) -> Float {
        let span = max(0.4 * viewport, 120)
        return Float(min(1, max(0, 1 - max(0, distance) / span)))
    }

    // MARK: phases

    private func glowChanged() {
        if glow != .idle { glowVisible = true }
        switch glow {
        case .working:
            turnToken = turnToken &+ 1
            enter(.processing)
        case .answering:
            if phase != .processing { enter(.processing) }
            applyKick(0.6)
        case .idle:
            if phase == .processing { enter(.response) } else if phase != .response { enter(.idle) }
        case .welcome:
            enter(.welcome)
        }
        wake()
    }

    private func enter(_ next: Phase) {
        phase = next
        switch next {
        case .idle, .processing: bloomTimer = 0
        case .response: bloomTimer = 0.7; applyKick(1)
        case .welcome: bloomTimer = 1.1
        }
    }

    private func applyKick(_ amount: Double) {
        kick = max(kick, 1.6 * min(1, max(0, amount)))
    }

    private func startSwell() {
        swellTimer = 1.1
        stirLevel = max(stirLevel, 0.35)
        if phase != .processing { enter(.welcome) }
    }

    // MARK: display link

    private func wake() {
        guard mounted > 0, appActive else { return }
        if link == nil {
            let l = CADisplayLink(target: LinkTarget(self), selector: #selector(LinkTarget.fire(_:)))
            l.isPaused = true
            l.preferredFrameRateRange = Self.fastRange
            fastRate = true
            l.add(to: .main, forMode: .common)
            link = l
        }
        lastStamp = nil
        link?.isPaused = false
    }

    fileprivate func frame(_ l: CADisplayLink) {
        let now = l.timestamp
        let dt = lastStamp.map { min(0.05, max(0, now - $0)) } ?? (1.0 / 60)
        lastStamp = now
        step(dt)
        publish(advanceClocks: true)

        if phase == .idle && glowLevel.value <= 0.001 {
            if glowVisible { glowVisible = false }
            if !zeroStateActive && presence.value <= 0.001 && swell.value <= 0.001 {
                l.isPaused = true
                return
            }
        }
        let busy = swell.value > 0.001 || stirLevel > 0.001 || kick > 0.02
            || phase == .response || phase == .welcome || (zeroStateActive && quiet < 0.8)
        if busy != fastRate {
            fastRate = busy
            l.preferredFrameRateRange = busy ? Self.fastRange : Self.calmRange
        }
    }

    private func step(_ dt: Double) {
        // Phase timers.
        if phase == .response || phase == .welcome {
            bloomTimer -= dt
            if bloomTimer <= 0 {
                bloomTimer = 0
                phase = (glow == .working || glow == .answering) ? .processing : .idle
            }
        }
        kick *= exp(-2.2 * dt)
        if kick < 0.01 { kick = 0 }
        if phase == .processing { tau += dt }

        // Crown.
        let b = 0.07 * sin(1.3 * tau)
        let crownTarget: (Double, Double, Double)
        switch phase {
        case .idle: crownTarget = (0, 0, 0.9)
        case .processing: crownTarget = (0.7 + b, 0.84 + 0.6 * b, 0.6)
        case .response: crownTarget = (1, 1, 0.45)
        case .welcome: crownTarget = (0.5, 0.66, 0.8)
        }
        glowLevel.step(toward: crownTarget.0, settle: crownTarget.2, dt: dt)
        reach.step(toward: crownTarget.1, settle: crownTarget.2, dt: dt)
        if glowLevel.value > 0.001 {
            hue = (hue + 0.045 * (1 + kick) * dt).truncatingRemainder(dividingBy: 64)
            drift = (drift + (0.8 + 0.5 * kick) * dt).truncatingRemainder(dividingBy: 4096)
        }

        // Swell.
        if swellTimer > 0 {
            swellTimer = max(0, swellTimer - dt)
            swell.step(toward: 1, settle: 1.3, dt: dt)
        } else {
            swell.step(toward: 0, settle: 1.6, dt: dt)
        }

        // Backdrop.
        var tideGoal = 0.0, presenceGoal = 0.0, liftGoal = 0.0
        if zeroStateActive {
            quiet += dt
            if keyboardVisible {
                let h = Double(screenHeight)
                liftGoal = overlap > 40 ? min(0.44, max(0.24, overlap / h)) : 0.35
                if quiet <= 10 { tideGoal = 0.46; presenceGoal = 1 } else { tideGoal = 0.28; presenceGoal = 0.55 }
            } else if quiet <= 5 {
                tideGoal = 0.34; presenceGoal = 0.85
            } else {
                tideGoal = 0.16; presenceGoal = 0.5
            }
            if swellTimer > 0 {
                tideGoal = max(tideGoal, 0.5)
                presenceGoal = 1
            }
        }
        let falling = presenceGoal < presence.value
        let settle = falling ? 1.8 : 0.7
        tide.step(toward: tideGoal, settle: settle, dt: dt)
        presence.step(toward: presenceGoal, settle: settle, dt: dt)
        lift.step(toward: liftGoal, settle: 0.4, dt: dt)
        stirLevel = max(0, stirLevel - 0.7 * dt)
        if presence.value > 0.001 || swell.value > 0.001 {
            clock = (clock + (1 + 0.8 * stirLevel) * dt).truncatingRemainder(dividingBy: 4096)
        }
    }

    private func publish(advanceClocks: Bool) {
        func unit(_ v: Double) -> Float { Float(min(1, max(0, v))) }
        func put(_ current: Float, _ next: Float, _ assign: (Float) -> Void) {
            if abs(current - next) > 1e-4 { assign(next) }
        }
        let bd = backdrop, cr = crown
        put(bd.tide, unit(tide.value)) { bd.tide = $0 }
        put(bd.presence, unit(presence.value)) { bd.presence = $0 }
        put(bd.stirLevel, unit(stirLevel)) { bd.stirLevel = $0 }
        put(bd.lift, unit(lift.value)) { bd.lift = $0 }
        put(bd.swell, unit(swell.value)) { bd.swell = $0 }
        put(cr.level, unit(glowLevel.value)) { cr.level = $0 }
        put(cr.reach, unit(reach.value)) { cr.reach = $0 }
        guard advanceClocks else { return }
        if presence.value > 0.001 || swell.value > 0.001, bd.clock != Float(clock) { bd.clock = Float(clock) }
        if glowLevel.value > 0.001 {
            if cr.hue != Float(hue) { cr.hue = Float(hue) }
            if cr.drift != Float(drift) { cr.drift = Float(drift) }
        }
    }
}

/// Breaks the retain cycle between the display link and the driver.
@MainActor
private final class LinkTarget: NSObject {
    weak var owner: GradientFlow?
    init(_ owner: GradientFlow) { self.owner = owner }
    @objc func fire(_ link: CADisplayLink) {
        if let owner { owner.frame(link) } else { link.invalidate() }
    }
}
