@preconcurrency import AVFoundation
import Observation
@preconcurrency import Speech

/// Voice dictation for the composer: speech is appended, after a space, to the text that was
/// there when it started (`prefix + transcript`). Recognition follows the preferred language
/// and stays on the device when the recogniser supports that.
@MainActor
@Observable
final class DictationController {
    private(set) var isListening = false
    private(set) var transcript = ""
    /// Smoothed 0…1 input level, for the ring around the mic.
    private(set) var level: Float = 0
    private(set) var prefix = ""
    /// Last problem, in words for the user (permission refused, unavailable, failed to start…).
    private(set) var errorMessage: String?
    /// Called with `errorMessage` whenever a new problem occurs.
    @ObservationIgnored var onError: ((String) -> Void)?

    @ObservationIgnored private var engine: AVAudioEngine?
    @ObservationIgnored private var request: SFSpeechAudioBufferRecognitionRequest?
    @ObservationIgnored private var task: SFSpeechRecognitionTask?
    /// Bumped per session so late callbacks from an old session are ignored.
    @ObservationIgnored private var session = 0
    /// Set between a start request and the engine running, so a second tap can't start twice.
    @ObservationIgnored private var starting = false
    /// Set after `stop()` while the recogniser delivers its final transcript.
    @ObservationIgnored private var finishing = false
    @ObservationIgnored private var finishTimeout: Task<Void, Never>?

    func toggle(current: String) {
        if isListening { stop(); return }
        guard !starting else { return }
        starting = true
        Task {
            await begin(with: current)
            starting = false
        }
    }

    /// Stops listening but lets the recogniser deliver its final transcript first (through
    /// `transcript`, while `isListening` is still true), then tears down. Gives up after a moment.
    /// Calling it again while finishing cancels at once.
    func stop() {
        guard isListening, task != nil, !finishing else { cancel(); return }
        finishing = true
        if let running = engine {
            running.stop()
            running.inputNode.removeTap(onBus: 0)
        }
        engine = nil
        level = 0
        request?.endAudio()
        task?.finish()
        let ticket = session
        let grace: Duration = .milliseconds(900)
        finishTimeout = Task { @MainActor [weak self] in
            try? await Task.sleep(for: grace)
            if Task.isCancelled { return }
            if let me = self, me.session == ticket { me.cancel() }
        }
    }

    /// Stops at once and drops anything not yet transcribed (for example when the user types).
    func cancel() {
        finishTimeout?.cancel()
        finishTimeout = nil
        finishing = false
        session += 1
        let oldTask = task, oldRequest = request
        task = nil; request = nil
        oldTask?.cancel()
        oldRequest?.endAudio()
        if let running = engine {
            running.stop()
            running.inputNode.removeTap(onBus: 0)
        }
        engine = nil
        isListening = false
        level = 0
        let shared = AVAudioSession.sharedInstance()
        do { try shared.setActive(false, options: [.notifyOthersOnDeactivation]) } catch {}
    }

    // MARK: - Starting

    private func begin(with current: String) async {
        if let refusal = await Self.askPermissions() { report(refusal); return }
        do {
            try open(with: current)
        } catch let problem as Problem {
            cancel()
            report(problem.errorDescription ?? "")
        } catch {
            cancel()
            report(String(localized: "Couldn't start dictation: \(error.localizedDescription)"))
        }
    }

