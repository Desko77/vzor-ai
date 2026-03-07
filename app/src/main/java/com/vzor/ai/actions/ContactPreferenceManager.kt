package com.vzor.ai.actions

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import com.vzor.ai.domain.model.MemoryCategory
import com.vzor.ai.domain.repository.MemoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Управляет предпочтениями контактов при неоднозначных запросах.
 *
 * UC#9: Пользователь говорит "позвони Саше", но в контактах
 * Саша Иванов, Саша Петров и Александр Сидоров.
 * ContactPreferenceManager:
 * 1. Ищет в persistent memory предпочтение ("contact_pref:Саша" → "Саша Иванов")
 * 2. Если есть — возвращает предпочтённый контакт
 * 3. Если нет — возвращает список кандидатов для выбора
 * 4. После выбора пользователя — запоминает предпочтение
 */
@Singleton
class ContactPreferenceManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val memoryRepository: MemoryRepository
) {
    companion object {
        private const val PREF_PREFIX = "contact_pref:"
        private const val CACHE_TTL_MS = 30_000L // 30 секунд
    }

    // Кеш контактов с TTL
    private var cachedContacts: Map<String, List<ContactMatch>> = emptyMap()
    private var cacheTimestamp: Long = 0L

    /**
     * Описание найденного контакта.
     */
    data class ContactMatch(
        val displayName: String,
        val phoneNumber: String,
        val contactId: String
    )

    /**
     * Результат поиска контакта.
     */
    sealed class ContactLookupResult {
        /** Найден единственный контакт. */
        data class SingleMatch(val contact: ContactMatch) : ContactLookupResult()

        /** Найден предпочтённый контакт из памяти. */
        data class PreferredMatch(val contact: ContactMatch) : ContactLookupResult()

        /** Несколько контактов — нужен выбор пользователя. */
        data class MultipleMatches(
            val query: String,
            val candidates: List<ContactMatch>
        ) : ContactLookupResult()

        /** Контакт не найден. */
        data class NotFound(val query: String) : ContactLookupResult()
    }

    /**
     * Ищет контакт по имени с учётом сохранённых предпочтений.
     * Если найдено несколько совпадений, проверяет persistent memory.
     */
    suspend fun resolveContact(name: String): ContactLookupResult {
        val candidates = findContactsByName(name)

        return when {
            candidates.isEmpty() -> ContactLookupResult.NotFound(name)
            candidates.size == 1 -> ContactLookupResult.SingleMatch(candidates.first())
            else -> {
                // Проверяем сохранённое предпочтение
                val preferred = getPreferredContact(name, candidates)
                if (preferred != null) {
                    ContactLookupResult.PreferredMatch(preferred)
                } else {
                    ContactLookupResult.MultipleMatches(name, candidates)
                }
            }
        }
    }

    /**
     * Сохраняет предпочтение пользователя для контакта.
     * При следующем запросе с тем же именем будет возвращён этот контакт.
     */
    suspend fun savePreference(queryName: String, chosenContact: ContactMatch) {
        val key = "$PREF_PREFIX${queryName.lowercase()}"
        memoryRepository.saveFact(
            fact = "$key → ${chosenContact.displayName}",
            category = MemoryCategory.CONTACT.name,
            importance = 4
        )
    }

    /**
     * Формирует текстовый запрос для пользователя с вариантами контактов.
     */
    fun formatDisambiguationMessage(result: ContactLookupResult.MultipleMatches): String {
        val sb = StringBuilder()
        sb.appendLine("Найдено несколько контактов \"${result.query}\":")
        result.candidates.forEachIndexed { i, c ->
            sb.appendLine("${i + 1}. ${c.displayName} (${c.phoneNumber})")
        }
        sb.append("Кому звонить?")
        return sb.toString()
    }

    /**
     * Выбирает контакт по номеру из списка.
     */
    suspend fun selectByIndex(
        result: ContactLookupResult.MultipleMatches,
        index: Int
    ): ContactMatch? {
        val candidate = result.candidates.getOrNull(index) ?: return null
        savePreference(result.query, candidate)
        return candidate
    }

    // --- Private ---

    private suspend fun getPreferredContact(
        name: String,
        candidates: List<ContactMatch>
    ): ContactMatch? {
        val key = "$PREF_PREFIX${name.lowercase()}"
        val facts = memoryRepository.searchFacts(key, 1)
        if (facts.isEmpty()) return null

        val preferredName = facts.first().fact
            .substringAfter("→", "")
            .trim()

        return candidates.firstOrNull {
            it.displayName.equals(preferredName, ignoreCase = true)
        }
    }

    @Synchronized
    private fun findContactsByName(name: String): List<ContactMatch> {
        // Проверяем кеш
        val now = System.currentTimeMillis()
        if (now - cacheTimestamp < CACHE_TTL_MS) {
            val cached = cachedContacts[name.lowercase()]
            if (cached != null) return cached
        } else {
            // TTL expired — очищаем весь кеш
            cachedContacts = emptyMap()
        }

        val result = queryContactsFromProvider(name)

        // Сохраняем в кеш
        cachedContacts = cachedContacts + (name.lowercase() to result)
        if (cachedContacts.size == 1) cacheTimestamp = now
        return result
    }

    private fun queryContactsFromProvider(name: String): List<ContactMatch> {
        val matches = mutableListOf<ContactMatch>()
        var cursor: Cursor? = null

        try {
            cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.DISPLAY_NAME,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER
                ),
                "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            while (cursor != null && cursor.moveToNext()) {
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                if (idIdx < 0 || nameIdx < 0 || hasPhoneIdx < 0) continue

                val contactId = cursor.getString(idIdx)
                val displayName = cursor.getString(nameIdx) ?: continue
                val hasPhone = cursor.getInt(hasPhoneIdx)

                if (hasPhone > 0) {
                    val phone = lookupPhoneNumber(contactId)
                    if (phone != null) {
                        matches.add(ContactMatch(displayName, phone, contactId))
                    }
                }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS permission not granted
        } catch (e: Exception) {
            android.util.Log.w("ContactPreference", "Contact lookup error", e)
        } finally {
            cursor?.close()
        }

        return matches
    }

    private fun lookupPhoneNumber(contactId: String): String? {
        var phoneCursor: Cursor? = null
        try {
            phoneCursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                arrayOf(contactId),
                null
            )

            if (phoneCursor != null && phoneCursor.moveToFirst()) {
                val idx = phoneCursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                if (idx >= 0) return phoneCursor.getString(idx)
            }
        } finally {
            phoneCursor?.close()
        }
        return null
    }
}
