import Foundation
import XCTest
@testable import Shortly

/// Reads a request body that `URLSession` may have turned into a stream.
private func requestBody(from request: URLRequest) -> Data? {
    if let body = request.httpBody {
        return body
    }

    guard let stream = request.httpBodyStream else {
        return nil
    }

    stream.open()
    defer { stream.close() }

    var data = Data()
    var buffer = [UInt8](repeating: 0, count: 1024)

    while true {
        let count = stream.read(&buffer, maxLength: buffer.count)
        guard count > 0 else { break }
        data.append(buffer, count: count)
    }

    return data
}

/// A minimal successful auth response, shared by the transport and refresh suites.
let authResponseJSON = """
{
  "token": "access-1",
  "refreshToken": "refresh-1",
  "expiresIn": 900,
  "refreshExpiresIn": 2592000,
  "userId": "user-1",
  "username": "alice",
  "email": "alice@example.com",
  "issuedAt": "2026-09-26T08:24:58Z"
}
"""

// MARK: - Transport

final class APIClientTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HTTPStub.reset()
    }

    override func tearDown() {
        HTTPStub.reset()
        super.tearDown()
    }

    func testRegisterSendsRequestAndDecodesSession() async throws {
        HTTPStub.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/register")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))

            let body = try XCTUnwrap(requestBody(from: request))
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(json["username"], "alice")
            XCTAssertEqual(json["email"], "alice@example.com")
            XCTAssertEqual(json["password"], "secret1234")

            return .json(try XCTUnwrap(HTTPStub.response(for: request, status: 201)), body: """
            {
              "token": "access-1",
              "refreshToken": "refresh-1",
              "expiresIn": 900,
              "refreshExpiresIn": 2592000,
              "userId": "user-1",
              "username": "alice",
              "email": "alice@example.com",
              "issuedAt": "2026-09-26T08:24:58.103135928Z"
            }
            """)
        }

        let response = try await makeClient().register(
            username: "alice",
            email: "alice@example.com",
            password: "secret1234"
        )
        let session = response.session()

        XCTAssertEqual(session.token, "access-1")
        XCTAssertEqual(session.refreshToken, "refresh-1")
        XCTAssertEqual(session.userId, "user-1")
        XCTAssertEqual(session.username, "alice")
    }

    /// The server renders `issuedAt` with nanosecond precision. `JSONDecoder`'s built-in
    /// `.iso8601` strategy returns nil for that, which would fail the whole decode and
    /// make every successful sign-in look like a server error.
    func testDecodesNanosecondIssuedAtAndDerivesExpiry() throws {
        // Nanosecond precision is what the server actually emits, and it is the one
        // precision `JSONDecoder.dateDecodingStrategy.iso8601` silently fails on.
        let issuedAt = "2026-09-26T08:24:58.103135928Z"
        let json = """
        {
          "token": "access-1",
          "refreshToken": "refresh-1",
          "expiresIn": 900,
          "refreshExpiresIn": 2592000,
          "userId": "user-1",
          "username": "alice",
          "email": "alice@example.com",
          "issuedAt": "\(issuedAt)"
        }
        """.data(using: .utf8)!

        let response = try JSONDecoder().decode(AuthResponse.self, from: json)
        let session = response.session()

        let issued = try XCTUnwrap(ISO8601.date(from: issuedAt))
        XCTAssertEqual(session.accessTokenExpiresAt.timeIntervalSince(issued), 900, accuracy: 1.0)
        XCTAssertEqual(
            session.accessTokenExpiresAt.timeIntervalSince1970.rounded(.down),
            session.accessTokenExpiresAt.timeIntervalSince1970,
            accuracy: 0.0001,
            "The expiry must be normalised to whole seconds so it survives the Keychain"
        )
    }

    /// Built from *now* rather than a fixed timestamp, so the freshness rules can be
    /// asserted without the fixture going stale.
    func testFreshnessIsJudgedAgainstTheExpiry() throws {
        func session(expiringIn seconds: TimeInterval) -> AuthSession {
            AuthSession(
                token: "access-1",
                refreshToken: "refresh-1",
                userId: "user-1",
                username: "alice",
                email: "alice@example.com",
                accessTokenExpiresAt: Date().addingTimeInterval(seconds)
            )
        }

        XCTAssertFalse(session(expiringIn: 900).needsRefresh())
        // Inside the 60s leeway, so a request would not start with a token that dies
        // mid-flight.
        XCTAssertTrue(session(expiringIn: 30).needsRefresh())
        XCTAssertTrue(session(expiringIn: -1).needsRefresh())
    }

    func testLoginSendsIdentifierAndDecodesSession() async throws {
        HTTPStub.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/login")

            let body = try XCTUnwrap(requestBody(from: request))
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(json["identifier"], "alice@example.com")
            XCTAssertEqual(json["password"], "secret1234")

            return .json(try XCTUnwrap(HTTPStub.response(for: request, status: 200)), body: authResponseJSON)
        }

        let response = try await makeClient().login(identifier: "alice@example.com", password: "secret1234")
        XCTAssertEqual(response.session().email, "alice@example.com")
    }

    func testVideosRequestCarriesTheCurrentAccessToken() async throws {
        HTTPStub.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/videos/user/user-1")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer access-1")

            return .json(try XCTUnwrap(HTTPStub.response(for: request, status: 200)), body: "[]")
        }

        let videos = try await makeAuthenticatedClient().videos(forUserID: "user-1")
        XCTAssertTrue(videos.isEmpty)
    }

    func testVideoResponseDecodesPlaybackURL() async throws {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 200))
            return .json(response, body: """
            [{
              "id": "11111111-1111-1111-1111-111111111111",
              "title": "First reel",
              "description": "A first upload",
              "uploadUrl": "https://cdn.example.com/raw/user-1/video.mp4",
              "userId": "user-1",
              "videoStatus": "READY",
              "createdAt": "2026-09-25T12:00:00"
            }]
            """)
        }

        let videos = try await makeAuthenticatedClient().videos(forUserID: "user-1")
        let video = try XCTUnwrap(videos.first)

        XCTAssertEqual(video.id.uuidString, "11111111-1111-1111-1111-111111111111")
        XCTAssertEqual(video.videoStatus, .ready)
        XCTAssertEqual(video.playbackURL?.host, "cdn.example.com")
    }

    // MARK: Problem decoding

    func testValidationProblemSurfacesEveryViolation() async {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 400))
            return .json(response, body: """
            {
              "detail": "One or more fields are invalid.",
              "status": 400,
              "title": "validation-failed",
              "type": "https://shortly.dev/problems/validation-failed",
              "code": "validation-failed",
              "violations": [
                "email: Must be a valid email address.",
                "username: Username must be between 3 and 32 characters."
              ]
            }
            """)
        }

        await assertError(code: "validation-failed", status: 400) { error in
            guard case .validationFailed(let violations) = error else {
                return XCTFail("Expected validationFailed, got \(error)")
            }
            XCTAssertEqual(violations.count, 2)
            XCTAssertTrue(violations.contains("email: Must be a valid email address."))
        }
    }

    func testAccountLockedCarriesTheRetryAfter() async {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 423))
            return .json(response, body: """
            {
              "type": "https://shortly.dev/problems/account-locked",
              "title": "account-locked",
              "status": 423,
              "detail": "Account is temporarily locked. Try again in 900 seconds.",
              "instance": "/api/v1/auth/login",
              "code": "account-locked",
              "retryAfterSeconds": 900
            }
            """)
        }

        await assertError(code: "account-locked", status: 423) { error in
            guard case .accountLocked(let seconds) = error else {
                return XCTFail("Expected accountLocked, got \(error)")
            }
            XCTAssertEqual(seconds, 900)
        }
    }

    func testInvalidCredentialsAreDistinctFromADeadSession() async {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 401))
            return .json(response, body: """
            {"status": 401, "code": "invalid-credentials", "detail": "Invalid username or password."}
            """)
        }

        await assertError(code: "invalid-credentials", status: 401) { error in
            // Must NOT ask the app to sign in: the user has no session to drop, and
            // doing so would wipe a perfectly good session on a mistyped password.
            XCTAssertEqual(error, .invalidCredentials)
            XCTAssertFalse(error.requiresSignIn)
        }
    }

    /// A proxy or a misconfigured gateway can answer 401 with an HTML body. It must
    /// still surface as a dead session rather than as an undecodable response.
    func testBodilessUnauthorizedIsTreatedAsADeadSession() async {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 401))
            return .data(response, body: "<html>401 Unauthorized</html>")
        }

        await assertError(code: "bodiless-401", status: 401) { error in
            XCTAssertEqual(error, .sessionExpired)
            XCTAssertTrue(error.requiresSignIn)
        }
    }

    func testProblemWithNoBodyIsNotADecodingFailure() async {
        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 409))
            return .data(response, body: "")
        }

        await assertError(code: "empty-409", status: 409) { error in
            XCTAssertEqual(error, .server(statusCode: 409, code: nil, message: nil))
        }
    }

    // MARK: Helpers

    private func makeClient() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPStub.self]
        return APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: configuration)
        )
    }

    /// A client whose session is well in date, so no refresh is triggered.
    private func makeAuthenticatedClient() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPStub.self]
        return APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: configuration),
            refresher: StubRefresher(token: "access-1")
        )
    }

    private func assertError(
        code: String,
        status: Int,
        _ inspect: (APIClientError) -> Void,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await makeClient().videos(forUserID: "user-1")
            XCTFail("Expected an error for \(code)", file: file, line: line)
        } catch let error as APIClientError {
            inspect(error)
        } catch {
            XCTFail("Expected APIClientError for \(code), got \(error)", file: file, line: line)
        }
    }
}

