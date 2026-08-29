import SwiftUI

@main
struct SendViaLocalNetApp: App {
    @StateObject private var service = LocalTransferService()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(service)
                .onAppear { service.start() }
        }
    }
}
