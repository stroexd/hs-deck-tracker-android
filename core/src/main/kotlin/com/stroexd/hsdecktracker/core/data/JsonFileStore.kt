package com.stroexd.hsdecktracker.core.data

import com.stroexd.hsdecktracker.core.util.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class JsonFileStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val default: T,
    private val json: Json = AppJson,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(load())
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    private fun load(): T {
        if (!file.exists()) return default
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            file.renameTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            default
        }
    }

    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val current = _state.value
        val updated = transform(current)
        if (updated != current) {
            withContext(Dispatchers.IO) { write(updated) }
            _state.value = updated
        }
        updated
    }

    suspend fun set(value: T): T = update { value }

    private fun write(value: T) {
        val dir = file.absoluteFile.parentFile
        dir.mkdirs()
        val tmp = File(dir, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
