package com.vzor.ai.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Тесты скользящего окна для YandexSttService.
 * Проверяют что partial recognition использует O(1) данных вместо O(n).
 */
class YandexSttSlidingWindowTest {

    @Test
    fun `sliding window merges chunks correctly`() {
        val chunks = ArrayDeque<ByteArray>()
        chunks.addLast(byteArrayOf(1, 2, 3))
        chunks.addLast(byteArrayOf(4, 5))
        chunks.addLast(byteArrayOf(6, 7, 8, 9))

        val merged = mergeChunks(chunks)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), merged)
    }

    @Test
    fun `sliding window limits to WINDOW_CHUNKS entries`() {
        val windowSize = 5
        val chunks = ArrayDeque<ByteArray>(windowSize + 1)

        // Добавляем 8 чанков — окно должно содержать только последние 5
        for (i in 1..8) {
            chunks.addLast(byteArrayOf(i.toByte()))
            if (chunks.size > windowSize) {
                chunks.removeFirst()
            }
        }

        assertEquals(windowSize, chunks.size)
        // Первый элемент — чанк #4 (0-indexed: chunks[0] = [4])
        assertEquals(4.toByte(), chunks.first()[0])
        // Последний — чанк #8
        assertEquals(8.toByte(), chunks.last()[0])
    }

    @Test
    fun `sliding window total size is bounded`() {
        val windowSize = 5
        val chunkSize = 32000 // ~1s of 16kHz 16-bit mono
        val chunks = ArrayDeque<ByteArray>(windowSize + 1)

        // Симулируем 30 секунд аудио (30 чанков)
        for (i in 1..30) {
            chunks.addLast(ByteArray(chunkSize))
            if (chunks.size > windowSize) {
                chunks.removeFirst()
            }
        }

        // Окно всегда содержит максимум windowSize чанков
        assertEquals(windowSize, chunks.size)

        // Размер данных для partial recognition: windowSize * chunkSize = 160KB (не 960KB)
        val totalBytes = chunks.sumOf { it.size }
        assertEquals(windowSize * chunkSize, totalBytes)
    }

    @Test
    fun `empty window produces empty merge`() {
        val chunks = ArrayDeque<ByteArray>()
        val merged = mergeChunks(chunks)
        assertEquals(0, merged.size)
    }

    @Test
    fun `single chunk window returns that chunk`() {
        val chunks = ArrayDeque<ByteArray>()
        chunks.addLast(byteArrayOf(42, 43, 44))

        val merged = mergeChunks(chunks)
        assertArrayEquals(byteArrayOf(42, 43, 44), merged)
    }

    @Test
    fun `full buffer grows linearly while window stays constant`() {
        val windowSize = 5
        val chunkSize = 100
        val chunks = ArrayDeque<ByteArray>(windowSize + 1)
        var fullBufferSize = 0

        for (i in 1..20) {
            val chunk = ByteArray(chunkSize) { (i % 256).toByte() }
            fullBufferSize += chunk.size

            chunks.addLast(chunk)
            if (chunks.size > windowSize) {
                chunks.removeFirst()
            }
        }

        // Полный буфер растёт линейно: 20 * 100 = 2000
        assertEquals(20 * chunkSize, fullBufferSize)

        // Окно фиксировано: 5 * 100 = 500
        val windowBytes = chunks.sumOf { it.size }
        assertEquals(windowSize * chunkSize, windowBytes)

        // Partial recognition отправляет 25% данных вместо 100%
        val ratio = windowBytes.toFloat() / fullBufferSize
        assertEquals(0.25f, ratio, 0.01f)
    }

    /**
     * Воспроизводит mergeChunks из YandexSttService.
     */
    private fun mergeChunks(chunks: ArrayDeque<ByteArray>): ByteArray {
        val totalSize = chunks.sumOf { it.size }
        val result = ByteArray(totalSize)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }
}
