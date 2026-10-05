package no.nav.eessi.pensjon.config

import com.google.cloud.storage.StorageException
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthenticationException
import org.apache.kafka.common.errors.SerializationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.kafka.KafkaException
import org.springframework.kafka.event.ConsumerFailedToStartEvent
import org.springframework.kafka.event.ConsumerStoppedEvent
import org.springframework.kafka.listener.MessageListenerContainer
import java.io.IOException

class KafkaStoppingErrorHandlerTest {
    private val shutdown = mockk<KafkaApplicationShutdown>(relaxed = true)
    private val handler = KafkaStoppingErrorHandler(shutdown)
    private val consumer = mockk<Consumer<String, String>>(relaxed = true)
    private val container = mockk<MessageListenerContainer>(relaxed = true)
    private val invokeListener = mockk<Runnable>(relaxed = true)
    private val records = (0 until 500).map { ConsumerRecord("test", 0, it.toLong(), "test", "test") }
    private val batch = ConsumerRecords(mapOf(TopicPartition("test", 0) to records), emptyMap())

    @ParameterizedTest
    @MethodSource("feil")
    fun `alle batchfeil avslutter applikasjonen uten retry eller offsetendringer`(feil: Exception) {
        val kastetFeil = assertThrows<KafkaException> {
            handler.handleBatch(feil, batch, consumer, container, invokeListener)
        }

        assertSame(feil, kastetFeil.cause)
        verify(exactly = 1) { shutdown.shutdown() }
        verify { consumer wasNot Called }
        verify { container wasNot Called }
        verify { invokeListener wasNot Called }
        assertFalse(handler.isAckAfterHandle)
    }

    @ParameterizedTest
    @MethodSource("feil")
    fun `feil fra poll avslutter applikasjonen`(feil: Exception) {
        assertThrows<KafkaException> {
            handler.handleOtherException(feil, consumer, container, true)
        }

        verify(exactly = 1) { shutdown.shutdown() }
        verify { consumer wasNot Called }
    }

    @Test
    fun `begge recordfeilhaandteringer avslutter applikasjonen`() {
        val feil = IllegalStateException("Feil")
        assertThrows<KafkaException> {
            handler.handleOne(feil, records.first(), consumer, container)
        }
        assertThrows<KafkaException> {
            handler.handleRemaining(feil, records.toMutableList(), consumer, container)
        }
        verify(exactly = 2) { shutdown.shutdown() }
        verify { consumer wasNot Called }
    }

    @Test
    fun `KafkaConfig bruker stoppende feilhaandtering`() {
        val config = KafkaConfig("", "", "", "", "", handler)
        assertSame(handler, config.kafkaErrorHandler())
    }

    @ParameterizedTest
    @EnumSource(value = ConsumerStoppedEvent.Reason::class, names = ["NORMAL"], mode = EnumSource.Mode.EXCLUDE)
    fun `unormal konsumentstopp avslutter applikasjonen`(aarsak: ConsumerStoppedEvent.Reason) {
        handler.konsumentStoppet(ConsumerStoppedEvent(container, container, aarsak))

        verify(exactly = 1) { shutdown.shutdown() }
    }

    @Test
    fun `normal konsumentstopp starter ikke feilavslutning`() {
        handler.konsumentStoppet(ConsumerStoppedEvent(container, container, ConsumerStoppedEvent.Reason.NORMAL))

        verify { shutdown wasNot Called }
    }

    @Test
    fun `mislykket konsumentstart avslutter applikasjonen`() {
        handler.konsumentStartFeilet(ConsumerFailedToStartEvent(container, container))

        verify(exactly = 1) { shutdown.shutdown() }
    }

    companion object {
        @JvmStatic
        fun feil(): List<Exception> = listOf(
            StorageException(403, "Mangler tilgang"),
            StorageException(503, "Midlertidig GCS-feil"),
            StorageException.translate(IOException(
                "Unexpected Error code 403 trying to get security access token from Compute Engine metadata"
            )),
            SerializationException("Ugyldig Avro"),
            AuthenticationException("Mangler Kafka-tilgang"),
            IllegalStateException("Feil i behandling")
        )
    }
}
