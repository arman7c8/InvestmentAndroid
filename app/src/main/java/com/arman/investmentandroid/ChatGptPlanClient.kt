package com.arman.investmentandroid

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executor
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Direct ChatGPT-plan connection for Investment Android.
 *
 * No API key is supported. Credentials are encrypted with Android Keystore and saved
 * under noBackupFilesDir, so they are not part of Investment portfolio/cloud backups.
 */
class ChatGptPlanClient(private val context: Context) {
    companion object {
        const val AUTHORIZE_URL = "https://auth.openai.com/api/accounts/authorize"
        const val TOKEN_URL = "https://auth.openai.com/api/accounts/oauth/token"
        const val JWKS_URL = "https://auth.openai.com/.well-known/jwks.json"
        const val RESOURCE = "https://api.openai.com/v1"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES =
            "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"
        const val DYNAMIC_CLIENT_ID = "dynamic_agent_client"
        const val CALLBACK_PATH = "/auth/callback"
        const val ISSUER = "https://auth.openai.com"
        private const val KEY_ALIAS = "investment_chatgpt_plan_v1"
        private const val TOKEN_FILE = "chatgpt-plan-token.bin"
        private const val PREFS = "investment_chatgpt_connection"
        private const val MAX_BODY = 2 * 1024 * 1024

        internal fun base64Url(bytes: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        internal fun pkceChallenge(verifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

        internal fun parseScopes(value: String): Set<String> =
            value.split(" ").filter { it.isNotBlank() }.toSet()

        private fun randomUrlToken(): String {
            val bytes = ByteArray(32)
            java.security.SecureRandom().nextBytes(bytes)
            return base64Url(bytes)
        }

        private fun formEncode(values: Map<String, String>): String =
            values.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" +
                    URLEncoder.encode(it.value, "UTF-8")
            }
    }

    class PlanException(message: String) : RuntimeException(message)

    data class Model(val slug: String, val displayName: String)

    data class ConnectionResult(
        val clientId: String,
        val subject: String,
        val models: List<Model>
    )

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val tokenFile = File(context.noBackupFilesDir, TOKEN_FILE)

    fun isConnected(): Boolean =
        prefs.getBoolean("plan_enabled", false) && tokenFile.isFile

    fun disconnect() {
        tokenFile.delete()
        prefs.edit().putBoolean("plan_enabled", false).remove("client_id").apply()
    }

    private fun hostId(): String {
        val existing = prefs.getString("host_id", null)
        if (!existing.isNullOrBlank()) return existing
        val created = "urn:uuid:" + UUID.randomUUID().toString()
        prefs.edit().putString("host_id", created).apply()
        return created
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun saveTokens(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toString().toByteArray(StandardCharsets.UTF_8))
        val output = ByteArrayOutputStream()
        output.write(cipher.iv.size)
        output.write(cipher.iv)
        output.write(encrypted)
        val atomic = AtomicFile(tokenFile)
        val stream = atomic.startWrite()
        try {
            stream.write(output.toByteArray())
            atomic.finishWrite(stream)
        } catch (exc: Exception) {
            atomic.failWrite(stream)
            throw PlanException("Android could not protect the ChatGPT connection.")
        }
    }

    private fun loadTokens(): JSONObject {
        if (!tokenFile.isFile) return JSONObject()
        try {
            val raw = tokenFile.readBytes()
            if (raw.size < 14) throw PlanException("Saved ChatGPT credentials are invalid.")
            val ivSize = raw[0].toInt() and 0xff
            if (ivSize !in 12..16 || raw.size <= 1 + ivSize) {
                throw PlanException("Saved ChatGPT credentials are invalid.")
            }
            val iv = raw.copyOfRange(1, 1 + ivSize)
            val encrypted = raw.copyOfRange(1 + ivSize, raw.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            return JSONObject(String(cipher.doFinal(encrypted), StandardCharsets.UTF_8))
        } catch (exc: PlanException) {
            throw exc
        } catch (exc: Exception) {
            throw PlanException("Saved ChatGPT credentials could not be unlocked. Reconnect ChatGPT.")
        }
    }

    private fun authorizationUrl(
        redirectUri: String,
        state: String,
        nonce: String,
        verifier: String
    ): Pair<String, Boolean> {
        val savedClient = prefs.getString("client_id", null)
        val first = savedClient.isNullOrBlank() || !savedClient.startsWith("oaiapp_")
        val clientId = if (first) DYNAMIC_CLIENT_ID else savedClient!!
        val builder = Uri.parse(AUTHORIZE_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("ext_agent_host_id", hostId())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("resource", RESOURCE)
            .appendQueryParameter("state", state)
            .appendQueryParameter("nonce", nonce)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", pkceChallenge(verifier))
        if (first) {
            builder.appendQueryParameter("agent_name_hint", "Investment")
        } else {
            val hint = try { loadTokens().optString("id_token", "") } catch (_: PlanException) { "" }
            if (hint.isNotBlank()) builder.appendQueryParameter("id_token_hint", hint)
        }
        return builder.build().toString() to first
    }

    /**
     * Starts the official system-browser flow. Network/callback work runs off the UI
     * thread. callback is invoked on the supplied callbackExecutor.
     */
    fun authorize(
        activity: Activity,
        workerExecutor: Executor,
        callbackExecutor: Executor,
        callback: (Result<ConnectionResult>) -> Unit
    ) {
        workerExecutor.execute {
            val result = runCatching { authorizeBlocking(activity) }
            callbackExecutor.execute { callback(result) }
        }
    }

    private fun authorizeBlocking(activity: Activity): ConnectionResult {
        val state = randomUrlToken()
        val nonce = randomUrlToken()
        val verifier = randomUrlToken() + randomUrlToken()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 180_000
            val redirect = "http://127.0.0.1:" + server.localPort + CALLBACK_PATH
            val (authUrl, first) = authorizationUrl(redirect, state, nonce, verifier)
            val requestedClient = if (first) DYNAMIC_CLIENT_ID
                else prefs.getString("client_id", null) ?: throw PlanException("ChatGPT client is missing.")

            activity.runOnUiThread {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }

            val callbackValues = try {
                server.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    val request = reader.readLine() ?: throw PlanException("ChatGPT callback was empty.")
                    val target = request.split(" ").getOrNull(1)
                        ?: throw PlanException("ChatGPT callback was invalid.")
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val body = (
                        "<!doctype html><meta charset='utf-8'><title>Investment</title>" +
                            "<h3>ChatGPT sign-in finished.</h3>" +
                            "<p>You can close this tab and return to Investment.</p>"
                        ).toByteArray(StandardCharsets.UTF_8)
                    val header = (
                        "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    socket.getOutputStream().apply {
                        write(header)
                        write(body)
                        flush()
                    }
                    parseQuery(target)
                }
            } catch (exc: java.net.SocketTimeoutException) {
                throw PlanException("ChatGPT sign-in timed out. Retry the connection.")
            }

            if (callbackValues["state"] != state) {
                throw PlanException("ChatGPT sign-in security check failed. Retry the connection.")
            }
            if (!callbackValues["error"].isNullOrBlank()) {
                if (callbackValues["error"] == "access_denied") {
                    throw PlanException("ChatGPT plan access was not approved for Investment.")
                }
                throw PlanException("ChatGPT sign-in was not completed.")
            }
            val code = callbackValues["code"]
                ?: throw PlanException("ChatGPT sign-in did not return an authorization code.")
            val clientId = if (first) {
                callbackValues["client_id"]?.takeIf { it.startsWith("oaiapp_") }
                    ?: throw PlanException(
                        "This ChatGPT account/client is not currently eligible for direct plan access."
                    )
            } else {
                val returned = callbackValues["client_id"]
                if (!returned.isNullOrBlank() && returned != requestedClient) {
                    throw PlanException("ChatGPT returned a different registered client.")
                }
                requestedClient
            }

            val previousSubject = if (first) "" else try {
                loadTokens().optString("subject", "")
            } catch (_: PlanException) { "" }

            val token = postForm(
                TOKEN_URL,
                mapOf(
                    "grant_type" to "authorization_code",
                    "client_id" to clientId,
                    "code" to code,
                    "code_verifier" to verifier,
                    "redirect_uri" to redirect,
                    "resource" to RESOURCE
                )
            )
            validateTokenResponse(token)
            val claims = validateIdToken(token.getString("id_token"), clientId, nonce)
            if (previousSubject.isNotBlank() && claims.getString("sub") != previousSubject) {
                throw PlanException("ChatGPT returned a different account. Reconnect the intended account.")
            }
            val scopes = parseScopes(token.getString("scope"))
            if (PLAN_SCOPE !in scopes) {
                prefs.edit().putString("client_id", clientId).putBoolean("plan_enabled", false).apply()
                throw PlanException("ChatGPT sign-in succeeded, but ChatGPT plan usage was not enabled.")
            }
            val now = System.currentTimeMillis() / 1000.0
            val expires = token.optDouble("expires_in", 3600.0)
            val saved = JSONObject()
                .put("client_id", clientId)
                .put("subject", claims.getString("sub"))
                .put("access_token", token.getString("access_token"))
                .put("refresh_token", token.getString("refresh_token"))
                .put("id_token", token.getString("id_token"))
                .put("scope", token.getString("scope"))
                .put("expires_at", now + expires)
            if (token.has("earliest_refresh_at")) {
                saved.put("earliest_refresh_at", token.get("earliest_refresh_at"))
            }
            saveTokens(saved)
            prefs.edit().putString("client_id", clientId).putBoolean("plan_enabled", true).apply()
            return ConnectionResult(clientId, claims.getString("sub"), availableModels())
        }
    }

    private fun parseQuery(target: String): Map<String, String> {
        val uri = Uri.parse("http://127.0.0.1" + target)
        val result = mutableMapOf<String, String>()
        uri.queryParameterNames.forEach { key ->
            uri.getQueryParameter(key)?.let { result[key] = it }
        }
        return result
    }

    private fun validateTokenResponse(value: JSONObject) {
        listOf("access_token", "refresh_token", "id_token", "scope").forEach {
            if (value.optString(it, "").isBlank()) {
                throw PlanException("ChatGPT sign-in returned incomplete credentials.")
            }
        }
    }

    private fun accessToken(): String {
        var saved = loadTokens()
        if (PLAN_SCOPE !in parseScopes(saved.optString("scope", ""))) {
            throw PlanException("ChatGPT plan is not connected. Connect ChatGPT first.")
        }
        val now = System.currentTimeMillis() / 1000.0
        if (saved.optDouble("expires_at", 0.0) <= now + 120.0) {
            saved = refresh(saved)
        }
        return saved.optString("access_token", "").takeIf { it.isNotBlank() }
            ?: throw PlanException("ChatGPT access is unavailable. Reconnect ChatGPT.")
    }

    private fun refresh(saved: JSONObject): JSONObject {
        val clientId = saved.optString("client_id", "")
        val refresh = saved.optString("refresh_token", "")
        if (!clientId.startsWith("oaiapp_") || refresh.isBlank()) {
            throw PlanException("Saved ChatGPT credentials are invalid. Reconnect ChatGPT.")
        }
        val token = postForm(
            TOKEN_URL,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to clientId,
                "refresh_token" to refresh,
                "resource" to RESOURCE
            )
        )
        validateTokenResponse(token)
        if (PLAN_SCOPE !in parseScopes(token.getString("scope"))) {
            throw PlanException("ChatGPT plan usage is no longer enabled for Investment.")
        }
        val claims = validateIdToken(token.getString("id_token"), clientId, null)
        val subject = saved.optString("subject", "")
        if (subject.isNotBlank() && claims.getString("sub") != subject) {
            throw PlanException("ChatGPT refresh returned a different account. Reconnect ChatGPT.")
        }
        val now = System.currentTimeMillis() / 1000.0
        val next = JSONObject()
            .put("client_id", clientId)
            .put("subject", claims.getString("sub"))
            .put("access_token", token.getString("access_token"))
            .put("refresh_token", token.getString("refresh_token"))
            .put("id_token", token.getString("id_token"))
            .put("scope", token.getString("scope"))
            .put("expires_at", now + token.optDouble("expires_in", 3600.0))
        if (token.has("earliest_refresh_at")) next.put("earliest_refresh_at", token.get("earliest_refresh_at"))
        saveTokens(next)
        return next
    }

