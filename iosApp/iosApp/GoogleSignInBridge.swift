import UIKit

/// Google Sign-In is **not** used in BNM Diagnosis — authentication is email/password
/// + counter pairing (see `FirebaseAuthManager`, which is an email-only stub with no
/// Firebase/Google dependency). This file is a no-op kept so `iOSApp`'s existing calls
/// compile. The `GoogleSignIn`/`GoogleSignInSwift` pods in the Podfile are now unused
/// and can be removed (then `pod install`).
enum GoogleSignInBridge {
    static func setup() {}
    static func handle(url: URL) -> Bool { false }
}
