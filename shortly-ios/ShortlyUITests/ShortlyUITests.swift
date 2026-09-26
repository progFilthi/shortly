import XCTest

final class ShortlyUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false

        app = XCUIApplication()
        // Forces the in-memory session store, so a signed-in run cannot leak into this one.
        app.launchArguments = ["--uitesting-reset-session"]
        app.launch()
    }

    /// The sign-in screen is the default, because most launches are returning users and
    /// making them find a "Create account" tab first is a step that only exists to be
    /// skipped.
    func testDefaultsToSignIn() {
        XCTAssertTrue(app.staticTexts["Welcome back"].waitForExistence(timeout: 5))

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Enter your username or email."].waitForExistence(timeout: 2))
    }

    func testCreateAccountValidatesEachField() {
        XCTAssertTrue(app.staticTexts["Welcome back"].waitForExistence(timeout: 5))

        switchToCreateAccount()

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Enter a username."].waitForExistence(timeout: 2))

        app.textFields["auth.username"].tap()
        app.textFields["auth.username"].typeText("alice")

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Enter your email address."].waitForExistence(timeout: 2))

        app.textFields["auth.email"].tap()
        app.textFields["auth.email"].typeText("not-an-email")

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Enter a valid email address."].waitForExistence(timeout: 2))
    }

    /// The client mirrors the server's 10-character minimum. Checking a lower bound here
    /// would let a user fill in a form the server is guaranteed to reject.
    func testCreateAccountRejectsAShortPasswordBeforeSendingIt() {
        XCTAssertTrue(app.staticTexts["Welcome back"].waitForExistence(timeout: 5))
        switchToCreateAccount()

        app.textFields["auth.username"].tap()
        app.textFields["auth.username"].typeText("alice")
        app.textFields["auth.email"].tap()
        app.textFields["auth.email"].typeText("alice@example.com")

        app.secureTextFields["auth.password"].tap()
        app.secureTextFields["auth.password"].typeText("short")

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Use at least 10 characters."].waitForExistence(timeout: 2))
    }

    private func switchToCreateAccount() {
        let segment = app.segmentedControls.buttons["Create account"]
        if segment.waitForExistence(timeout: 2) {
            segment.tap()
        } else {
            // A segmented Picker is not always exposed as a segmented control; fall back
            // to a plain button lookup rather than failing the whole test.
            let button = app.buttons["Create account"]
            XCTAssertTrue(button.waitForExistence(timeout: 2), "Could not find the Create account tab")
            button.tap()
        }

        XCTAssertTrue(app.staticTexts["Create your account"].waitForExistence(timeout: 2))
    }
}
