package kr.joonlab.core

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 맥 한 대 = 에이전트 하나. 앱이 둘 다 **직접** 붙는다(허브 없음 — README 결정 ②). */
data class Machine(val key: String, val label: String, val base: String)

/**
 * 맥 목록은 빌드 때 주입한다(core/build.gradle.kts → BuildConfig.CMR_MACHINES).
 * 형식: `key|이름|https://주소:포트` 를 쉼표로 잇는다. key 는 에이전트의 CMR_MACHINE 과 맞춘다(laptop·home 권장).
 * 비어 있으면 맥 목록이 빈 채로 뜬다 — README «설정» 참고.
 */
val MACHINES: List<Machine> = parseMachines(BuildConfig.CMR_MACHINES)

fun parseMachines(spec: String): List<Machine> =
    spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { item ->
        val p = item.split('|').map { it.trim() }
        if (p.size == 3 && p.all { it.isNotEmpty() }) Machine(p[0], p[1], p[2].trimEnd('/')) else null
    }

class ApiError(msg: String, val code: Int = 0, val body: JSONObject? = null) : IOException(msg)

/** 에이전트 한 번 부르기 — post 가 있으면 POST(JSON). 2xx 가 아니면 에이전트가 detail 에 실은 이유로 [ApiError]. */
object Http {
    fun call(url: String, post: JSONObject? = null, readMs: Int = 12000): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = readMs
            if (post != null) {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (post != null) c.outputStream.use { it.write(post.toString().toByteArray()) }
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                // 에이전트는 이유를 detail 에 한국어로 싣는다(문자열 또는 {reason, prompt, trace}) — 그대로 보여 준다
                val j = runCatching { JSONObject(body) }.getOrNull()
                val det = j?.opt("detail")
                val msg = when (det) {
                    is JSONObject -> det.optString("reason")
                    is String -> det
                    else -> body.take(200)
                }
                throw ApiError("HTTP $code $msg", code, det as? JSONObject)
            }
            return JSONObject(body)
        } finally {
            c.disconnect()
        }
    }

    /**
     * 큰 GET 한 번 — gzip 으로 받고 ETag 가 같으면 304(body = null). 기록 앱 인덱스(원본 2.5MB → gzip 711KB)용.
     * Accept-Encoding 을 직접 걸었으므로 풀기도 여기서 한다(HttpURLConnection 의 투명 해제는 헤더를 안 건 경우만).
     */
    fun fetch(url: String, etag: String? = null, readMs: Int = 30000): Fetched {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = readMs
            setRequestProperty("Accept-Encoding", "gzip")
            if (etag != null) setRequestProperty("If-None-Match", etag)
        }
        try {
            val code = c.responseCode
            if (code == 304) return Fetched(304, null, etag)
            val raw = if (code in 200..299) c.inputStream else c.errorStream
            val ins = if (c.contentEncoding.equals("gzip", ignoreCase = true) && raw != null) GZIPInputStream(raw) else raw
            val body = ins?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val det = runCatching { JSONObject(body).opt("detail") }.getOrNull()
                throw ApiError("HTTP $code ${(det as? String) ?: body.take(200)}", code)
            }
            return Fetched(code, body, c.getHeaderField("ETag"))
        } finally {
            c.disconnect()
        }
    }
}

data class Fetched(val code: Int, val body: String?, val etag: String?)

fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)
fun JSONObject.dbl(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k)
