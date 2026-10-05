package com.munin.app.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** Reads names and numbers into memory only while the user has allowed contacts. Nothing is copied into Munin's database or leaves the phone. */
class ContactIndex(private val context: Context) {
    @Volatile private var contacts: List<ContactEntry> = emptyList()
    @Volatile private var loadedAt = 0L

    fun granted() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Reloads when stale; call off the main thread. Does nothing, and forgets everything, if the permission is gone. */
    fun refresh(maxAgeMs: Long = 2 * 60_000L) {
        if (!granted()) { contacts = emptyList(); return }
        if (contacts.isNotEmpty() && System.currentTimeMillis() - loadedAt < maxAgeMs) return
        val out = ArrayList<ContactEntry>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    val number = c.getString(2) ?: continue
                    out.add(ContactEntry(c.getLong(0), name, number))
                }
            }
        }
        contacts = out
        loadedAt = System.currentTimeMillis()
    }

    fun search(query: String) = ContactMatcher.search(query, contacts)
}