    private func open(with current: String) throws {
        let locale = Locale.preferredLanguages.first.map(Locale.init(identifier:)) ?? .current
        let picked = SFSpeechRecognizer(locale: locale) ?? SFSpeechRecognizer()
        guard let recognizer = picked, recognizer.isAvailable else { throw Problem.unavailable }

        let audio = AVAudioSession.sharedInstance()
        try audio.setCategory(AVAudioSession.Category.record, mode: AVAudioSession.Mode.measurement, options: [.duckOthers])
        try audio.setActive(true, options: [.notifyOthersOnDeactivation])

        let req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition { req.requiresOnDeviceRecognition = true }

        session += 1
        let ticket = session
        let fresh = AVAudioEngine()
        let input = fresh.inputNode
        let tap = Self.audioTap(feeding: req) { [weak self] sample in
            Task { @MainActor in self?.take(level: sample, session: ticket) }
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: input.outputFormat(forBus: 0), block: tap)
        fresh.prepare()
        try fresh.start()

        let base = current.trimmingCharacters(in: .whitespacesAndNewlines)
        prefix = base.isEmpty ? "" : base + " "
        transcript = ""
        errorMessage = nil
        engine = fresh
        request = req
        isListening = true
        let callback = Self.recognitionCallback { [weak self] text, ended, failure in
            Task { @MainActor in self?.take(text: text, ended: ended, failure: failure, session: ticket) }
        }
        task = recognizer.recognitionTask(with: req, resultHandler: callback)
    }

    // MARK: - Callbacks (back on the main actor)

    private func take(level sample: Float, session id: Int) {
        guard id == session, isListening else { return }
        level = 0.6 * level + 0.4 * sample
    }

    private func take(text: String?, ended: Bool, failure: String?, session id: Int) {
        guard id == session, isListening else { return }
        if let text {
            transcript = text.trimmingCharacters(in: .whitespacesAndNewlines)
        }
        guard ended else { return }
        cancel()
        // Cancelling or a silent ending is not worth a message; real errors are.
        if let failure { report(String(localized: "Dictation stopped: \(failure)")) }
    }

    private func report(_ message: String) {
        errorMessage = message
        onError?(message)
    }

    // MARK: - Off the main actor

    /// The tap runs on the realtime audio thread, so it is built in a nonisolated context.
    nonisolated private static func audioTap(feeding req: SFSpeechAudioBufferRecognitionRequest,
                                             level: @escaping @Sendable (Float) -> Void) -> AVAudioNodeTapBlock {
        return { buffer, _ in
            req.append(buffer)
            level(loudness(buffer))
        }
    }

    /// Speech delivers results on its own queue; built nonisolated for the same reason.
    typealias Delivery = @Sendable (_ text: String?, _ ended: Bool, _ failure: String?) -> Void

    typealias RecognitionCallback = @Sendable (SFSpeechRecognitionResult?, (any Error)?) -> Void

    nonisolated private static func recognitionCallback(into sink: @escaping Delivery) -> RecognitionCallback {
        return { result, error in
            let ended = error != nil || (result?.isFinal ?? false)
            // Code 216/301 are the recogniser being cancelled by `stop()`.
            let nsError = error as NSError?
            let quiet = nsError.map { [216, 301, 1110].contains($0.code) } ?? true
            sink(result?.bestTranscription.formattedString, ended, quiet ? nil : nsError?.localizedDescription)
        }
    }

    /// RMS in decibels, −50 dB … −5 dB mapped onto 0…1.
    nonisolated private static func loudness(_ buffer: AVAudioPCMBuffer) -> Float {
        let count = Int(buffer.frameLength)
        guard count > 0, let channel = buffer.floatChannelData?.pointee else { return 0 }
        var energy: Float = 0
        for k in 0..<count { energy += channel[k] * channel[k] }
        let decibels = 10 * log10(max(energy / Float(count), 1e-10))
        return min(1, max(0, (decibels + 50) / 45))
    }

    nonisolated private static func askPermissions() async -> String? {
        let speech = await withCheckedContinuation { (done: CheckedContinuation<SFSpeechRecognizerAuthorizationStatus, Never>) in
            SFSpeechRecognizer.requestAuthorization { done.resume(returning: $0) }
        }
        let speechOff = String(localized: "Speech recognition is turned off for AGY Remote in Settings.")
        let micOff = String(localized: "Microphone access is turned off for AGY Remote in Settings.")
        switch speech {
        case .authorized:
            let allowed = await AVAudioApplication.requestRecordPermission()
            return allowed ? nil : micOff
        default:
            return speechOff
        }
    }

    private enum Problem: LocalizedError {
        case unavailable

        var errorDescription: String? {
            switch self {
            case .unavailable: return String(localized: "Speech recognition isn't available right now.")
            }
        }
    }
}
