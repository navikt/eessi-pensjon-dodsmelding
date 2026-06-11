# Etterlevelsesrapport – eessi-pensjon-dodsmelding

> Auto-generert analyse av hvordan komponenten **eessi-pensjon-dodsmelding** bidrar til
> EESSI Pensjon-domenets etterlevelseskrav. Dette er en intern oversikt for
> team eessipensjon, ikke en besvarelse i etterlevelse-portalen.

- **Komponent:** eessi-pensjon-dodsmelding
- **Rolle:** Kafka-lytter (asynkron worker). Lytter på dødsmeldinger fra PDL (`pdl.leesah-v1`), sjekker PESYS for pensjonsrelasjoner og oppretter/sender H070 SED (dødsnotifikasjon) til nordiske institusjoner via EUX-RINA-API.
- **Generert:** 2026-06-11
- **Kilde for krav:** navikt/eessi-pensjon / etterlevelse/agent-input
- **Antall krav vurdert:** 16

## Sammendrag

| Kravid | Tittel | Tema | Samlet status |
|--------|--------|------|---------------|
| K103.2 | Personopplysninger skal kunne rettes | Personvern | Ja (delvis) |
| K104.1 | Personopplysninger skal kunne slettes | Personvern | Ja (delvis) |
| K105.1 | Det må tilrettelegges for dataportabilitet | Personvern | Ikke relevant |
| K108.2 | Den registrerte skal informeres om behandling | Personvern | Ikke relevant |
| K109.1 | Fødselsnummer skal bare brukes der nødvendig | Personvern | Ja |
| K113.2 | Den registrerte har krav på innsyn | Personvern | Ja (delvis) |
| K115.1 | Automatisering oppfyller vilkårene | Personvern | Ja (delvis) |
| K116.1 | Behandling av personopplysninger må kunne begrenses | Personvern | Ja (delvis) |
| K187.1 | Informasjon ved automatiske avgjørelser | Personvern | Ikke relevant |
| K188.1 | Profilering oppfyller vilkårene | Personvern | Ikke relevant |
| K191.1 | Lagringstid skal være avklart | Personvern | Ja (delvis) |
| K245.1 | Krav til risikovurdering | Informasjonssikkerhet | Uavklart |
| K253.1 | Visning til oppslagslogg (Arcsight) | Informasjonssikkerhet | Ikke relevant |
| K255.1 | Nav skal beskytte brukere med adressebeskyttelse | Informasjonssikkerhet | Nei (delvis) |
| K262.1 | Protestere mot behandling av personopplysninger | Personvern | Ikke relevant |
| K267.1 | Applikasjoner skal ha forsvarlig sikkerhetsnivå | Informasjonssikkerhet | Ja (delvis) |

## Krav i detalj

### K103.2 – Personopplysninger skal kunne rettes

**Tema:** Personvern · **Samlet status:** Ja (delvis)

Komponenten lagrer hashede fødselsnumre i GCP Storage (leveattestregisteret) og konsumerer persondata fra PDL. Persondata hentes «live» ved behov og lagres ikke i en egen database, men hashede fnr i GCP-bucketen kan potensielt bli utdaterte.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert behov for tiltak for korrekthet | Ja (delvis) | Personopplysninger hentes direkte fra PDL ved behandling av hver hendelse (`DodsmeldingBehandler.kt:46`), noe som sikrer oppdaterte data. Hashede fnr i GCP-bucketen kan ikke «rettes» direkte — de er enveiskrypterte oppslag. |
| 2 | Rutiner for retting og supplering | Ja (delvis) | Appen lagrer ikke redigérbare personopplysninger — den bruker PDL som master. Hashede verdier i bucket kan slettes og opprettes på nytt via `LagringsService`. Formell rutine for retting må bekreftes. |
| 3 | Varslet mottaker om retting | Ikke relevant | Komponenten utleverer ikke personopplysninger til eksterne mottakere som skal varsles ved retting. H070-SED sendes til utenlandske institusjoner, men retting håndteres via nye SED-er i EESSI-prosessen (EUX-plattformens ansvar). |

