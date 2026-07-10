package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.saf.BrukerIdType
import no.nav.eessi.pensjon.saf.Journalpost
import no.nav.eessi.pensjon.saf.SafClient
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class SafService (
    private val safClient: SafClient,
){
    private val logger: Logger = LoggerFactory.getLogger(DodsmeldingBehandler::class.java)


    fun brukerRinasakIdFraJoark(valgtPersonident: String): String? =
        hentJournalposter(valgtPersonident)
            .firstNotNullOfOrNull(::bucIdForP6000Journalpost)

    private fun hentJournalposter(valgtPersonident: String) =
        safClient
            .hentDokumentMetadata(valgtPersonident, BrukerIdType.FNR)
            .data
            .dokumentoversiktBruker
            .journalposter

    private fun bucIdForP6000Journalpost(journalpost: Journalpost): String? {
        val bucId = hentBucId(journalpost) ?: return null

        if (!harP6000Dokument(journalpost)) {
            return null
        }

        loggTreff(journalpost, bucId)
        return bucId
    }

    private fun hentBucId(journalpost: Journalpost): String? =
        journalpost.tilleggsopplysninger
            .firstNotNullOfOrNull {
                it.takeIf { opplysning -> opplysning["nokkel"] == "eessi_pensjon_bucid" }
                    ?.get("verdi")
            }


    private fun harP6000Dokument(journalpost: Journalpost): Boolean =
        journalpost.dokumenter.orEmpty()
            .any { it.tittel?.contains("P6000") == true }

    private fun loggTreff(journalpost: Journalpost, bucId: String) {
        logger.info(
            "Treff for journalpostId: ${journalpost.journalpostId}, " +
                    "bucId: $bucId, " +
                    "datoOpprettet: ${journalpost.datoOpprettet}, " +
                    "journalfoerendeEnhet: ${journalpost.tilleggsopplysninger}"
        )
    }
}