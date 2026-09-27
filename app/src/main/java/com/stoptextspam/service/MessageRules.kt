package com.stoptextspam.service

/** Deterministic rules that do not require uploading message contents. */
object MessageRules {
    fun photoIsSpam(knownContact: Boolean, mimeTypes: List<String?>): Boolean =
        !knownContact && mimeTypes.any { it?.startsWith("image/", ignoreCase = true) == true }

    fun politicalSurvey(body: String): Boolean =
        Regex("\\b(survey|poll)\\b", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
            Regex("\\b(voters?|elections?|ballot|political)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(body)
}
