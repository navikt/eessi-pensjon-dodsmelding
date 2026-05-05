package no.nav.eessi.pensjon.no.nav.eessi.pensjon

import no.nav.security.token.support.client.spring.oauth2.EnableOAuth2Client
import no.nav.security.token.support.spring.api.EnableJwtTokenValidation
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Profile

@EnableJwtTokenValidation(ignore = ["org.springframework", "no.nav.eessi.pensjon.shared.api.health.DiagnosticsController"])
@EnableOAuth2Client(cacheEnabled = true)
@SpringBootApplication
@Profile("!unsecured-webmvctest")
class EessiDodsmeldingApplication

fun main(args: Array<String>) {
    runApplication<EessiDodsmeldingApplication>(*args)
}