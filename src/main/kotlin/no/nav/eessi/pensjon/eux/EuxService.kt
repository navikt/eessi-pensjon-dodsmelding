package no.nav.eessi.pensjon.eux

import no.nav.eessi.pensjon.eux.klient.EuxGenericServerException
import no.nav.eessi.pensjon.eux.klient.EuxKlientLib
import no.nav.eessi.pensjon.eux.klient.SedDokumentIkkeOpprettetException
import no.nav.eessi.pensjon.eux.model.Avsendere
import no.nav.eessi.pensjon.eux.model.Motparter
import no.nav.eessi.pensjon.eux.model.sed.SED
import no.nav.eessi.pensjon.metrics.MetricsHelper
import no.nav.eessi.pensjon.utils.mapJsonToAny
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate

@Service
class EuxService(
    private val euxKlient: EuxKlientLib,
    private val euxV2RestTemplate: RestTemplate,
    @Autowired(required = false) private val metricsHelper: MetricsHelper = MetricsHelper.ForTest()
) {
    private var opprettH070: MetricsHelper.Metric
    private val secureLogger = LoggerFactory.getLogger("secureLog")

    init {
        opprettH070 = metricsHelper.init("opprettH070", alert = MetricsHelper.Toggle.OFF)
    }
    private val logger = LoggerFactory.getLogger(EuxService::class.java)

    /**
     * Oppretter ny H070 SED på ekisterende BUC
     */
    @Throws(EuxGenericServerException::class, SedDokumentIkkeOpprettetException::class)
    fun opprettH070(mottakerId: String, h070: SED): SaksDetaljer {
        return opprettH070.measure {
            val json = euxKlient.createHBuc07(mottakerId, h070).also { secureLogger.info("Oppretter H_BUC_07 med H070:\n$it") }
            mapJsonToAny<SaksDetaljer>(json)
        }
    }

    fun sendSed(rinaSakId: String, dokumentId: String): Boolean {
        logger.info("Sender H070 til Rina: $rinaSakId, sedId: $dokumentId")
        return euxKlient.sendSed(rinaSakId, dokumentId)
    }

    fun hentAvsenderLand(rinaSakId: String?): List<Motparter>? {
        logger.info("Henter avsenderland fra SED med rinasakId: $rinaSakId, fra rina")
        try {
            return rinaSakId?.let { euxKlient.hentSedMetadataLand(it, euxV2RestTemplate) }?.normalisert()?.motparter
        } catch (e: Exception) {
           logger.warn("Feil under henting av avsenderland fra Rina for rinasakId: $rinaSakId", e)
        }
        return null
    }


    data class SaksDetaljer(
        val caseId: String,
        val documentId: String
    )

}
