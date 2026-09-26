import XCTest

/// Drives the real app against a running stack.
///
/// Skipped rather than failed when the backend is absent, so a routine `xcodebuild test`
/// without Docker still passes. `LiveBackendTests` covers the same ground at the API level,
/// where failures point at a line of code instead of a screen.
final class LiveAuthUITests: XCTestCase {
    private var app: XCUIApplication!

    private let passwordText = "correct-horse-battery"

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    override func tearDown() {
        // Without this the next launch() only foregrounds the still-running app and the
        // previous test's signed-in state leaks in.
        app?.terminate()
        app = nil
        super.tearDown()
    }

    func testRegisterThroughTheUIAndLandOnTheApp() throws {
        try launchAndRegister(prefix: "uitest")

        // Reaching the tab bar means the register call returned, the session was adopted
        // and persisted, and the authenticated /me call came back - the whole chain,
        // through the real UI.
        XCTAssertTrue(
            app.tabBars.buttons["Profile"].waitForExistence(timeout: 20),
            "Registration did not reach the main screen"
        )
    }

    func testSignOutReturnsToTheAuthScreen() throws {
        try launchAndRegister(prefix: "uisout")

        // The keyboard can still be over the tab bar after the auth screen is torn down,
        // and a tap underneath it is swallowed rather than delivered.
        XCTAssertTrue(
            app.keyboards.firstMatch.waitForNonExistence(timeout: 15),
            "The keyboard never dismissed after sign-in"
        )

        try showProfileTab()

        let signOut = app.buttons["profile.signOut"]
        XCTAssertTrue(signOut.waitForExistence(timeout: 10), "No sign-out control on the profile screen")
        signOut.tap()

        // The confirmation repeats the toolbar's label, so the confirming action is the
        // last match rather than the only one.
        let confirms = app.buttons.matching(NSPredicate(format: "label == %@", "Sign out"))
        XCTAssertGreaterThan(confirms.count, 1, "No sign-out confirmation appeared")
        confirms.element(boundBy: confirms.count - 1).tap()

        // Sign-out clears the session, so the app must land back on the auth screen
        // rather than on a signed-out shell.
        XCTAssertTrue(
            app.staticTexts["Welcome back"].waitForExistence(timeout: 15),
            "Sign out did not return to the auth screen"
        )
    }

    // MARK: - Helpers

    private func launch() {
        app = XCUIApplication()
        // The in-memory session store so a signed-in run cannot leak into this one. It
        // also downgrades the password field's content type, without which AutoFill
        // swallows programmatic typing after the first character.
        app.launchArguments = ["--uitesting-reset-session"]
        app.launch()
    }

    private func launchAndRegister(prefix: String) throws {
        let username = "\(prefix)\(Int(Date().timeIntervalSince1970 * 1000) % 1_000_000)"
        launch()

        XCTAssertTrue(app.staticTexts["Welcome back"].waitForExistence(timeout: 10))

        let createTab = app.segmentedControls.buttons["Create account"]
        if createTab.waitForExistence(timeout: 3) {
            createTab.tap()
        } else {
            let button = app.buttons["Create account"]
            XCTAssertTrue(button.waitForExistence(timeout: 3))
            button.tap()
        }
        XCTAssertTrue(app.staticTexts["Create your account"].waitForExistence(timeout: 3))

        type(username, into: app.textFields["auth.username"])
        type("\(username)@example.com", into: app.textFields["auth.email"])
        type(passwordText, into: app.secureTextFields["auth.password"])

        app.buttons["auth.submit"].tap()
    }

    private func type(_ text: String, into field: XCUIElement) {
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText(text)
    }

    /// Switches to the profile tab, retrying until the screen confirms it.
    ///
    /// A freshly created `TabView` reaches the accessibility tree before it is
    /// interactive, so the first tap after the auth-to-tabs transition is routinely lost.
    /// Waiting for the *result* of the tap rather than sleeping a fixed amount is the
    /// difference between a stable test and a coin flip.
    private func showProfileTab() throws {
        let tab = app.tabBars.buttons["Profile"]
        let header = app.staticTexts["profile.username"]

        for _ in 0..<3 {
            tab.tap()
            if header.waitForExistence(timeout: 5) {
                return
            }
        }

        XCTFail("The profile screen did not appear after three taps on the Profile tab")
    }
}
