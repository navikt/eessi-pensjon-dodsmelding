package no.nav.eessi.pensjon.dodsmelding

import io.mockk.*
import no.nav.eessi.pensjon.eux.EuxService
import no.nav.eessi.pensjon.eux.model.Motparter
import no.nav.eessi.pensjon.gcp.LagringsService
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.personoppslag.pdl.PersonService
import no.nav.eessi.pensjon.personoppslag.pdl.model.*
import no.nav.eessi.pensjon.saf.*
import no.nav.eessi.pensjon.saf.BrukerIdType.FNR
import no.nav.eessi.pensjon.shared.person.Fodselsnummer
import no.nav.eessi.pensjon.utils.mapJsonToAny
import no.nav.eessi.pensjon.utils.toJsonSkipEmpty
import no.nav.person.pdl.leesah.Personhendelse
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpMethod
import org.springframework.http.ResponseEntity
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.exchange
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

const val FNR_OVER_62 = "09035225916"   // SLAPP SKILPADDE

class DodsmeldingBehandlerTest {

    private val safGraphQlOidcRestTemplate: RestTemplate = mockk(relaxed = true)
    private val hentRestUrlRestTemplate: RestTemplate = mockk(relaxed = true)
    private val safClient: SafClient = spyk(SafClient(safGraphQlOidcRestTemplate, hentRestUrlRestTemplate))
    private val safService = SafService(safClient)
    private val personService = mockk<PersonService>()
    private val opprettH070 = mockk<OpprettH070>()
    private val pesysKlient = mockk<PesysKlient>()
    private val euxService = mockk<EuxService>()
    private val lagringsService = mockk<LagringsService>()

    private lateinit var dodsmeldingBehandler: DodsmeldingBehandler

    private fun adresseMock(utenlandskAdresse: UtenlandskAdresse? = null) = mockk<Bostedsadresse>(relaxed = true) {
        every { this@mockk.utenlandskAdresse } returns utenlandskAdresse
        every { gyldigFraOgMed } returns LocalDateTime.now()
        every { gyldigTilOgMed } returns null
        every { vegadresse } returns null
    }

    private fun aktivNorskAdresseMock() = mockk<Bostedsadresse>(relaxed = true) {
        every { gyldigFraOgMed } returns LocalDateTime.now()
        every { gyldigTilOgMed } returns null
        every { vegadresse } returns mockk(relaxed = true)
    }

    private fun metadataMock() = Metadata(emptyList(), false, "FREG", "test")

    private fun personhendelseMock(vararg identer: String): Personhendelse = mockk {
        every { personidenter } returns identer.toList()
        every { doedsfall } returns mockk {
            every { doedsdato } returns LocalDate.now()
        }
    }

    @BeforeEach
    fun setup() {
        clearAllMocks()
        dodsmeldingBehandler = DodsmeldingBehandler(pesysKlient, personService, opprettH070, euxService, safService, lagringsService, "q2")
        every { pesysKlient.hentPensjonSaklist(any()) } returns emptyList()

        // ting som ikke er så viktig akkurat nå
        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { lagringsService.finnesDoedsmeldingAlleredeForBruker(any()) } returns mockk(relaxed = true )
        every { lagringsService.lagreFnrForBruker(any()) } returns mockk(relaxed = true )
        every { euxService.opprettH070(any(), any()) } returns mockk(relaxed = true)
        every { euxService.sendSed(any(), any()) } returns mockk(relaxed = true)
        every { opprettH070.preutFyltH070(any(), any(), any()) } returns mockk(relaxed = true)
        every { lagringsService.lagreH070(any(), any()) } returns mockk(relaxed = true)

        every { personService.hentPerson(any()) } returns createBrukerWith(FNR_OVER_62, "Voksen ", "Forsikret", "SWE", aktorId = "1005094340092")
        every { safClient.hentDokumentMetadata(any(),any()) } returns mockk(relaxed = true) {
            every { data } returns mockk(relaxed = true) {
                every { dokumentoversiktBruker } returns mockk(relaxed = true) {
                    every { journalposter } returns emptyList()
                }
            }
        }
    }

