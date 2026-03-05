package com.vzor.ai.actions

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract

class CallAction(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun call(contactName: String): ActionResult {
        val phoneNumber = lookupContact(contactName)

        return if (phoneNumber != null) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL).apply {
                    data = Uri.parse("tel:$phoneNumber")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(callIntent)
                ActionResult(
                    success = true,
                    message = "Звоню $contactName ($phoneNumber)",
                    requiresConfirmation = true
                )
            } catch (e: SecurityException) {
                ActionResult(
                    success = false,
                    message = "Нет разрешения на совершение звонков. Предоставьте разрешение CALL_PHONE."
                )
            } catch (e: Exception) {
                ActionResult(
                    success = false,
                    message = "Не удалось позвонить: ${e.message}"
                )
            }
        } else {
            // Contact not found — open dialer with name search
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                ActionResult(
                    success = false,
                    message = "Контакт \"$contactName\" не найден. Открыт номеронабиратель."
                )
            } catch (e: Exception) {
                ActionResult(
                    success = false,
                    message = "Контакт \"$contactName\" не найден"
                )
            }
        }
    }

    fun lookupContact(name: String): String? {
        var phoneNumber: String? = null
        var cursor: Cursor? = null

        try {
            val contentResolver = context.contentResolver

            // First, find the contact ID by display name
            val contactUri = ContactsContract.Contacts.CONTENT_URI
            val contactProjection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
                ContactsContract.Contacts.HAS_PHONE_NUMBER
            )
            val contactSelection = "${ContactsContract.Contacts.DISPLAY_NAME} LIKE ?"
            val contactSelectionArgs = arrayOf("%$name%")

            cursor = contentResolver.query(
                contactUri,
                contactProjection,
                contactSelection,
                contactSelectionArgs,
                "${ContactsContract.Contacts.DISPLAY_NAME} ASC"
            )

            if (cursor != null && cursor.moveToFirst()) {
                val contactIdIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val hasPhoneIndex = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)

                if (contactIdIndex >= 0 && hasPhoneIndex >= 0) {
                    val contactId = cursor.getString(contactIdIndex)
                    val hasPhone = cursor.getInt(hasPhoneIndex)

                    if (hasPhone > 0) {
                        // Query phone numbers for this contact
                        var phoneCursor: Cursor? = null
                        try {
                            phoneCursor = contentResolver.query(
                                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                                "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                                arrayOf(contactId),
                                null
                            )

                            if (phoneCursor != null && phoneCursor.moveToFirst()) {
                                val numberIndex = phoneCursor.getColumnIndex(
                                    ContactsContract.CommonDataKinds.Phone.NUMBER
                                )
                                if (numberIndex >= 0) {
                                    phoneNumber = phoneCursor.getString(numberIndex)
                                }
                            }
                        } finally {
                            phoneCursor?.close()
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            // READ_CONTACTS permission not granted
            return null
        } catch (e: Exception) {
            return null
        } finally {
            cursor?.close()
        }

        return phoneNumber
    }
}
