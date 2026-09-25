import Foundation
import XCTest
@testable import Shortly

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
        guard count > 0 else {
            break
        }
        data.append(buffer, count: count)
    }

    return data
}

final class APIClientTests: XCTestCase {
    override func tearDown() {
        URLProtocolStub.requestHandler = nil
        super.tearDown()
    }

    func testRegisterSendsRequestAndDecodesSession() async throws {
        URLProtocolStub.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/auth/register")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Content-Type"), "application/json")

            let body = try XCTUnwrap(requestBody(from: request))
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(json["username"], "alice")
            XCTAssertEqual(json["email"], "alice@example.com")
            XCTAssertEqual(json["password"], "secret123")

            let data = Data(
                """
                {
                  "token": "jwt",
                  "userId": "user-1",
                  "username": "alice",
                  "email": "alice@example.com"
                }
                """.utf8
            )
            return (HTTPURLResponse(url: request.url!, statusCode: 201, httpVersion: nil, headerFields: nil)!, data)
        }

        let client = makeClient()
        let session = try await client.register(username: "alice", email: "alice@example.com", password: "secret123")

        XCTAssertEqual(session.token, "jwt")
        XCTAssertEqual(session.userId, "user-1")
        XCTAssertEqual(session.username, "alice")
    }

    func testVideosRequestIncludesBearerToken() async throws {
        URLProtocolStub.requestHandler = { request in
            XCTAssertEqual(request.url?.path, "/api/v1/videos/user/user-1")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer jwt")

            let data = Data("[]".utf8)
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, data)
        }

        let client = makeClient()
        let videos = try await client.videos(forUserID: "user-1", token: "jwt")

        XCTAssertTrue(videos.isEmpty)
    }

    func testVideoResponseDecodesPlaybackURL() async throws {
        URLProtocolStub.requestHandler = { request in
            let data = Data(
                """
                [{
                  "id": "11111111-1111-1111-1111-111111111111",
                  "title": "First reel",
                  "description": "A first upload",
                  "uploadUrl": "https://cdn.example.com/raw/user-1/video.mp4",
                  "userId": "user-1",
                  "videoStatus": "READY",
                  "createdAt": "2026-09-25T12:00:00"
                }]
                """.utf8
            )
            return (HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!, data)
        }

        let client = makeClient()
        let videos = try await client.videos(forUserID: "user-1", token: "jwt")
        let video = try XCTUnwrap(videos.first)

        XCTAssertEqual(video.id.uuidString, "11111111-1111-1111-1111-111111111111")
        XCTAssertEqual(video.videoStatus, .ready)
        XCTAssertEqual(video.playbackURL?.host, "cdn.example.com")
    }

    func testUnauthorizedResponseUsesSessionError() async {
        URLProtocolStub.requestHandler = { request in
            let data = Data("{\"error\":\"Invalid or expired JWT token\"}".utf8)
            return (HTTPURLResponse(url: request.url!, statusCode: 401, httpVersion: nil, headerFields: nil)!, data)
        }

        do {
            _ = try await makeClient().videos(forUserID: "user-1", token: "expired")
            XCTFail("Expected an unauthorized error")
        } catch let error as APIClientError {
            guard case .unauthorized = error else {
                return XCTFail("Expected unauthorized, received \(error)")
            }
        } catch {
            XCTFail("Expected APIClientError, received \(error)")
        }
    }

    private func makeClient() -> APIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [URLProtocolStub.self]
        return APIClient(
            baseURL: URL(string: "http://localhost:8080")!,
            session: URLSession(configuration: configuration)
        )
    }
}

private final class URLProtocolStub: URLProtocol {
    static var requestHandler: ((URLRequest) throws -> (HTTPURLResponse, Data))?

    override class func canInit(with request: URLRequest) -> Bool {
        true
    }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest {
        request
    }

    override func startLoading() {
        guard let requestHandler = Self.requestHandler else {
            XCTFail("URLProtocolStub.requestHandler is missing")
            return
        }

        do {
            let (response, data) = try requestHandler(request)
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch {
            client?.urlProtocol(self, didFailWithError: error)
        }
    }

    override func stopLoading() {}
}
