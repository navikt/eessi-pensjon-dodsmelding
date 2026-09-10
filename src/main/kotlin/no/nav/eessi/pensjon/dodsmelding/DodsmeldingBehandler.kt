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
import no.nav.eessi.pensjon.personoppslag.pdl.model.PdlPersonUtvidet
import no.nav.eessi.pensjon.utils.toJson
import no.nav.person.pdl.leesah.Personhendelse
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime


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


    /**
     * Behandler dødsmelding og vurderer om H070 skal opprettes.
     *
     * Flyt:
     * 1. Mottar dødsmelding.
     * 2. Sjekker ident mot leveattestregisteret.
     * 3. Hvis ikke treff: sjekker Joark etter P6000 med relevant avsenderland.
     * 4. Ved treff i ett av sporene lagres preutfylt H070.
     */
    fun behandle(personhendelse: Personhendelse) {
        val norskIdent = hentNorskIndent(personhendelse)

        if (norskIdent == null) {
            logger.warn("Fant ingen gyldig ident i personidenter: ${personhendelse.personidenter}")
            return
        }

        logger.info("Henter informasjon for ident: ${norskIdent.take(4)}")
        val identFraPdl = Ident.bestemIdent(norskIdent)

        if (lagringsService.finnesDoedsmeldingAlleredeForBruker(identFraPdl.id)) {
            logger.info("Bruker finnes allerede i bucket, avbryter opprettelse av H070")
            return
        }

        val person = personService.hentPersonUtvidet(identFraPdl).also { logger.debug("Henter person: {}", it) }

        val (identFraRegister, land) = lagringsService.finnesDodBrukerILeveAttReg(person?.identer) ?: (null to null)

        val rinaSakId = if (identFraRegister == null) safService.brukerRinasakIdFraJoark(norskIdent) else null

        // Hvis vi ikke finner ident i leveattestregisteret og heller ikke rinaSakId i Joark, avslutter vi prosessen.
        if(identFraRegister == null && rinaSakId == null) {
            logger.info("Fant ingen ident i leveattestregisteret eller rinaSakId i Joark for personhendelse: avslutter")
            return
        }

        person?.let { logPerson(it, identFraRegister, rinaSakId) }

        secureLogger.info("Personhendelse for H070: ${person?.doedsfall?.toJson()}")
        if (person == null) {
            logger.warn("Fant ingen personident")
            return
        }

        val utenlandskAdresse = harAktivUtenlandskAdresse(person, personhendelse.doedsfall.doedsdato)
        if (utenlandskAdresse) {
            logger.info("Bruker har aktiv utenlandsk adresse; avbryter opprettelse av H070")
            return
        }

        val norskAdresse = harAktivNorskAdresse(person, personhendelse.doedsfall.doedsdato)
        if (norskAdresse.not()) {
            logger.info("Bruker har ingen gyldig norsk adresse; avbryter opprettelse av H070")
            return
        }

        val landFraKontaktadresse = hentLandFraKontaktadresse(person)
        if (landFraKontaktadresse !in gyldigeUtstederland) {
            logger.info("Bruker har utenlandsk kontaktadresse, men utstederland ($landFraKontaktadresse) er ikke gyldig for opprettelse av H070")
            return
        }

        val pin = opprettPinListe(person)
        if (pin.none { it.land == "NOR" }) {
            logger.warn("Fant ingen norsk ident; avbryter opprettelse av H070")
            return
        }

        if (identFraRegister != null && land != null) {
            behandleLeveattest(personhendelse, person, pin, identFraPdl, identFraRegister, land)
            return
        }

        behandleJoark(personhendelse, person, pin, identFraPdl, rinaSakId)
    }

    private fun behandleLeveattest(
        personhendelse: Personhendelse,
        person: PdlPersonUtvidet,
        pin: List<PinItem>,
        identFraPdl: Ident,
        identILeveAttReg: String,
        landILeveAttReg: String
    ) {
        require(identILeveAttReg.isNotBlank()) { "Finner ikke fnr i leveattestregisteret" }
        if (landILeveAttReg !in gyldigeUtstederland) {
            logger.info("Bruker finnes i leveattestregisteret, men utstederland ($landILeveAttReg) er ikke gyldig")
            return
        }

        logger.info("Bruker finnes i leveattestregisteret, oppretter H070")
        val fnr = person.identer.firstOrNull { it.gruppe == IdentGruppe.FOLKEREGISTERIDENT }?.ident
        if (fnr == null) {
            logger.warn("Fant ingen norsk ident; avbryter opprettelse av H070")
            return
        }
        val landInstitusjon = institusjon(fnr, landILeveAttReg)
            .also { logger.info("Sender til institusjon: {}", it) }

        lagringsService.lagreFnrForBruker(identFraPdl.id)
        val h070 = opprettH070.preutFyltH070(personhendelse, person, pin)
            .also { secureLogger.info("preutfylt h070 fra LeveAttestReg / edifact: $it, land: $landInstitusjon") }
        lagringsService.lagreH070(h070, H070_LAGRET_PREFIX_EDIFACT)
//        opprettOgSendH070(h070, landInstitusjon).also { logger.info("Oppretter og sender ut H070 til $landILeveAttReg") }
    }

    private fun behandleJoark(
        personhendelse: Personhendelse,
        person: PdlPersonUtvidet,
        pin: List<PinItem>,
        identFraPdl: Ident,
        rinaSakId: String?
    ) {
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
        val landInstitusjon =
            institusjon(identFraPdl.id, mottakerLand).also { logger.info("Sender til institusjon: {}", it) }
        val h070 = opprettH070.preutFyltH070(personhendelse, person, pin)
            .also { secureLogger.info("preutfylt h070 fra Joark/P6000: $it , land:$landInstitusjon") }
        lagringsService.lagreH070(h070, H070_LAGRET_PREFIX_STANDARD)

//        opprettOgSendH070(h070, mottakerLand)
//            .also { logger.info("Oppretter og sender ut H070 for Joark bruker til $mottakerLand") }
        logger.info("I dette tilfellet ville vi opprettet H070 og sendt den ut til $mottakerLand")
        secureLogger.info("PersonHendelse for H070: $personhendelse")

        //TODO: Sjekk hvilken ytelse bruker har før vi går videre med å preutfylle en H070
        //TODO: Sjekk hvilken institusjon som skal legges til ut i fra hvilket land det er som skal motta H070 fra oss.
    }

    private fun logPerson(person: PdlPersonUtvidet, identFraRegister: String?, rinaSakId: String?) {
        if (person.bostedsadresseInklHistoriske != null) {
            logJsonValue("bruker i levattest: ${identFraRegister != null}, joark: $rinaSakId, bostedsadresse for H070") { person.bostedsadresseInklHistoriske }
        }

        if (person.oppholdsadresseInklHistoriske != null) {
            logJsonValue("bruker i levattest: ${identFraRegister != null}, joark: $rinaSakId, kontaktadresse for H070") { person.oppholdsadresseInklHistoriske }
        }

        if (person.kontaktadresseInklHistoriske != null) {
            logJsonValue("bruker i levattest: ${identFraRegister != null}, joark: $rinaSakId, kontaktadresseInklHistoriske for H070") { person.kontaktadresseInklHistoriske }
        }

        logJsonValue("innflytting for H070") { person.innflyttingTilNorge }
        logJsonValue("utflytting for H070") { person.utflyttingFraNorge }
    }

    private fun opprettPinListe(person: PdlPersonUtvidet): List<PinItem> {
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
        secureLogger.info("Institusjoner $fnr,+$landFraIdentUtland")
        val ytelsesInfo =
            pesysKlient.hentPensjonSaklist(fnr).also { logger.info("Henter pensjonsakliste: {}", it.toJson()) }
        val penytelse = ytelsesInfo.firstOrNull { it.sakType in listOf(UFOREP, GJENLEV, BARNEP, ALDER, OMSORG) }

        if (penytelse != null) {
            logger.info("Fant ytelse for bruker: ${penytelse.sakType} med sakId: ${penytelse.sakId}")
        } else {
            logger.info("Fant ingen ytelse for bruker")
        }

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

    /**
     * Sjekker om bruker har en aktiv norsk adresse i PDL.
     * En norsk adresse anses som aktiv dersom gyldigTilOgMed er null eller etter doedsdato minus 2 uker.
     */
    fun harAktivNorskAdresse(person: PdlPersonUtvidet, doedsdato: LocalDate): Boolean {
        val bostedsadresse = person.bostedsadresseInklHistoriske ?: return false.also { logger.info("Bruker har ingen bostedsadresse i PDL") }
        if (bostedsadresse.vegadresse == null) return false
        val gyldigTilOgMed = bostedsadresse.gyldigTilOgMed
        val harAktivNorskAdresse = erGyldigPaaDoedsdato(gyldigTilOgMed, doedsdato)

        logger.info(
            "Har aktiv norsk adresse: Adressevurdering: doedsdato={}, gyldigFraOgMed={}, gyldigTilOgMed={}, harAktivNorskAdresse={}",
            doedsdato,
            bostedsadresse.gyldigFraOgMed,
            gyldigTilOgMed,
            harAktivNorskAdresse
        )
        return harAktivNorskAdresse
    }

    fun harAktivUtenlandskAdresse(person: PdlPersonUtvidet, doedsdato: LocalDate): Boolean {
        val kontaktadresse = person.kontaktadresseInklHistoriske ?: return false.also { logger.info("Bruker har ingen utenlandsk-kontaktadresse i PDL") }

        val gyldigAdresse = erGyldigPaaDoedsdato(kontaktadresse.gyldigTilOgMed, doedsdato)
        val harAdresse = kontaktadresse.utenlandskAdresse != null || kontaktadresse.utenlandskAdresseIFrittFormat != null

        val harAktivUtenlandskAdresse = gyldigAdresse && harAdresse

        logger.info(
            "Har aktiv utenlandsk adresse: Adressevurdering: doedsdato={}, gyldigFraOgMed={}, gyldigTilOgMed={}, gyldigAdresse={}, harAdresse={}",
            doedsdato,
            kontaktadresse.gyldigFraOgMed,
            kontaktadresse.gyldigTilOgMed ,
            gyldigAdresse,
            harAdresse
        )

        return harAktivUtenlandskAdresse
    }

    /**
     * En adresse anses som gyldig på dødsdato dersom gyldigTilOgMed er null,
     * eller ligger etter doedsdato minus 2 uker.
     */
    private fun erGyldigPaaDoedsdato(gyldigTilOgMed: LocalDateTime?, doedsdato: LocalDate): Boolean {
        val toUkerFoerDoedsdato = doedsdato.minusWeeks(2).atStartOfDay()
        return gyldigTilOgMed?.isAfter(toUkerFoerDoedsdato) != false
    }


    private fun hentLandFraKontaktadresse(person: PdlPersonUtvidet): String? {
        val kontaktadresse = person.kontaktadresseInklHistoriske
        val utenlandskAdresse = kontaktadresse?.utenlandskAdresse
        val utenlandskAdresseIFrittFormat = kontaktadresse?.utenlandskAdresseIFrittFormat

        return when {
            utenlandskAdresse != null -> {
                logJsonValue("kontaktadresseInklHistoriske for H070 (utenlandskAdresse)") { utenlandskAdresse }
                utenlandskAdresse.landkode
            }

            utenlandskAdresseIFrittFormat != null -> {
                logJsonValue("kontaktadresseInklHistoriske for H070 (utenlandskAdresseIFrittFormat)") { kontaktadresse }
                utenlandskAdresseIFrittFormat.landkode
            }

            else -> {
                logJsonValue("kontaktadresseInklHistoriske for H070 (ingen utenlandsk adresse)") { kontaktadresse }
                null
            }
        }
    }

    private fun logJsonValue(label: String, value: (() -> Any?)?) {
        try {
            val resolvedValue = value?.invoke()
            secureLogger.info("$label: ${resolvedValue?.toJson() ?: "null"}")
        } catch (_: Exception) {
            secureLogger.info("$label: <not available>")
        }
    }

    private fun hentNorskIndent(personhendelse: Personhendelse?): String? {
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
