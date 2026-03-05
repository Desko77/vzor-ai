package com.vzor.ai.domain.model

data class MemoryFact(
    val id: Long = 0,
    val fact: String,
    val category: MemoryCategory,
    val importance: Int,
    val createdAt: Long = System.currentTimeMillis()
)

enum class MemoryCategory {
    PREFERENCE,
    PERSONAL,
    LOCATION,
    CONTACT,
    HABIT,
    OTHER;

    companion object {
        fun fromString(value: String): MemoryCategory =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OTHER
    }
}