---

### K104.1 – Personopplysninger skal kunne slettes

**Tema:** Personvern · **Samlet status:** Ja (delvis)

GCP-bucketen inneholder hashede fnr. Disse kan slettes teknisk via `LagringsService.lagre()`/GCP Storage API, men det finnes ingen dedikert slettefunksjon i koden i dag.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Rutiner og funksjonalitet for sletting | Ja (delvis) | GCP Storage støtter sletting av blobs. `LagringsService` har `gcpStorage`-referanse som teknisk muliggjør dette, men ingen eksplisitt slettefunksjon er implementert. Rutine for håndtering av slettekrav må formaliseres. |
| 2 | Varslet mottaker om sletting | Ikke relevant | Samme vurdering som K103.2 SK3 — utlevering skjer via EESSI/H070 og håndteres av EUX-plattformen. |

---

### K105.1 – Det må tilrettelegges for dataportabilitet

**Tema:** Personvern · **Samlet status:** Ikke relevant

Komponenten behandler personopplysninger for å utøve offentlig myndighet (pensjonskoordinering). Behandlingsgrunnlaget er ikke samtykke eller avtale. Dataportabilitet er dermed ikke aktuelt.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert om dataportabilitet er aktuelt | Ikke relevant | Behandlingen skjer som ledd i offentlig myndighetsutøvelse — vilkårene for dataportabilitet er ikke oppfylt. |
| 2 | Funksjonalitet for utlevering | Ikke relevant | Se SK1. |

---

### K108.2 – Den registrerte skal informeres om behandling

**Tema:** Personvern · **Samlet status:** Ikke relevant

Informasjonsplikten ivaretas sentralt av Nav (personvernerklæring på nav.no) og er ikke noe denne backend-komponenten implementerer. Komponenten har ingen brukergrensesnitt og samler ikke inn data direkte fra registrerte.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert hvilken informasjon som må gis | Ikke relevant | Informasjonsplikten er et sentralt Nav-ansvar, ikke komponent-spesifikt. |
| 2 | Informasjon ved innhenting fra andre kilder | Ikke relevant | Behandlingen er hjemlet i lov og innhenting fra PDL/PESYS er uttrykkelig hjemlet — unntaket i GDPR art. 14(5)(c) kan gjelde. Uansett er dette et domene-/Nav-sentralt ansvar. |
| 3 | Hvordan informasjon gis | Ikke relevant | Ingen brukerflate i denne komponenten. |

---

### K109.1 – Fødselsnummer skal bare brukes der nødvendig

**Tema:** Personvern · **Samlet status:** Ja

Fødselsnummer brukes for sikker identifisering mot PDL, PESYS og SAF, og for å matche døde personer mot leveattestregisteret. Fnr sendes kun i sikre kanaler (mTLS/OAuth2).

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Nødvendig og saklig bruk av fnr | Ja | Fnr brukes for entydig identifisering av pensjonist på tvers av PDL, PESYS, SAF og EUX. Det er nødvendig for å sikre at riktig H070 sendes for riktig person. Se `DodsmeldingBehandler.kt:36-44`, `PesysKlient.kt:25`. |
| 2 | Tiltak for sikring ved sending i usikrede kanaler | Ja | Fnr sendes aldri i usikrede kanaler. All kommunikasjon skjer via OAuth2 client_credentials over TLS (`RestTemplateConfig.kt`). I GCP-bucket lagres kun HMAC-SHA256-hash av fnr, ikke klartekst (`LagringsService.kt:142-148`). Fnr logges til secureLog, ikke til standard logg (`MeldingFraPdlListener.kt:53`). |

---

### K113.2 – Den registrerte har krav på innsyn

**Tema:** Personvern · **Samlet status:** Ja (delvis)