    private fun personUtenAdresser() = PdlPerson(
        identer = listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT)),
        adressebeskyttelse = emptyList(),
        statsborgerskap = emptyList(),
        forelderBarnRelasjon = emptyList(),
        sivilstand = emptyList(),
        utenlandskIdentifikasjonsnummer = emptyList()
    )

    private fun kontaktadresseMedLand(strukturert: String, frittFormat: String?) = Kontaktadresse(
        metadata = metadataMock(),
        type = KontaktadresseType.Utland,
        utenlandskAdresse = UtenlandskAdresse(landkode = strukturert),
        utenlandskAdresseIFrittFormat = UtenlandskAdresseIFrittFormat(landkode = frittFormat)
    )

    private fun bostedsadresseMedLand(land: String) = Bostedsadresse(
        utenlandskAdresse = UtenlandskAdresse(landkode = land),
        metadata = metadataMock()
    )

    @Test
    fun `hentLandFraAdresser samler begge kontaktadresseformater og alle valgte adressekilder`() {
        val person = PdlPersonUtvidet(
            pdlPerson = personUtenAdresser().copy(
                kontaktadresse = kontaktadresseMedLand("SWE", "FIN"),
                bostedsadresse = bostedsadresseMedLand("POL"),
                oppholdsadresse = bostedsadresseMedLand("DNK"),
                geografiskTilknytning = GeografiskTilknytning(gtType = GtType.UTLAND, gtLand = "DEU")
            ),
            kontaktadresseInklHistoriske = kontaktadresseMedLand("FRA", "ESP"),
            bostedsadresseInklHistoriske = bostedsadresseMedLand("ITA"),
            oppholdsadresseInklHistoriske = bostedsadresseMedLand("PRT")
        )
        val personVanlig = personUtenAdresser().copy(
            kontaktadresse = kontaktadresseMedLand("GBR", "IRL"),
            bostedsadresse = bostedsadresseMedLand("NLD"),
            oppholdsadresse = bostedsadresseMedLand("BEL"),
            geografiskTilknytning = GeografiskTilknytning(gtType = GtType.UTLAND, gtLand = "AUT")
        )

        val resultat = dodsmeldingBehandler.hentLandFraAdresser(person, personVanlig)

        assertEquals(
            mapOf(
                "SWE" to setOf("person.kontaktadresse.utenlandskAdresse.landkode"),
                "FIN" to setOf("person.kontaktadresse.utenlandskAdresseIFrittFormat.landkode"),
                "POL" to setOf("person.bostedsadresse.utenlandskAdresse.landkode"),
                "DNK" to setOf("person.oppholdsadresse.utenlandskAdresse.landkode"),
                "DEU" to setOf("person.geografiskTilknytning.gtLand"),
                "FRA" to setOf("person.kontaktadresseInklHistoriske.utenlandskAdresse.landkode"),
                "ESP" to setOf("person.kontaktadresseInklHistoriske.utenlandskAdresseIFrittFormat.landkode"),
                "ITA" to setOf("person.bostedsadresseInklHistoriske.utenlandskAdresse.landkode"),
                "PRT" to setOf("person.oppholdsadresseInklHistoriske.utenlandskAdresse.landkode"),
                "GBR" to setOf("personVanlig.kontaktadresse.utenlandskAdresse.landkode"),
                "IRL" to setOf("personVanlig.kontaktadresse.utenlandskAdresseIFrittFormat.landkode"),
                "NLD" to setOf("personVanlig.bostedsadresse.utenlandskAdresse.landkode"),
                "BEL" to setOf("personVanlig.oppholdsadresse.utenlandskAdresse.landkode"),
                "AUT" to setOf("personVanlig.geografiskTilknytning.gtLand")
            ),
            resultat
        )
    }

    @Test
    fun `hentLandFraAdresser normaliserer landkoder og beholder alle kilder til samme land`() {
        val person = PdlPersonUtvidet(
            pdlPerson = personUtenAdresser().copy(
                kontaktadresse = kontaktadresseMedLand(" fin ", "FIN"),
                oppholdsadresse = bostedsadresseMedLand("FI")
            ),
            kontaktadresseInklHistoriske = kontaktadresseMedLand("fin", " \t "),
            bostedsadresseInklHistoriske = bostedsadresseMedLand("")
        )

        val resultat = dodsmeldingBehandler.hentLandFraAdresser(person, null)

        assertEquals(
            mapOf(
                "FIN" to setOf(
                    "person.kontaktadresse.utenlandskAdresse.landkode",
                    "person.kontaktadresse.utenlandskAdresseIFrittFormat.landkode",
                    "person.kontaktadresseInklHistoriske.utenlandskAdresse.landkode"
                ),
                "FI" to setOf("person.oppholdsadresse.utenlandskAdresse.landkode")
            ),
            resultat
        )
    }

    @Test
    fun `hentLandFraAdresser returnerer tomt resultat uten eksplisitte landkoder`() {
        val person = PdlPersonUtvidet(
            pdlPerson = personUtenAdresser().copy(
                bostedsadresse = Bostedsadresse(vegadresse = Vegadresse(), metadata = metadataMock()),
                kontaktadresse = Kontaktadresse(
                    type = KontaktadresseType.Utland,
                    metadata = metadataMock(),
                    utenlandskAdresseIFrittFormat = UtenlandskAdresseIFrittFormat()
                )
            )
        )

        assertEquals(emptyMap<String, Set<String>>(), dodsmeldingBehandler.hentLandFraAdresser(person, null))
    }

    @ParameterizedTest
    @CsvSource("DEU, FIN, false", "FIN, DEU, true", "'', FIN, false")
    fun `behandle beholder kontaktadressekontrollen uavhengig av innsamlede land`(
        historiskLand: String,
        gjeldendeLand: String,
        skalOpprette: Boolean
    ) {
        val grunnperson = personUtenAdresser().copy(
            bostedsadresse = Bostedsadresse(vegadresse = Vegadresse(), metadata = metadataMock()),
            kontaktadresse = kontaktadresseMedLand(gjeldendeLand, "FIN")
        )
        val person = PdlPersonUtvidet(
            pdlPerson = grunnperson,
            kontaktadresseInklHistoriske = kontaktadresseMedLand(historiskLand, "FIN")
        )
        every { personService.hentPersonUtvidet(any()) } returns person
        every { personService.hentPerson(any()) } returns grunnperson
        every { lagringsService.finnesDoedsmeldingAlleredeForBruker(any()) } returns false

        dodsmeldingBehandler.behandle(personhendelseMock("12345678901"))

        verify(exactly = if (skalOpprette) 1 else 0) { opprettH070.preutFyltH070(any(), any(), any()) }
        verify(exactly = if (skalOpprette) 1 else 0) { lagringsService.lagreH070(any(), any()) }
    }

    @Test
    fun `harAktivNorskAdresse er true naar gyldigTilOgMed er nyere enn to uker foer doedsdato`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val bostedsadresse = mockk<Bostedsadresse>(relaxed = true) {
            every { vegadresse } returns mockk(relaxed = true)
            every { gyldigTilOgMed } returns doedsdato.minusDays(13).atStartOfDay()
        }
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { bostedsadresseInklHistoriske } returns bostedsadresse
        }

        val resultat = dodsmeldingBehandler.harAktivNorskAdresse(person, null, doedsdato)

        assertTrue(resultat)
    }

    @Test
    fun `harAktivNorskAdresse er false naar gyldigTilOgMed er eldre enn to uker foer doedsdato`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val bostedsadresse = mockk<Bostedsadresse>(relaxed = true) {
            every { vegadresse } returns mockk(relaxed = true)
            every { gyldigTilOgMed } returns doedsdato.minusDays(15).atStartOfDay()
        }
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { bostedsadresseInklHistoriske } returns bostedsadresse
        }

        val resultat = dodsmeldingBehandler.harAktivNorskAdresse(person, null, doedsdato)

        assertFalse(resultat)
    }

    @Test
    fun `harAktivUtenlandskAdresse er false naar adresse er utgaatt og ingen utenlandsk adresse finnes`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { gyldigTilOgMed } returns doedsdato.minusDays(15).atStartOfDay()
                every { utenlandskAdresse } returns null
                every { utenlandskAdresseIFrittFormat } returns null
            }
        }

        val resultat = dodsmeldingBehandler.harAktivUtenlandskAdresse(person,mockk(relaxed = true), doedsdato, null)

        assertFalse(resultat)
    }

    @Test
    fun `harAktivUtenlandskAdresse er true naar adresse er gyldig og har utenlandsk adresse`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { gyldigTilOgMed } returns doedsdato.minusDays(13).atStartOfDay()
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
        }

        val personPdlEnkel = mockk<PdlPerson>(relaxed = true) {
            every { kontaktadresse } returns null
        }
        val resultat = dodsmeldingBehandler.harAktivUtenlandskAdresse(person, personPdlEnkel, doedsdato, null)

        assertTrue(resultat)
    }

    @Test
    fun `harAktivUtenlandskAdresse er true naar gyldigTilOgMed er null`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { gyldigTilOgMed } returns null
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
        }

        val personPdlEnkel = mockk<PdlPerson>(relaxed = true) {
            every { kontaktadresse } returns null
        }
        val resultat = dodsmeldingBehandler.harAktivUtenlandskAdresse(person, personPdlEnkel, doedsdato, null)

        assertTrue(resultat)
    }

    @Test
    fun `harAktivUtenlandskAdresse er false naar utenlandskAdresse sin gyldigTilOgMed er utgaatt`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { gyldigTilOgMed } returns LocalDateTime.of(2025, 9, 1, 0, 0)
                every { utenlandskAdresse } returns mockk(relaxed = true)
                every { utenlandskAdresseIFrittFormat } returns null
            }
        }

        val personPdlEnkel = mockk<PdlPerson>(relaxed = true) {
            every { kontaktadresse } returns null
        }
        val resultat = dodsmeldingBehandler.harAktivUtenlandskAdresse(person, personPdlEnkel, doedsdato, null)

        assertFalse(resultat)
    }

    @Test
    fun `harAktivUtenlandskAdresse flagger uventet naar identFraRegister finnes men ingen aktiv utenlandsk adresse`() {
        val doedsdato = LocalDate.of(2026, 9, 1)
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { kontaktadresseInklHistoriske } returns null
        }

        val resultat = dodsmeldingBehandler.harAktivUtenlandskAdresse(person,mockk(relaxed = true), doedsdato, "12345678910")

        assertFalse(resultat)
    }

    @ParameterizedTest(name = "erNorskAdresseNyereEnnUtenlandsk(norsk={0}, utenlandsk={1}, adresse={2}) = {4}")
    @CsvSource(
        "2026-01-01, 2020-01-01, UTLAND, true, true",
        "2020-01-01, 2026-01-01, UTLAND, true, false",
        "2020-01-01, 2020-01-01, UTLAND, true, true",
        "2020-01-01, 2020-01-01, null, true, true",
        "2020-01-01, NULL, UTLAND, true, true",
        "NULL, 2020-01-01, UTLAND, true, true",
        "NULL, NULL, INNLAND, true, true",
        "NULL, NULL, UTLAND_UTEN_ADRESSE, true, true",
        "NULL, NULL, UTLAND, false, true",
        nullValues = ["NULL"]
    )
    fun `erNorskAdresseNyereEnnUtenlandsk gir forventet resultat`(
        norskGyldigFraOgMed: String?,
        utenlandskGyldigFraOgMed: String?,
        kontaktadresseType: String,
        harKontaktadresse: Boolean,
        forventet: Boolean
    ) {
        val person = mockk<PdlPersonUtvidet>(relaxed = true) {
            every { bostedsadresseInklHistoriske } returns norskGyldigFraOgMed?.let { dato ->
                mockk(relaxed = true) { every { gyldigFraOgMed } returns LocalDate.parse(dato).atStartOfDay() }
            }
            every { kontaktadresseInklHistoriske } returns if (harKontaktadresse) {
                mockk(relaxed = true) {
                    every { type } returns if (kontaktadresseType == "INNLAND") {
                        KontaktadresseType.Innland
                    } else {
                        KontaktadresseType.Utland
                    }
                    every { gyldigFraOgMed } returns utenlandskGyldigFraOgMed?.let { dato ->
                        LocalDate.parse(dato).atStartOfDay()
                    }
                    every { utenlandskAdresse } returns if (kontaktadresseType == "UTLAND") {
                        mockk(relaxed = true)
                    } else {
                        null
                    }
                }
            } else {
                null
            }
        }

        val resultat = dodsmeldingBehandler.erNorskAdresseNyereEnnUtenlandsk(person, null)

        assertEquals(forventet, resultat)
    }

    @Test
    fun `behandle returnerer tidlig naar personhendelse har tom liste med identer`() {
        val personhendelse = personhendelseMock()

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 0) { personService.hentPerson(any()) }
        verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle sjekker Joark for eksisterende H070 ved treff i leveattestregister uten utenlandsk identifikasjonsnummer`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns emptyList()
            every { identer } returns emptyList()
            every { bostedsadresse } returns adresseMock(mockk(relaxed = true))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresseInklHistoriske } returns null
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        verify(exactly = 1) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle henter ikke dokumentmetadata naar person er null`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns null
        every { personService.hentPerson(ident) } returns null
        every { safClient.hentDokumentMetadata(any(), any()) } returns mockk(relaxed = true )
        every { lagringsService.finnesDoedsmeldingAlleredeForBruker(any()) } returns mockk(relaxed = true )


        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        //verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle oppretter ikke H070 naar utenlandsk kontaktadresse ikke er gyldig utstederland`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                UtenlandskIdentifikasjonsnummer(
                    identifikasjonsnummer = "SE1234567890",
                    utstederland = "DEU",
                    opphoert = false,
                    metadata = metadataMock()
                )
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { bostedsadresse } returns null
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "DEU"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresseInklHistoriske } returns null
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()
        }

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        verify(exactly = 1) { safClient.hentDokumentMetadata(any(), any()) }
        verify(exactly = 0) { opprettH070.preutFyltH070(any(), any(), any()) }
    }

    @Disabled
    @ParameterizedTest
    @CsvSource("SWE", "FIN", "POL")
    fun `behandle henter dokumentmetadata naar utstederland er `(land: String) {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk { every { utstederland } returns land }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
        }
        every { safClient.hentDokumentMetadata("12345678901", FNR) } returns mockk {
            every { data } returns mockk {
                every { dokumentoversiktBruker } returns mockk {
                    every { journalposter } returns emptyList()
                }
            }
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { safClient.hentDokumentMetadata("12345678901", FNR) }
    }

    @Test
    fun `behandle henter dokumentmetadata skal fungere uten tema`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { bostedsadresse } returns null
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresseInklHistoriske } returns null
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()
        }

        every {
            safGraphQlOidcRestTemplate.exchange("", HttpMethod.POST, any(), String::class.java)
        } returns ResponseEntity(
            """
                {
                  "data": {
                    "dokumentoversiktBruker": {
                      "journalposter": [
                        {
                          "tilleggsopplysninger": [
                            {
                              "nokkel": "eessi_pensjon_bucid",
                              "verdi": "1455350"
                            }
                          ],
                          "journalpostId": "454102392",
                          "datoOpprettet": "2026-03-25T11:15:48",
                          "tittel": "Inngående P6000 - Melding om vedtak",
                          "journalfoerendeEnhet": "4476",
                          "behandlingstema": "ab0254",
                          "dokumenter": [
                            {
                              "dokumentInfoId": "454528669",
                              "tittel": "P6000 - Melding om vedtak.pdf",
                              "dokumentvarianter": [
                                {
                                  "filnavn": null,
                                  "variantformat": "ARKIV"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
            """.trimIndent(),
            org.springframework.http.HttpStatus.OK
        )

        val dummyResource: Resource = ByteArrayResource("dummy".toByteArray())
        every {
            hentRestUrlRestTemplate.exchange<Resource>(
                any<String>(),
                HttpMethod.GET,
                any()
            )
        } returns ResponseEntity(dummyResource, org.springframework.http.HttpStatus.OK)

        every { opprettH070.preutFyltH070(any(), any(), any()) } returns mockk(relaxed = true)

        dodsmeldingBehandler.behandle(personhendelse)

