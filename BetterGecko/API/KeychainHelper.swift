import Foundation
import Security

enum Keychain {
    private static let service = "com.bettergecko.app"
    /// Readable once the phone has been unlocked after a restart, so a background refresh
    /// can re-login while the phone is locked. The default (WhenUnlocked) would block that.
    private static let accessible = kSecAttrAccessibleAfterFirstUnlock

    /// Moves items saved with the old default accessibility to `accessible`. Needs the
    /// phone unlocked, so call it on a normal launch. No-op once migrated.
    static func migrateAccessibility() {
        let query: [CFString: Any] = [kSecClass: kSecClassGenericPassword, kSecAttrService: service]
        SecItemUpdate(query as CFDictionary, [kSecAttrAccessible: accessible] as CFDictionary)
    }

    static func save(_ value: String, forKey key: String) {
        let data = Data(value.utf8)
        let query: [CFString: Any] = [
            kSecClass:       kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
        ]
        // Delete any existing item first
        SecItemDelete(query as CFDictionary)
        // Add new item
        var attrs = query
        attrs[kSecValueData] = data
        attrs[kSecAttrAccessible] = accessible
        SecItemAdd(attrs as CFDictionary, nil)
    }

    static func load(forKey key: String) -> String? {
        let query: [CFString: Any] = [
            kSecClass:            kSecClassGenericPassword,
            kSecAttrService:      service,
            kSecAttrAccount:      key,
            kSecReturnData:       true,
            kSecMatchLimit:       kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func delete(forKey key: String) {
        let query: [CFString: Any] = [
            kSecClass:       kSecClassGenericPassword,
            kSecAttrService: service,
            kSecAttrAccount: key,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
