import Foundation

protocol SessionStoring {
    func load() throws -> AuthSession?
    func save(_ session: AuthSession) throws
    func clear() throws
}

enum SessionStoreError: LocalizedError {
    case keychain(OSStatus)
    case invalidData
    case encodingFailed

    var errorDescription: String? {
        switch self {
        case .keychain(let status):
            return "Keychain operation failed with status \(status)."
        case .invalidData:
            return "The saved session is invalid."
        case .encodingFailed:
            return "The session could not be saved."
        }
    }
}

final class KeychainSessionStore: SessionStoring {
    private let service: String
    private let account = "current-session"
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(service: String = "com.shortly.ios.auth") {
        self.service = service
    }

    func load() throws -> AuthSession? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecItemNotFound {
            return nil
        }

        guard status == errSecSuccess else {
            throw SessionStoreError.keychain(status)
        }

        guard let data = result as? Data else {
            throw SessionStoreError.invalidData
        }

        return try decoder.decode(AuthSession.self, from: data)
    }

    func save(_ session: AuthSession) throws {
        let data: Data
        do {
            data = try encoder.encode(session)
        } catch {
            throw SessionStoreError.encodingFailed
        }

        SecItemDelete(baseQuery as CFDictionary)

        var query = baseQuery
        query[kSecValueData as String] = data
        query[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly

        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw SessionStoreError.keychain(status)
        }
    }

    func clear() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SessionStoreError.keychain(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }
}

final class InMemorySessionStore: SessionStoring {
    private(set) var session: AuthSession?

    init(session: AuthSession? = nil) {
        self.session = session
    }

    func load() throws -> AuthSession? {
        session
    }

    func save(_ session: AuthSession) throws {
        self.session = session
    }

    func clear() throws {
        session = nil
    }
}
