import Foundation

protocol APIClientProtocol: AnyObject {
    func createVideo(title: String, description: String, contentType: String) async throws -> PresignedVideoUpload
    func completeVideo(id: UUID) async throws -> Video
    func videos(forUserID userID: String) async throws -> [Video]
    func video(id: UUID) async throws -> Video
}

actor APIClient: APIClientProtocol, AuthSessionServing {
    private let baseURL: URL
    private let session: URLSession
    private let refresher: SessionRefreshing?
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(
        baseURL: URL,
        session: URLSession = .shared,
        refresher: SessionRefreshing? = nil
    ) {
        self.baseURL = baseURL
        self.session = session
        self.refresher = refresher
    }

    // MARK: - Auth (unauthenticated transport)

    func register(username: String, email: String, password: String) async throws -> AuthResponse {
        let body = try encoder.encode(RegisterRequest(username: username, email: email, password: password))
        return try await send(path: "/api/v1/auth/register", method: "POST", body: body, requiresToken: false)
    }

    func login(identifier: String, password: String) async throws -> AuthResponse {
        let body = try encoder.encode(LoginRequest(identifier: identifier, password: password))
        return try await send(path: "/api/v1/auth/login", method: "POST", body: body, requiresToken: false)
    }

    func refresh(refreshToken: String) async throws -> AuthResponse {
        let body = try encoder.encode(RefreshRequest(refreshToken: refreshToken))
        return try await send(path: "/api/v1/auth/refresh", method: "POST", body: body, requiresToken: false)
    }

    func logout(refreshToken: String, allDevices: Bool) async throws {
        let body = try encoder.encode(LogoutRequest(refreshToken: refreshToken, allDevices: allDevices))
        // 204 with an empty body, so there is nothing to decode.
        _ = try await sendEmpty(path: "/api/v1/auth/logout", method: "POST", body: body)
    }

    func me(token: String) async throws -> UserProfile {
        try await send(path: "/api/v1/auth/me", token: token)
    }

    // MARK: - Videos

    func createVideo(title: String, description: String, contentType: String) async throws -> PresignedVideoUpload {
        let body = try encoder.encode(
            CreateVideoRequest(title: title, description: description, contentType: contentType)
        )
        return try await send(path: "/api/v1/videos", method: "POST", body: body)
    }

    func completeVideo(id: UUID) async throws -> Video {
        try await send(path: "/api/v1/videos/\(id.uuidString)/complete", method: "POST")
    }

    func videos(forUserID userID: String) async throws -> [Video] {
        let encodedUserID = userID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? userID
        return try await send(path: "/api/v1/videos/user/\(encodedUserID)")
    }

    func video(id: UUID) async throws -> Video {
        try await send(path: "/api/v1/videos/\(id.uuidString)")
    }

    // MARK: - Transport

    private func send<Response: Decodable>(
        path: String,
        method: String = "GET",
        body: Data? = nil,
        token: String? = nil,
        requiresToken: Bool = true
    ) async throws -> Response {
        // An explicit token wins; otherwise ask the session, which refreshes if needed.
        let resolvedToken = try await resolveToken(explicit: token, requiresToken: requiresToken)
        var request = try makeRequest(path: path, method: method, body: body, token: resolvedToken)
        var attempt = 1

        while true {
            let (data, response) = try await perform(request)

            if let error = try problemError(data: data, response: response) {
                // A rejected access token gets exactly one retry. Retrying more than
                // once would loop against a server that rejects every token we hold.
                if case .tokenExpired = error, attempt == 1, requiresToken, token == nil {
                    attempt += 1
                    request = try makeRequest(
                        path: path,
                        method: method,
                        body: body,
                        token: try await recoveryToken(replacing: request)
                    )
                    continue
                }

                // Only now, with the retry spent, is the session beyond saving. Clearing
                // it on the first `token-expired` would discard the refresh token that
                // the retry is about to need.
                if error.requiresSignIn, let refresher {
                    await refresher.invalidate()
                }
                throw error
            }

            do {
                return try decoder.decode(Response.self, from: data)
            } catch {
                throw APIClientError.decoding(error.localizedDescription)
            }
        }
    }

    private func sendEmpty(path: String, method: String, body: Data?) async throws {
        let request = try makeRequest(path: path, method: method, body: body, token: nil)
        let (data, response) = try await perform(request)
        if let error = try problemError(data: data, response: response) {
            throw error
        }
    }

    private func resolveToken(explicit: String?, requiresToken: Bool) async throws -> String? {
        if let explicit { return explicit }
        guard requiresToken, let refresher else { return nil }

        do {
            return try await refresher.accessToken()
        } catch {
            // The session cannot produce a token at all. Clearing it here rather than
            // relying on the refresher to have already done so keeps the invariant
            // local: after this throws, the app is signed out.
            if (error as? APIClientError)?.requiresSignIn == true {
                await refresher.invalidate()
            }
            throw error
        }
    }

    /// A token to retry with after the server rejected the previous one.
    ///
    /// Prefers whatever is current, because a concurrent request may already have
    /// refreshed and the new token may simply not have reached us. Only forces a
    /// refresh when the token has not actually changed, which is the real signal that
    /// our view of the expiry and the server's disagree.
    private func recoveryToken(replacing request: URLRequest) async throws -> String {
        guard let refresher else { throw APIClientError.sessionExpired }

        let rejected = request.value(forHTTPHeaderField: "Authorization")?
            .replacingOccurrences(of: "Bearer ", with: "")
        let current = try await refresher.accessToken()

        if current == rejected {
            return try await refresher.forceRefresh()
        }
        return current
    }

    private func makeRequest(path: String, method: String, body: Data?, token: String?) throws -> URLRequest {
        guard let url = URL(string: path, relativeTo: baseURL)?.absoluteURL else {
            throw APIClientError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")

        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        if let token {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        return request
    }

    private func perform(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch {
            throw APIClientError.transport(error.localizedDescription)
        }

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIClientError.invalidResponse
        }

        return (data, httpResponse)
    }

    /// The typed error for a non-2xx response, or `nil` when the response was fine.
    ///
    /// The body is decoded leniently on purpose: a proxy's HTML error page must still
    /// surface as an HTTP error, not as "the response could not be read".
    private func problemError(data: Data, response: HTTPURLResponse) throws -> APIClientError? {
        guard !(200..<300).contains(response.statusCode) else { return nil }

        let problem = try? decoder.decode(APIProblem.self, from: data)
        return APIClientError.from(problem: problem, statusCode: response.statusCode)
    }
}
