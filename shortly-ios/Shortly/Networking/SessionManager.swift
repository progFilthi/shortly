import Foundation

/// Supplies access tokens to `APIClient` and hides refresh from every call site.
protocol SessionRefreshing: AnyObject {
    /// A token that is valid now, refreshing first if the current one is close to expiry.
    func accessToken() async throws -> String

    /// Forces a refresh and returns the new token. Used when the server rejected a
    /// token we believed was good, which means our expiry and theirs disagree.
    func forceRefresh() async throws -> String

    /// Called when a refresh failed unrecoverably, so the session is dropped instead of
    /// being retried on every later request.
    func invalidate() async
}

/// Breaks the construction cycle between the transport and the session that
/// authenticates it: `APIClient` needs a token provider, and the token provider
/// needs an `APIClient` to refresh with.
///
/// The reference is weak so the box, the manager, and the client can be collected
/// together instead of pinning each other in a cycle.
final class SessionRefresherBox: SessionRefreshing, @unchecked Sendable {
    private let lock = NSLock()
    private weak var impl: SessionRefreshing?

    func install(_ impl: SessionRefreshing) {
        lock.withLock { self.impl = impl }
    }

    func accessToken() async throws -> String {
        try await provider().accessToken()
    }

    func forceRefresh() async throws -> String {
        try await provider().forceRefresh()
    }

    func invalidate() async {
        // Non-throwing on purpose: with nothing installed there is no session to
        // invalidate, and this method is not allowed to fail.
        let current = lock.withLock { impl }
        await current?.invalidate()
    }

    /// Held strongly for the duration of the call so the weak reference cannot be
    /// cleared out from under an in-flight request.
    private func provider() throws -> SessionRefreshing {
        guard let current = lock.withLock({ impl }) else {
            throw APIClientError.sessionExpired
        }
        return current
    }
}

/// Owns the signed-in session: persistence, refresh, and sign-out.
actor SessionManager: SessionRefreshing {
    private let client: AuthSessionServing
    private let store: SessionStoring

    private(set) var session: AuthSession?

    /// The in-flight refresh, if any.
    ///
    /// Access tokens last 15 minutes and the app fires several requests at once on
    /// launch, so without this each one would independently notice the expiry and start
    /// its own refresh. The backend *rotates* refresh tokens and revokes the family on
    /// replay, so those parallel refreshes are indistinguishable from a stolen token and
    /// would sign the user out. One task; everyone waits on it.
    private var refreshTask: Task<AuthResponse, Error>?

    /// Notifies the UI when the session changes or is dropped.
    var onChange: (@Sendable (AuthSession?) -> Void)?

    init(client: AuthSessionServing, store: SessionStoring) {
        self.client = client
        self.store = store
    }

    /// Installs the change callback. Separate from `init` because the property is
    /// actor-isolated and the caller is on the main actor.
    func setOnChange(_ handler: @escaping @Sendable (AuthSession?) -> Void) {
        onChange = handler
    }

    var currentSession: AuthSession? { session }

    func restore() {
        guard session == nil else { return }

        do {
            // A session written by an older build has no refresh token and will not
            // decode. That is a sign-in, not a failure, so it clears quietly rather
            // than putting an error in front of the user.
            if let saved = try store.load() {
                session = saved
                onChange?(saved)
            }
        } catch {
            try? store.clear()
        }
    }

    func adopt(_ newSession: AuthSession) {
        session = newSession
        // Persist before publishing, so a crash cannot leave the UI showing a session
        // that a relaunch would not have.
        try? store.save(newSession)
        onChange?(newSession)
    }

    func accessToken() async throws -> String {
        if let session, !session.needsRefresh() {
            return session.token
        }
        return try await forceRefresh()
    }

    func forceRefresh() async throws -> String {
        // A refresh may already be running. Joining it is the whole point: the backend
        // rotates on every use, so a second concurrent refresh would present an
        // already-consumed token and trip reuse detection.
        if let existing = refreshTask {
            return try await existing.value.session().token
        }

        guard let refreshToken = session?.refreshToken else {
            throw APIClientError.sessionExpired
        }

        // Captures only Sendable values rather than `self`. A task inheriting this
        // actor's isolation would have to re-enter the actor we are already suspended
        // on, and the resulting deadlock is very hard to see.
        let client = self.client
        let task = Task<AuthResponse, Error> {
            try await client.refresh(refreshToken: refreshToken)
        }
        refreshTask = task
        defer { refreshTask = nil }

        do {
            let response = try await task.value
            let newSession = response.session()
            adopt(newSession)
            return newSession.token
        } catch {
            // A dead refresh token cannot be recovered, and keeping it would make every
            // later request attempt a refresh that is certain to fail.
            if case APIClientError.sessionExpired = error {
                invalidate()
            }
            throw error
        }
    }

    func invalidate() {
        session = nil
        try? store.clear()
        onChange?(nil)
    }

    /// Revokes the session on the server, then clears it locally.
    ///
    /// The local clear happens even if the call fails: leaving a token the user asked to
    /// revoke on the device is the worse of the two outcomes.
    func signOut(allDevices: Bool = false) async {
        let refreshToken = session?.refreshToken

        invalidate()

        guard let refreshToken else { return }

        do {
            try await client.logout(refreshToken: refreshToken, allDevices: allDevices)
        } catch {
            // Nothing actionable. The user asked to be signed out and now is.
        }
    }
}

/// The auth endpoints, separated from the token-injecting path so refreshing does not
/// recurse back into `accessToken()`.
protocol AuthSessionServing: AnyObject {
    func register(username: String, email: String, password: String) async throws -> AuthResponse
    func login(identifier: String, password: String) async throws -> AuthResponse
    func refresh(refreshToken: String) async throws -> AuthResponse
    func logout(refreshToken: String, allDevices: Bool) async throws
    func me(token: String) async throws -> UserProfile
}