Komponenten lagrer hashede fnr i GCP Storage. Ved innsynskrav kan det bekreftes om en person finnes i bucketen, men selve hashen er ikke reversibel.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Rutine for innsynsforespørsler | Ja (delvis) | Teknisk kan teamet søke i GCP-bucket med hashet fnr via `LagringsService.finnesDodBrukerILeveAttReg()`. Formell rutine for mottak av innsynskrav fra Nav Kontaktsenter må bekreftes. |
| 2 | Elektronisk innsyn | Ikke relevant | Komponenten har ingen brukerflate og er ikke et fagsystem der registrerte logger inn. |

---

### K115.1 – Behandling som benytter automatisering oppfyller vilkårene

**Tema:** Personvern · **Samlet status:** Ja (delvis)

Komponenten utfører automatisert behandling: den mottar dødshendelser og oppretter automatisk H070-SED. Dette er imidlertid ikke en «avgjørelse» som påvirker den registrertes rettigheter — det er en notifikasjon til utenlandske institusjoner om et faktum (dødsfall). Personen er død og vedtaksrettigheter er ikke aktuelle.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert om helautomatisk | Ja (delvis) | Behandlingen er helautomatisk: PDL-hendelse → vurdering → H070-utsendelse uten saksbehandlerinvolvering (`MeldingFraPdlListener.kt:49-61`). Dokumentasjon i Behandlingskatalogen må verifiseres. |
| 2 | Regelverket egner seg for automatisering | Ja | Dødsfall er et objektivt faktum fra Folkeregisteret — ingen skjønnsvurdering. Kravet om å sende H070 ved pensjonsrelasjon til utlandet følger av EESSI-forordningen. |
| 3 | Likt faktagrunnlag gir likt resultat | Ja | Behandlingsreglene er deterministiske: død + match i leveattestregister/Joark → H070 til riktig land/institusjon. Se `DodsmeldingBehandler.kt:58-76`. |
| 4 | Datakilde god nok | Ja | PDL (Folkeregisteret) er autoritativ kilde for dødsfall. PESYS er autoritativ for pensjonsdata. `DodsmeldingBehandler.kt:46`. |
| 5 | Ikke diskriminering/utenforliggende hensyn | Ja | Behandlingen vurderer kun: (1) er personen død, (2) finnes pensjonsrelasjon til utlandet. Ingen skjønn, profil eller vekting. |
| 6 | Kan overprøves manuelt | Ja (delvis) | H070-sending i prod er delvis kommentert ut (`DodsmeldingBehandler.kt:107-108`), noe som tyder på manuell kontroll. Men det finnes ingen eksplisitt overprøvingsmekanisme i koden for ferdigstilt flyt. |
| 7 | Dokumentert helautomatisk behandling | Uavklart | Kan ikke verifiseres fra koden om dette er registrert i Behandlingskatalogen. |

---

### K116.1 – Behandling av personopplysninger må kunne begrenses

**Tema:** Personvern · **Samlet status:** Ja (delvis)

Komponenten behandler personopplysninger automatisk ved dødshendelser. Begrensning av behandling ville innebære at spesifikke personer ekskluderes fra H070-prosessen.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Rutiner og funksjonalitet for begrensning | Ja (delvis) | Ingen eksplisitt «begrensningsliste» i koden for å ekskludere spesifikke personer. Teknisk kan en person fjernes fra GCP-bucket for å forhindre match, men dette er ikke en formell begrensningsmekanisme. Rutine må formaliseres. |
| 2 | Varslet mottaker om begrensning | Ikke relevant | Samme vurdering som K103.2 SK3 — varsling av utenlandske institusjoner håndteres via EESSI-prosessen/EUX. |

---

### K187.1 – Informasjon ved automatiske avgjørelser

**Tema:** Personvern · **Samlet status:** Ikke relevant

