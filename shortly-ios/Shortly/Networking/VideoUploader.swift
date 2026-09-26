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
            // S3 replies with its own XML error, not our problem document, so there is
            // no code to switch on. A 403 here is nearly always an expired presigned URL
            // rather than a permissions problem, and saying so saves the user from
            // retrying a request that can never succeed.
            let message: String? = httpResponse.statusCode == 403
                ? "The upload link expired before the upload finished. Please try again."
                : nil

            throw APIClientError.server(statusCode: httpResponse.statusCode, code: nil, message: message)
        }
    }
}
