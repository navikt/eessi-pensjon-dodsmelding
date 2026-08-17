package no.nav.eessi.pensjon.dodsmelding

import no.nav.eessi.pensjon.eux.EuxService
import no.nav.eessi.pensjon.eux.model.buc.SakType.*
import no.nav.eessi.pensjon.eux.model.sed.PinItem
import no.nav.eessi.pensjon.eux.model.sed.SED
import no.nav.eessi.pensjon.gcp.LagringsService
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.oppgaverouting.SakInformasjon
import no.nav.eessi.pensjon.personoppslag.pdl.PersonService
import no.nav.eessi.pensjon.personoppslag.pdl.model.Ident
import no.nav.eessi.pensjon.personoppslag.pdl.model.IdentGruppe
import no.nav.eessi.pensjon.personoppslag.pdl.model.PdlPerson
import no.nav.eessi.pensjon.utils.toJson
import no.nav.person.pdl.leesah.Personhendelse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component


private const val H070_LAGRET_PREFIX_STANDARD = "H070_STANDARD"
private const val H070_LAGRET_PREFIX_EDIFACT = "H070_EDIFACT"

@Component
class DodsmeldingBehandler(
    private val pesysKlient: PesysKlient,
    private val personService: PersonService,
    private val opprettH070: OpprettH070,
    private val euxService: EuxService,
    private val safService: SafService,
    private val lagringsService: LagringsService,
    @Value("\${ENV}") private val env: String
) {
    private val logger: Logger = LoggerFactory.getLogger(DodsmeldingBehandler::class.java)
    private val secureLogger = LoggerFactory.getLogger("secureLog")

    val gyldigeUtstederland = listOf("SE", "SW", "SWE", "FI", "FIN", "PO", "POL")

    fun behandle(personhendelse: Personhendelse) {
        val valgtPersonident = hentAlleNorskeIdenter(personhendelse)

        if (valgtPersonident == null) {
            logger.warn("Fant ingen gyldig ident i personidenter: ${personhendelse.personidenter}")
            return
        }

        logger.info("Henter informasjon for ident: ${valgtPersonident.take(4)}")
        val identFraPdl = Ident.bestemIdent(valgtPersonident)

        if (lagringsService.finnesDoedsmeldingAlleredeForBruker(identFraPdl.id)) {
            logger.info("Bruker finnes allerede i bucket, avbryter opprettelse av H070")
            return
        }

        val person = personService.hentPerson(identFraPdl).also { logger.debug("Henter person: {}", it) }

        if (person == null) {
            logger.warn("Fant ingen personident")
            return
        }

        if(person.bostedsadresse?.utenlandskAdresse != null) {
            logger.info("Bruker har utenlandsk adresse. Oppretter dermed ikke H070")
            return
        }

        val pin = opprettPinListe(person)
        if (pin.isEmpty()) {
            logger.warn("Fant verken norsk eller utenlandsk ident, avbryter opprettelse av H070")
            return
        }

        //1. Det kommer inn en dødsmelding på køen
        //2. Sjekk ident mot leveattester
        //3. Sjekk ident i joark, om det finnes en P6000 som har avsender fra SE, PL, DK eller FI
        //4. Dersom treff i en av disse to, send ut en H070

        val brukerILeveAttReg = lagringsService.finnesDodBrukerILeveAttReg(person.identer)
        if (brukerILeveAttReg != null) {
            require(brukerILeveAttReg.first.isNotEmpty()) { "Finner ikke fnr i leveattestregisteret" }
            if (brukerILeveAttReg.second !in gyldigeUtstederland) {
                logger.info("Bruker finnes i leveattestregisteret, men utstederland (${brukerILeveAttReg.second}) er ikke gyldig")
                return
            }

            logger.info("Bruker finnes i leveattestregisteret, oppretter H070")
            val landInstitusjon = institusjon(brukerILeveAttReg.first, brukerILeveAttReg.second)
                .also { logger.info("Sender til institusjon: {}", it) }
            lagringsService.lagreFnrForBruker(identFraPdl.id)
            val h070 = opprettH070.preutFyltH070(personhendelse, person, pin).also { secureLogger.info("preutfylt h070 fra LeveAttestReg: {}, land: $landInstitusjon", it) }
            lagringsService.lagreH070(h070, H070_LAGRET_PREFIX_EDIFACT)
//            opprettOgSendH070(h070, landInstitusjon).also { logger.info("Oppretter og sender ut H070 til ${brukerILeveAttReg.second}") }
            return
        }

        val rinaSakId = safService.brukerRinasakIdFraJoark(valgtPersonident)

        if (rinaSakId.isNullOrBlank()) {
            logger.info("Mangler rinaSakId fra Joark, avbryter")
            return
        }

        val motparter = euxService.hentAvsenderLand(rinaSakId).also { logger.info("AvsenderLand: {}", it) }
        if (motparter.isNullOrEmpty()) {
            logger.warn("Mangler land, avbryter")
            return
        }

        val mottakerLand = motparter
            .mapNotNull { it.motpartLand?.trim() }
            .firstOrNull { it in setOf("SE", "FI", "PL", "DK") }

        if (mottakerLand.isNullOrBlank()) {
            logger.warn("Mangler mottaker land, avbryter")
            return
        }

        lagringsService.lagreFnrForBruker(identFraPdl.id)
        val h070 = opprettH070.preutFyltH070(personhendelse, person, pin).also { secureLogger.info("preutfylt h070 fra Joark: {}", it) }
        lagringsService.lagreH070(h070, H070_LAGRET_PREFIX_STANDARD)
//        opprettOgSendH070(h070, mottakerLand)
//            .also { logger.info("Oppretter og sender ut H070 for Joark bruker til $mottakerLand") }
        logger.info("I dette tilfellet ville vi opprettet H070 og sendt den ut til $mottakerLand")
        secureLogger.info("PersonHendelse for H070: $personhendelse")

        //TODO: Sjekk hvilken ytelse bruker har før vi går videre med å preutfylle en H070
        //TODO: Sjekk hvilken institusjon som skal legges til ut i fra hvilket land det er som skal motta H070 fra oss.

    }

//    private fun brukerRinasakIdFraJoark(valgtPersonident: String): String? {
//        val responseFraSaf = safClient.hentDokumentMetadata(valgtPersonident, BrukerIdType.FNR)
//
//        responseFraSaf.data.dokumentoversiktBruker.journalposter.forEach { journalpost ->
//            val buciD = hentBucId(journalpost)
//            journalpost.dokumenter?.forEach { dokument ->
//                if (buciD != null && dokument.tittel?.contains("P6000") == true) {
//                    logger.info("Treff for journalpostId: ${journalpost.journalpostId}, buciD: $buciD, datoOpprettet: ${journalpost.datoOpprettet}, journalfoerendeEnhet: ${journalpost.tilleggsopplysninger}")
//                    return buciD
//                }
//            }
//        }
//        return null
//    }


    private fun opprettPinListe(person: PdlPerson): List<PinItem> {
        val norskIdent = person.identer.firstOrNull { it.gruppe == IdentGruppe.FOLKEREGISTERIDENT }?.ident
        val utenlandskIdent = person.utenlandskIdentifikasjonsnummer.firstOrNull()

        return buildList {
            norskIdent?.let {
                add(
                    PinItem(
                        identifikator = it,
                        land = "NOR"
                    )
                )
            }

            utenlandskIdent?.let {
                add(
                    PinItem(
                        identifikator = it.identifikasjonsnummer,
                        land = it.utstederland.take(3)
                    )
                )
            }
        }
    }

    fun opprettOgSendH070(h070: SED, instViSkalSendeTil: String) {

        throw RuntimeException("Denne metoden skal ikke brukes i prod enda") //TODO: Skal fjerne når alt annet er testet

        try {
            if (env == "q2") {
                val response = euxService.opprettH070("NO:NAVAT05", h070)
                Thread.sleep(5000) // Legger inn en liten delay for å unngå at sendSed blir kalt før opprettH070 er ferdig.
                euxService.sendSed(response.caseId, response.documentId)
            } else {
                val response = euxService.opprettH070(instViSkalSendeTil, h070)
                //TODO: Legg inn denne for å få sendt h070 i prod
                euxService.sendSed(response.caseId, response.documentId)
            }
        } catch (e: Exception) {
            logger.error("Feil ved opprettelse av H070", e)
            return
        }
    }

    fun institusjon(fnr: String, landFraIdentUtland: String): String {
        val ytelsesInfo = pesysKlient.hentPensjonSaklist(fnr).also { logger.debug("Henter pensjonsakliste: {}", it.toJson()) }
        val penytelse = ytelsesInfo.firstOrNull { it.sakType in listOf(UFOREP, GJENLEV, BARNEP, ALDER, OMSORG) }
        val land =
            if (landFraIdentUtland.contains("FI")) "FIN"
            else if (landFraIdentUtland.contains("SE")) "SWE"
            else if (landFraIdentUtland.contains("DK")) "DKK"
            else if (landFraIdentUtland.contains("PL")) "POL" else null
        val institusjonViSkalSendeTil = mottakendeInstitusjon(penytelse, land)
        return institusjonViSkalSendeTil
    }

    private fun mottakendeInstitusjon(penytelse: SakInformasjon?, land: String?): String {
        //TODO: Avklaring om vi trenger å sende H070 til en annen institusjon i landet dersom ytelsen er forskjellig
        return when (land) {
            // Dersom bruker har en en uføre ytelse, sendes H070 til institusjon nummer 2 for Sverige
            "SE", "SWE" -> if (penytelse?.sakType == UFOREP) "SE:2001" else "SE:3002"
            "FI", "FIN" -> "FI:0200000010"
            "PL", "POL" -> "PL:PL390050ER"
            else -> throw IllegalArgumentException("Ugyldig land. $land er ikke en av de gyldige landene for opprettelse av H070")
        }
    }

    private fun hentAlleNorskeIdenter(personhendelse: Personhendelse?): String? {
        val valgtPersonident = personhendelse?.personidenter
            ?.filter { it.length > 10 }
            ?.firstOrNull { ident ->
                try {
                    Ident.bestemIdent(ident)
                    true
                } catch (e: Exception) {
                    logger.debug("Ignorerer ident som ikke kan bestemmes: $ident", e)
                    false
                }
            }
        return valgtPersonident
    }
}
