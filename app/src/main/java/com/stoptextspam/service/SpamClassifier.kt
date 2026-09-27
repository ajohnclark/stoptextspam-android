package com.stoptextspam.service

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

data class ClassificationResult(val isSpam: Boolean, val reason: String) {
    val failed: Boolean get() = reason.startsWith("API ") || reason.startsWith("Parse error")
}

object SpamClassifier {

    private const val TAG = "SpamClassifier"
    private const val PRIMARY_MODEL = "~deepseek/deepseek-v4-flash-latest"
    private const val FALLBACK_MODEL = "~google/gemini-flash-latest"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json".toMediaType()

    // Patterns that are almost always spam — skip API call
    private val SPAM_PATTERNS = listOf(
        "reply stop" to "Unsolicited automated message (opt-out language)",
        "text stop" to "Unsolicited automated message (opt-out language)",
        "send stop" to "Unsolicited automated message (opt-out language)",
        "msg stop" to "Unsolicited automated message (opt-out language)",
        "stop to end" to "Unsolicited automated message (opt-out language)",
        "stop to opt" to "Unsolicited automated message (opt-out language)",
        "stop to unsub" to "Unsolicited automated message (opt-out language)",
        "unsubscribe" to "Unsolicited mass message",
        "pre-approved" to "Unsolicited loan/credit offer",
        "pre approved" to "Unsolicited loan/credit offer",
        "gift card" to "Gift card scam",
        "claim your" to "Prize/reward scam",
        "claim now" to "Prize/reward scam",
        "act now" to "Urgency-based scam",
        "your account has been" to "Phishing attempt",
        "verify your identity" to "Phishing attempt",
        "confirm your identity" to "Phishing attempt",
        "account has been locked" to "Phishing attempt",
        "account has been suspended" to "Phishing attempt",
        "you have been selected" to "Prize/reward scam",
        "congratulations" to "Prize/reward scam",
        "no credit check" to "Predatory loan scam",
        "bitcoin" to "Cryptocurrency spam",
        "crypto" to "Cryptocurrency spam",
        "vote for" to "Political spam",
        "donate to" to "Political solicitation",
        "campaign" to "Political spam",
        "iboboto" to "Political survey spam (Filipino)",
        "senador" to "Political spam (Filipino)",
    )

    /**
     * Quick local filter using keyword patterns. Returns ClassificationResult if
     * obvious spam is detected, null if the message needs API classification.
     */
    fun localFilter(body: String): ClassificationResult? {
        val lower = body.lowercase()
        for ((pattern, reason) in SPAM_PATTERNS) {
            if (lower.contains(pattern)) {
                return ClassificationResult(isSpam = true, reason = reason)
            }
        }
        return null
    }

    /**
     * Classify a message using the primary model, falling back to the secondary
     * model if the primary fails.
     */
    suspend fun classify(sender: String, body: String, apiKey: String): ClassificationResult {
        if (MessageRules.politicalSurvey(body)) {
            return ClassificationResult(true, "Political voter survey (local rule)")
        }
        if (apiKey.isBlank()) return ClassificationResult(false, "API key missing")

        val primaryResult = callApi(sender, body, PRIMARY_MODEL, apiKey)
        if (!isApiError(primaryResult)) return primaryResult

        Log.w(TAG, "Primary model failed; trying fallback")
        return callApi(sender, body, FALLBACK_MODEL, apiKey)
    }

    private fun isApiError(result: ClassificationResult): Boolean {
        return result.failed
    }

    private suspend fun callApi(sender: String, body: String, model: String, apiKey: String): ClassificationResult {
        val prompt = buildString {
            append("Classify this SMS as spam or not spam using the sender and message only. ")
            append("POLITICAL AUTO-REJECT RULE: If the message has any political focus, always ")
            append("classify it as spam. This includes candidates, elected officials, political ")
            append("parties, campaigns, elections, voting or voter registration, political ")
            append("fundraising, polls or surveys about politics, petitions, ballot measures, ")
            append("public-policy advocacy, ideological persuasion, rallies, and political ")
            append("volunteering. Apply this rule even when the message is legitimate, neutral, ")
            append("informational, nonpartisan, opted-in, or from an official organization. ")
            append("Do not trigger the political rule merely because a nonpolitical message uses ")
            append("a word such as campaign, vote, poll, party, candidate, primary, bill, left, ")
            append("right, red, or blue in an unrelated context. ")
            append("Also classify scams, phishing, impersonation, unsolicited marketing, loan ")
            append("offers, cryptocurrency schemes, fake delivery notices, and other unwanted ")
            append("automated messages as spam. Legitimate nonpolitical transactional messages, ")
            append("authentication codes, delivery updates, appointments, receipts, government ")
            append("service notices, and plausible personal conversations are not spam.\n\n")
            append("Sender: $sender\n")
            append("Message: $body\n\n")
            append("Respond with ONLY a JSON object, no other text:\n")
            append("{\"spam\": true/false, \"reason\": \"brief reason if spam, empty string if not\"}")
        }

        val requestJson = JSONObject().apply {
            put("model", model)
            put("max_tokens", 100)
            put("reasoning", JSONObject().apply {
                put(
                    "effort",
                    if (model == PRIMARY_MODEL) "none" else "minimal"
                )
            })
            put("response_format", JSONObject().apply {
                put("type", "json_schema")
                put("json_schema", JSONObject().apply {
                    put("name", "spam_classification")
                    put("strict", true)
                    put("schema", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("spam", JSONObject().put("type", "boolean"))
                            put("reason", JSONObject().put("type", "string"))
                        })
                        put("required", JSONArray().apply {
                            put("spam")
                            put("reason")
                        })
                        put("additionalProperties", false)
                    })
                })
            })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestJson.toString().toRequestBody(JSON_MEDIA))
            .build()

        Log.d(TAG, "Calling $model")

        return suspendCancellableCoroutine { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }

            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "$model request failed")
                    cont.resume(ClassificationResult(false, "API request failed"))
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val responseBody = response.body?.string() ?: ""

                        if (!response.isSuccessful) {
                            Log.w(TAG, "$model HTTP ${response.code}")
                            cont.resume(ClassificationResult(false, "API HTTP ${response.code}"))
                            return
                        }

                        // OpenRouter uses OpenAI format: choices[0].message.content
                        val json = JSONObject(responseBody)
                        val resolvedModel = json.optString("model", model)
                        val content = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()

                        // Extract JSON from response (handle markdown code blocks)
                        val jsonStr = if (content.startsWith("{")) {
                            content
                        } else {
                            val start = content.indexOf("{")
                            val end = content.lastIndexOf("}") + 1
                            if (start >= 0 && end > start) content.substring(start, end) else content
                        }

                        val result = JSONObject(jsonStr)
                        require(result.get("spam") is Boolean) { "Missing boolean spam verdict" }
                        Log.d(
                            TAG,
                            "$model resolved to $resolvedModel: spam=${result.optBoolean("spam")}"
                        )
                        cont.resume(
                            ClassificationResult(
                                isSpam = result.optBoolean("spam", false),
                                reason = result.optString("reason", "")
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "$model response could not be parsed")
                        cont.resume(ClassificationResult(false, "Parse error"))
                    }
                }
            })
        }
    }
}
