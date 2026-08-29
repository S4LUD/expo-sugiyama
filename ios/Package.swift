// Swift Package Manager test harness for the native layout engine.
// Layout/ and Tests/ are Foundation-only (no ExpoModulesCore), so this
// package lets Phase 3 XCTests run via `swift test` (macOS host) or
// xcodebuild on an iOS Simulator destination, without CocoaPods.
// swift-tools-version:6.0
import PackageDescription

let package = Package(
  name: "ExpoSugiyama",
  platforms: [
    .macOS(.v13),
    .iOS(.v16),
  ],
  targets: [
    .target(
      name: "ExpoSugiyama",
      path: ".",
      exclude: [
        "Bridge.swift",
        "SugiyamaModule.swift",
        "ExpoSugiyama.podspec",
        "Tests",
        "Package.swift",
      ],
      sources: [
        "Layout",
        "Diagnostics.swift",
      ],
      swiftSettings: [
        .swiftLanguageMode(.v5)
      ]
    ),
    .testTarget(
      name: "ExpoSugiyamaTests",
      dependencies: ["ExpoSugiyama"],
      path: "Tests",
      swiftSettings: [
        .swiftLanguageMode(.v5)
      ]
    ),
  ]
)
