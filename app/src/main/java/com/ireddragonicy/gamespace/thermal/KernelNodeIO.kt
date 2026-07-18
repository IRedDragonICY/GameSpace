/*
 * Copyright (C) 2026 IRedDragonICY
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ireddragonicy.gamespace.thermal

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.util.concurrent.ConcurrentHashMap

/**
 * Zero-overhead sysfs/procfs I/O for GKI kernel nodes exposed by .ko modules
 * (migt, metis, thermal_message, kgsl, walt, …).
 *
 * Mirrors the fd-pool design of the mi_thermal_engine Rust daemon (io.rs):
 *  - file descriptors are opened once and cached — no open/close per operation
 *  - pread/pwrite at offset 0 via [Os] — no stream objects, no seek syscall
 *  - a single reusable read buffer per node handle — no per-read allocations
 *  - transparent one-shot reopen on EIO/EBADF (some nodes invalidate fds on
 *    module reload)
 *
 * SELinux: access is granted to the `system_app` domain by
 * device/xiaomi/onyx/sepolicy/{private,vendor}/gamespace_app.te (enforcing,
 * no permissive domains). DAC access comes from the chown/chmod block in
 * mi_thermal_engine.rc.
 *
 * Thread-safety: per-node handles are internally synchronized; callers may
 * invoke from any thread. Hot-path callers should still confine themselves to
 * a single background dispatcher to keep cache lines quiet.
 */
object KernelNodeIO {

    private const val TAG = "KernelNodeIO"
    private const val READ_BUF_SIZE = 4096

    /** A cached, pre-opened node handle. */
    private class NodeHandle(val path: String, val writable: Boolean) {
        var fd: FileDescriptor? = null
        val buf = ByteArray(READ_BUF_SIZE)

        fun ensureOpen(): FileDescriptor? {
            fd?.let { return it }
            return try {
                val flags = if (writable) OsConstants.O_RDWR else OsConstants.O_RDONLY
                Os.open(path, flags, 0).also { fd = it }
            } catch (e: Exception) {
                null // Missing node or EACCES — caller logs once via exists()
            }
        }

        fun close() {
            fd?.let { runCatching { Os.close(it) } }
            fd = null
        }
    }

    private val readHandles = ConcurrentHashMap<String, NodeHandle>()
    private val writeHandles = ConcurrentHashMap<String, NodeHandle>()

    /** Nodes that already logged a failure — avoids logspam on absent hardware. */
    private val warned = ConcurrentHashMap.newKeySet<String>()

    fun exists(path: String): Boolean = File(path).exists()

    /**
     * Read the node content as a trimmed string, or null on failure.
     * Zero allocation beyond the returned String.
     */
    fun read(path: String): String? {
        val handle = readHandles.getOrPut(path) { NodeHandle(path, writable = false) }
        synchronized(handle) {
            repeat(2) { attempt ->
                val fd = handle.ensureOpen() ?: return failOnce(path, "open for read")
                try {
                    val n = Os.pread(fd, handle.buf, 0, READ_BUF_SIZE, 0)
                    if (n <= 0) return null
                    return String(handle.buf, 0, n).trim()
                } catch (e: Exception) { // ErrnoException | InterruptedIOException
                    handle.close()
                    if (attempt == 1) return failOnce(path, "pread failed: $e")
                }
            }
        }
        return null
    }

    /** Read the node as a Long (handles trailing newline/whitespace). */
    fun readLong(path: String): Long? = read(path)?.toLongOrNull()

    /** Read the node as an Int. */
    fun readInt(path: String): Int? = read(path)?.toIntOrNull()

    /**
     * Write a string to the node. Returns true on success.
     * Retries exactly once with a fresh fd on EIO/EBADF/ENODEV.
     */
    fun write(path: String, value: String): Boolean {
        val handle = writeHandles.getOrPut(path) { NodeHandle(path, writable = true) }
        val bytes = value.toByteArray()
        synchronized(handle) {
            repeat(2) { attempt ->
                val fd = handle.ensureOpen() ?: run {
                    failOnce(path, "open for write")
                    return false
                }
                try {
                    Os.pwrite(fd, bytes, 0, bytes.size, 0)
                    return true
                } catch (e: Exception) { // ErrnoException | InterruptedIOException
                    handle.close()
                    if (attempt == 1) {
                        failOnce(path, "pwrite '$value' failed: $e")
                        return false
                    }
                }
            }
        }
        return false
    }

    fun write(path: String, value: Long): Boolean = write(path, value.toString())

    fun write(path: String, value: Int): Boolean = write(path, value.toString())

    fun write(path: String, enabled: Boolean): Boolean = write(path, if (enabled) "1" else "0")

    /**
     * Read a space/newline separated list of Longs (e.g. scaling_available_frequencies,
     * kgsl gpu_available_frequencies).
     */
    fun readLongList(path: String): List<Long> =
        read(path)?.split(' ', '\n', '\t')?.mapNotNull { it.toLongOrNull() } ?: emptyList()

    /** Read a whitespace-separated token list (e.g. available_governors). */
    fun readTokenList(path: String): List<String> =
        read(path)?.split(' ', '\n', '\t')?.filter { it.isNotBlank() } ?: emptyList()

    /** Drop all cached descriptors (e.g. after a .ko reload event). */
    fun invalidateAll() {
        readHandles.values.forEach { synchronized(it) { it.close() } }
        writeHandles.values.forEach { synchronized(it) { it.close() } }
        warned.clear()
    }

    private fun failOnce(path: String, what: String): Nothing? {
        if (warned.add(path)) Log.w(TAG, "$what failed: $path")
        return null
    }
}
