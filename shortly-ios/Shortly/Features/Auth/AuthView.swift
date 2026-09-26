import SwiftUI

/// Sign-in and account creation.
///
/// One screen with two modes rather than two screens, because the majority of
/// launches are returning users and making them find a "Sign in" link first is a
/// step that exists only to be skipped.
struct AuthView: View {
    private enum Mode: String, CaseIterable {
        case signIn = "Sign in"
        case register = "Create account"

        var cta: String {
            switch self {
            case .signIn: return "Sign in"
            case .register: return "Create account"
            }
        }

        var title: String {
            switch self {
            case .signIn: return "Welcome back"
            case .register: return "Create your account"
            }
        }
    }

    @EnvironmentObject private var appModel: AppModel

    @State private var mode: Mode = .signIn
    @State private var identifier = ""
    @State private var username = ""
    @State private var email = ""
    @State private var password = ""
    @State private var validationMessage: String?

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color.black, Color(red: 0.22, green: 0.02, blue: 0.13), Color.black],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 28) {
                    header

                    Picker("Mode", selection: $mode) {
                        ForEach(Mode.allCases, id: \.self) { Text($0.rawValue).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    .accessibilityIdentifier("auth.mode")

                    fields

                    if let validationMessage {
                        Text(validationMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .accessibilityIdentifier("auth.error")
                    }

                    if let lockout = appModel.lockout {
                        lockoutBanner(lockout)
                    }

                    submitButton
                }
                .frame(maxWidth: 440)
                .padding(.horizontal, 24)
                .padding(.vertical, 56)
                .frame(maxWidth: .infinity)
            }
            // The form is longer than a phone screen once the keyboard is up, and a
            // keyboard that will not get out of the way also covers the submit button.
            .scrollDismissesKeyboard(.immediately)
        }
    }

    /// `.newPassword` on the create-account screen is what lets iOS offer a generated
    /// strong password, which is the whole reason it exists.
    ///
    /// Downgraded under UI tests, because the AutoFill flow swallows programmatic
    /// `typeText` after the first character - a field typed 20 characters in arrives
    /// holding one, and the create-account path becomes untestable. Only the keyboard
    /// hint changes; the auth path under test is identical.
    private var passwordContentType: UITextContentType {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--uitesting-reset-session") {
            return .password
        }
        #endif
        return mode == .signIn ? .password : .newPassword
    }

    // MARK: - Pieces

    private var header: some View {
        VStack(spacing: 10) {
            Image(systemName: "play.square.stack.fill")
                .font(.system(size: 54, weight: .bold))
                .foregroundStyle(.pink)
            Text("Shortly")
                .font(.system(size: 42, weight: .black, design: .rounded))
            Text(mode.title)
                .font(.title2.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityIdentifier("auth.title")
        }
    }

    @ViewBuilder
    private var fields: some View {
        VStack(spacing: 14) {
            if mode == .signIn {
                // The backend takes a username or an email in one field, so the label
                // says both rather than making the user guess which one it wants.
                field(title: "Username or email", text: $identifier, identifier: "auth.username")
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)
            } else {
                field(title: "Username", text: $username, identifier: "auth.username")
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)

                field(title: "Email", text: $email, identifier: "auth.email")
                    .keyboardType(.emailAddress)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.emailAddress)
            }

            SecureField("Password", text: $password)
                .textContentType(passwordContentType)
                .padding(16)
                .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                .overlay {
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(.white.opacity(0.12), lineWidth: 1)
                }
                .accessibilityIdentifier("auth.password")
        }
    }

    /// `TimelineView` rather than a `Timer`, so only this label re-renders once a
    /// second while the countdown is on screen. A timer on the whole form would
    /// re-evaluate every text field once a second, for the whole time it is open.
    private func lockoutBanner(_ lockout: LockoutState) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { _ in
            HStack(spacing: 10) {
                Image(systemName: "lock.fill")
                Text("Too many attempts. Try again in \(formatted(lockout.remainingSeconds(at: .now))).")
                    .font(.footnote.weight(.semibold))
            }
            .foregroundStyle(.orange)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(.orange.opacity(0.14), in: RoundedRectangle(cornerRadius: 12))
            .accessibilityIdentifier("auth.lockout")
        }
    }

    private var submitButton: some View {
        Button {
            submit()
        } label: {
            HStack {
                if appModel.isAuthenticating {
                    ProgressView().tint(.white)
                }
                Text(appModel.isAuthenticating ? "Please wait" : mode.cta)
                    .fontWeight(.bold)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 16)
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.roundedRectangle(radius: 14))
        .disabled(appModel.isAuthenticating || appModel.lockout != nil)
        .accessibilityIdentifier("auth.submit")
    }

    // MARK: - Actions

    private func submit() {
        validationMessage = validate()
        guard validationMessage == nil else { return }

        Task {
            switch mode {
            case .signIn:
                let clean = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
                await appModel.signIn(identifier: clean, password: password)
            case .register:
                await appModel.register(
                    username: username.trimmingCharacters(in: .whitespacesAndNewlines),
                    email: email.trimmingCharacters(in: .whitespacesAndNewlines),
                    password: password
                )
            }
            password = ""
        }
    }

    /// Client-side checks, kept to what saves a round trip.
    ///
    /// The server remains the authority; it returns per-field violations that are
    /// surfaced in full, so a rule that only exists there still produces a useful
    /// message rather than a bare rejection.
    private func validate() -> String? {
        if appModel.lockout != nil {
            return "This account is temporarily locked."
        }

        switch mode {
        case .signIn:
            let clean = identifier.trimmingCharacters(in: .whitespacesAndNewlines)
            if clean.isEmpty {
                return "Enter your username or email."
            }

        case .register:
            let cleanUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
            let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)

            if cleanUsername.isEmpty {
                return "Enter a username."
            }
            if cleanEmail.isEmpty {
                return "Enter your email address."
            }
            if !cleanEmail.contains("@") {
                return "Enter a valid email address."
            }
        }

        if password.isEmpty {
            return "Enter your password."
        }
        // 10, to match the server. Checking 6 here would let a user fill in a form
        // the server is guaranteed to reject.
        if mode == .register && password.count < 10 {
            return "Use at least 10 characters."
        }

        return nil
    }

    private func formatted(_ seconds: Int) -> String {
        let minutes = seconds / 60
        let remainder = seconds % 60
        if minutes > 0 {
            return remainder > 0 ? "\(minutes)m \(remainder)s" : "\(minutes)m"
        }
        return "\(remainder)s"
    }

    private func field(title: String, text: Binding<String>, identifier: String) -> some View {
        TextField(title, text: text)
            .padding(16)
            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
            .overlay {
                RoundedRectangle(cornerRadius: 14)
                    .stroke(.white.opacity(0.12), lineWidth: 1)
            }
            .accessibilityIdentifier(identifier)
    }
}
