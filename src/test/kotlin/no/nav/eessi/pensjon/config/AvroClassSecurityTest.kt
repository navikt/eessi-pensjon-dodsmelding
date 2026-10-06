package no.nav.eessi.pensjon.config

import no.nav.person.identhendelse.v1.common.KjoennType
import no.nav.person.identhendelse.v1.common.Personnavn
import no.nav.person.identhendelse.v1.common.RelatertBiPerson
import no.nav.person.pdl.leesah.Endringstype
import no.nav.person.pdl.leesah.Personhendelse
import no.nav.person.pdl.leesah.doedsfall.Doedsfall
import org.apache.avro.data.TimeConversions
import org.apache.avro.io.DecoderFactory
import org.apache.avro.io.EncoderFactory
import org.apache.avro.specific.SpecificData
import org.apache.avro.specific.SpecificDatumReader
import org.apache.avro.specific.SpecificDatumWriter
import org.apache.avro.util.ClassSecurityValidator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate

@ResourceLock("AvroClassSecurityValidator")
class AvroClassSecurityTest {
    private lateinit var originalValidator: ClassSecurityValidator.ClassSecurityPredicate

    @BeforeEach
    fun saveValidator() {
        originalValidator = ClassSecurityValidator.getGlobal()
        ClassSecurityValidator.setGlobal(ClassSecurityValidator.DEFAULT_TRUSTED_CLASSES)
    }

    @AfterEach
    fun restoreValidator() {
        ClassSecurityValidator.setGlobal(originalValidator)
    }

    @Test
    fun `vurderer Leesah records, enums og subpackages, men ikke urelaterte classes`() {
        assertThrows(SecurityException::class.java) {
            ClassSecurityValidator.validate(Personhendelse::class.java)
        }

        AvroSecurityConfig.configureAvroClassSecurityForPdlPersonhendelse()

        val validator = ClassSecurityValidator.getGlobal()
        assertTrue(validator.isTrusted(Personhendelse::class.java))
        assertTrue(validator.isTrusted(Endringstype::class.java))
        assertTrue(validator.isTrusted(Doedsfall::class.java))
        assertTrue(validator.isTrusted(Personnavn::class.java))
        assertTrue(validator.isTrusted(RelatertBiPerson::class.java))
        assertTrue(validator.isTrusted(KjoennType::class.java))
        assertTrue(validator.isTrusted(String::class.java))
        assertFalse(validator.isTrusted(AvroClassSecurityTest::class.java))
        assertThrows(SecurityException::class.java) {
            ClassSecurityValidator.validate(AvroClassSecurityTest::class.java)
        }
    }

    @Test
    fun `bevarer eksisterende tilpassede tillitsregler`() {
        ClassSecurityValidator.setGlobal(
            ClassSecurityValidator.builder().add(AvroClassSecurityTest::class.java).build()
        )

        AvroSecurityConfig.configureAvroClassSecurityForPdlPersonhendelse()

        assertTrue(ClassSecurityValidator.getGlobal().isTrusted(AvroClassSecurityTest::class.java))
        assertTrue(ClassSecurityValidator.getGlobal().isTrusted(Personhendelse::class.java))
    }

    @Test
    fun `deserializes Personhendelse med dodsmeldinger etter init`() {
        AvroSecurityConfig.configureAvroClassSecurityForPdlPersonhendelse()
        val event = Personhendelse.newBuilder()
            .setHendelseId("test-event")
            .setPersonidenter(emptyList())
            .setMaster("FREG")
            .setOpprettet(Instant.parse("2026-01-01T00:00:00Z"))
            .setOpplysningstype("DOEDSFALL_V1")
            .setEndringstype(Endringstype.OPPRETTET)
            .setDoedsfall(Doedsfall.newBuilder().setDoedsdato(LocalDate.of(2026, 1, 1)).build())
            .build()
        val output = ByteArrayOutputStream()
        val encoder = EncoderFactory.get().binaryEncoder(output, null)
        SpecificDatumWriter<Personhendelse>(Personhendelse::class.java).write(event, encoder)
        encoder.flush()

        val specificData = SpecificData()
        specificData.addLogicalTypeConversion(TimeConversions.DateConversion())
        specificData.addLogicalTypeConversion(TimeConversions.TimestampMillisConversion())
        assertEquals(Personhendelse::class.java, specificData.getClass(event.schema))
        val reader = SpecificDatumReader<Personhendelse>(event.schema, event.schema, specificData)
        val decoded = reader.read(null, DecoderFactory.get().binaryDecoder(output.toByteArray(), null))

        assertEquals(event, decoded)
        assertEquals(event.doedsfall.doedsdato, decoded.doedsfall.doedsdato)
    }
}
