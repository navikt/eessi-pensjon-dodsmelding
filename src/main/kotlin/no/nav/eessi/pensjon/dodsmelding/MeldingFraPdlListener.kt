package no.nav.eessi.pensjon.dodsmelding

import io.micrometer.core.instrument.Metrics
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.metrics.MetricsHelper
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class MeldingFraPdlListener(
    private val dodsmeldingBehandler: DodsmeldingBehandler,
    @Autowired(required = false) private val metricsHelper: MetricsHelper = MetricsHelper.ForTest()
) {
    @Autowired
    private lateinit var opprettH070: OpprettH070
    private val logger = LoggerFactory.getLogger(MeldingFraPdlListener::class.java)
    private val secureLogger = LoggerFactory.getLogger("secureLog")
    private val messureOpplysningstype = MessureOpplysningstypeHelper()
    private var leesahKafkaListenerMetric : MetricsHelper.Metric = metricsHelper.init("leesahPersonoppslag")

    init {
        messureOpplysningstype.clearAll()
    }

    @KafkaListener(
        autoStartup = "\${pdl.kafka.autoStartup}",
        batch = "true",
        topics = ["pdl.leesah-v1"],
        groupId = "eessi-pensjon-dodsmelding",
        containerFactory = "kafkaAivenHendelseListenerAvroLatestContainerFactory",
    )
    fun mottaLeesahMelding(consumerRecords: List<ConsumerRecord<String, Personhendelse>>, ack: Acknowledgment) {
        try {
//            logger.info("Behandler ${consumerRecords.size} meldinger, firstOffset=${consumerRecords.first().offset()}, lastOffset=${consumerRecords.last().offset()}")
            var recordCount = 0
            consumerRecords.forEach { record ->
                leesahKafkaListenerMetric.measure {
                    val personhendelse = record.value()
                    MDC.put("x_request_id", UUID.randomUUID().toString())
                    try {
                        when (personhendelse.opplysningstype) {
                            "DOEDSFALL_V1" -> behandleDoedsfall(personhendelse, consumerRecords, recordCount++)
                            "BOSTEDSADRESSE_V1", "KONTAKTADRESSE_V1", "OPPHOLDSADRESSE_V1" ->
                                messureOpplysningstype.addKjent(personhendelse)
                            else -> messureOpplysningstype.addUkjent(personhendelse)
                        }
                    } finally {
                        MDC.remove("x_request_id")
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Behandling av hendelse feilet", e)
            throw e
        }

        messureOpplysningstype.createMetrics()
        messureOpplysningstype.clearAll()
//        ack.acknowledge()
    }


    private fun behandleDoedsfall(
        personhendelse: Personhendelse,
        consumerRecords: List<ConsumerRecord<String, Personhendelse>>,
        recordCount: Int
    ) {
        logger.info("Behandler $recordCount av ${consumerRecords.size} meldinger, firstOffset=${consumerRecords.first().offset()}, lastOffset=${consumerRecords.last().offset()}")
        secureLogger.info("DOEDSFALL_V1: $personhendelse")

        when (personhendelse.endringstype) {
            Endringstype.OPPRETTET ->
                dodsmeldingBehandler.behandle(personhendelse).also {
                    logger.info("DOEDSFALL_V1 ${personhendelse.endringstype}, behandler denne")
                }
            else -> {
                logger.info("DOEDSFALL_V1 ${personhendelse.endringstype}, ignorerer denne")
            }
        }
    }


    class MessureOpplysningstypeHelper() {

        private val logger: Logger = LoggerFactory.getLogger(javaClass)
        private val knownType : MutableList<String> = mutableListOf()
        private val unkownType : MutableList<String> = mutableListOf()

        fun addKjent(personhendelse: Personhendelse) = knownType.add(personhendelse.opplysningstype)

        fun addUkjent(personhendelse: Personhendelse) = unkownType.add(personhendelse.opplysningstype)

        fun createMetrics() {
            try {
                knownType.map { navn ->
//                    logger.debug("Opplysningstype: $navn")
                    Metrics.counter("personhendelse_kjent_opplysningstype", "Navn", navn).increment()
                }
                unkownType.map { navn ->
//                    logger.debug("Ukjentopplysningstype: $navn")
                    Metrics.counter("personhendelse_ukjent_opplysningstype", "Navn", navn).increment()
                }
            } catch (_: Exception) {
                logger.warn("Metrics feilet på opplysningstype")
            }
        }

        fun clearAll() {
            knownType.clear()
            unkownType.clear()
        }
    }
}