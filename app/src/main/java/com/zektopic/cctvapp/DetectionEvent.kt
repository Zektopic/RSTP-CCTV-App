package com.zektopic.cctvapp

import org.json.JSONObject

data class DetectionEvent(
    val id: String,
    val type: String,
    val score: Double?,
    val startTimeMs: Long,
    val endTimeMs: Long?,
    val snapshotFileName: String?,
    val clipFileName: String?,
    val createdAtMs: Long
) {
    fun toJsonObject(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("type", type)
        if (score != null) obj.put("score", score)
        obj.put("start_time", startTimeMs)
        if (endTimeMs != null) obj.put("end_time", endTimeMs)
        if (snapshotFileName != null) obj.put("snapshot", snapshotFileName)
        if (clipFileName != null) obj.put("clip", clipFileName)
        obj.put("has_snapshot", snapshotFileName != null)
        obj.put("has_clip", clipFileName != null)
        obj.put("created_at", createdAtMs)
        return obj
    }

    companion object {
        fun fromJsonObject(obj: JSONObject): DetectionEvent {
            return DetectionEvent(
                id = obj.getString("id"),
                type = obj.optString("type", "unknown"),
                score = if (obj.has("score")) obj.optDouble("score") else null,
                startTimeMs = obj.optLong("start_time", 0L),
                endTimeMs = if (obj.has("end_time")) obj.optLong("end_time") else null,
                snapshotFileName = if (obj.has("snapshot")) obj.optString("snapshot") else null,
                clipFileName = if (obj.has("clip")) obj.optString("clip") else null,
                createdAtMs = obj.optLong("created_at", System.currentTimeMillis())
            )
        }
    }
}
