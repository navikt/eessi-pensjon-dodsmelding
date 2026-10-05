package no.nav.eessi.pensjon.config

import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.system.exitProcess

@Component
class KafkaApplicationShutdown(private val context: ConfigurableApplicationContext) {
    private val shuttingDown = AtomicBoolean()

    fun shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            // Closing the context on the consumer thread would wait for that same consumer to stop.
            thread(name = "kafka-application-shutdown", isDaemon = false) {
                try {
                    SpringApplication.exit(context, ExitCodeGenerator { 1 })
                } finally {
                    exitProcess(1)
                }
            }
        }
    }
}
