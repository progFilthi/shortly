import Foundation
import XCTest
@testable import Shortly

/// Exercises the real client against a running stack.
///
/// Skipped rather than failed when the backend is absent, so an ordinary `xcodebuild test`
/// on a machine without Docker still passes. Run it with the stack up:
///
///     docker compose up -d --build
///     xcodebuild test -project Shortly.xcodeproj -scheme Shortly \
///       -destination 'platform=iOS Simulator,name=iPhone 18 Pro' \
///       -only-testing:ShortlyTests/LiveBackendTests
final class LiveBackendTests: XCTestCase {
    private var baseURL: URL!

    override func setUp() async throws {
        try await super.setUp()
        baseURL = try XCTUnwrap(
            URL(string: AppConfiguration.apiBaseURL.absoluteString),
            "API_BASE_URL is not a valid URL"
        )
        try await requireBackend()
    }

    private func requireBackend() async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent("/actuator/health"))
        request.timeoutInterval = 3

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                throw XCTSkip("The gateway is not answering on \(baseURL)")
            }
        } catch is XCTSkip {
            throw XCTSkip("The gateway is not answering on \(baseURL)")
        } catch {
            throw XCTSkip("The gateway is not reachable on \(baseURL): \(error)")
        }
    }

    /// A client wired exactly as the app wires it, including the refresh cycle.
    private func makeStack() -> (APIClient, SessionManager, InMemorySessionStore) {
        let store = InMemorySessionStore()
        let refresher = SessionRefresherBox()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 20
        let client = APIClient(
            baseURL: baseURL,
            session: URLSession(configuration: configuration),
            refresher: refresher
        )
        let manager = SessionManager(client: client, store: store)
        refresher.install(manager)
        return (client, manager, store)
    }

    // MARK: - Tests

    func testRegisterThenSignInThenReadProfile() async throws {
        let (client, manager, _) = makeStack()
        let credentials = try await register(client: client)

        // Sign in as a separate act, so the returned session is not just the
        // registration response echoed back.
        let signedIn = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        ).session()
        await manager.adopt(signedIn)

        let profile = try await client.me(token: signedIn.token)
        XCTAssertEqual(profile.userId, signedIn.userId)
        XCTAssertEqual(profile.username, credentials.username)
        XCTAssertGreaterThanOrEqual(profile.activeSessionCount, 1)
    }

    /// The real reason the client stores a refresh token: a 15-minute access token with
    /// nothing to renew it is a hard logout every quarter hour.
    ///
    /// Requires a short `ACCESS_TOKEN_TTL`, because a genuinely expired token is the only
    /// honest way to test this. A forged token is not a substitute: the server calls that
    /// `token-invalid`, which is deliberately *not* recoverable, and asserting on it
    /// would test the wrong branch.
    func testExpiredAccessTokenIsRecoveredWithTheRefreshToken() async throws {
        let (client, manager, store) = makeStack()
        let credentials = try await register(client: client)

        let loginResponse = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        )
        let session = loginResponse.session()
        await manager.adopt(session)
        let firstToken = session.token

        // Waiting out a real expiry is the whole point, but at the default 15-minute
        // TTL that is a quarter of an hour in a unit test run. Gated rather than
        // silently slow.
        try XCTSkipUnless(
            loginResponse.expiresIn <= 60,
            "Needs a short ACCESS_TOKEN_TTL (this stack issues \(loginResponse.expiresIn)s). "
                + "Re-run with: ACCESS_TOKEN_TTL=20s docker compose up -d auth-service"
        )

        // Wait past the server's access token lifetime.
        try await Task.sleep(for: .seconds(padding(for: loginResponse)))

        // Goes through the session, not an explicit token, so the refresh path runs.
        let videos = try await client.videos(forUserID: session.userId)
        XCTAssertTrue(videos.isEmpty)

        // Recovered silently: a new session, and the user was never signed out.
        let current = await manager.currentSession
        XCTAssertNotNil(current, "The session should have been refreshed, not dropped")
        XCTAssertNotEqual(current?.token, firstToken, "A new access token should have been issued")
        XCTAssertNotEqual(current?.refreshToken, session.refreshToken, "The refresh token rotates too")
        XCTAssertNotNil(store.session, "The refreshed session should have been persisted")
    }

    func testRefreshRotatesTheTokenAndTheOldOneStopsWorking() async throws {
        let (client, _, _) = makeStack()
        let credentials = try await register(client: client)

        let first = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        ).session()
        let second = try await client.refresh(refreshToken: first.refreshToken).session()

        XCTAssertNotEqual(second.refreshToken, first.refreshToken, "Refresh must rotate")
        XCTAssertNotEqual(second.token, first.token, "Refresh must also issue a new access token")

        // Outside the 20s reuse grace, the rotated-away token is treated as stolen and
        // the whole family dies, replacement included. Unforgiving, but correct.
        try await Task.sleep(for: .seconds(22))

        do {
            _ = try await client.refresh(refreshToken: first.refreshToken)
            XCTFail("Replaying a rotated refresh token should be rejected")
        } catch let error as APIClientError {
            XCTAssertEqual(error, .sessionExpired)
        }

        do {
            _ = try await client.refresh(refreshToken: second.refreshToken)
            XCTFail("The family should have been revoked along with the replayed token")
        } catch let error as APIClientError {
            XCTAssertEqual(error, .sessionExpired)
        }
    }

    func testSignOutRevokesTheRefreshTokenServerSide() async throws {
        let (client, manager, store) = makeStack()
        let credentials = try await register(client: client)
        let session = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        ).session()
        await manager.adopt(session)

        try await client.logout(refreshToken: session.refreshToken, allDevices: false)

        do {
            _ = try await client.refresh(refreshToken: session.refreshToken)
            XCTFail("The refresh token should be dead after sign-out")
        } catch let error as APIClientError {
            XCTAssertEqual(error, .sessionExpired)
        }

        // And the device no longer holds it.
        XCTAssertNil(store.session)
        let current = await manager.currentSession
        XCTAssertNil(current)
    }

    func testWrongPasswordIsRejectedWithoutWipingAValidSession() async throws {
        let (client, manager, _) = makeStack()
        let credentials = try await register(client: client)
        let session = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        ).session()
        await manager.adopt(session)

        do {
            _ = try await client.login(identifier: credentials.username, password: "wrong-password-here")
            XCTFail("Expected the sign-in to be rejected")
        } catch let error as APIClientError {
            XCTAssertEqual(error, .invalidCredentials)
            // The bug this guards: a mistyped password must not log the user out.
            XCTAssertFalse(error.requiresSignIn)
        }

        let current = await manager.currentSession
        XCTAssertEqual(current?.token, session.token, "A failed sign-in must not disturb the session")
    }

    func testAccountLockoutIsReportedWithACountdown() async throws {
        let (client, _, _) = makeStack()
        let credentials = try await register(client: client)

        var locked: APIClientError?
        for _ in 0..<6 {
            do {
                _ = try await client.login(identifier: credentials.username, password: "wrong-password-here")
            } catch let error as APIClientError {
                if case .accountLocked = error { locked = error; break }
            }
        }

        guard case .accountLocked(let seconds)? = locked else {
            return XCTFail("Six wrong passwords should lock the account, got \(String(describing: locked))")
        }
        XCTAssertGreaterThan(seconds, 0)

        // The correct password is refused while locked, which is the whole point.
        do {
            _ = try await client.login(identifier: credentials.username, password: credentials.password)
            XCTFail("The account should still be locked")
        } catch let error as APIClientError {
            guard case .accountLocked = error else {
                return XCTFail("Expected accountLocked, got \(error)")
            }
        }
    }

    func testListingVideosUsesTheAuthenticatedIdentity() async throws {
        let (client, manager, _) = makeStack()
        let credentials = try await register(client: client)
        let session = try await client.login(
            identifier: credentials.username,
            password: credentials.password
        ).session()
        await manager.adopt(session)

        // No videos yet, but the call must succeed with the token and nothing else:
        // the gateway rejects any X-User-Id that was not minted from the token.
        let videos = try await client.videos(forUserID: session.userId)
        XCTAssertTrue(videos.isEmpty)
    }

    // MARK: - Helpers

    private let password = "correct-horse-battery"

    private func uniqueUsername() -> String {
        "ios\(Int(Date().timeIntervalSince1970 * 1000) % 1_000_000)"
    }

    private func register(client: APIClient) async throws -> (username: String, password: String) {
        let username = uniqueUsername()
        let response = try await client.register(
            username: username,
            email: "\(username)@example.com",
            password: password
        )
        return (username, password)
    }

    /// Seconds to wait past the server's access token lifetime.
    ///
    /// Read from the token the server just issued, so the test adapts to whatever
    /// `ACCESS_TOKEN_TTL` the stack is running with instead of hard-coding 15 minutes.
    private func padding(for response: AuthResponse) -> TimeInterval {
        // A 60s client-side leeway means the client refreshes on its own before the
        // server expires anything, so this only needs to clear the server's own TTL.
        TimeInterval(response.expiresIn) + 2
    }
}