// MARK: - Refresh behaviour

/// The behaviour that a 15-minute access token forces on the client: refresh before
/// expiry, refresh once on rejection, and never loop.
final class TokenRefreshTests: XCTestCase {
    override func setUp() {
        super.setUp()
        HTTPStub.reset()
    }

    override func tearDown() {
        HTTPStub.reset()
        super.tearDown()
    }

    /// The client must send whatever token the session hands it, and must not attempt
    /// its own expiry arithmetic. Deciding when a token is stale belongs to the session,
    /// which is the only thing that knows the refresh token.
    func testRequestCarriesTheTokenTheSessionSupplies() async throws {
        let refresher = StubRefresher(token: "whatever-the-session-says")
        let client = makeClient(refresher: refresher)

        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 200))
            return .json(response, body: "[]")
        }

        _ = try await client.videos(forUserID: "user-1")

        XCTAssertEqual(HTTPStub.recordedAuthorizations, ["Bearer whatever-the-session-says"])
        XCTAssertEqual(refresher.accessTokenCalls, 1)
    }

    func testRejectedTokenIsRefreshedAndTheRequestRetriedOnce() async throws {
        let refresher = StubRefresher(token: "stale", rotatesOnForceRefresh: true)
        let client = makeClient(refresher: refresher)

        HTTPStub.handler = { request in
            if request.value(forHTTPHeaderField: "Authorization") == "Bearer stale" {
                let rejected = try XCTUnwrap(HTTPStub.response(for: request, status: 401))
                return .json(rejected, body: """
                {"status": 401, "code": "token-expired", "detail": "The access token has expired."}
                """)
            }
            let ok = try XCTUnwrap(HTTPStub.response(for: request, status: 200))
            return .json(ok, body: "[]")
        }

        let videos = try await client.videos(forUserID: "user-1")

        XCTAssertTrue(videos.isEmpty)
        XCTAssertEqual(
            HTTPStub.recordedAuthorizations,
            ["Bearer stale", "Bearer fresh"],
            "Expected exactly one retry with the refreshed token"
        )
    }

    func testRetryHappensOnlyOnce() async {
        let refresher = StubRefresher(token: "stale", rotatesOnForceRefresh: true)
        let client = makeClient(refresher: refresher)

        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 401))
            return .json(response, body: #"{"status": 401, "code": "token-expired"}"#)
        }

        do {
            _ = try await client.videos(forUserID: "user-1")
            XCTFail("Expected the error to surface")
        } catch let error as APIClientError {
            guard case .tokenExpired = error else {
                return XCTFail("Expected tokenExpired, got \(error)")
            }
        } catch {
            XCTFail("Expected APIClientError, got \(error)")
        }

        // Two requests total. A third would mean the retry is a loop.
        XCTAssertEqual(HTTPStub.recordedAuthorizations.count, 2)
    }

    /// A refresh that fails must not leave the old token in place being retried.
    /// A session that cannot produce any token at all must be cleared, so the app
    /// returns to sign-in rather than retrying forever.
    func testUnusableSessionIsInvalidatedBeforeAnyRequest() async {
        let refresher = StubRefresher(token: "stale", rotatesOnForceRefresh: true)
        refresher.accessTokenError = .sessionExpired
        let client = makeClient(refresher: refresher)

        HTTPStub.handler = { request in
            XCTFail("No request should be sent without a token")
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 200))
            return .json(response, body: "[]")
        }

        do {
            _ = try await client.videos(forUserID: "user-1")
            XCTFail("Expected an error")
        } catch {
            XCTAssertEqual(error as? APIClientError, .sessionExpired)
        }

        XCTAssertTrue(refresher.invalidated, "A session that cannot produce a token must be cleared")
        XCTAssertTrue(HTTPStub.recordedAuthorizations.isEmpty)
    }

    /// The auth endpoints must never inject a token, or refreshing would recurse.
    func testAuthEndpointsDoNotSendAuthorization() async throws {
        let refresher = StubRefresher(token: "access-1")
        let client = makeClient(refresher: refresher)

        HTTPStub.handler = { request in
            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 200))
            return .json(response, body: authResponseJSON)
        }

        _ = try await client.login(identifier: "alice", password: "secret1234")
        _ = try await client.refresh(refreshToken: "refresh-1")

        XCTAssertTrue(
            HTTPStub.recordedAuthorizations.allSatisfy { $0 == "<none>" },
            "Auth endpoints must be unauthenticated, saw \(HTTPStub.recordedAuthorizations)"
        )
    }

    func testLogoutSendsTheRefreshTokenAndAllDevices() async throws {
        let client = makeClient(refresher: StubRefresher(token: "access-1"))

        HTTPStub.handler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/logout")
            let body = try XCTUnwrap(requestBody(from: request))
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            XCTAssertEqual(json["refreshToken"] as? String, "refresh-1")
            XCTAssertEqual(json["allDevices"] as? Bool, true)

            let response = try XCTUnwrap(HTTPStub.response(for: request, status: 204))
            return .data(response, body: "")
        }

        try await client.logout(refreshToken: "refresh-1", allDevices: true)
    }

    private func makeClient(refresher: SessionRefreshing) -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [HTTPStub.self]
        return APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: configuration),
            refresher: refresher
        )
    }
}

