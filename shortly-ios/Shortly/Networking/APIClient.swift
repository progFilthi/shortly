import Foundation

protocol APIClientProtocol: AnyObject {
    func register(username: String, email: String, password: String) async throws -> AuthSession
    func createVideo(title: String, description: String, contentType: String, token: String) async throws -> PresignedVideoUpload
    func completeVideo(id: UUID, token: String) async throws -> Video
    func videos(forUserID userID: String, token: String) async throws -> [Video]
}

enum APIClientError: LocalizedError {
    case invalidURL
    case invalidResponse
    case unauthorized
    case http(statusCode: Int, message: String?)
    case decoding(String)
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "The API URL is invalid."
        case .invalidResponse:
            return "The server returned an invalid response."
        case .unauthorized:
            return "Your session has expired. Create a new prototype account to continue."
        case .http(let statusCode, let message):
            if let message, !message.isEmpty {
                return message
            }
            return "The server returned HTTP \(statusCode)."
        case .decoding:
            return "The server response could not be read."
        case .transport(let message):
            return message
        }
    }
}

actor APIClient: APIClientProtocol {
    private struct BackendError: Decodable {
        let error: String?
    }

    private let baseURL: URL
    private let session: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    func register(username: String, email: String, password: String) async throws -> AuthSession {
        let body = try encoder.encode(RegisterRequest(username: username, email: email, password: password))
        return try await send(path: "/api/v1/auth/register", method: "POST", body: body)
    }

    func createVideo(
        title: String,
        description: String,
        contentType: String,
        token: String
    ) async throws -> PresignedVideoUpload {
        let body = try encoder.encode(
            CreateVideoRequest(title: title, description: description, contentType: contentType)
        )
        return try await send(path: "/api/v1/videos", method: "POST", body: body, token: token)
    }

    func completeVideo(id: UUID, token: String) async throws -> Video {
        try await send(path: "/api/v1/videos/\(id.uuidString)/complete", method: "POST", token: token)
    }

    func videos(forUserID userID: String, token: String) async throws -> [Video] {
        let encodedUserID = userID.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? userID
        return try await send(path: "/api/v1/videos/user/\(encodedUserID)", token: token)
    }

    private func send<Response: Decodable>(
        path: String,
        method: String = "GET",
        body: Data? = nil,
        token: String? = nil
    ) async throws -> Response {
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

        guard (200..<300).contains(httpResponse.statusCode) else {
            if httpResponse.statusCode == 401 {
                throw APIClientError.unauthorized
            }

            let message = (try? decoder.decode(BackendError.self, from: data))?.error
            throw APIClientError.http(statusCode: httpResponse.statusCode, message: message)
        }

        do {
            return try decoder.decode(Response.self, from: data)
        } catch {
            throw APIClientError.decoding(error.localizedDescription)
        }
    }
}
