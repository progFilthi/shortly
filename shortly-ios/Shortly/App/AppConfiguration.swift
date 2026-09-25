import Foundation

enum AppConfiguration {
    static var apiBaseURL: URL {
        guard
            let value = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String,
            let url = URL(string: value)
        else {
            preconditionFailure("API_BASE_URL is missing from Info.plist")
        }

        return url
    }
}