    fun availableModels(): List<Model> {
        val connection = open("GET", RESOURCE + "/models", accessToken())
        val body = readJson(connection, "model list")
        if (connection.responseCode != 200) {
            throw PlanException("ChatGPT plan access could not load available models.")
        }
        val items = body.optJSONArray("models")
            ?: throw PlanException("ChatGPT returned an invalid model list.")
        val result = mutableListOf<Model>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            if (item.optString("visibility") != "list") continue
            val slug = item.optString("slug")
            if (slug.isNotBlank()) result += Model(slug, item.optString("display_name", slug))
        }
        if (result.isEmpty()) throw PlanException("No ChatGPT plan models are currently available.")
        return result
    }

    fun testConnection(model: String): String =
        streamResponse(
            JSONObject()
                .put("model", model)
                .put("input", JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("content", "Say exactly: Investment AI connection OK")
                ))
                .put("store", false)
                .put("stream", true)
        )

    fun analyzeSnapshot(snapshot: JSONObject, model: String): JSONObject {
        AiAdvisorContract.assertSnapshotSafe(snapshot)
        val raw = streamResponse(
            JSONObject()
                .put("model", model)
                .put(
                    "instructions",
                    "You are the Investment AI Advisor. Analyze only the supplied percentage-based " +
                        "portfolio snapshot and optional Atlas public market context. Never request " +
                        "or infer monetary values, balances, account identity, or credentials. " +
                        "Target changes are recommendations only and require explicit user approval. " +
                        AiAdvisorRecommendation.promptContract()
                )
                .put(
                    "input",
                    JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", snapshot.toString())
                    )
                )
                .put("store", false)
                .put("stream", true)
        )
        return AiAdvisorRecommendation.parse(raw)
    }

    private fun streamResponse(payload: JSONObject): String {
        if (payload.opt("store") != false || payload.opt("stream") != true) {
            throw PlanException("Investment AI requests must be private streamed requests.")
        }
        val connection = open("POST", RESOURCE + "/responses", accessToken())
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use {
            it.write(payload.toString().toByteArray(StandardCharsets.UTF_8))
        }
        if (connection.responseCode != 200) {
            connection.disconnect()
            throw PlanException("The ChatGPT plan request was not accepted.")
        }
        val output = StringBuilder()
        var completed = false
        var received = 0
        connection.inputStream.bufferedReader(StandardCharsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (!line.startsWith("data:")) return@forEach
                val data = line.substring(5).trim()
                if (data.isBlank() || data == "[DONE]") return@forEach
                received += data.length
                if (received > MAX_BODY) throw PlanException("The ChatGPT response was unexpectedly large.")
                val event = try { JSONObject(data) } catch (_: Exception) { return@forEach }
                when (event.optString("type")) {
                    "response.output_text.delta" -> output.append(event.optString("delta"))
                    "response.failed" -> {
                        val code = event.optJSONObject("response")
                            ?.optJSONObject("error")?.optString("code", "") ?: ""
                        if (
                            code == "subscription_sharing_usage_limit_exceeded" ||
                            code == "subscription_sharing_usage_unavailable"
                        ) {
                            throw PlanException(
                                "Your ChatGPT plan usage for Investment is currently unavailable or at its limit."
                            )
                        }
                        throw PlanException("The ChatGPT request failed during inference.")
                    }
                    "response.completed" -> completed = true
                }
            }
        }
        connection.disconnect()
        if (!completed) throw PlanException("The ChatGPT response ended before completion.")
        return output.toString().trim()
    }

    private fun validateIdToken(token: String, clientId: String, nonce: String?): JSONObject {
        val parts = token.split(".")
        if (parts.size != 3) throw PlanException("ChatGPT identity validation failed.")
        val header = try {
            JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[0])), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            throw PlanException("ChatGPT identity validation failed.")
        }
        if (header.optString("alg") != "RS256" || header.optString("kid").isBlank()) {
            throw PlanException("ChatGPT returned an unsupported identity signature.")
        }
        val jwksConnection = open("GET", JWKS_URL, null)
        val jwks = readJson(jwksConnection, "identity")
        if (jwksConnection.responseCode != 200) {
            throw PlanException("ChatGPT identity keys could not be loaded.")
        }
        val keys = jwks.optJSONArray("keys")
            ?: throw PlanException("ChatGPT identity keys were invalid.")
        var matching: JSONObject? = null
        for (index in 0 until keys.length()) {
            val candidate = keys.optJSONObject(index) ?: continue
            if (candidate.optString("kid") == header.optString("kid")) {
                matching = candidate
                break
            }
        }
        val key = matching ?: throw PlanException("ChatGPT identity key was not recognized.")
        val nBytes = Base64.getUrlDecoder().decode(pad(key.getString("n")))
        val eBytes = Base64.getUrlDecoder().decode(pad(key.getString("e")))
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(
                java.math.BigInteger(1, nBytes),
                java.math.BigInteger(1, eBytes)
            )
        )
        val verifier = Signature.getInstance("SHA256withRSA")
        verifier.initVerify(publicKey)
        verifier.update((parts[0] + "." + parts[1]).toByteArray(StandardCharsets.US_ASCII))
        if (!verifier.verify(Base64.getUrlDecoder().decode(pad(parts[2])))) {
            throw PlanException("ChatGPT identity signature was invalid.")
        }
        val claims = try {
            JSONObject(String(Base64.getUrlDecoder().decode(pad(parts[1])), StandardCharsets.UTF_8))
        } catch (_: Exception) {
            throw PlanException("ChatGPT identity validation failed.")
        }
        val now = System.currentTimeMillis() / 1000L
        if (claims.optString("iss") != ISSUER) throw PlanException("ChatGPT identity validation failed.")
        val audienceOk = when (val aud = claims.opt("aud")) {
            is String -> aud == clientId
            is JSONArray -> (0 until aud.length()).any { aud.optString(it) == clientId }
            else -> false
        }
        if (!audienceOk) throw PlanException("ChatGPT identity validation failed.")
        if (nonce != null && claims.optString("nonce") != nonce) {
            throw PlanException("ChatGPT identity validation failed.")
        }
        if (claims.optString("sub").isBlank() || claims.optLong("exp", 0) < now - 5) {
            throw PlanException("ChatGPT identity validation failed.")
        }
        return claims
    }

    private fun pad(value: String): String =
        value + "=".repeat((4 - value.length % 4) % 4)

    private fun postForm(url: String, values: Map<String, String>): JSONObject {
        val connection = open("POST", url, null)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        connection.outputStream.use {
            it.write(formEncode(values).toByteArray(StandardCharsets.UTF_8))
        }
        val body = readJson(connection, "sign-in")
        if (connection.responseCode != 200) {
            throw PlanException("ChatGPT sign-in could not finish. Retry the connection.")
        }
        return body
    }

    private fun open(method: String, url: String, bearer: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = false
        if (!bearer.isNullOrBlank()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer)
        }
        return connection
    }

    private fun readJson(connection: HttpURLConnection, label: String): JSONObject {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val bytes = stream?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BODY) throw PlanException("ChatGPT returned an unexpectedly large " + label + " response.")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: ByteArray(0)
        return try {
            JSONObject(String(bytes, StandardCharsets.UTF_8))
        } catch (_: Exception) {
            throw PlanException("ChatGPT returned an invalid " + label + " response.")
        }
    }
}
