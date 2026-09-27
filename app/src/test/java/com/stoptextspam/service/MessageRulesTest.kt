package com.stoptextspam.service

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MessageRulesTest {
    @Test fun unknownPhotoWithoutCaptionIsSpam() {
        assertTrue(MessageRules.photoIsSpam(false, listOf("image/jpeg")))
        assertTrue(MessageRules.photoIsSpam(false, listOf(null, "IMAGE/PNG")))
    }
    @Test fun contactPhotosArePrivate() {
        assertFalse(MessageRules.photoIsSpam(true, listOf("image/jpeg")))
    }
    @Test fun textAndOtherAttachmentsAreNotPhotos() {
        assertFalse(MessageRules.photoIsSpam(false, emptyList()))
        assertFalse(MessageRules.photoIsSpam(false, listOf(null, "video/mp4", "application/pdf")))
    }
    @Test fun voterSurveyIsCaughtLocally() {
        assertTrue(MessageRules.politicalSurvey("Hi Alex, this is Insights Research. We're conducting a survey of voters in our area. Text Stop to quit"))
        assertFalse(MessageRules.politicalSurvey("Please complete our customer service survey"))
        assertFalse(MessageRules.politicalSurvey("The party starts at 7. Vote for pizza!"))
    }
    @Test fun classifierUsesOfflineSurveyRule() = runBlocking {
        val result = SpamClassifier.classify("+15555550123", "We're conducting a survey of voters in our area", "")
        assertTrue(result.isSpam)
        assertEquals("Political voter survey (local rule)", result.reason)
        assertFalse(result.failed)
    }
    @Test fun apiFailureIsDistinctFromNotSpam() {
        assertTrue(ClassificationResult(false, "API HTTP 503").failed)
        assertTrue(ClassificationResult(false, "Parse error: missing verdict").failed)
        assertFalse(ClassificationResult(false, "").failed)
    }

    @Test fun noKeyLeavesOtherMessagesVisible() = runBlocking {
        val result = SpamClassifier.classify("+15555550123", "Your appointment is tomorrow", "")
        assertFalse(result.isSpam)
        assertEquals("API key missing", result.reason)
        assertTrue(result.failed)
    }
}
