package no.nav.eessi.pensjon.h070

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonInclude
import no.nav.eessi.pensjon.eux.model.sed.Person as SedPerson
import no.nav.eessi.pensjon.eux.model.sed.PinItem

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Person(
    val pin: List<PinItem>,
    val etternavn: String?,
    val fornavn: String?,
    val foedselsdato: String?,
    val kjoenn: String?,
) {
    fun toSedPerson(): SedPerson = SedPerson(
        pin = pin,
        etternavn = etternavn,
        fornavn = fornavn,
        foedselsdato = foedselsdato,
        kjoenn = kjoenn
    )
}
