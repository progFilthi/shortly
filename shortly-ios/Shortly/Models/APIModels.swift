import Foundation

struct RegisterRequest: Encodable {
    let username: String
    let email: String
    let password: String
}

struct AuthSession: Codable, Equatable {
    let token: String
    let userId: String
    let username: String
    let email: String
}

struct CreateVideoRequest: Encodable {
    let title: String
    let description: String
    let contentType: String
}

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
