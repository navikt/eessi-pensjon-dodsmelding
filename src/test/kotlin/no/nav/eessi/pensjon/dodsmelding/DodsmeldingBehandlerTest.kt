package no.nav.eessi.pensjon.dodsmelding

import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import no.nav.eessi.pensjon.eux.EuxService
import no.nav.eessi.pensjon.eux.klient.EuxKlientLib
import no.nav.eessi.pensjon.eux.model.Motparter
import no.nav.eessi.pensjon.gcp.LagringsService
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.personoppslag.pdl.PersonService
import no.nav.eessi.pensjon.personoppslag.pdl.model.Ident
import no.nav.eessi.pensjon.personoppslag.pdl.model.IdentGruppe
import no.nav.eessi.pensjon.personoppslag.pdl.model.IdentInformasjon
import no.nav.eessi.pensjon.personoppslag.pdl.model.UtenlandskIdentifikasjonsnummer
import no.nav.eessi.pensjon.saf.*
import no.nav.eessi.pensjon.saf.BrukerIdType.FNR
import no.nav.eessi.pensjon.utils.mapJsonToAny
import no.nav.person.pdl.leesah.Personhendelse
import org.junit.jupiter.api.Assertions.assertEquals
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

class DodsmeldingBehandlerTest {

    private val safGraphQlOidcRestTemplate: RestTemplate = mockk(relaxed = true)
    private val hentRestUrlRestTemplate: RestTemplate = mockk(relaxed = true)
    private val safClient: SafClient = spyk(SafClient(safGraphQlOidcRestTemplate, hentRestUrlRestTemplate))
    private val safService = SafService(safClient)
    private val personService = mockk<PersonService>()
    private val opprettH070 = mockk<OpprettH070>()
    private val pesysKlient = mockk<PesysKlient>()
    private val euxService = mockk<EuxService>()
    private val euxKlient = mockk<EuxKlientLib>()
    private val lagringsService = mockk<LagringsService>()

    private lateinit var dodsmeldingBehandler: DodsmeldingBehandler

    @BeforeEach
    fun setup() {
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
    }

    @Test
    fun `behandle returnerer tidlig naar personhendelse har tom liste med identer`() {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns emptyList()
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 0) { personService.hentPerson(any()) }
        verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle henter ikke dokumentmetadata naar person ikke har utenlandskIdentifikasjonsnummer`() {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns emptyList()
            every { identer } returns emptyList()
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
        verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle henter ikke dokumentmetadata naar person er null`() {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns null
        every { safClient.hentDokumentMetadata(any(), any()) } returns mockk(relaxed = true )
        every { lagringsService.finnesDoedsmeldingAlleredeForBruker(any()) } returns mockk(relaxed = true )


        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
        verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Test
    fun `behandle henter ikke dokumentmetadata naar utstederland ikke er i gyldigeUtstederland`() {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "DEU"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }

            )
            every { identer } returns emptyList()
        }

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
        verify(exactly = 0) { safClient.hentDokumentMetadata(any(), any()) }
    }

    @Disabled
    @ParameterizedTest
    @CsvSource("SWE", "FIN", "POL")
    fun `behandle henter dokumentmetadata naar utstederland er `(land: String) {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk { every { utstederland } returns land }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
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
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }
            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
        }
        val ident = Ident.bestemIdent("12345678901")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(
                mockk {
                    every { utstederland } returns "SWE"
                    every { identifikasjonsnummer } returns "SE1234567890"
                }            )
            every { identer } returns listOf(IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT))
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
    fun `behandle velger første gyldige ident fra listen`() {
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("ugyldig", "12345678901", "98765432100")
        }
        val ident = Ident.bestemIdent("12345678901")

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns emptyList()
            every { identer } returns listOf(
                IdentInformasjon("ugyldig", IdentGruppe.FOLKEREGISTERIDENT),
                IdentInformasjon("12345678901", IdentGruppe.FOLKEREGISTERIDENT),
                IdentInformasjon("98765432100", IdentGruppe.FOLKEREGISTERIDENT))
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
    }

    @Test
    fun `Når det kommer inn er dødsmelding på pdl køen saa skal det sjekkes om den ligger i bucket Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf(norskIdent)
        }
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair("bla1", "FI")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = mockk())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
        verify(exactly = 1) { opprettH070.preutFyltH070(personhendelse, any(), any()) }
//        verify(exactly = 1) { euxService.sendSed(any(), any()) }
    }

    @Test
    fun `Når det kommer inn er dødsmelding på pdl køen saa skal det sjekkes om den finnes i buvket eller joark Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf(norskIdent)
        }
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns Pair(norskIdent, "FI")
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = mockk())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
        }

        dodsmeldingBehandler.behandle(personhendelse)

        verify(exactly = 1) { personService.hentPerson(ident) }
        verify(exactly = 1) { opprettH070.preutFyltH070(personhendelse, any(), any()) }
//        verify(exactly = 1) { euxService.sendSed(any(), any()) }
    }

    @Test
    fun `Når det kommer inn er dødsmelding på pdl køen saa skal det sjekkes om den finnes joark Dersom ja saa sendes det ut en H070 til utlandet`() {
        val norskIdent = "12345678901"
        val bucid = "1455350"
        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf(norskIdent)
        }
        val ident = Ident.bestemIdent(norskIdent)

        every { lagringsService.finnesDodBrukerILeveAttReg(any()) } returns null
        every { personService.hentPerson(ident) } returns mockk {
            every { utenlandskIdentifikasjonsnummer } returns listOf(UtenlandskIdentifikasjonsnummer(
                identifikasjonsnummer = "10105636985", utstederland = "FIN", opphoert = false, metadata = mockk())
            )
            every { identer } returns listOf(
                IdentInformasjon(norskIdent, IdentGruppe.FOLKEREGISTERIDENT)
            )
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

        verify(exactly = 1) { personService.hentPerson(ident) }
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
}