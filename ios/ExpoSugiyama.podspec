Pod::Spec.new do |s|
  s.name           = 'ExpoSugiyama'
  s.version        = '0.1.1'
  s.summary        = 'High-performance native hierarchical graph layout computation for React Native.'
  s.description    = 'expo-sugiyama is a reusable Expo Modules library that computes Sugiyama-framework graph layouts (layering, ordering, coordinate assignment) entirely in native code, off the JS thread.'
  s.author         = 'S4LUD'
  s.homepage       = 'https://github.com/S4LUD/expo-sugiyama'
  s.license        = 'MIT'
  s.platforms      = {
    :ios => '16.0',
    :tvos => '16.0'
  }
  s.source         = { git: 'https://github.com/S4LUD/expo-sugiyama.git', tag: 'v0.1.1' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
  }

  # Explicit root sources so the SwiftPM Package.swift (test harness) is not
  # compiled into the pod.
  s.source_files = "Bridge.swift", "SugiyamaModule.swift", "Diagnostics.swift", "Layout/**/*.swift"

  # Tests (spec §16.3): run the golden parity suite via `pod lib lint --test-specs=Tests`
  s.test_spec 'Tests' do |test_spec|
    test_spec.source_files = 'Tests/*.swift'
  end
end
