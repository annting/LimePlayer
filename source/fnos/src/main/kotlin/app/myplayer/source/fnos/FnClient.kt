package app.myplayer.source.fnos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 飞牛影视（fnOS Media Center）私有 API 客户端（任务 4-2 底层）。
 *
 * 鉴权三层：`Authorization: <token>` + `authx` 签名头 + 客户端标识头。
 * 签名：`md5(SECRET_path_nonce_timestamp_bodyHash_API_KEY)`，
 * GET 的 bodyHash = 排序后 query 串的 md5（无参为 md5("")），
 * POST 的 bodyHash = 紧凑 JSON 的 md5（`docs/05` §6.9 已实测）。
 */
class FnClient(
    private val baseUrl: String,
    private val secret: String = BuildConfig.FN_SECRET,
    private val apiKey: String = BuildConfig.FN_API_KEY,
    private val client: OkHttpClient = OkHttpClient(),
    private val deviceId: String = "limeplayer",
    /** 版本号随飞牛升级可能变化，可在服务器配置里覆盖。 */
    private val clientVersion: String = "631",
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var token: String? = null

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun authx(path: String, bodyHash: String): String {
        val nonce = (Random.nextInt(100000, 999999)).toString()
        val ts = System.currentTimeMillis().toString()
        val sign = md5(listOf(secret, path, nonce, ts, bodyHash, apiKey).joinToString("_"))
        return "nonce=$nonce&timestamp=$ts&sign=$sign"
    }

    private fun url(path: String, params: Map<String, String>): String {
        val q = if (params.isEmpty()) "" else params.toSortedMap()
            .entries.joinToString("&") { "${it.key}=${it.value}" }
        return baseUrl.trimEnd('/') + path + if (q.isEmpty()) "" else "?$q"
    }

    /** GET：参数进 query，签名用排序后 query 串。 */
    suspend fun get(path: String, params: Map<String, String> = emptyMap()): JsonObject =
        withContext(Dispatchers.IO) {
            val q = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
            val req = Request.Builder()
                .url(url(path, params))
                .header("authx", authx(path, md5(q)))
                .applyToken()
                .get()
                .build()
            execute(req)
        }

    /** POST：载荷在 body（放 query 会被判 invalid sign）。 */
    suspend fun post(path: String, body: JsonObject = JsonObject(emptyMap())): JsonObject =
        withContext(Dispatchers.IO) {
            val raw = body.toString()
            val req = Request.Builder()
                .url(url(path, emptyMap()))
                .header("authx", authx(path, md5(raw)))
                .applyToken()
                .post(raw.toRequestBody("application/json".toMediaType()))
                .build()
            execute(req)
        }

    private fun Request.Builder.applyToken(): Request.Builder = apply {
        token?.let { header("Authorization", it) }
        header("x-trim-client", "web")
        header("x-trim-client-version", clientVersion)
        header("Content-Type", "application/json")
    }

    private fun execute(req: Request): JsonObject {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw FnException("HTTP ${resp.code}: ${text.take(120)}")
            val obj = json.parseToJsonElement(text).jsonObject
            val code = obj["code"]?.jsonPrimitive?.longOrNull ?: 0L
            if (code != 0L) {
                throw FnException("飞牛接口错误 code=$code msg=${obj["msg"]?.jsonPrimitive?.content.orEmpty()}")
            }
            return obj
        }
    }

    /** 账号密码登录 → token（影视账号，不是 NAS 系统账号）。 */
    suspend fun login(username: String, password: String): String {
        val body = JsonObject(
            mapOf("username" to kotlinx.serialization.json.JsonPrimitive(username),
                  "password" to kotlinx.serialization.json.JsonPrimitive(password))
        )
        val resp = post("/v/api/v1/login", body)
        val t = (resp["data"] as? JsonObject)?.get("token")?.jsonPrimitive?.content
            ?: throw FnException("登录失败：未返回 token")
        token = t
        return t
    }

    fun setToken(value: String?) { token = value }

    enum class Kind { OK }
}

class FnException(message: String) : Exception(message)

/** 便捷取值（保持调用处简洁）。 */
internal fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
