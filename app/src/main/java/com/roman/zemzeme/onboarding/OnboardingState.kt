package com.roman.zemzeme.onboarding

enum class OnboardingState {
    CHECKING,
    BLUETOOTH_CHECK,
    LOCATION_CHECK,
    PERMISSION_EXPLANATION,
    PERMISSION_REQUESTING,
    INITIALIZING,
    APP_LOCK_SETUP,
    COMPLETE,
    ERROR
}