Komponenten treffer ingen «avgjørelse» som har rettsvirkning eller lignende for den registrerte. Den sender en dødsnotifikasjon til utenlandske pensjonsinstitusjoner — personen er allerede død og har ingen rettigheter som påvirkes av H070-utsendelsen.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Informert om helautomatisk avgjørelse | Ikke relevant | Ingen rettslig avgjørelse treffes. |
| 2 | Informert om underliggende logikk | Ikke relevant | Se SK1. |
| 3 | Informert om betydning/konsekvenser | Ikke relevant | Se SK1. |

---

### K188.1 – Profilering oppfyller vilkårene

**Tema:** Personvern · **Samlet status:** Ikke relevant

Komponenten utfører ingen profilering. Den matcher døde personer mot et register for å sende notifikasjon — ingen prediksjon, kategorisering eller vurdering av personlige egenskaper.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert om profilering | Ikke relevant | Ingen profilering. Behandlingen er ren fakta-sjekk (død + pensjonsrelasjon). |
| 2 | Regelverket åpner for profilering | Ikke relevant | Se SK1. |
| 3 | Ikke diskriminering | Ikke relevant | Se SK1. |
| 4 | Særskilte krav/rettigheter | Ikke relevant | Se SK1. |

---

### K191.1 – Lagringstid skal være avklart

**Tema:** Personvern · **Samlet status:** Ja (delvis)

Komponenten lagrer hashede fnr i GCP Storage-bucket (`eessi-pensjon-dodsmelding`). EDIFACT-filer lagres også midlertidig. Lagringstid for disse dataene bør være avklart.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Avklart lagringsbehov | Ja (delvis) | GCP-bucketen brukes som et register over personer med utenlandsk pensjon (`LagringsService.kt`). Det er uklart om lagringstid er formelt dokumentert i Behandlingskatalogen/Confluence. |
| 2 | Hva skjer etter lagringsbehov opphører | Uavklart | Ikke dokumentert i koden hva som skjer med hashede fnr etter at personen er død og H070 er sendt. Bør avklares om de skal slettes etter utsendelse. |
| 3 | Tekniske løsninger for rutinemessig sletting | Nei (delvis) | Ingen automatisk slettejobb i kodebasen. GCP-bucket har lifecycle-policies som mulighet, men dette er ikke konfigurert i `nais/prod-gcp.yml`. |

---

### K245.1 – Krav til risikovurdering

**Tema:** Informasjonssikkerhet · **Samlet status:** Uavklart

Kan ikke verifiseres fra koden om verdivurdering, trusselmodellering og risikovurdering er gjennomført og lagret. Dette er prosessdokumenter som lever utenfor repoet.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Verdivurdering gjennomført | Uavklart | Ikke sporbart i koden. Må verifiseres mot Sharepoint/HSB. |
| 2 | Trusselmodellering | Uavklart | Ikke sporbart i koden. Dataflytdiagram finnes i `README.md` og `docs/dataflyt-register-kritikalitet.md`, men formell STRIPED/DFD-basert trusselmodellering kan ikke bekreftes. |
| 3 | Sikkerhetsrisikovurdering (Høy/Svært Høy) | Uavklart | Avhenger av verdivurderingens utfall. |
| 4 | Rutiner for oppdatering | Uavklart | Ikke sporbart i koden. |
| 5 | Lagret på trygt sted | Uavklart | Ikke sporbart i koden. |

---

### K253.1 – Visning til oppslagslogg (Arcsight)

**Tema:** Informasjonssikkerhet · **Samlet status:** Ikke relevant

