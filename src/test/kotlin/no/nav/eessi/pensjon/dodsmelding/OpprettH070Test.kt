package no.nav.eessi.pensjon.dodsmelding

import io.mockk.every
import io.mockk.mockk
import no.nav.eessi.pensjon.h070.OpprettH070
import no.nav.eessi.pensjon.personoppslag.pdl.model.*
import no.nav.person.pdl.leesah.Personhendelse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class OpprettH070Test {

    private val opprettH070 = OpprettH070()

    @Test
    fun `Oppretter H070 og sender denne dersom vi får inn dødsmelding fra Sverige`() {
        val pdlPerson = PdlPerson(
            navn = Navn("Lever", "Ikke", "Lenger", metadata = mockMeta()),
            foedselsdato = Foedselsdato(1950,"1950-10-10", folkeregistermetadata = Folkeregistermetadata(), metadata = mockMeta()),
            kjoenn = Kjoenn(KjoennType.KVINNE, metadata = mockMeta()),
            utenlandskIdentifikasjonsnummer = listOf(
                UtenlandskIdentifikasjonsnummer(
                    identifikasjonsnummer = "SE1234567890",
                    utstederland = "SWE",
                    opphoert = false,
                    metadata = mockMeta()
                )
            ),
            identer = listOf(
                IdentInformasjon(
                    "12345678901",
                    IdentGruppe.FOLKEREGISTERIDENT
                )
            ),
            doedsfall = Doedsfall(LocalDate.now(), metadata = mockMeta() ),
            adressebeskyttelse = emptyList(),
            statsborgerskap = emptyList(),
            forelderBarnRelasjon = emptyList(),
            sivilstand = emptyList(),
        )

        val personhendelse = mockk<Personhendelse> {
            every { personidenter } returns listOf("12345678901")
            every { doedsfall } returns no.nav.person.pdl.leesah.doedsfall.Doedsfall(LocalDate.of(2024, 5, 1))
        }

        val response = opprettH070.oppretterH070(personhendelse, pdlPerson)

        assertEquals("2024-05-01", response.hnav?.bruker?.doedsfall?.doedsdato)

    }

    internal fun mockMeta(registrert: LocalDateTime = LocalDateTime.of(2010, 4, 2, 10, 14, 12)) : Metadata {
        return Metadata(
            listOf(
                Endring(
                    "DOLLY",
                    registrert,
                    "Dolly",
                    "FREG",
                    Endringstype.OPPRETT
                )
            ),
            false,
            "FREG",
            "23123123-12312312-123123"
        )
    }

}