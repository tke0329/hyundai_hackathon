package com.example.hyundai_hackathon.data

import android.content.Context
import android.util.AtomicFile
import com.example.hyundai_hackathon.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Local, atomic per-drive files. No account, network upload, or location collection. */
class DriveStore(context: Context) {
    private val directory = File(context.filesDir, "drives").apply { mkdirs() }
    private val active = File(directory, "active.json")
    var readWarnings: Int = 0
        private set

    @Synchronized fun load(): List<Drive> {
        readWarnings = 0
        val saved = directory.listFiles().orEmpty().filter { it.extension == "json" && it.name != "active.json" }
            .mapNotNull { read(it) }.sortedByDescending { it.startedAt }
        val interrupted = if (active.exists()) read(active)?.let { d ->
            d.copy(endedAt = d.startedAt + d.durationMs, interrupted = true)
        } else null
        if (interrupted != null) {
            save(interrupted)
            active.delete()
        }
        val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val retained = (listOfNotNull(interrupted) + saved).distinctBy { it.id }
            .filter { it.startedAt >= cutoff }.sortedByDescending { it.startedAt }.take(100)
        val keep = retained.map { "${it.id}.json" }.toSet()
        directory.listFiles().orEmpty().filter { it.extension == "json" && it.name != "active.json" && it.name !in keep }.forEach { it.delete() }
        return retained
    }

    @Synchronized fun checkpoint(drive: Drive) = write(active, drive)
    @Synchronized fun complete(drive: Drive) { save(drive); active.delete() }
    @Synchronized fun save(drive: Drive) {
        require(drive.id.matches(Regex("[a-zA-Z0-9-]+")))
        write(File(directory, "${drive.id}.json"), drive)
    }
    @Synchronized fun delete(id: String) { require(id.matches(Regex("[a-zA-Z0-9-]+"))); File(directory, "$id.json").delete() }
    @Synchronized fun clear() { directory.listFiles().orEmpty().forEach { it.delete() } }

    private fun read(file: File): Drive? = try {
        DriveJson.decode(JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }))
    } catch (_: Exception) {
        readWarnings++
        file.renameTo(File(directory, file.name + ".unreadable"))
        null
    }

    private fun write(file: File, drive: Drive) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(DriveJson.encode(drive).toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

object DriveJson {
    fun encode(d: Drive): JSONObject = JSONObject().apply {
        put("schemaVersion", 1); put("id", d.id); put("startedAt", d.startedAt)
        put("endedAt", d.endedAt ?: JSONObject.NULL); put("mode", d.mode.name); put("source", d.source.name)
        put("durationMs", d.durationMs); put("distanceMeters", d.distanceMeters)
        put("sampleCount", d.sampleCount); put("scoredMs", d.scoredMs); put("ruleVersion", d.ruleVersion)
        put("interrupted", d.interrupted); put("driverType", d.driverType)
        put("comfortFeedback", d.comfortFeedback ?: JSONObject.NULL)
        put("feedbackAuthor", d.feedbackAuthor ?: JSONObject.NULL)
        put("events", JSONArray().apply { d.events.forEach { e -> put(JSONObject().apply {
            put("id", e.id); put("atMs", e.atMs); put("kind", e.kind.name)
            put("vehiclePoints", e.vehiclePoints); put("comfortPoints", e.comfortPoints)
            put("review", e.review.name); put("before", e.before); put("after", e.after)
            put("observation", e.observation); put("context", e.context)
            put("estimatedAcceleration", e.peakAcceleration ?: JSONObject.NULL)
        }) } })
        put("signals", JSONArray().apply { d.signalTrace.forEach { s -> put(JSONObject().apply {
            put("atMs", s.atMs); put("speedMps", s.speedMps)
            put("estimatedAcceleration", s.estimatedAcceleration ?: JSONObject.NULL)
            put("brakePressed", s.brakePressed ?: JSONObject.NULL)
            put("frontWheelAngle", s.frontWheelAngle ?: JSONObject.NULL)
        }) } })
    }

    fun decode(j: JSONObject): Drive {
        val events = j.optJSONArray("events") ?: JSONArray()
        val signals = j.optJSONArray("signals") ?: JSONArray()
        return Drive(id = j.getString("id"), startedAt = j.getLong("startedAt"),
            endedAt = if (j.isNull("endedAt")) null else j.getLong("endedAt"),
            mode = PassengerMode.valueOf(j.getString("mode")), source = DriveSource.valueOf(j.getString("source")),
            durationMs = j.getLong("durationMs"), distanceMeters = j.getDouble("distanceMeters"),
            sampleCount = j.optInt("sampleCount"), scoredMs = j.optLong("scoredMs"),
            interrupted = j.optBoolean("interrupted"), ruleVersion = j.optString("ruleVersion", "0.1-pilot"),
            driverType = j.optString("driverType", "미확인"),
            comfortFeedback = if (j.isNull("comfortFeedback")) null else j.getInt("comfortFeedback"),
            feedbackAuthor = if (j.isNull("feedbackAuthor")) null else j.getString("feedbackAuthor"),
            events = ScorePolicy.recompute((0 until events.length()).map { i ->
                val e = events.getJSONObject(i)
                DriveEvent(id = e.getString("id"), atMs = e.getLong("atMs"), kind = EventKind.valueOf(e.getString("kind")),
                    vehiclePoints = e.getInt("vehiclePoints"), comfortPoints = e.getInt("comfortPoints"),
                    review = EventReview.valueOf(e.getString("review")), observation = e.getString("observation"),
                    context = e.optString("context"),
                    peakAcceleration = if (e.isNull("estimatedAcceleration")) null else e.getDouble("estimatedAcceleration"))
            }),
            signalTrace = (0 until signals.length()).map { i ->
                val s = signals.getJSONObject(i)
                SignalPoint(s.getLong("atMs"), s.getDouble("speedMps"),
                    if (s.isNull("estimatedAcceleration")) null else s.getDouble("estimatedAcceleration"),
                    if (s.isNull("brakePressed")) null else s.getBoolean("brakePressed"),
                    if (s.isNull("frontWheelAngle")) null else s.getDouble("frontWheelAngle"))
            })
    }

    fun export(drives: List<Drive>): String = JSONObject().apply {
        put("schemaVersion", 1); put("exportedAt", System.currentTimeMillis())
        put("app", "Onyou Drive")
        put("notice", "시범 평가 기준에 따른 기록입니다. estimatedAcceleration은 속도 차분의 추정값이며 실제 부품 마모·의학적 편안함 측정값이 아닙니다. 체험 주행과 차량 주행을 구분해 사용하세요.")
        put("drives", JSONArray().apply { drives.forEach { put(encode(it)) } })
    }.toString(2)
}