Komponenten er en backend Kafka-consumer uten brukergrensesnitt. Ingen Nav-ansatte «slår opp» brukere gjennom denne applikasjonen — den prosesserer hendelser automatisk. Kravet gjelder kun fagsystemer med visning av personopplysninger til Nav-ansatte.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Oppslag logges til Arcsight | Ikke relevant | Ingen brukerflate. Ingen visning av personopplysninger til ansatte. |
| 2 | Logger kun ved visning | Ikke relevant | Se SK1. |
| 3 | Ikke logge listevisninger | Ikke relevant | Se SK1. |
| 4 | Ikke logge mer enn nødvendig | Ikke relevant | Se SK1. |
| 5 | Avklart med Team Auditlogging | Ikke relevant | Se SK1. |
| 6 | Bekreftet loggformat | Ikke relevant | Se SK1. |

---

### K255.1 – Nav skal beskytte brukere med adressebeskyttelse

**Tema:** Informasjonssikkerhet · **Samlet status:** Nei (delvis)

Komponenten sender H070 med personopplysninger (navn, fnr, dødsdato, utenlandsk ID) til utenlandske institusjoner via EESSI. Det er ingen synlig sjekk i koden for adressebeskyttelse (kode 6/7) før H070 sendes.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Rollekontroll for adressebeskyttede | Ikke relevant | Ingen brukerflate for Nav-ansatte. |
| 2 | Tydelig markering ved oppslag | Ikke relevant | Ingen brukerflate. |
| 3 | Beskyttelse ved lagring på andres saker | Ikke relevant | Appen lagrer ikke data på andres saker. |
| 4 | Sammenstilte oversikter | Ikke relevant | Ingen slike oversikter. |
| 5 | Deler ikke adresse med eksterne parter | Nei (delvis) | H070-SED inneholder persondata (navn, fnr, dødsdato). Koden sjekker ikke adressebeskyttelse før sending (`OpprettH070.kt`, `DodsmeldingBehandler.kt`). Adressen sendes ikke eksplisitt i H070, men det bør verifiseres at ingen geolokaliserende opplysninger lekker. |
| 6 | Varsomhet ved deling av geolokaliserende opplysninger | Nei (delvis) | Ingen eksplisitt sjekk for kode 6/7 i koden. H070 inneholder kun navn, fnr, dødsdato og utenlandsk ID — trolig ikke geolokaliserende, men dette bør verifiseres og dokumenteres. |
| 7 | Selvbetjeningsløsninger | Ikke relevant | Ingen selvbetjening. |
| 8 | Begrense manuell håndtering | Ja | Hele prosessen er automatisert — ingen manuell behandling av adressebeskyttede brukeres opplysninger. |

---

### K262.1 – Protestere mot behandling av personopplysninger

**Tema:** Personvern · **Samlet status:** Ikke relevant

Behandlingsgrunnlaget er utøvelse av offentlig myndighet (pensjonskoordinering etter EU-forordning). Den registrerte er død — retten til å protestere er ikke praktisk relevant for denne behandlingen.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Vurdert om rettigheten er aktuell | Ikke relevant | Person er død ved tidspunkt for behandling. Protestrett gjelder levende personer. |
| 2 | Rutiner for å stanse behandling | Ikke relevant | Se SK1. |
| 3 | Informert om retten til å protestere | Ikke relevant | Se SK1. |

---

### K267.1 – Applikasjoner skal ha forsvarlig sikkerhetsnivå

**Tema:** Informasjonssikkerhet · **Samlet status:** Ja (delvis)

Komponenten bruker NAIS-plattformen med standard sikkerhetsfunksjoner. OAuth2 client_credentials for all service-kommunikasjon. Fnr hashes med HMAC-SHA256 ved lagring.

