import SwiftUI

struct SplashView: View {
    @State private var iconScale: CGFloat = 0.6
    @State private var iconOpacity: Double = 0
    @State private var textOpacity: Double = 0
    @State private var subtitleOpacity: Double = 0
    @State private var glowRadius: CGFloat = 0

    var body: some View {
        ZStack {
            // Background gradient
            LinearGradient(
                colors: [
                    Color(red: 0.08, green: 0.55, blue: 0.28),
                    Color(red: 0.04, green: 0.32, blue: 0.16),
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            // Subtle radial glow behind icon
            RadialGradient(
                colors: [
                    Color.white.opacity(0.12),
                    Color.clear,
                ],
                center: .center,
                startRadius: 0,
                endRadius: glowRadius
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                Spacer()

                // Icon
                ZStack {
                    // Shadow ring
                    Circle()
                        .fill(Color.black.opacity(0.15))
                        .frame(width: 136, height: 136)
                        .offset(y: 4)
                        .blur(radius: 12)

                    // App icon image
                    Image("GeckoIcon")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 128, height: 128)
                        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
                        .shadow(color: .black.opacity(0.25), radius: 12, y: 4)
                }
                .scaleEffect(iconScale)
                .opacity(iconOpacity)

                Spacer().frame(height: 40)

                // App name
                Text("BetterGecko")
                    .font(.system(size: 36, weight: .bold, design: .rounded))
                    .foregroundStyle(.white)
                    .opacity(textOpacity)

                Spacer().frame(height: 8)

                Text("Solar Geyser Controller")
                    .font(.system(size: 15, weight: .medium, design: .rounded))
                    .foregroundStyle(.white.opacity(0.7))
                    .opacity(subtitleOpacity)

                Spacer()
                Spacer()
            }
        }
        .onAppear { animate() }
    }

    private func animate() {
        withAnimation(.spring(response: 0.6, dampingFraction: 0.7).delay(0.1)) {
            iconScale = 1.0
            iconOpacity = 1.0
        }
        withAnimation(.easeOut(duration: 0.5).delay(0.35)) {
            glowRadius = 220
            textOpacity = 1.0
        }
        withAnimation(.easeOut(duration: 0.4).delay(0.55)) {
            subtitleOpacity = 1.0
        }
    }
}

#Preview {
    SplashView()
}