// MARK: - Session manager

final class SessionManagerTests: XCTestCase {
    func testRestoreLoadsAPersistedSession() async throws {
        let saved = SessionManagerTests.session(token: "access-1", expiresIn: 900)
        let store = InMemorySessionStore(session: saved)
        let manager = SessionManager(client: StubAuthService(), store: store)

        await manager.restore()

        let loaded = await manager.currentSession
        XCTAssertEqual(loaded?.token, "access-1")
        XCTAssertEqual(loaded?.refreshToken, "refresh-1")
    }

    /// A session written before refresh tokens existed will not decode. That is a
    /// sign-in, not a crash, and must not put an error in front of the user.
    func testRestoreQuietlyDiscardsAnUndecodableSession() async throws {
        let store = FailingSessionStore()
        let manager = SessionManager(client: StubAuthService(), store: store)

        await manager.restore()

        let loaded = await manager.currentSession
        XCTAssertNil(loaded)
        XCTAssertTrue(store.wasCleared)
    }

    func testAdoptPersistsTheSession() async {
        let store = InMemorySessionStore()
        let manager = SessionManager(client: StubAuthService(), store: store)

        await manager.adopt(SessionManagerTests.session(token: "access-1", expiresIn: 900))

        XCTAssertEqual(store.session?.token, "access-1")
    }

