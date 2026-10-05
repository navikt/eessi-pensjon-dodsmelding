package no.nav.eessi.pensjon.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.support.GenericApplicationContext
import java.util.concurrent.TimeUnit

class KafkaApplicationShutdownTest {
    @Test
    fun `avslutning lukker Spring context og terminerer prosessen med exitkode 1`() {
        val process = ProcessBuilder(
            "${System.getProperty("java.home")}/bin/java",
            "-cp", System.getProperty("java.class.path"),
            KafkaAvslutningTestprosess::class.java.name
        ).redirectErrorStream(true).start()

        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Testprosessen avsluttet ikke")
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(1, process.exitValue(), output)
            assertEquals(1, output.lineSequence().count { it == "CONTEXT_LUKKET" }, output)
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}

object KafkaAvslutningTestprosess {
    @JvmStatic
    fun main(args: Array<String>) {
        val context = GenericApplicationContext()
        context.defaultListableBeanFactory.registerDisposableBean(
            "avslutningskontroll",
            DisposableBean { println("CONTEXT_LUKKET") }
        )
        context.refresh()
        val shutdown = KafkaApplicationShutdown(context)
        shutdown.shutdown()
        shutdown.shutdown()
        Thread.sleep(30_000)
    }
}
