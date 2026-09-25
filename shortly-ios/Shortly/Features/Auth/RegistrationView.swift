import SwiftUI

struct RegistrationView: View {
    @EnvironmentObject private var appModel: AppModel
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
                    VStack(spacing: 10) {
                        Image(systemName: "play.square.stack.fill")
                            .font(.system(size: 54, weight: .bold))
                            .foregroundStyle(.pink)
                        Text("Shortly")
                            .font(.system(size: 42, weight: .black, design: .rounded))
                        Text("Create your account")
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }

                    VStack(spacing: 14) {
                        field(title: "Username", text: $username, identifier: "auth.username")
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textContentType(.username)

                        field(title: "Email", text: $email, identifier: "auth.email")
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textContentType(.emailAddress)

                        SecureField("Password", text: $password)
                            .textContentType(.newPassword)
                            .padding(16)
                            .background(.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                            .overlay {
                                RoundedRectangle(cornerRadius: 14)
                                    .stroke(.white.opacity(0.12), lineWidth: 1)
                            }
                            .accessibilityIdentifier("auth.password")
                    }

                    if let validationMessage {
                        Text(validationMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }

                    Button {
                        submit()
                    } label: {
                        HStack {
                            if appModel.isRegistering {
                                ProgressView()
                                    .tint(.white)
                            }
                            Text(appModel.isRegistering ? "Creating account" : "Continue")
                                .fontWeight(.bold)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                    }
                    .buttonStyle(.borderedProminent)
                    .buttonBorderShape(.roundedRectangle(radius: 14))
                    .disabled(appModel.isRegistering)
                    .accessibilityIdentifier("auth.submit")

                    Text("The current API supports registration only. Login and token refresh will be added when the auth contract is completed.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
                .frame(maxWidth: 440)
                .padding(.horizontal, 24)
                .padding(.vertical, 56)
                .frame(maxWidth: .infinity)
            }
        }
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

    private func submit() {
        let cleanUsername = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !cleanUsername.isEmpty else {
            validationMessage = "Enter a username."
            return
        }

        guard cleanEmail.contains("@") else {
            validationMessage = "Enter a valid email address."
            return
        }

        guard password.count >= 6 else {
            validationMessage = "Password must contain at least 6 characters."
            return
        }

        validationMessage = nil
        Task {
            await appModel.register(username: cleanUsername, email: cleanEmail, password: password)
            password = ""
        }
    }
}