    func testAccessTokenIsReturnedUntouchedWhenStillFresh() async throws {
        let auth = StubAuthService()
        let manager = SessionManager(
            client: auth,
            store: InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 900))
        )
        await manager.restore()

        let token = try await manager.accessToken()

        XCTAssertEqual(token, "access-1")
        XCTAssertEqual(auth.refreshCalls, 0, "A fresh token must not trigger a refresh")
    }

    func testAccessTokenRefreshesWhenCloseToExpiry() async throws {
        let auth = StubAuthService()
        // Inside the 60s leeway.
        let manager = SessionManager(
            client: auth,
            store: InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 30))
        )
        await manager.restore()

        let token = try await manager.accessToken()

        XCTAssertEqual(token, "access-2")
        XCTAssertEqual(auth.refreshCalls, 1)
    }

    /// The behaviour that protects the session from self-inflicted reuse detection.
    ///
    /// The backend rotates on every refresh and revokes the whole family on a replay,
    /// so N concurrent requests each starting their own refresh would look exactly
    /// like a stolen token and sign the user out. They must share one refresh.
    func testConcurrentRefreshesCollapseIntoOne() async throws {
        let auth = StubAuthService()
        auth.refreshDelay = .milliseconds(50)

        let manager = SessionManager(
            client: auth,
            store: InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 30))
        )
        await manager.restore()

        let tokens = try await withThrowingTaskGroup(of: String.self) { group in
            for _ in 0..<8 {
                group.addTask { try await manager.accessToken() }
            }
            var collected: [String] = []
            for try await token in group {
                collected.append(token)
            }
            return collected
        }

        XCTAssertEqual(auth.refreshCalls, 1, "Eight concurrent requests must not trigger eight refreshes")
        XCTAssertEqual(Set(tokens), ["access-2"])
    }

    func testADeadRefreshTokenClearsTheSession() async throws {
        let auth = StubAuthService()
        auth.refreshError = .sessionExpired

        let store = InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 30))
        let manager = SessionManager(client: auth, store: store)
        await manager.restore()

        do {
            _ = try await manager.accessToken()
            XCTFail("Expected a failure")
        } catch {
            XCTAssertEqual(error as? APIClientError, .sessionExpired)
        }

        let remaining = await manager.currentSession
        XCTAssertNil(remaining, "A dead refresh token must not be kept for the next attempt")
        XCTAssertNil(store.session)
    }

    /// Sign-out must clear locally even when the server call fails, or the user is left
    /// holding a token they explicitly asked to revoke.
    func testSignOutClearsLocallyEvenWhenTheServerFails() async throws {
        let auth = StubAuthService()
        auth.logoutError = .transport("offline")

        let store = InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 900))
        let manager = SessionManager(client: auth, store: store)
        await manager.restore()

        await manager.signOut()

        let remaining = await manager.currentSession
        XCTAssertNil(remaining)
        XCTAssertNil(store.session)
    }

    func testSignOutRevokesTheRefreshTokenServerSide() async throws {
        let auth = StubAuthService()
        let manager = SessionManager(
            client: auth,
            store: InMemorySessionStore(session: SessionManagerTests.session(token: "access-1", expiresIn: 900))
        )
        await manager.restore()

        await manager.signOut(allDevices: true)

        XCTAssertEqual(auth.logoutRefreshToken, "refresh-1")
        XCTAssertEqual(auth.logoutAllDevices, true)
    }

    static func session(token: String, expiresIn: TimeInterval) -> AuthSession {
        AuthSession(
            token: token,
            refreshToken: "refresh-1",
            userId: "user-1",
            username: "alice",
            email: "alice@example.com",
            accessTokenExpiresAt: Date().addingTimeInterval(expiresIn)
        )
    }
}

