package no.nav.eessi.pensjon.config

import no.nav.person.identhendelse.v1.common.KjoennType
import no.nav.person.identhendelse.v1.common.Personnavn
import no.nav.person.identhendelse.v1.common.RelatertBiPerson
import org.apache.avro.util.ClassSecurityValidator

private const val PDL_LEESAH_PACKAGE = "no.nav.person.pdl.leesah"

fun configureAvroClassSecurityForPdlPersonhendelse() {
    val trustedPdlLeesahTypes = ClassSecurityValidator.ClassSecurityPredicate { clazz ->
        clazz.packageName == PDL_LEESAH_PACKAGE || clazz.packageName.startsWith("$PDL_LEESAH_PACKAGE.")
    }
    val trustedSharedTypes = ClassSecurityValidator.builder()
        .add(Personnavn::class.java)
        .add(RelatertBiPerson::class.java)
        .add(KjoennType::class.java)
        .build()

    ClassSecurityValidator.setGlobal(
        ClassSecurityValidator.composite(
            ClassSecurityValidator.getGlobal(),
            trustedPdlLeesahTypes,
            trustedSharedTypes
        )
    )
}
