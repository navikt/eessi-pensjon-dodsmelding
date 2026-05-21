package no.nav.eessi.pensjon.dodsmelding

open class EdifactDokument(
    val avsender: String?,
    val mottaker: String?,
    val meldingstype: String?,
    val norskIdent: String?,
    val avsenderLand: String?,
    val mottakerLand: String?,
    val fodselsdato: String?,
    val erSveFin: Boolean
)