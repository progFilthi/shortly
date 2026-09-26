import Combine
import Foundation

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var session: AuthSession?
    @Published private(set) var profile: UserProfile?
    @Published private(set) var videos: [Video] = []
    @Published private(set) var isRestoringSession = true
    @Published private(set) var isAuthenticating = false
    @Published private(set) var isLoadingVideos = false
    @Published private(set) var isUploading = false
    @Published private(set) var uploadProgress = 0.0
    @Published private(set) var uploadStage = ""

    /// Set when the account is locked, so the sign-in screen can show a countdown
    /// instead of letting the user keep tapping a button that cannot succeed.
    @Published private(set) var lockout: LockoutState?

    @Published var appAlert: AppAlert?

    private let apiClient: APIClientProtocol
    private let authService: AuthSessionServing
    private let sessionManager: SessionManager
    private let uploader: VideoUploading

    /// Clears `lockout` when its countdown expires, so the sign-in button re-enables
    /// on its own. The banner's digits come from a `TimelineView`; this is what makes
    /// the *rest* of the screen agree that the lockout is over.
    private var lockoutExpiry: Task<Void, Never>?

    init(
        apiClient: APIClientProtocol,
        authService: AuthSessionServing,
        sessionManager: SessionManager,
        uploader: VideoUploading
    ) {
        self.apiClient = apiClient
        self.authService = authService
        self.sessionManager = sessionManager
        self.uploader = uploader
    }

    /// Wires the real stack.
    ///
    /// The three objects form a cycle by nature: the client needs a token provider, the
    /// token provider needs the client to refresh with, and the session manager ties the
    /// two together. `SessionRefresherBox` is the seam that lets the client be built
    /// first and the manager installed immediately after.
    static func live() -> AppModel {
        let arguments = ProcessInfo.processInfo.arguments
        let store: SessionStoring = arguments.contains("--uitesting-reset-session")
            ? InMemorySessionStore()
            : KeychainSessionStore()

        let refresher = SessionRefresherBox()
        let client = APIClient(baseURL: AppConfiguration.apiBaseURL, refresher: refresher)
        let manager = SessionManager(client: client, store: store)
        refresher.install(manager)

        let model = AppModel(
            apiClient: client,
            authService: client,
            sessionManager: manager,
            uploader: S3VideoUploader()
        )

        return model
    }

    /// Mirrors session changes into the UI.
    ///
    /// Installed from `restoreSession` rather than `live` because the manager's callback
    /// is actor-isolated, and `live` runs in a property initializer where there is no
    /// suspension point. Without this, a refresh that fails deep inside `APIClient`
    /// would clear the session without the UI ever hearing about it.
    private func beginObservingSession() async {
        await sessionManager.setOnChange { [weak self] session in
            Task { @MainActor in
                guard let self else { return }
                self.session = session
                if session == nil {
                    self.profile = nil
                    self.videos = []
                }
            }
        }
    }

    // MARK: - Session lifecycle

    func restoreSession() async {
        guard isRestoringSession else { return }
        defer { isRestoringSession = false }

        await beginObservingSession()
        await sessionManager.restore()
        session = await sessionManager.currentSession

        // A restored session can be a week old. Its access token is long expired, and
        // the refresh token may have been revoked or rotated out from under it, so this
        // call is what actually proves the session still works.
        guard session != nil else { return }

        await loadProfile(showErrors: false)
        if session != nil {
            await loadVideos(showErrors: false)
        }
    }

    func register(username: String, email: String, password: String) async {
        await authenticate {
            try await self.authService.register(username: username, email: email, password: password)
        }
    }

    func signIn(identifier: String, password: String) async {
        await authenticate {
            try await self.authService.login(identifier: identifier, password: password)
        }
    }

    private func authenticate(_ operation: () async throws -> AuthResponse) async {
        guard !isAuthenticating else { return }

        isAuthenticating = true
        defer { isAuthenticating = false }

        do {
            let response = try await operation()
            await sessionManager.adopt(response.session())
            session = await sessionManager.currentSession
            clearLockout()
            videos = []
            await loadProfile(showErrors: false)
            if session != nil {
                await loadVideos(showErrors: false)
            }
        } catch {
            if case APIClientError.accountLocked(let seconds) = error {
                beginLockout(seconds)
            }
            present(error, title: "Sign in failed")
        }
    }

    func signOut(allDevices: Bool = false) async {
        await sessionManager.signOut(allDevices: allDevices)
        session = nil
        profile = nil
        videos = []
        clearLockout()
    }

    func refreshVideos() async {
        await loadVideos(showErrors: true)
    }

    // MARK: - Upload

    func uploadVideo(fileURL: URL, contentType: String, title: String, description: String) async throws -> Video {
        guard session != nil else {
            throw AppModelError.notAuthenticated
        }

        guard !isUploading else {
            throw AppModelError.uploadInProgress
        }

        isUploading = true
        uploadProgress = 0.1
        uploadStage = "Preparing upload"
        defer {
            isUploading = false
            uploadProgress = 0
            uploadStage = ""
        }

        do {
            let presignedUpload = try await apiClient.createVideo(
                title: title.trimmingCharacters(in: .whitespacesAndNewlines),
                description: description.trimmingCharacters(in: .whitespacesAndNewlines),
                contentType: contentType
            )

            uploadProgress = 0.4
            uploadStage = "Uploading video"
            try await uploader.upload(
                fileAt: fileURL,
                to: presignedUpload.uploadURL,
                contentType: contentType
            )

            uploadProgress = 0.8
            uploadStage = "Finalizing video"
            let video = try await apiClient.completeVideo(id: presignedUpload.videoId)

            videos.removeAll { $0.id == video.id }
            videos.insert(video, at: 0)
            uploadProgress = 1
            return video
        } catch {
            if let apiError = error as? APIClientError, apiError.requiresSignIn {
                await sessionManager.invalidate()
            }
            present(error, title: "Upload failed")
            throw error
        }
    }

    // MARK: - Private

    private func loadProfile(showErrors: Bool) async {
        do {
            let token = try await sessionManager.accessToken()
            profile = try await authService.me(token: token)
        } catch {
            if let apiError = error as? APIClientError, apiError.requiresSignIn {
                await sessionManager.invalidate()
            } else if showErrors {
                present(error, title: "Could not load your profile")
            }
        }
    }

    private func loadVideos(showErrors: Bool) async {
        guard let userID = session?.userId else { return }
        guard !isLoadingVideos else { return }

        isLoadingVideos = true
        defer { isLoadingVideos = false }

        do {
            videos = try await apiClient.videos(forUserID: userID)
        } catch {
            if let apiError = error as? APIClientError, apiError.requiresSignIn {
                await sessionManager.invalidate()
            } else if showErrors {
                present(error, title: "Could not load videos")
            }
        }
    }

    private func beginLockout(_ seconds: Int) {
        let state = LockoutState(retryAfterSeconds: seconds)
        lockout = state

        lockoutExpiry?.cancel()
        lockoutExpiry = Task { [weak self] in
            let wait = max(0, state.deadline.timeIntervalSinceNow)
            try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.clearLockout() }
        }
    }

    private func clearLockout() {
        lockoutExpiry?.cancel()
        lockoutExpiry = nil
        lockout = nil
    }

    private func present(_ error: Error, title: String) {
        appAlert = AppAlert(title: title, message: error.localizedDescription)
    }
}

/// A countdown shown while an account is locked.
struct LockoutState: Equatable {
    let retryAfterSeconds: Int

    /// Wall-clock deadline rather than a remaining-seconds counter, so the countdown
    /// stays correct across a backgrounded app, where timers do not fire.
    let deadline: Date

    init(retryAfterSeconds: Int, now: Date = Date()) {
        self.retryAfterSeconds = retryAfterSeconds
        self.deadline = now.addingTimeInterval(TimeInterval(retryAfterSeconds))
    }

    func remainingSeconds(at now: Date = Date()) -> Int {
        max(0, Int(deadline.timeIntervalSince(now).rounded(.up)))
    }
}

struct AppAlert: Identifiable, Equatable {
    let id = UUID()
    let title: String
    let message: String
}

enum AppModelError: LocalizedError {
    case notAuthenticated
    case uploadInProgress

    var errorDescription: String? {
        switch self {
        case .notAuthenticated:
            return "Sign in before uploading a video."
        case .uploadInProgress:
            return "Another upload is already in progress."
        }
    }
}
