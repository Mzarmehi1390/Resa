package com.roman.zemzeme.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object AppLockManager {

    // Start locked so the very first frame never exposes content
    private val _isLocked = MutableStateFlow(true)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    /** Call after AppLockPreferenceManager.init() — unlocks immediately if lock is disabled. */
    fun initLockState() {
        if (!AppLockPreferenceManager.isEnabled()) {
            _isLocked.value = false
        }
    }

    fun lock() {
        if (AppLockPreferenceManager.isEnabled()) {
            _isLocked.value = true
        }
    }

    fun unlock() {
        _isLocked.value = false
    }

    fun isLockEnabled(): Boolean = AppLockPreferenceManager.isEnabled()
}
