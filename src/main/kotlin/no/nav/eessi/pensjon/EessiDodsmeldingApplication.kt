package no.nav.eessi.pensjon

import no.nav.security.token.support.client.spring.oauth2.EnableOAuth2Client
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Profile
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableScheduling

//@Profile("prod", "test")
@EnableJwtTokenValidation
@EnableOAuth2Client(cacheEnabled = false)
@SpringBootApplication
@EnableScheduling
@EnableRetry
class EessiDodsmeldingApplication

fun main(args: Array<String>) {
    // Nyere versjoner av Avro begrenser hvilke klasser som kan brukes ved (de)serialisering
    // av Avro-schemaer (org.apache.avro.util.ClassSecurityValidator). Uten dette blir
    // "no.nav.person.pdl.leesah.Personhendelse" avvist med en SecurityException, som igjen
    // fører til at kafka-konsumeringen krasjer på hver eneste melding.
    System.setProperty("org.apache.avro.SERIALIZABLE_PACKAGES", "no.nav.person.pdl.leesah")
    runApplication<EessiDodsmeldingApplication>(*args)
}