import XCTest

final class ShortlyUITests: XCTestCase {
    func testRegistrationFormValidation() {
        let app = XCUIApplication()
        app.launchArguments = ["--uitesting-reset-session"]
        app.launch()

        XCTAssertTrue(app.staticTexts["Create your account"].waitForExistence(timeout: 5))

        app.buttons["auth.submit"].tap()
        XCTAssertTrue(app.staticTexts["Enter a username."].waitForExistence(timeout: 2))
    }
}