//        verify(exactly = 1) { safClient.hentDokumentMetadata("12345678901", FNR) }
    }

    @Test
    fun `behandle henter dokumentmetadata naar minst ett utstederland er gyldig blant flere`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "DEU"
                    every { identifikasjonsnummer } returns "DE1234567890"
                },
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { bostedsadresse } returns adresseMock(mockk(relaxed = true))
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresseInklHistoriske } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
        }
        every { safClient.hentDokumentMetadata("12345678901", FNR) } returns mockk {
            every { data } returns mockk {
                every { dokumentoversiktBruker } returns mockk {
                    every { journalposter } returns emptyList()
                }
            }
        }

        dodsmeldingBehandler.behandle(personhendelse)

//        verify(exactly = 1) { safClient.hentDokumentMetadata("12345678901", FNR) }
    }

    @Test
    fun `behandle henter dokumentinnhold for journalposter med dokumenter`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { bostedsadresse } returns adresseMock(mockk(relaxed = true))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
        }
        every { safClient.hentDokumentMetadata("12345678901", FNR) } returns mockk {
            every { data } returns mockk {
                every { dokumentoversiktBruker } returns mockk {
                    every { journalposter } returns listOf(
                        mockk {
                            every { journalpostId } returns "123456"
                            every { datoOpprettet } returns "2026-01-01"
                            every { tittel } returns "Test dokument"
                            every { tilleggsopplysninger } returns emptyList()
                            every { dokumenter } returns listOf(
                                mockk { every { dokumentInfoId } returns "dok123" }
                            )
                        }
                    )
                }
            }
        }

        every { safClient.hentDokumentInnhold(any(), any(), any()) } returns HentdokumentInnholdResponse(
            filInnhold = "",
            fileName = "test.pdf",
            contentType = "application/pdf"
        )

        every { opprettH070.preutFyltH070(any(), any(), any()) } returns mockk(relaxed = true)

        dodsmeldingBehandler.behandle(personhendelse)

