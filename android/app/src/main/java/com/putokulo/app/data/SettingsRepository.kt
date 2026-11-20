package com.putokulo.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class EmergencyContact(val name: String, val phone: String)

/** Rider name, emergency contacts and helmet address, saved on the phone. */
object SettingsRepository {
    private const val KEY_RIDER_NAME = "rider_name"
    private const val KEY_CONTACTS = "contacts"
    private const val KEY_HELMET_ADDRESS = "helmet_address"

    private lateinit var prefs: SharedPreferences

    private val _riderName = MutableStateFlow("")
    val riderName: StateFlow<String> = _riderName

    private val _contacts = MutableStateFlow<List<EmergencyContact>>(emptyList())
    val contacts: StateFlow<List<EmergencyContact>> = _contacts

    /** "host:port" typed by the user. Empty means find the helmet automatically. */
    private val _helmetAddress = MutableStateFlow("")
    val helmetAddress: StateFlow<String> = _helmetAddress

    fun init(context: Context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        _riderName.value = prefs.getString(KEY_RIDER_NAME, "") ?: ""
        _contacts.value = parseContacts(prefs.getString(KEY_CONTACTS, null))
        _helmetAddress.value = prefs.getString(KEY_HELMET_ADDRESS, "") ?: ""
    }

    fun setRiderName(name: String) {
        _riderName.value = name
        prefs.edit().putString(KEY_RIDER_NAME, name).apply()
    }

    fun setHelmetAddress(address: String) {
        _helmetAddress.value = address
        prefs.edit().putString(KEY_HELMET_ADDRESS, address).apply()
    }

    fun addContact(contact: EmergencyContact) = saveContacts(_contacts.value + contact)

    fun removeContact(contact: EmergencyContact) = saveContacts(_contacts.value - contact)

    private fun saveContacts(contacts: List<EmergencyContact>) {
        _contacts.value = contacts
        val json = JSONArray(contacts.map { JSONObject().put("name", it.name).put("phone", it.phone) })
        prefs.edit().putString(KEY_CONTACTS, json.toString()).apply()
    }

    private fun parseContacts(json: String?): List<EmergencyContact> {
        val array = JSONArray(json ?: "[]")
        return (0 until array.length()).map {
            val item = array.getJSONObject(it)
            EmergencyContact(item.getString("name"), item.getString("phone"))
        }
    }
}
