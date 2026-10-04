package no.nav.eessi.pensjon.config

import com.google.cloud.storage.StorageException
import io.mockk.Called
import io.mockk.mockk
import io.mockk.verify
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.listener.MessageListenerContainer
import java.io.IOException

class KafkaBatchErrorHandlerTest {
    private val standardFeilhaandtering = mockk<CommonErrorHandler>(relaxed = true)
    private val stoppendeFeilhaandtering = mockk<CommonErrorHandler>(relaxed = true)
    private val consumer = mockk<Consumer<String, String>>(relaxed = true)
    private val container = mockk<MessageListenerContainer>(relaxed = true)
    private val invokeListener = mockk<Runnable>(relaxed = true)
    private val partisjon = TopicPartition("test", 0)
    private val batch = ConsumerRecords(
        mapOf(partisjon to (0 until 500).map { offset ->
            ConsumerRecord("test", 0, offset.toLong(), "test", "test")
        }),
        emptyMap()
    )
    private val handler = KafkaBatchErrorHandler(standardFeilhaandtering, stoppendeFeilhaandtering)

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `tilgangsfeil stopper en batch med 500 meldinger uten nye behandlingsforsok`(kode: Int) {
        val feil = ListenerExecutionFailedException("Feil i listener", StorageException(kode, "Mangler tilgang"))

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            stoppendeFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { standardFeilhaandtering wasNot Called }
        verify { invokeListener wasNot Called }
        verify { consumer wasNot Called }
    }

    @Test
    fun `tilgangsfeil finnes gjennom flere innpakkede unntak`() {
        val feil = ListenerExecutionFailedException(
            "Feil i listener",
            IllegalStateException("Feil ved lagring", StorageException(403, "Mangler tilgang"))
        )

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            stoppendeFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { standardFeilhaandtering wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `metadatafeil stopper batchen selv om StorageException mangler HTTP-status`(kode: Int) {
        val metadatafeil = IOException(
            "Unexpected Error code $kode trying to get security access token from Compute Engine metadata " +
                "for the default service account: Unable to generate access token"
        )
        val feil = ListenerExecutionFailedException("Feil i listener", StorageException.translate(metadatafeil))

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            stoppendeFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { standardFeilhaandtering wasNot Called }
        verify { invokeListener wasNot Called }
        verify { consumer wasNot Called }
    }

    @Test
    fun `midlertidig metadatafeil bruker eksisterende feilhaandtering`() {
        val metadatafeil = IOException(
            "Unexpected Error code 503 trying to get security access token from Compute Engine metadata " +
                "for the default service account: Service unavailable"
        )
        val feil = ListenerExecutionFailedException("Feil i listener", StorageException.translate(metadatafeil))

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            standardFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { stoppendeFeilhaandtering wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 429, 500, 503])
    fun `andre GCS-feil bruker eksisterende feilhaandtering`(kode: Int) {
        val feil = ListenerExecutionFailedException("Feil i listener", StorageException(kode, "GCS-feil"))

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            standardFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { stoppendeFeilhaandtering wasNot Called }
    }

    @Test
    fun `andre feil bruker eksisterende feilhaandtering`() {
        val feil = IllegalStateException("Feil i behandling")

        handler.handleBatch(feil, batch, consumer, container, invokeListener)

        verify(exactly = 1) {
            standardFeilhaandtering.handleBatch(feil, batch, consumer, container, invokeListener)
        }
        verify { stoppendeFeilhaandtering wasNot Called }
    }

    @Test
    fun `stopp av container kaster feil uten aa kvittere ut batchen`() {
        val stoppendeHandler = CommonContainerStoppingErrorHandler { oppgave -> oppgave.run() }
        val handlerMedStopp = KafkaBatchErrorHandler(standardFeilhaandtering, stoppendeHandler)
        val feil = ListenerExecutionFailedException("Feil i listener", StorageException(403, "Mangler tilgang"))

        val kastetFeil = assertThrows<KafkaException> {
            handlerMedStopp.handleBatch(feil, batch, consumer, container, invokeListener)
        }

        assertSame(feil, kastetFeil.cause)
        verify(exactly = 1) { container.stopAbnormally(any()) }
        verify { consumer wasNot Called }
        verify { invokeListener wasNot Called }
        verify { standardFeilhaandtering wasNot Called }
    }

    @ParameterizedTest
    @ValueSource(ints = [401, 403])
    fun `KafkaConfig stopper 500 meldinger en gang ved metadatafeil uten aa lagre offsets`(kode: Int) {
        val config = KafkaConfig("", "", "", "", "", KafkaStoppingErrorHandler())
        val metadatafeil = IOException(
            "Unexpected Error code $kode trying to get security access token from Compute Engine metadata " +
                "for the default service account: Unable to generate access token; " +
                "IAM returned $kode: Permission 'iam.serviceAccounts.getAccessToken' denied on resource " +
                "(or it may not exist)."
        )
        val lagringsfeil = StorageException.translate(metadatafeil)
        assertNotEquals(kode, lagringsfeil.code)
        val feil = ListenerExecutionFailedException("Feil i listener", lagringsfeil)

        val kastetFeil = assertThrows<KafkaException> {
            config.kafkaRestartingErrorHandler().handleBatch(feil, batch, consumer, container, invokeListener)
        }

        assertSame(feil, kastetFeil.cause)
        verify(timeout = 1000, exactly = 1) { container.stopAbnormally(any()) }
        verify { consumer wasNot Called }
        verify { invokeListener wasNot Called }
    }
}
