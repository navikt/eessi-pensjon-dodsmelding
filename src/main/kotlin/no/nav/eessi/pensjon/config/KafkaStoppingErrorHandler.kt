package no.nav.eessi.pensjon.config

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.kafka.KafkaException
import org.springframework.kafka.event.ConsumerFailedToStartEvent
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.stereotype.Component

@Profile("prod", "test")
@Component
class KafkaStoppingErrorHandler(
    private val shutdown: KafkaApplicationShutdown
) : CommonErrorHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun isAckAfterHandle(): Boolean = false

    override fun seeksAfterHandling(): Boolean = true

    @EventListener
    fun konsumentStoppet(event: ConsumerStoppedEvent) {
        if (event.reason != ConsumerStoppedEvent.Reason.NORMAL) {
            logger.error("Kafka-konsumenten stoppet med årsak {}. Avslutter applikasjonen.", event.reason)
            shutdown.shutdown()
        }
    }

    @EventListener
    fun konsumentStartFeilet(event: ConsumerFailedToStartEvent) {
        logger.error("Kafka-konsumenten kunne ikke starte. Avslutter applikasjonen.")
        shutdown.shutdown()
    }

    override fun handleBatch(
        thrownException: Exception,
        data: ConsumerRecords<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        invokeListener: Runnable
    ) = stopp(thrownException)

    override fun handleRemaining(
        thrownException: Exception,
        records: MutableList<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ) = stopp(thrownException)

    override fun handleOne(
        thrownException: Exception,
        record: ConsumerRecord<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ): Boolean = stopp(thrownException)

    override fun handleOtherException(
        thrownException: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        batchListener: Boolean
    ) = stopp(thrownException)

    private fun stopp(feil: Exception): Nothing {
        logger.error("Kafka-feil. Avslutter applikasjonen uten å kvittere ut meldingene.", feil)
        shutdown.shutdown()
        throw KafkaException("Avslutter applikasjonen etter Kafka-feil", feil)
    }
}
