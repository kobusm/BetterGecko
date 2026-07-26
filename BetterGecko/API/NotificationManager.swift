import Foundation
import UserNotifications

/// Handles local-notification permission and reacts to a schedule notification being
/// tapped by applying the mode it carries (there is no background execution — the tap
/// is what triggers the mode change).
final class NotificationManager: NSObject, UNUserNotificationCenterDelegate {
    static let shared = NotificationManager()

    static let modeCategory = "MODE_SCHEDULE"

    /// Registers the delegate and an "Apply now" action. Call once at launch.
    func configure() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let apply = UNNotificationAction(identifier: "APPLY_MODE", title: "Apply now",
                                         options: [.foreground])
        let category = UNNotificationCategory(identifier: Self.modeCategory,
                                              actions: [apply], intentIdentifiers: [])
        center.setNotificationCategories([category])
    }

    @discardableResult
    func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current()
            .requestAuthorization(options: [.alert, .sound])) ?? false
    }

    // Show the banner even when BetterGecko is in the foreground.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification) async
        -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }

    // Tapping the notification (or its "Apply now" action) applies the scheduled mode.
    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let gsn = info["gsn"] as? String, let mode = info["mode"] as? String else { return }
        try? await DeviceAPI.shared.setMode(gsn: gsn, geckoMode: mode)
        // Keep the local settings cache in step with the applied mode.
        PersistedSettings.updateMode(mode, gsn: gsn)
    }
}
