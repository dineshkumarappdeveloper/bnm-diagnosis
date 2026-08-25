import SwiftUI

@main
struct iOSApp: App {

    init() {
        GoogleSignInBridge.setup()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    _ = GoogleSignInBridge.handle(url: url)
                }
        }
    }
}
