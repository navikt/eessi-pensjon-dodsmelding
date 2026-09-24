package no.nav.eessi.pensjon.dodsmelding

import io.micrometer.core.instrument.Metrics
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.metrics.MetricsHelper
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.doedsfall.Doedsfall
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
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
        autoStartup = "false",
        batch = "true",
        topics = ["pdl.leesah-v1"],
        groupId = "eessi-pensjon-dodsmelding",
        containerFactory = "kafkaAivenHendelseListenerAvroLatestContainerFactory",
    )
    fun mottaLeesahMelding(consumerRecords: List<ConsumerRecord<String, Personhendelse>>, ack: Acknowledgment) {
        behandleMeldinger(consumerRecords)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun mottaFakeMeldinger() {
        behandleMeldinger(genererFakeListe())
    }

    private fun behandleMeldinger(consumerRecords: List<ConsumerRecord<String, Personhendelse>>) {
        try {
//            logger.info("Behandler ${consumerRecords.size} meldinger, firstOffset=${consumerRecords.first().offset()}, lastOffset=${consumerRecords.last().offset()}")
            consumerRecords.forEachIndexed { recordCount, record ->
                leesahKafkaListenerMetric.measure {
                    val personhendelse = record.value()
                    MDC.put("x_request_id", UUID.randomUUID().toString())
                    try {
                        when (personhendelse.opplysningstype) {
                            "DOEDSFALL_V1" -> behandleDoedsfall(personhendelse, consumerRecords, recordCount)
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

    private fun genererFakeListe(): List<ConsumerRecord<String, Personhendelse>> {
        val identListe = listOf("05075721589", "18114239115", "01115724173", "01055049404", "03106710952", "06105628755", "17017330063", "16037327372", "10064233020", "24115734195", "48066202634", "29075249622", "08055523585", "28036116090", "18074429277", "06083739639", "71054402184", "25124530566", "24055225377", "04115046352", "25104232534", "21095125147", "11054626470", "03085226079", "19065426520", "15104440042", "18074936853", "17075723024", "08024646085", "23044920964", "04116527729", "29104233293", "07025544204", "25055624162", "16043246813", "14025237804", "18033944655", "04054727645", "17045916928", "09105629718", "08053741313", "19045521322", "31034335225", "61105802284", "06035326985", "12054545469", "18084146516", "08063437744", "09115229300", "20094735581", "16116735842", "08035144340", "19124741672", "29015725034", "15094147635", "18085747262", "68065803023", "30016227451", "31085521341", "27065822070")

        return identListe.mapIndexed { index, ident ->
            val hendelseId = UUID.randomUUID().toString()
            val personhendelse = Personhendelse.newBuilder()
                .setHendelseId(hendelseId)
                .setPersonidenter(listOf(ident))
                .setMaster("FREG")
                .setOpprettet(Instant.now())
                .setOpplysningstype("DOEDSFALL_V1")
                .setEndringstype(Endringstype.OPPRETTET)
                .setDoedsfall(
                    Doedsfall.newBuilder()
                        .setDoedsdato(LocalDate.now())
                        .build()
                )
                .build()

            ConsumerRecord("fake-pdl.leesah-v1", 0, index.toLong(), hendelseId, personhendelse)
        }
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