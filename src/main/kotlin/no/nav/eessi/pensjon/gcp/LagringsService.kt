package no.nav.eessi.pensjon.gcp

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.Storage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import no.nav.eessi.pensjon.eux.model.sed.H070
import no.nav.eessi.pensjon.personoppslag.pdl.model.IdentInformasjon
import no.nav.eessi.pensjon.utils.toJson
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val HASH_PRESET = "HashedUsers"

@Service
class LagringsService (
    @param:Value("\${GCP_BUCKET_UTL_YTELSE}") var utenlandkYtelseBucket: String,
    @param:Value("\${GCP_H070_OPPRETTET}") var h070_opprettetBucket: String,
    private val gcpStorage: Storage,
    @param:Value("\${HASH_SECRET_KEY}") private val hashSecretKey: String
) {

    private val logger = LoggerFactory.getLogger(LagringsService::class.java)

    fun lagreFnrForBruker(fnr: String): Boolean {
        val hashafnr = hashedValue(fnr)
        try {
            if(finnesDoedsmeldingAlleredeForBruker(fnr)) return false.also { logger.error("Bruker finnes i bucket.") }
            logger.debug("Hasha : $hashafnr")
            lagre("$HASH_PRESET/$hashafnr", h070_opprettetBucket)
            return true
        } catch (ex: Exception) {
            logger.error("Feiler ved lagring av: $hashafnr $ex")
        }
        return false
    }

    fun kanHendelsenOpprettes(fnr: String?, land: String?) : Boolean {
        logger.debug("liste over obj FI/" + hentListeFraS3("FI/", utenlandkYtelseBucket).toString())
        logger.debug("liste over obj SE/" + hentListeFraS3("SE/", utenlandkYtelseBucket).toString())
        return !eksisterer(land, fnr, utenlandkYtelseBucket)
    }

    fun finnesDodBrukerILeveAttReg(
        fnr: List<IdentInformasjon>?
    ): Pair<String, String>? {
        logger.debug("Sjekker om fødselsnummer ligger i bucket")

        val identifikatorer = fnr.orEmpty()
        if (identifikatorer.isEmpty()) {
            logger.debug("Ingen identifikatorer mottatt fra PDL")
            return null
        }

        val bucketEntries = listOf("FI/", "SE/", "PL/", "DK/")
            .flatMap { prefix ->
                hentListeFraS3(prefix, utenlandkYtelseBucket)
            }

        val resultat = bucketEntries.firstNotNullOfOrNull { bucketEntry ->
            identifikatorer.firstNotNullOfOrNull { identInformasjon ->
                val ident = identInformasjon.ident
                val hash = hashedValue(ident)

                if (bucketEntry.contains(hash)) {
                    ident to hentLandFraPrefix(bucketEntry)
                } else {
                    null
                }
            }
        }

        if (resultat != null) {
            logger.debug("Brukeren finnes i bucket. H070 kan sendes ut")
        } else {
            logger.info("Brukeren finnes ikke i bucket og kan dermed ignoreres")
        }

        return resultat
    }

    fun hentLandFraPrefix(hash: String) : String {
        val land = hash.substring(0, 2)
        if(land in listOf("FI", "SE", "DK")) return land
        throw RuntimeException("Henter landkode fra prefix $land")
    }

    fun finnesDoedsmeldingAlleredeForBruker(fnr: String): Boolean {
        logger.debug("sjekker om fnr allerede ligger inne med dodsmelding i bucket")
        val hasha = hashedValue(fnr)
        val listeOverFnrIBucket = hentListeFraS3("$HASH_PRESET/$hasha", h070_opprettetBucket)
        logger.debug("Sjekker om fnr finnes i bucket for bruker: $hasha")

        val finnesFraFor = listeOverFnrIBucket.any { fnrIBucket -> fnrIBucket.contains(hasha) }
        if (finnesFraFor) {
            logger.debug("Denne brukeren finnes i bucket. H070 er allerede sendt ut")
        } else {
            logger.info("Bruker finnes ikke i bucket, H070 kan opprettes på bruker.")
        }
        return finnesFraFor
    }

    fun hentFraGcp(storageKey: String): String? {
        val filIS3 =  gcpStorage.get(BlobId.of(utenlandkYtelseBucket, storageKey))
        logger.debug("Henter fila {}", filIS3)

        if(filIS3!= null && filIS3.exists()){
            return String(filIS3.getContent(), Charsets.UTF_8)
        }
        return null
    }

    fun eksisterer(land: String?, fnr: String?, bucketNavn: String): Boolean {
        logger.debug("sjekker om $land finnes i bucket: $bucketNavn")
        val path = landOgIdent(land, fnr!!) ?: return false

        kotlin.runCatching {
            gcpStorage.get(BlobId.of(bucketNavn, path)).exists()
        }.onFailure {
            return false
        }.onSuccess {
            return true
        }
        return false
    }

    fun hentListeFraS3(keyPrefix: String, bucket: String) : List<String> {
        logger.debug("lister innhold i fila")
        // Page.values gir kun forste side av resultatet. Bruker iterateAll() slik at alle sider
        // hentes - ellers vil eksisterer-sjekker feilaktig kunne mislykkes for identer som ligger
        // pa senere sider naar bucketen inneholder flere blobber enn en enkelt side (default 1000),
        // med fare for at samme ident lagres pa nytt i hver kjoring av batchen.
        return gcpStorage.list(bucket , Storage.BlobListOption.prefix(keyPrefix))?.iterateAll()?.map { v -> v.name}  ?:  emptyList()
    }

    fun landOgIdent(landkode: String?, fnr: String): String? {
        val land = when (landkode?.trim()?.replace(" ", "")) {
            "FI" -> "FI"
            "SE" -> "SE"
            "PL" -> "PL"
            else -> {
                logger.warn("Ikke gyldig landkode: $landkode")
                return null
            }
        }
        val path =  "$land/${hashedValue(fnr)}"
        logger.debug("Hendelsespath: $path")

        return path
    }

    fun lagre(storageKey: String, bucket: String) {
        val blobInfo =  BlobInfo.newBuilder(BlobId.of(bucket, storageKey)).setContentType("application/json").build()
        kotlin.runCatching {
            gcpStorage.writer(blobInfo).use { it.write(ByteBuffer.wrap(storageKey.toByteArray())) }
        }.onFailure { e ->
            logger.error("Feilet med å lagre dokument med id: ${blobInfo.blobId.name}", e)
        }.onSuccess {
            logger.info("Lagret fil med blobid:  ${blobInfo.blobId.name} og bytes: $it")
        }
    }

    fun hashedValue(input: String?): String {
        return hashedValueInternal(input)
            .also { logger.debug("Hashet verdi for input $input: $it") }
    }

    private fun hashedValueInternal(input: String?): String {
        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(hashSecretKey.toByteArray(), "HmacSHA256"))
        }
        return mac.doFinal(input?.toByteArray() ?: ByteArray(0))
            .joinToString("") { "%02x".format(it) }
    }

    fun lagreH070(h070: H070, H070_PREFIX: String) {
        val storageKey = "$H070_PREFIX/${Instant.now().toEpochMilli()}.json"
        val obfuskertH070 = obfuskerPinIdentifikator(h070.toJson())
        val blobInfo = BlobInfo.newBuilder(BlobId.of(h070_opprettetBucket, storageKey))
            .setContentType("application/json")
            .build()

        kotlin.runCatching {
            gcpStorage.writer(blobInfo).use { it.write(ByteBuffer.wrap(obfuskertH070.toByteArray())) }
        }.onFailure { e ->
            logger.error("Feilet med å lagre obfuskert H070 med id: ${blobInfo.blobId.name}", e)
        }.onSuccess {
            logger.info("Lagret obfuskert H070 til bucket: ${blobInfo.blobId.bucket}, path: ${blobInfo.blobId.name}")
        }
    }

    private fun obfuskerPinIdentifikator(json: String): String {
        val objectMapper = ObjectMapper()
        val root = objectMapper.readTree(json)
        root.findParents("person")
            .mapNotNull { it.get("person")?.get("pin") as? ArrayNode }
            .forEach { pin ->
                pin.forEach { pinItem ->
                    val identifikator = pinItem.path("identifikator").asText()
                    if (identifikator.isNotBlank() && pinItem is ObjectNode) {
                        pinItem.put("identifikator", hashedValueInternal(identifikator))
                    }
                }
            }
        return objectMapper.writeValueAsString(root)
    }
}