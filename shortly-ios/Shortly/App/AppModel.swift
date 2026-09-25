import Combine
import Foundation

@MainActor
final class AppModel: ObservableObject {
    @Published private(set) var session: AuthSession?
    @Published private(set) var videos: [Video] = []
    @Published private(set) var isRestoringSession = true
    @Published private(set) var isRegistering = false
    @Published private(set) var isLoadingVideos = false
    @Published private(set) var isUploading = false
    @Published private(set) var uploadProgress = 0.0
    @Published private(set) var uploadStage = ""
    @Published var appAlert: AppAlert?

    private let apiClient: APIClientProtocol
    private let uploader: VideoUploading
    private let sessionStore: SessionStoring

    init(apiClient: APIClientProtocol, uploader: VideoUploading, sessionStore: SessionStoring) {
        self.apiClient = apiClient
        self.uploader = uploader
        self.sessionStore = sessionStore
    }

    static func live() -> AppModel {
        let arguments = ProcessInfo.processInfo.arguments
        let sessionStore: SessionStoring = arguments.contains("--uitesting-reset-session")
            ? InMemorySessionStore()
            : KeychainSessionStore()

        return AppModel(
            apiClient: APIClient(baseURL: AppConfiguration.apiBaseURL),
            uploader: S3VideoUploader(),
            sessionStore: sessionStore
        )
    }

    func restoreSession() async {
        guard isRestoringSession else {
            return
        }

        defer {
            isRestoringSession = false
        }

        do {
            let savedSession = try sessionStore.load()
            session = savedSession

            if savedSession != nil {
                await loadVideos(showErrors: false)
            }
        } catch {
            try? sessionStore.clear()
            present(error, title: "Session unavailable")
        }
    }

    func register(username: String, email: String, password: String) async {
        guard !isRegistering else {
            return
        }

        isRegistering = true
        defer {
            isRegistering = false
        }

        do {
            let newSession = try await apiClient.register(
                username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                password: password
            )

            try sessionStore.save(newSession)
            session = newSession
            videos = []
            await loadVideos(showErrors: false)
        } catch {
            present(error, title: "Account creation failed")
        }
    }

    func refreshVideos() async {
        await loadVideos(showErrors: true)
    }

    func uploadVideo(fileURL: URL, contentType: String, title: String, description: String) async throws -> Video {
        guard let session else {
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
                contentType: contentType,
                token: session.token
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
            let video = try await apiClient.completeVideo(id: presignedUpload.videoId, token: session.token)

            videos.removeAll { $0.id == video.id }
            videos.insert(video, at: 0)
            uploadProgress = 1
            return video
        } catch {
            if case APIClientError.unauthorized = error {
                clearSession()
            }
            present(error, title: "Upload failed")
            throw error
        }
    }

    func clearSession() {
        try? sessionStore.clear()
        session = nil
        videos = []
        appAlert = nil
    }

    private func loadVideos(showErrors: Bool) async {
        guard let session else {
            return
        }

        guard !isLoadingVideos else {
            return
        }

        isLoadingVideos = true
        defer {
            isLoadingVideos = false
        }

        do {
            videos = try await apiClient.videos(forUserID: session.userId, token: session.token)
        } catch {
            if case APIClientError.unauthorized = error {
                clearSession()
            }

            if showErrors {
                present(error, title: "Could not load videos")
            }
        }
    }

    private func present(_ error: Error, title: String) {
        appAlert = AppAlert(title: title, message: error.localizedDescription)
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
            return "Create an account before uploading a video."
        case .uploadInProgress:
            return "Another upload is already in progress."
        }
    }
}