// MARK: - Doubles

/// A refresher whose answers the test dictates.
///
/// `rotatesOnForceRefresh` models a real rotation: the token handed out changes only
/// when a refresh is forced, so a test can watch the rejected token and the new one.
private final class StubRefresher: SessionRefreshing, @unchecked Sendable {
    private let lock = NSLock()
    private var _token: String
    private let rotatesOnForceRefresh: Bool

    private var _accessTokenCalls = 0
    private var _invalidated = false
    var accessTokenError: APIClientError?

    init(token: String, rotatesOnForceRefresh: Bool = false) {
        self._token = token
        self.rotatesOnForceRefresh = rotatesOnForceRefresh
    }

    var accessTokenCalls: Int {
        lock.withLock { _accessTokenCalls }
    }

    var invalidated: Bool {
        lock.withLock { _invalidated }
    }

    func accessToken() async throws -> String {
        if let accessTokenError { throw accessTokenError }
        return lock.withLock { () -> String in
            _accessTokenCalls += 1
            return _token
        }
    }

    func forceRefresh() async throws -> String {
        lock.withLock {
            if rotatesOnForceRefresh { _token = "fresh" }
            return _token
        }
    }

    func invalidate() async {
        lock.withLock { _invalidated = true }
    }
}

private final class StubAuthService: AuthSessionServing, @unchecked Sendable {
    private let lock = NSLock()
    private var _refreshCalls = 0
    private var _logoutRefreshToken: String?
    private var _logoutAllDevices = false

