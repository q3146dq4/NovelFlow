package com.NovelRegEx.app.filter

internal class SynchronizedLruCache<K, V>(
  private val maxSize: Int,
) {
  init {
    require(maxSize > 0)
  }

  private val map =
    object : LinkedHashMap<K, V>(maxSize + 1, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean =
        size > maxSize
    }

  @Synchronized operator fun get(key: K): V? = map[key]

  @Synchronized
  fun put(key: K, value: V) {
    map[key] = value
  }

  @Synchronized
  fun remove(key: K) {
    map.remove(key)
  }

  @Synchronized
  fun clear() {
    map.clear()
  }

  @Synchronized
  fun getOrPut(key: K, producer: () -> V): V {
    map[key]?.let { return it }
    return producer().also { map[key] = it }
  }
}
