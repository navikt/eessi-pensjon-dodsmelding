package no.nav.eessi.pensjon.config

import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

@Profile("prod", "test")
@Component
class KafkaApplicationShutdown(
    private val context: ConfigurableApplicationContext
) {
    private val avslutningStartet = AtomicBoolean(false)

    fun shutdown() {
        if (!avslutningStartet.compareAndSet(false, true)) return

        // Egen tråd unngår at Kafka-tråden venter på seg selv når context lukkes.
        Thread({
            try {
                SpringApplication.exit(context, { 1 })
            } finally {
                exitProcess(1)
            }
        }, "kafka-applikasjon-stopp").start()
    }
}
