// SPDX-License-Identifier: AGPL-3.0-only
import Foundation
import Observation
import WatchConnectivity
import WidgetKit

/// The watch's side of the WatchConnectivity link: holds the phone's latest snapshot and
/// sends requests, falling back to a queued transfer when the phone can't answer right now.
@MainActor
@Observable
final class PhoneLink: NSObject {
    private(set) var snapshot: WatchSnapshot?
    private(set) var reachable = false
    private(set) var ready = false

    func start() {
        guard WCSession.isSupported() else { return }
        let s = WCSession.default
        s.delegate = self
        s.activate()
    }

    /// Asks the phone and waits for its answer. Nil means it went out as a queued transfer
    /// (or not at all) and no answer is coming.
    @discardableResult
    func ask(_ request: WatchRequest) async -> WatchReply? {
        let s = WCSession.default
        let payload = WatchLink.pack(request)
        guard s.activationState == .activated else { return nil }
        guard s.isReachable else {
            queue(request, payload)
            return nil
        }
        let reply = await Self.exchange(request)
        guard let reply else {
            queue(request, payload)
            return nil
        }
        if let fresh = reply.snapshot { adopt(fresh) }
        return reply
    }

    /// WatchConnectivity calls back on its own queue, so the callbacks must not belong to the main actor.
    private nonisolated static func exchange(_ request: WatchRequest) async -> WatchReply? {
        await withCheckedContinuation { (done: CheckedContinuation<WatchReply?, Never>) in
            WCSession.default.sendMessage(WatchLink.pack(request), replyHandler: { answer in
                done.resume(returning: WatchLink.unpack(WatchReply.self, from: answer))
            }, errorHandler: { _ in
                done.resume(returning: nil)
            })
        }
    }

    /// Only requests that change something are worth delivering late.
    private func queue(_ request: WatchRequest, _ payload: [String: Any]) {
        switch request {
        case .refresh, .transcript: return
        default: WCSession.default.transferUserInfo(payload)
        }
    }

    private func adopt(_ fresh: WatchSnapshot) {
        if let old = snapshot, old.stamp > fresh.stamp, fresh.stamp > 0 { return }
        var compare = fresh
        compare.stamp = snapshot?.stamp ?? 0
        let changed = compare != snapshot
        snapshot = fresh
        guard changed else { return }
        SnapshotStore.save(fresh)
        WidgetCenter.shared.reloadAllTimelines()
    }

    fileprivate func update(reachable now: Bool, context: WatchSnapshot?) {
        ready = true
        reachable = now
        if let context { adopt(context) }
    }
}

extension PhoneLink: WCSessionDelegate {
    nonisolated func session(_ session: WCSession, activationDidCompleteWith state: WCSessionActivationState, error: Error?) {
        let context = WatchLink.unpack(WatchSnapshot.self, from: session.receivedApplicationContext)
        let now = session.isReachable
        Task { @MainActor in
            self.update(reachable: now, context: context)
            if now { await self.ask(.refresh) }
        }
    }

    nonisolated func sessionReachabilityDidChange(_ session: WCSession) {
        let now = session.isReachable
        Task { @MainActor in
            self.update(reachable: now, context: nil)
            if now { await self.ask(.refresh) }
        }
    }

    nonisolated func session(_ session: WCSession, didReceiveApplicationContext applicationContext: [String: Any]) {
        let context = WatchLink.unpack(WatchSnapshot.self, from: applicationContext)
        let now = session.isReachable
        Task { @MainActor in self.update(reachable: now, context: context) }
    }
}
