package no.nav.eessi.pensjon.h070

import no.nav.eessi.pensjon.eux.model.SedType
import no.nav.eessi.pensjon.eux.model.sed.H070
import no.nav.eessi.pensjon.eux.model.sed.PinItem
import no.nav.eessi.pensjon.personoppslag.pdl.model.PdlPerson
import no.nav.person.pdl.leesah.Personhendelse
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Component
class OpprettH070  {

    fun preutFyltH070(personhendelse: Personhendelse, pdlPerson: PdlPerson, pin: List<PinItem>): H070 {
        val person = Person(
            //1.1 Personnummer
            //1.1.7.1 Personnummer
            //Under skal fyles ut en bolk for hver pin (norsk og utenlandsk)
            pin = pin,
            //1.1.1 Etternavn
            etternavn = pdlPerson.navn?.etternavn,
            //1.1.2 Fornavn
            fornavn = pdlPerson.navn?.fornavn,
            foedselsdato = pdlPerson.foedselsdato?.foedselsdato,
            //1.1.4 Kjønn
            kjoenn = pdlPerson.kjoenn?.kjoenn?.name?.substring(0, 1)
        )

        val nav = Nav(
            bruker = Bruker(
                //2.1 Dødsdato
                doedsdato = personhendelse.doedsfall.doedsdato.simpleFormat(),
                person = person
            )
        )

        return H070(
            type = SedType.H070,
            hnav = nav.toSedNav()
        )
    }
    fun LocalDate.simpleFormat(): String = DateTimeFormatter.ofPattern("yyyy-MM-dd").format(this)


}