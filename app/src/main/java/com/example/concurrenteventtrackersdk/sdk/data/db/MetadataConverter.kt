package com.example.concurrenteventtrackersdk.sdk.data.db

import androidx.room.TypeConverter
import org.json.JSONObject

class MetadataConverter {

    @TypeConverter
    fun fromMap(map: Map<String, String>): String =
        JSONObject(map).toString()

    @TypeConverter
    fun toMap(json: String): Map<String, String> {
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }
}