//        verify(exactly = 1) { safClient.hentDokumentInnhold("123456", "dok123", "ARKIV") }
    }

    @Test
    fun `behandle henter ikke dokumentinnhold naar journalpost har ingen dokumenter`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { bostedsadresse } returns adresseMock(mockk(relaxed = true))
            every { kontaktadresseInklHistoriske } returns null
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
        }
        every { safClient.hentDokumentMetadata("12345678901", FNR) } returns mockk {
            every { data } returns mockk {
                every { dokumentoversiktBruker } returns mockk {
                    every { journalposter } returns listOf(
                        mockk {
                            every { journalpostId } returns "123456"
                            every { datoOpprettet } returns "2026-01-01"
                            every { tittel } returns "Test dokument"
                            every { tilleggsopplysninger } returns emptyList()
                            every { dokumenter } returns null
                        }
                    )
                }
            }
        }

        every { opprettH070.preutFyltH070(any(), any(), any()) } returns mockk(relaxed = true)

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 0) { safClient.hentDokumentInnhold(any(), any(), any()) }
    }

    @Test
    fun `behandle henter ikke dokumentinnhold naar dokumenter har tom liste`() {
        val personhendelse = personhendelseMock("12345678901")
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }            )
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
            every { kontaktadresseInklHistoriske } returns null
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresseInklHistoriske } returns null
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()

        }
        every { safClient.hentDokumentMetadata("12345678901", FNR) } returns mockk {
            every { data } returns mockk {
                every { dokumentoversiktBruker } returns mockk {
                    every { journalposter } returns listOf(
                        mockk {
                            every { journalpostId } returns "123456"
                            every { datoOpprettet } returns "2026-01-01"
                            every { tittel } returns "Test dokument"
                            every { tilleggsopplysninger } returns emptyList()
                            every { dokumenter } returns emptyList()
                        }
                    )
                }
            }
        }
        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { opprettH070.preutFyltH070(any(), any(), any()) } returns mockk(relaxed = true)

        dodsmeldingBehandler.behandle(personhendelse)