| SK | Suksesskriterium | Status | Begrunnelse (med kodereferanse) |
|----|------------------|--------|---------------------------------|
| 1 | Følger med på sårbarheter | Uavklart | Kan ikke verifiseres fra koden om Dependabot/Snyk er aktivert. `build.gradle` og GitHub-innstillinger ikke undersøkt for dette. |
| 2 | Avhengigheter oppdatert | Uavklart | Kan ikke verifiseres siste deploy-tidspunkt eller oppdateringsfrekvens fra koden alene. |
| 3 | Validerer input og output | Ja (delvis) | Kafka-meldinger deserialiseres via Avro schema (`KafkaConfig.kt:86`), som gir skjemavalidering. EDIFACT-parsing har grunnleggende validering (`VurderSveFinEdifactDokument.kt:10-11`). Ingen eksplisitt output-validering av H070-innhold mot XSD/schema. |
| 4 | Beskytter og roterer hemmeligheter | Ja | Hemmeligheter håndteres via GCP Secret Manager (`nais/prod-gcp.yml:18` — `dodsmelding-prod`). OAuth2-tokens er kortvarige og automatisk generert. HASH_SECRET_KEY leses fra secret. |
| 5 | Logger feil og sikkerhetshendelser | Ja | Strukturert logging med Logstash-encoder (`logback-spring.xml`). Fødselsnummer logges kun til secureLog (`MeldingFraPdlListener.kt:53`, `DodsmeldingBehandler.kt:43`). Standard applikasjonslogg maskerer fnr. Prometheus-metrikker eksponeres (`nais/prod-gcp.yml:97-99`). |
| 6 | Backup av data og kode | Ja | Kode i GitHub (NAIS-standard backup). GCP Storage har innebygd redundans. Appen er stateless utover bucket — kan redeployes fra scratch. |
| 7 | Tilgangskontroll på alle endepunkter | Ja | Azure AD er aktivert (`nais/prod-gcp.yml:19-21`). Helseendepunkter er markert `@Unprotected` (`DiagnosticsController.kt:12`), resten krever token. Access policy definerer eksplisitt inbound/outbound-regler (`nais/prod-gcp.yml:22-36`). |

## Avgrensninger

- Generisk EESSI-/RINA-infrastruktur (meldingsruting, BUC-håndtering, hendelsesstrøm) ivaretas av EUX-plattformen (team eessibasis).
- Vedtaksbehandling, brevproduksjon og lagringstider per ytelse ligger i fagsystemene (PESYS m.fl.).
- Arkivering av SED-er (journalføring i Joark) ivaretas av eessi-pensjon-journalforing — ikke denne komponenten.
- Adressebeskyttelse/gradering av BUC-er håndteres av eessi-pensjon-begrens-innsyn — men den opererer på sedSendt/sedMottatt-topics, ikke på H070-utsendelse fra denne appen.
- Informasjonsplikt og personvernerklæring er et sentralt Nav-ansvar.
- Oppslagslogging (Arcsight) er kun relevant for fagsystemer med brukergrensesnitt.

## Punkter som må følges opp

- [ ] **K103.2/SK2:** Formell rutine for retting av data i GCP-bucket (hashede fnr) må bekreftes/etableres.
- [ ] **K104.1/SK1:** Slettefunksjonalitet for GCP-bucket bør implementeres eller rutine dokumenteres.
- [ ] **K113.2/SK1:** Rutine for innsynsforespørsler via Nav Kontaktsenter må bekreftes å dekke denne appen.
- [ ] **K115.1/SK7:** Verifiser at den helautomatiske behandlingen (dødshendelse → H070) er dokumentert i Behandlingskatalogen.
- [ ] **K191.1/SK2-3:** Lagringstid for hashede fnr i GCP-bucket må avklares. Vurder GCP lifecycle policy eller automatisk slettejobb etter H070 er sendt.
- [ ] **K245.1:** Verdivurdering, trusselmodellering og ev. risikovurdering må verifiseres gjennomført og lagret (Sharepoint/HSB).
- [ ] **K255.1/SK5-6:** Verifiser at H070-SED ikke inneholder geolokaliserende opplysninger for adressebeskyttede. Vurder om sjekk mot PDL for kode 6/7 bør legges inn før H070-opprettelse.
- [ ] **K267.1/SK1-2:** Bekreft at sårbarhetsskanning (Dependabot/Snyk) er aktivert og at appen deployes regelmessig.
