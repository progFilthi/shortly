import Foundation
import XCTest
@testable import Shortly

final class SessionStoreTests: XCTestCase {
    func testInMemoryStoreRoundTrip() throws {
        let store = InMemorySessionStore()
        let session = AuthSession(
            token: "jwt",
            userId: "user-1",
            username: "alice",
            email: "alice@example.com"
        )

        XCTAssertNil(try store.load())

        try store.save(session)
        XCTAssertEqual(try store.load(), session)

        try store.clear()
        XCTAssertNil(try store.load())
    }

    func testKeychainStoreRoundTrip() throws {
        let store = KeychainSessionStore(service: "com.shortly.ios.tests.\(UUID().uuidString)")
        let session = AuthSession(
            token: "jwt",
            userId: "user-1",
            username: "alice",
            email: "alice@example.com"
        )

        defer {
            try? store.clear()
        }

        XCTAssertNil(try store.load())

        try store.save(session)
        XCTAssertEqual(try store.load(), session)

        try store.clear()
        XCTAssertNil(try store.load())
    }
}