//        verify(exactly = 0) { safClient.hentDokumentInnhold(any(), any(), any()) }
    }

    @Test
    fun `behandle prioriterer folkeregisterident foran aktorid`() {
        val personhendelse = personhendelseMock("1000016953359", "ugyldig", "12345678901", "98765432100")
        val ident = Ident.bestemIdent("12345678901")

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns emptyList()
            every { identer } returns listOf(
                IdentInformasjon("ugyldig", IdentGruppe.FOLKEREGISTERIDENT),
                IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT),
                IdentInformasjon("98765432100", IdentGruppe.FOLKEREGISTERIDENT))
            every { bostedsadresse } returns adresseMock(mockk(relaxed = true))
            every { kontaktadresseInklHistoriske } returns null
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
    }

    @Test
    fun `Nar det kommer inn er dodsmelding pa pdl koen saa skal det sjekkes om den ligger i bucket Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val personhendelse = personhendelseMock(norskIdent)
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = metadataMock())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { gyldigTilOgMed } returns LocalDateTime.of(2025, 9, 1, 0, 0)
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresse } returns null
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { utenlandskIdentifikasjonsnummer} returns emptyList()
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        verify(exactly = 1) { opprettH070.preutFyltH070(personhendelse, any(), any()) }
//        verify(exactly = 1) { euxService.sendSed(any(), any()) }
    }

    @Test
    fun `Nar det kommer inn er dodsmelding pa pdl koen saa skal det sjekkes om den finnes i buvket eller joark Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val personhendelse = personhendelseMock(norskIdent)
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair(norskIdent, "FI")
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { bostedsadresse } returns null
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = metadataMock())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        verify(exactly = 1) { opprettH070.preutFyltH070(personhendelse, any(), any()) }
//        verify(exactly = 1) { euxService.sendSed(any(), any()) }
    }

    @Test
    fun `Nar det kommer inn er dodsmelding pa pdl koen saa skal det sjekkes om den finnes joark Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val bucid = "1455350"
        val personhendelse = personhendelseMock(norskIdent)
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns null
        every { personService.hentPersonUtvidet(ident) } returns mockk(relaxed = true) {
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = metadataMock())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
            every { kontaktadresseInklHistoriske } returns mockk(relaxed = true) {
                every { utenlandskAdresse } returns mockk(relaxed = true) {
                    every { landkode } returns "FIN"
                }
                every { utenlandskAdresseIFrittFormat } returns null
            }
            every { oppholdsadresseInklHistoriske } returns null
            every { bostedsadresse?.utenlandskAdresse } returns null
            every { doedsfall } returns null
            every { oppholdsadresse } returns mockk(relaxed = true)
            every { kontaktadresse } returns mockk(relaxed = true)
            every { geografiskTilknytning } returns mockk(relaxed = true)
            every { innflyttingTilNorge} returns mockk(relaxed = true)
            every { utflyttingFraNorge } returns mockk(relaxed = true)
            every { bostedsadresseInklHistoriske } returns aktivNorskAdresseMock()
        }

        val tilleggsopplysninger = """
        {
          "tilleggsopplysninger": [
            {
              "nokkel": "eessi_pensjon_bucid",
              "verdi": "$bucid"
            }
          ],
          "journalpostId": "454102392",
          "datoOpprettet": "2026-03-25T11:15:48",
          "tittel": "Inngående P6000 - Melding om vedtak",
          "journalfoerendeEnhet": "4476",
          "behandlingstema": "ab0254",
          "dokumenter": [
            {
              "dokumentInfoId": "454528669",
              "tittel": "P6000 - Melding om vedtak.pdf",
              "dokumentvarianter": [
                {
                  "filnavn": null,
                  "variantformat": "ARKIV"
                }
              ]
            }
          ]
        }
        """.trimIndent()
        val bla = mapJsonToAny<Journalpost>(tilleggsopplysninger)

        every { safClient.hentDokumentMetadata(any(), any()) } returns HentMetadataResponse(data = Data(
            DokumentoversiktBruker(listOf(bla),
        )))
        every { euxService.hentAvsenderLand(bucid) } returns listOf(Motparter(motpartId = "123456", motpartLand = "FI", motpartLandkode = "FI"))

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPersonUtvidet(ident) }
        verify(exactly = 1) { euxService.hentAvsenderLand(bucid) }
        verify(exactly = 1) { opprettH070.preutFyltH070(personhendelse, any(), any()) }
        //TODO: kan kommenteres inn etter prodsetting av sende ut H070 sed
//        verify(exactly = 1) { euxService.sendSed(any(), any()) }
    }

    @Test
    @Disabled
    fun `brukerRinasakIdFraJoark henter bucid fra tilleggsopplysninger`() {
        val norskIdent = "12345678901"
        val bucid = "1455350"

        val journalpost = Journalpost(
            tilleggsopplysninger = listOf(mapOf("nokkel" to "eessi_pensjon_bucid", "verdi" to bucid)),
            journalpostId = "454102392",
            datoOpprettet = "2026-03-25T11:15:48",
            tittel = "Inngående P6000 - Melding om vedtak",
            journalfoerendeEnhet = "4476",
            dokumenter = listOf(
                Dokument(
                    dokumentInfoId = "454528669",
                    tittel = "P6000 - Melding om vedtak.pdf",
                    dokumentvarianter = emptyList()
                )
            )
        )

        every { safClient.hentDokumentMetadata(norskIdent, FNR) } returns HentMetadataResponse(
            data = Data(DokumentoversiktBruker(listOf(journalpost)))
        )

        val method = DodsmeldingBehandler::class.java
            .getDeclaredMethod("brukerRinasakIdFraJoark", String::class.java)
            .apply { isAccessible = true }

        val result = method.invoke(dodsmeldingBehandler, norskIdent) as String?

        assertEquals(bucid, result)
    }


    fun createBrukerWith(
        fnr: String?,
        fornavn: String = "Fornavn",
        etternavn: String = "Etternavn",
        land: String? = "NOR",
        geo: String = "1234",
        harAdressebeskyttelse: Boolean = false,
        aktorId: String? = null
    ): PdlPerson {

        val foedselsdato  = if(Fodselsnummer.fra(fnr)?.erNpid == true)
            Foedselsdato(foedselsdato = "1988-07-12", metadata = mockk(relaxed = true)).also { println("XXX" + it.toJsonSkipEmpty()) }
        else
            fnr?.let {
                Foedselsdato(foedselsdato = Fodselsnummer.fra(it)?.getBirthDate()?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")), metadata = mockk(relaxed = true)).also { println("XXX" + it.toJsonSkipEmpty()) }
            }

        val utenlandskadresse = if (land == null || land == "NOR") null else UtenlandskAdresse(landkode = land)

        val identer = listOfNotNull(
            fnr?.let { IdentInformasjon(ident = it, gruppe = IdentGruppe.FOLKEREGISTERIDENT) },
            aktorId?.let { IdentInformasjon(ident = it, gruppe = IdentGruppe.AKTORID) }
        )

        val adressebeskyttelse = if (harAdressebeskyttelse) listOf(AdressebeskyttelseGradering.STRENGT_FORTROLIG)
        else listOf(AdressebeskyttelseGradering.UGRADERT)

        val metadata = Metadata(
            listOf(
                Endring(
                    "kilde",
                    LocalDateTime.now(),
                    "ole",
                    "system1",
                    Endringstype.OPPRETT
                )
            ),
            false,
            "nav",
            "1234"
        )

        return PdlPerson(
            identer = identer,
            navn = Navn(
                fornavn = fornavn, etternavn = etternavn, metadata = metadata
            ),
            adressebeskyttelse = adressebeskyttelse,
            bostedsadresse = Bostedsadresse(
                gyldigFraOgMed = LocalDateTime.now(),
                gyldigTilOgMed = LocalDateTime.now(),
                vegadresse = Vegadresse("Oppoverbakken", "66", null, "1920"),
                utenlandskAdresse = utenlandskadresse,
                metadata
            ),
            oppholdsadresse = null,
            statsborgerskap = emptyList(),
            foedselsdato = foedselsdato,
            foedested = Foedested(foedeland = "NOR", foedested = "OSLO", metadata = metadata),
            geografiskTilknytning = GeografiskTilknytning(GtType.KOMMUNE, geo),
            kjoenn = Kjoenn(KjoennType.KVINNE, metadata = metadata),
            doedsfall = null,
            forelderBarnRelasjon = emptyList(),
            sivilstand = emptyList(),
            utenlandskIdentifikasjonsnummer = emptyList()
        )
    }
}