package hd.kinoshka.app.data.repo

import java.util.concurrent.ConcurrentHashMap

class BoundedCache<K, V>(
    private val maxSize: Int = 100,
    private val ttlMs: Long = 3 * 24 * 60 * 60 * 1000L
) {
    private val cache = ConcurrentHashMap<K, Entry<V>>()

    fun get(key: K): V? {
        val entry = cache[key] ?: return null
        val age = System.currentTimeMillis() - entry.savedAtMs
        return if (age in 0..ttlMs) entry.value else null
    }

    fun put(key: K, value: V) {
        evictIfNeeded()
        cache[key] = Entry(value, System.currentTimeMillis())
    }

    fun clear() {
        cache.clear()
    }

    private fun evictIfNeeded() {
        if (cache.size >= maxSize) {
            val oldest = cache.entries
                .minByOrNull { it.value.savedAtMs }
                ?.key
            if (oldest != null) cache.remove(oldest)
        }
    }

    private data class Entry<V>(
        val value: V,
        val savedAtMs: Long
    )
}
