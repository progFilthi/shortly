import Foundation

protocol VideoUploading: AnyObject {
    func upload(fileAt fileURL: URL, to uploadURL: URL, contentType: String) async throws
}

final class S3VideoUploader: VideoUploading {
    private let session: URLSession

    init(session: URLSession = .shared) {
        self.session = session
    }

    func upload(fileAt fileURL: URL, to uploadURL: URL, contentType: String) async throws {
        var request = URLRequest(url: uploadURL)
        request.httpMethod = "PUT"
        request.timeoutInterval = 300
        request.setValue(contentType, forHTTPHeaderField: "Content-Type")

        let (_, response) = try await session.upload(for: request, fromFile: fileURL)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw APIClientError.invalidResponse
        }

        guard (200..<300).contains(httpResponse.statusCode) else {
            throw APIClientError.http(statusCode: httpResponse.statusCode, message: nil)
        }
    }
}
