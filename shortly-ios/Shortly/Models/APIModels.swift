import Foundation

// MARK: - Timestamps

/// ISO-8601 parsing that tolerates the precision the backend actually emits.
///
/// The server renders `issuedAt` with nanosecond precision
/// (`2026-09-26T08:24:58.103135928Z`). `JSONDecoder.dateDecodingStrategy.iso8601`
/// cannot read that and fails the *whole* decode with no hint about which field broke,
/// so the wire format is decoded as `String` and parsed here instead.
enum ISO8601 {
    static func date(from string: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]

        if let date = fractional.date(from: string) {
            return date
        }

        // Tolerate timestamps with no zone designator by assuming UTC rather than
        // dropping them. A missing zone is more likely a server quirk than local time.
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        if let date = plain.date(from: string) {
            return date
        }

        return plain.date(from: string + "Z")
    }
}

// MARK: - Requests

struct RegisterRequest: Encodable {
    let username: String
    let email: String
    let password: String
}

/// The backend accepts a username *or* an email in one field, which is what a sign-in
/// screen should send: people do not remember which one they registered with.
struct LoginRequest: Encodable {
    let identifier: String
    let password: String
}

struct RefreshRequest: Encodable {
    let refreshToken: String
}

struct LogoutRequest: Encodable {
    let refreshToken: String
    let allDevices: Bool
}

struct CreateVideoRequest: Encodable {
    let title: String
    let description: String
    let contentType: String
}

// MARK: - Responses

/// The wire shape returned by register, login, and refresh.
///
/// Kept separate from `AuthSession` so the wire format can gain fields without
/// changing what is persisted in the Keychain, and so a session can be built
/// with a computed absolute expiry rather than a relative one.
struct AuthResponse: Decodable, Equatable {
    let token: String
    let refreshToken: String
    let expiresIn: Int
    let refreshExpiresIn: Int
    let userId: String
    let username: String
    let email: String
    let issuedAt: String

    /// `issuedAt` falls back to now if the server ever omits it, because a session
    /// with a wrong-but-present expiry is worse than one treated as short-lived.
    func session(now: Date = Date()) -> AuthSession {
        let issued = ISO8601.date(from: issuedAt) ?? now
        return AuthSession(
            token: token,
            refreshToken: refreshToken,
            userId: userId,
            username: username,
            email: email,
            accessTokenExpiresAt: issued.addingTimeInterval(TimeInterval(expiresIn))
        )
    }
}

struct UserProfile: Decodable, Equatable {
    let userId: String
    let username: String
    let email: String
    let profilePictureUrl: String?
    let bio: String?
    let createdAt: String
    let activeSessionCount: Int
}

// MARK: - Session

/// What the app persists. `token` is still the access token, so the original
/// `AuthSession { token, userId, username, email }` shape is unchanged; the refresh
/// token and the absolute expiry are additions.
struct AuthSession: Codable, Equatable {
    let token: String
    let refreshToken: String
    let userId: String
    let username: String
    let email: String
    let accessTokenExpiresAt: Date

    /// Normalises the expiry to whole seconds.
    ///
    /// The Keychain stores this as ISO-8601, which has no sub-second component, so a
    /// session with a fractional expiry would not compare equal to the same session read
    /// back from storage. Enforcing it here rather than at each construction site means
    /// `Equatable` means what callers expect, and the value is the same in memory and on
    /// disk. Rounding *down* is the safe direction: it can only refresh marginally early.
    init(
        token: String,
        refreshToken: String,
        userId: String,
        username: String,
        email: String,
        accessTokenExpiresAt: Date
    ) {
        self.token = token
        self.refreshToken = refreshToken
        self.userId = userId
        self.username = username
        self.email = email
        self.accessTokenExpiresAt = Date(
            timeIntervalSince1970: accessTokenExpiresAt.timeIntervalSince1970.rounded(.down)
        )
    }

    /// How early to refresh, in seconds.
    ///
    /// A request issued with a token that expires *during* the round trip fails, and
    /// the user sees an error for a session that was valid when they tapped. Refreshing
    /// a minute early costs one extra refresh on a 15-minute token and removes that
    /// whole class of spurious failure.
    static let refreshLeeway: TimeInterval = 60

    func needsRefresh(now: Date = Date()) -> Bool {
        now.addingTimeInterval(Self.refreshLeeway) >= accessTokenExpiresAt
    }
}

// MARK: - Video

struct PresignedVideoUpload: Decodable, Equatable {
    let videoId: UUID
    let uploadURL: URL
    let s3Key: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case videoId
        case uploadURL = "uploadUrl"
        case s3Key
        case expiresAt
    }
}

enum VideoStatus: String, Decodable {
    case pending = "PENDING"
    case uploading = "UPLOADING"
    case processing = "PROCESSING"
    case ready = "READY"
    case failed = "FAILED"
}

struct Video: Decodable, Identifiable, Equatable {
    let id: UUID
    let title: String
    let description: String?
    let uploadURL: URL?
    let userId: String
    let videoStatus: VideoStatus
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id
        case title
        case description
        case uploadURL = "uploadUrl"
        case userId
        case videoStatus
        case createdAt
    }

    var playbackURL: URL? {
        uploadURL
    }
}
