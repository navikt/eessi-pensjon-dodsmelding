package no.nav.eessi.pensjon.config

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Component
import java.lang.Exception


@Profile("prod", "test")
@Component
class KafkaStoppingErrorHandler : CommonContainerStoppingErrorHandler() {
    private val logger = LoggerFactory.getLogger(KafkaStoppingErrorHandler::class.java)

    override fun handleBatch(
        thrownException: Exception,
        data: ConsumerRecords<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        invokeListener: Runnable
    ) {
        logger.error(
            "Mangler tilgang til GCP. Stopper Kafka-containeren med ${data.count()} meldinger i batchen. " +
                "Rett tilgangen og restart applikasjonen for å fortsette konsumeringen."
        )
        super.handleBatch(thrownException, data, consumer, container, invokeListener)
    }

    override fun handleRemaining(
        thrownException: Exception,
        records: MutableList<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ) {
        logger.error("En feil oppstod under kafka konsumering av meldinger: \n" + textListingOf(records) +
                "\nStopper containeren ! Restart er nødvendig for å fortsette konsumering", thrownException)
        super.handleRemaining(thrownException, records, consumer, container)
    }

    fun textListingOf(records: List<ConsumerRecord<*, *>>) =
        records.joinToString(separator = "\n") {
            "-" .repeat(20) + "\n" + vaskFnr(it.toString())
        }

    private fun vaskFnr(tekst: String) = tekst.replace(Regex("""\b\d{11}\b"""), "***")
}
