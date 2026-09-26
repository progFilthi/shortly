import Foundation

/// An RFC 9457 problem document as the backend emits it.
///
/// Every field is optional because a 5xx may arrive from a proxy or a load balancer
/// rather than from our own handler, and a client that cannot decode an error body
/// reports "The server response could not be read" instead of the actual problem.
struct APIProblem: Decodable, Equatable {
    let type: String?
    let title: String?
    let status: Int?
    let detail: String?
    let instance: String?
    let code: String?
    let violations: [String]?
    let retryAfterSeconds: Int?
    let currentStatus: String?
    let traceId: String?

    enum CodingKeys: String, CodingKey {
        case type
        case title
        case status
        case detail
        case instance
        case code
        case violations
        case retryAfterSeconds
        case currentStatus
        case traceId
    }

    /// The best human-readable message available, in order of usefulness.
    var bestMessage: String? {
        if let detail, !detail.isEmpty { return detail }
        if let title, !title.isEmpty { return title }
        return nil
    }
}

enum APIClientError: LocalizedError, Equatable {
    case invalidURL
    case invalidResponse
    case decoding(String)

    /// The access token is past its expiry. Recoverable with the refresh token.
    case tokenExpired

    /// The credentials were wrong, or the account does not exist. Deliberately
    /// indistinguishable: telling them apart would enumerate accounts.
    case invalidCredentials

    /// The refresh token is gone, was revoked, or its family was revoked after a
    /// replay. The session cannot be recovered; the user must sign in again.
    case sessionExpired

    case accountLocked(retryAfterSeconds: Int)

    case validationFailed([String])

    /// A state conflict, e.g. completing a video whose object was never uploaded.
    case conflict(message: String, currentStatus: String?)

    /// Any other non-2xx, carrying the problem `code` when there was one.
    case server(statusCode: Int, code: String?, message: String?)

    case transport(String)

    var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "The API URL is invalid."
        case .invalidResponse:
            return "The server returned an invalid response."
        case .decoding:
            return "The server response could not be read."
        case .tokenExpired:
            return "Your session has expired."
        case .invalidCredentials:
            return "Incorrect username or password."
        case .sessionExpired:
            return "Your session has ended. Please sign in again."
        case .accountLocked(let seconds):
            return "Too many failed attempts. Try again in \(seconds / 60) minute(s)."
        case .validationFailed(let violations):
            return violations.joined(separator: "\n")
        case .conflict(let message, _):
            return message
        case .server(let statusCode, _, let message):
            return message ?? "The server returned HTTP \(statusCode)."
        case .transport(let message):
            return message
        }
    }

    /// True when the app should drop the session and return to the sign-in screen.
    ///
    /// Deliberately narrow. `.tokenExpired` is excluded because the client retries it
    /// with a refresh first, and `.invalidCredentials` because a rejected sign-in has
    /// no session to drop. A bare 401 from a proxy is treated as a dead session, since
    /// there is nothing to refresh and retrying would loop.
    var requiresSignIn: Bool {
        self == .sessionExpired
    }

    /// Builds a typed error from a problem document.
    static func from(problem: APIProblem?, statusCode: Int) -> APIClientError {
        let code = problem?.code
        let message = problem?.bestMessage

        switch code {
        case "token-expired":
            // The one 401 that is recoverable. Worth separating from every other 401
            // because the recovery is "refresh and retry", not "sign in again".
            return .tokenExpired

        case "invalid-credentials":
            return .invalidCredentials

        case "refresh-token-invalid", "unauthenticated", "token-invalid":
            return .sessionExpired

        case "account-locked":
            return .accountLocked(retryAfterSeconds: problem?.retryAfterSeconds ?? 900)

        case "validation-failed":
            return .validationFailed(problem?.violations ?? [message ?? "The request was rejected."])

        case "conflicting-state", "upload-missing", "upload-too-large", "username-or-email-taken":
            return .conflict(
                message: message ?? "That action conflicts with the current state.",
                currentStatus: problem?.currentStatus
            )

        default:
            if statusCode == 401 {
                return .sessionExpired
            }
            return .server(statusCode: statusCode, code: code, message: message)
        }
    }
}
