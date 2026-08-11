package com.secureguard.mdm.firewall.data

import com.secureguard.mdm.firewall.model.ConnectionEvent
import com.secureguard.mdm.utils.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Bounded, batched sink so packet callbacks never wait for Room I/O. */
@Singleton
class ConnectionHistoryRecorder @Inject constructor(
    private val repository: ConnectionHistoryRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<ConnectionEvent>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    init {
        scope.launch { consumeEvents() }
        scope.launch { runRetentionLoop() }
    }

    fun record(event: ConnectionEvent) {
        events.trySend(event)
    }

    private suspend fun consumeEvents() {
        while (true) {
            val batch = ArrayList<ConnectionEvent>(MAX_BATCH_SIZE)
            batch += events.receive()
            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < MAX_BATCH_SIZE) batch += events.receive()
            }
            persistWithRetry(batch)
        }
    }

    private suspend fun persistWithRetry(batch: List<ConnectionEvent>) {
        repeat(MAX_WRITE_ATTEMPTS) { attempt ->
            val result = runCatching { repository.recordBatch(batch) }
            if (result.isSuccess) return
            FileLogger.log(
                TAG,
                "History batch write attempt ${attempt + 1}/$MAX_WRITE_ATTEMPTS failed: ${result.exceptionOrNull()?.message}",
            )
            if (attempt + 1 < MAX_WRITE_ATTEMPTS) delay(RETRY_BASE_DELAY_MS * (attempt + 1))
        }
        FileLogger.log(TAG, "Dropped one history batch after repeated local database failures.")
    }

    private suspend fun runRetentionLoop() {
        while (true) {
            val result = runCatching {
                repository.purge(System.currentTimeMillis() - RETENTION_MS)
            }
            if (result.isFailure) {
                FileLogger.log(TAG, "History retention failed and will be retried: ${result.exceptionOrNull()?.message}")
            }
            delay(if (result.isSuccess) PURGE_INTERVAL_MS else PURGE_RETRY_DELAY_MS)
        }
    }

    private companion object {
        const val TAG = "ConnectionHistory"
        const val MAX_BATCH_SIZE = 50
        const val MAX_WRITE_ATTEMPTS = 3
        const val BATCH_WINDOW_MS = 250L
        const val RETRY_BASE_DELAY_MS = 250L
        const val PURGE_RETRY_DELAY_MS = 60 * 1_000L
        const val PURGE_INTERVAL_MS = 24 * 60 * 60 * 1_000L
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1_000L
    }
}
