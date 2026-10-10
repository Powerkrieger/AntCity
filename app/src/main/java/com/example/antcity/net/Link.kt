package com.example.antcity.net

import com.example.antcity.core.duel.NetMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream

/** A connection to the other device, over any stream transport. Messages are lines of JSON. */
class Link(input: InputStream, output: OutputStream, private val closeable: Closeable) {
    private val reader = input.bufferedReader()
    private val writer = output.bufferedWriter()

    suspend fun send(m: NetMessage) = withContext(Dispatchers.IO) {
        synchronized(writer) {
            writer.write(m.encode())
            writer.newLine()
            writer.flush()
        }
    }

    /** Incoming messages; completes when the other side closes, throws if the connection breaks. */
    fun messages(): Flow<NetMessage> = flow {
        while (true) emit(NetMessage.decode(reader.readLine() ?: break))
    }.flowOn(Dispatchers.IO)

    fun close() {
        runCatching { closeable.close() }
    }
}

/**
 * Runs a blocking call (accept, connect) on the IO dispatcher. If the coroutine is cancelled,
 * [abort] is called to unblock it, typically by closing the socket.
 */
suspend fun <T> cancellableBlocking(abort: () -> Unit, block: () -> T): T = coroutineScope {
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            abort()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        watcher.cancel()
    }
}
