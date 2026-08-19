package no.nav.eessi.pensjon.config

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Component
import java.lang.Exception
import kotlin.system.exitProcess


@Profile("prod", "test")
@Component
class KafkaStoppingErrorHandler(
    @Autowired private val applicationContext: ConfigurableApplicationContext
) : CommonContainerStoppingErrorHandler() {
    private val logger = LoggerFactory.getLogger(KafkaStoppingErrorHandler::class.java)

    override fun handleRemaining(
        thrownException: Exception,
        records: MutableList<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ) {
        logger.error("En feil oppstod under kafka konsumering av meldinger: \n" + textListingOf(records) +
                "\nStopper containeren og applikasjonen! Restart er nødvendig for å fortsette konsumering", thrownException)
        super.handleRemaining(thrownException, records, consumer, container)
        stoppApplikasjonen()
    }

    /**
     * Å stoppe kun kafka-containeren er ikke nok til at appen faktisk restartes: prosessen
     * kjører videre og fremstår som frisk (f.eks. mot liveness-probe) selv om konsumeringen
     * har stanset permanent. Derfor avsluttes hele applikasjonen her, slik at Kubernetes
     * starter en ny, frisk pod som kan fortsette konsumeringen.
     */
    private fun stoppApplikasjonen() {
        Thread {
            try {
                val exitCode = SpringApplication.exit(applicationContext)
                exitProcess(exitCode)
            } catch (e: Exception) {
                logger.error("Klarte ikke å stoppe applikasjonen på vanlig måte, avslutter prosessen direkte", e)
                exitProcess(1)
            }
        }.apply {
            isDaemon = true
            name = "kafka-stopping-error-handler-shutdown"
        }.start()
    }

    fun textListingOf(records: List<ConsumerRecord<*, *>>) =
        records.joinToString(separator = "\n") {
            "-" .repeat(20) + "\n" + vaskFnr(it.toString())
        }

    private fun vaskFnr(tekst: String) = tekst.replace(Regex("""\b\d{11}\b"""), "***")
}
