package com.vzor.ai.vision

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceIdentificationHelperTest {

    @Test
    fun `isPlaceQuery detects building questions`() {
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за здание"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("какое здание"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за дом"))
    }

    @Test
    fun `isPlaceQuery detects location questions`() {
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("где я нахожусь"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за место"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что это за место"))
    }

    @Test
    fun `isPlaceQuery detects landmark queries`() {
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("достопримечательность"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("памятник"))
    }

    @Test
    fun `isPlaceQuery detects venue queries`() {
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за магазин"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за ресторан"))
        assertTrue(PlaceIdentificationHelper.isPlaceQuery("что за кафе"))
    }

    @Test
    fun `isPlaceQuery rejects non-place queries`() {
        assertFalse(PlaceIdentificationHelper.isPlaceQuery("сколько калорий"))
        assertFalse(PlaceIdentificationHelper.isPlaceQuery("включи музыку"))
    }

    @Test
    fun `buildPlaceIdentificationPrompt includes user query`() {
        val prompt = PlaceIdentificationHelper.buildPlaceIdentificationPrompt("что за церковь")
        assertTrue(prompt.contains("что за церковь"))
        assertTrue(prompt.contains("Название"))
    }

    @Test
    fun `buildWebSearchQuery includes place name`() {
        val query = PlaceIdentificationHelper.buildWebSearchQuery("Большой театр")
        assertTrue(query.contains("Большой театр"))
        assertTrue(query.contains("часы работы"))
    }

    @Test
    fun `buildNearbyQuery with location`() {
        val query = PlaceIdentificationHelper.buildNearbyQuery("кафе", "Арбат")
        assertTrue(query.contains("кафе"))
        assertTrue(query.contains("Арбат"))
    }

    @Test
    fun `buildNearbyQuery without location`() {
        val query = PlaceIdentificationHelper.buildNearbyQuery("аптека")
        assertTrue(query.contains("аптека"))
        assertTrue(query.contains("рядом со мной"))
    }
}
