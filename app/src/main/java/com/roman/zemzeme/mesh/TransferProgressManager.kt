package com.roman.zemzeme.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

data class TransferProgressEvent(
    val transferId: String,
    val sent: Int,
    val total: Int,
    val completed: Boolean,
    val failureReason: String? = null,
    val cancelled: Boolean = false
)

object TransferProgressManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingEvents = Channel<TransferProgressEvent>(Channel.UNLIMITED)
    private val _events = MutableSharedFlow<TransferProgressEvent>(replay = 0, extraBufferCapacity = 32)
    val events: SharedFlow<TransferProgressEvent> = _events

    init {
        scope.launch {
            for (event in pendingEvents) {
                _events.emit(event)
            }
        }
    }

    fun start(id: String, total: Int) { emit(id, 0, total, false) }
    fun progress(id: String, sent: Int, total: Int) { emit(id, sent, total, sent >= total) }
    fun complete(id: String, total: Int) { emit(id, total, total, true) }
    fun fail(id: String, sent: Int, total: Int, reason: String) {
        pendingEvents.trySend(TransferProgressEvent(id, sent, total, false, failureReason = reason))
    }
    fun cancel(id: String, sent: Int, total: Int) {
        pendingEvents.trySend(TransferProgressEvent(id, sent, total, false, cancelled = true))
    }

    private fun emit(id: String, sent: Int, total: Int, done: Boolean) {
        pendingEvents.trySend(TransferProgressEvent(id, sent, total, done))
    }
}
