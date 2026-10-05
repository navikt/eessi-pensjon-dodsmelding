package no.nav.eessi.pensjon.config

import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.SerializationException
import org.slf4j.LoggerFactory
import org.springframework.core.log.LogAccessor
import org.springframework.kafka.KafkaException
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.MessageListenerContainer
import org.springframework.kafka.support.TopicPartitionOffset
import org.springframework.kafka.support.converter.ConversionException
import org.springframework.kafka.support.serializer.DeserializationException
import org.springframework.messaging.converter.MessageConversionException

class KafkaConversionErrorHandler(
    private val delegate: CommonErrorHandler,
    private val applicationShutdown: KafkaApplicationShutdown
) : CommonErrorHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun seeksAfterHandling(): Boolean = delegate.seeksAfterHandling()

    override fun deliveryAttemptHeader(): Boolean = delegate.deliveryAttemptHeader()

    override fun deliveryAttempt(topicPartitionOffset: TopicPartitionOffset): Int =
        delegate.deliveryAttempt(topicPartitionOffset)

    override fun isAckAfterHandle(): Boolean = delegate.isAckAfterHandle

    override fun setAckAfterHandle(ack: Boolean) = delegate.setAckAfterHandle(ack)

    override fun clearThreadState() = delegate.clearThreadState()

    override fun logger(): LogAccessor = delegate.logger()

    override fun onPartitionsAssigned(
        consumer: Consumer<*, *>,
        partitions: MutableCollection<TopicPartition>,
        publishPause: Runnable
    ) = delegate.onPartitionsAssigned(consumer, partitions, publishPause)

    override fun handleOtherException(
        thrownException: Exception,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        batchListener: Boolean
    ) {
        shutdownOnConversionFailure(thrownException)
        delegate.handleOtherException(thrownException, consumer, container, batchListener)
    }

    override fun handleBatch(
        thrownException: Exception,
        data: ConsumerRecords<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        invokeListener: Runnable
    ) {
        shutdownOnConversionFailure(thrownException)
        delegate.handleBatch(thrownException, data, consumer, container, invokeListener)
    }

    override fun <K : Any, V : Any> handleBatchAndReturnRemaining(
        thrownException: Exception,
        data: ConsumerRecords<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer,
        invokeListener: Runnable
    ): ConsumerRecords<K, V> {
        shutdownOnConversionFailure(thrownException)
        return delegate.handleBatchAndReturnRemaining<K, V>(thrownException, data, consumer, container, invokeListener)
    }

    override fun handleRemaining(
        thrownException: Exception,
        records: MutableList<ConsumerRecord<*, *>>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ) {
        shutdownOnConversionFailure(thrownException)
        delegate.handleRemaining(thrownException, records, consumer, container)
    }

    override fun handleOne(
        thrownException: Exception,
        record: ConsumerRecord<*, *>,
        consumer: Consumer<*, *>,
        container: MessageListenerContainer
    ): Boolean {
        shutdownOnConversionFailure(thrownException)
        return delegate.handleOne(thrownException, record, consumer, container)
    }

    private fun shutdownOnConversionFailure(exception: Exception) {
        val conversionFailure = generateSequence<Throwable>(exception) { it.cause }.any {
            it is SerializationException || it is DeserializationException ||
                it is ConversionException || it is MessageConversionException
        }
        if (conversionFailure) {
            logger.error("Kafka message deserialization or conversion failed; shutting down application", exception)
            applicationShutdown.shutdown()
            // Never return successfully: the failed record must not be recovered or committed.
            throw KafkaException("Application is shutting down after Kafka message conversion failed", exception)
        }
    }
}
