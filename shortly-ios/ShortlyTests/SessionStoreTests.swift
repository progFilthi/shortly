import Foundation
import XCTest
@testable import Shortly

final class SessionStoreTests: XCTestCase {
    func testInMemoryStoreRoundTrip() throws {
        let store = InMemorySessionStore()
        let session = SessionManagerTests.session(token: "jwt", expiresIn: 900)

        XCTAssertNil(try store.load())

        try store.save(session)
        XCTAssertEqual(try store.load(), session)

        try store.clear()
        XCTAssertNil(try store.load())
    }

    func testKeychainStoreRoundTrip() throws {
        let store = KeychainSessionStore(service: "com.shortly.ios.tests.\(UUID().uuidString)")
        let session = SessionManagerTests.session(token: "jwt", expiresIn: 900)

        defer {
            try? store.clear()
        }

        XCTAssertNil(try store.load())

        try store.save(session)
        let loaded = try XCTUnwrap(try store.load())

        // The expiry is what tells the client to refresh, so it has to survive the
        // round trip intact rather than decaying to "always refresh".
        XCTAssertEqual(loaded, session)
        XCTAssertEqual(
            loaded.accessTokenExpiresAt.timeIntervalSince1970,
            session.accessTokenExpiresAt.timeIntervalSince1970,
            accuracy: 1.0
        )
        XCTAssertFalse(loaded.needsRefresh())

        try store.clear()
        XCTAssertNil(try store.load())
    }
}
