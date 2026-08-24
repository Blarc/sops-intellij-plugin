package com.github.blarc.sops.intellij.plugin.services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SerializedEditRequestsTest {
    @Test
    fun `only the newest queued edit request remains current`() {
        val requests = SerializedEditRequests()
        val first = requests.next()
        val second = requests.next()

        assertFalse(requests.isLatest(first))
        assertTrue(requests.isLatest(second))
    }

    @Test
    fun `requests are serialized and superseded queued requests are skipped`() = runBlocking {
        val requests = SerializedEditRequests()
        val first = requests.next()
        val firstStarted = CompletableDeferred<Unit>()
        val finishFirst = CompletableDeferred<Unit>()
        val executed = mutableListOf<Long>()

        val firstJob = launch {
            requests.runIfLatest(first) {
                firstStarted.complete(Unit)
                finishFirst.await()
                executed += first
            }
        }
        firstStarted.await()

        val superseded = requests.next()
        val latest = requests.next()
        val supersededJob = launch {
            requests.runIfLatest(superseded) {
                executed += superseded
            }
        }
        val latestJob = launch {
            requests.runIfLatest(latest) {
                executed += latest
            }
        }

        finishFirst.complete(Unit)
        joinAll(firstJob, supersededJob, latestJob)

        assertEquals(listOf(first, latest), executed)
    }
}
