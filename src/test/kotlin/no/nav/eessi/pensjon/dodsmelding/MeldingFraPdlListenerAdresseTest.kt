package no.nav.eessi.pensjon.dodsmelding

import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

@Disabled
class MeldingFraPdlListenerAdresseTest {

    @Test
    fun `adressehendelser registreres som kjente uten behandling av doedsfall`() {
        val register = SimpleMeterRegistry()
        Metrics.addRegistry(register)
        try {
            val behandler = mockk<PersonHendelseBehandler>()
            val kvittering = mockk<Acknowledgment>(relaxed = true)
            val listener = MeldingFraPdlListener(behandler, "q2")
            val opplysningstyper = listOf("BOSTEDSADRESSE_V1", "KONTAKTADRESSE_V1", "OPPHOLDSADRESSE_V1")
            val meldinger = opplysningstyper.mapIndexed { indeks, type ->
                val hendelse = mockk<Personhendelse> {
                    every { opplysningstype } returns type
                    every { endringstype } returns Endringstype.OPPRETTET
                    every { hendelseId } returns "12345"
                    every { personidenter } returns listOf("12345678901")
                    every { master } returns "BOSTEDSADRESSE_V1"
                }
                ConsumerRecord("pdl.leesah-v1", 0, indeks.toLong(), "hendelse-$indeks", hendelse)
            }

            listener.mottaLeesahMelding(meldinger, kvittering)

            opplysningstyper.forEach { type ->
                assertEquals(
                    1.0,
                    register.get("personhendelse_kjent_opplysningstype").tag("Navn", type).counter().count()
                )
            }
            verify(exactly = 0) { behandler.behandle(any()) }
            verify(exactly = 1) { kvittering.acknowledge() }
        } finally {
            Metrics.removeRegistry(register)
            register.close()
        }
    }
}