    var refreshDelay: Duration?
    var refreshError: APIClientError?
    var logoutError: APIClientError?

    var refreshCalls: Int { lock.withLock { _refreshCalls } }
    var logoutRefreshToken: String? { lock.withLock { _logoutRefreshToken } }
    var logoutAllDevices: Bool { lock.withLock { _logoutAllDevices } }

    func register(username: String, email: String, password: String) async throws -> AuthResponse {
        throw APIClientError.server(statusCode: 501, code: nil, message: "not used")
    }

    func login(identifier: String, password: String) async throws -> AuthResponse {
        throw APIClientError.server(statusCode: 501, code: nil, message: "not used")
    }

    func refresh(refreshToken: String) async throws -> AuthResponse {
        if let refreshDelay {
            try? await Task.sleep(for: refreshDelay)
        }
        if let refreshError {
            lock.withLock { _refreshCalls += 1 }
            throw refreshError
        }

        return lock.withLock {
            _refreshCalls += 1
            let json = """
            {
              "token": "access-2",
              "refreshToken": "refresh-2",
              "expiresIn": 900,
              "refreshExpiresIn": 2592000,
              "userId": "user-1",
              "username": "alice",
              "email": "alice@example.com",
              "issuedAt": "2026-09-26T08:24:58Z"
            }
            """
            // Safe: a literal with no interpolation.
            return try! JSONDecoder().decode(AuthResponse.self, from: Data(json.utf8))
        }
    }

    func logout(refreshToken: String, allDevices: Bool) async throws {
        lock.withLock {
            _logoutRefreshToken = refreshToken
            _logoutAllDevices = allDevices
        }
        if let logoutError { throw logoutError }
    }

    func me(token: String) async throws -> UserProfile {
        throw APIClientError.server(statusCode: 501, code: nil, message: "not used")
    }
}

private final class FailingSessionStore: SessionStoring, @unchecked Sendable {
    private(set) var wasCleared = false

    func load() throws -> AuthSession? {
        throw SessionStoreError.invalidData
    }

    func save(_ session: AuthSession) throws {
        throw SessionStoreError.encodingFailed
    }

    func clear() throws {
        wasCleared = true
    }
}

// MARK: - HTTP stub

private enum StubBody {
    case json(HTTPURLResponse, body: String)
    case data(HTTPURLResponse, body: String)
}

private final class HTTPStub: URLProtocol {
    static var handler: ((URLRequest) throws -> StubBody)?

    private static let lock = NSLock()
    private static var _authorizations: [String] = []

    static var recordedAuthorizations: [String] {
        lock.withLock { _authorizations }
    }

    /// Called from `setUp` as well as `tearDown`, so recorded headers never leak
    /// between tests and an ordering change cannot fail an unrelated case.
    static func reset() {
        lock.withLock { _authorizations = [] }
        handler = nil
    }

    static func response(for request: URLRequest, status: Int) -> HTTPURLResponse? {
        HTTPURLResponse(
            url: request.url!,
            statusCode: status,
            httpVersion: nil,
            headerFields: ["Content-Type": "application/json"]
        )
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        let request = self.request

        Self.lock.withLock {
            Self._authorizations.append(
                request.value(forHTTPHeaderField: "Authorization") ?? "<none>"
            )
        }

        guard let handler = Self.handler else {
            XCTFail("HTTPStub.handler is missing")
            return
        }

        do {
            let result = try handler(request)
            let (response, data): (HTTPURLResponse, Data) = {
                switch result {
                case .json(let response, let body):
                    return (response, Data(body.utf8))
                case .data(let response, let body):
                    return (response, Data(body.utf8))
                }
            }()

            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
