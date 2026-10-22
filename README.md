# eessi-pensjon-dodsmelding
Dette er en applikasjon som mottar meldinger fra PDL om dødsfall og sender disse videre til utenlanske pensjonsmyndigheter via EESSI.
En H070 sendes i de tilfeller hvor vi har en match i PDL på en person som er registrert som død, og hvor det er en tilknytning til utlandet. Tilknyttningen kan være: 
    1. Personen har en utenlandsk pensjon (registrert i S3 via edifact filer fra utenlandske pensjonsmyndigheter)
    2. Personen har en P6000 registrert i Jorak

Applikasjonen er skrevet i Kotlin og Spring Boot.

## Arkitektur

```
┌─────────────────┐
│   PDL (Kafka)   │  pdl.leesah-v1 (Dødsmeldinger)
│  DOEDSFALL_V1   │
└────────┬────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│  MeldingFraPdlListener (Kafka-konsument)     │
│  - Filtrerer på DOEDSFALL_V1-hendelser       │
│  - Starter behandling av dødsmelding         │
└────────┬─────────────────────────────────────┘
         │
         ▼
┌──────────────────────────────────────────────┐
│  DodsmeldingBehandler (hovedorkestrering)    │
│  - Vurderer om H070 skal sendes              │
│  - Koordinerer underliggende tjenester       │
└────┬──────────────────────┬──────────┬───────┘
     │                      │          │
     ▼                      ▼          ▼
┌─────────────────┐ ┌───────────────┐ ┌──────────────────┐
│ PersonService   │ │LagringsService│ │ OpprettH070      │
│ (PDL-oppslag)   │ │ (GCP-lagring) │ │ (Oppretter H070) │
│                 │ │ - S3-match    │ │ - Formatterer    │
└─────────────────┘ │ - EDIFACT     │ │   melding        │
                    │   parsing     │ └────────┬─────────┘
                    └───────────────┘          │
                                               ▼
                                    ┌──────────────────────┐
                                    │  EuxService          │
                                    │  (EESSI-integrasjon) │
                                    │  - Sender H070 til EU│
                                    └──────────────────────┘
```

### Komponenter

- **MeldingFraPdlListener**: Kafka-konsument som lytter på dødsmeldinger fra PDL
- **DodsmeldingBehandler**: Hovedorkestrering som avgjør om H070 skal sendes
- **PersonService**: Henter persondata fra PDL
- **LagringsService**: Håndterer GCP-lagring for pensjonsmatch (Sverige/Finland EDIFACT-filer)
- **OpprettH070**: Oppretter og formatterer H070-meldinger
- **EuxService**: Sender H070 til EESSI (EU-utveksling)

## Land fra adresser

`DodsmeldingBehandler.hentLandFraAdresser` samler eksplisitte landkoder fra kontakt-,
bosteds- og oppholdsadresser og geografisk tilknytning i begge PDL-oppslagene.
Begge utenlandske kontaktadresseformatene tas med, også fra de valgte historiske adressene.
Resultatet inneholder hver landkode én gang, koblet til alle kildefeltene, og logges i `secureLog`.
Tomme verdier utelates, og landkoder trimmes og gjøres om til store bokstaver.
To- og trebokstavskoder beholdes som separate verdier. Norske adresser uten landkode gir
ikke en utledet `NOR`-verdi.

Feltene `InklHistoriske` inneholder bare den sist registrerte adressen av hver type,
ikke hele adressehistorikken. Landkontrollen for H070 godtar treff på minst ett gyldig
utstederland i den samlede mengden, ikke bare i `kontaktadresseInklHistoriske`.
De øvrige kontrollene gjelder fortsatt. Mottaker velges fortsatt fra leveattestregisteret
eller EUX-motpartene, ikke fra adressene.
Statsborgerskap, fødeland, dødsboadresse og andre landkilder inngår ikke i innsamlingen.

## Etterlevelse: monitorering av kritisk dataflyt

- Overordnet rutine og krav: `docs/monitorering-kritisk-dataflyt.md`
- Dataflytregister og kritikalitet: `docs/dataflyt-register-kritikalitet.md`

## Feil ved oppstart og Kafka-konsumering

Ved oppstart kontrollerer applikasjonen at den kan liste innholdet i begge GCS-bucketene.
Hvis en kontroll feiler, feiler oppstarten og Spring lukker applikasjonen.

Alle Kafka-feil avslutter applikasjonen med exit-kode 1, også feil ved deserialisering,
autentisering og behandling av en batch. Applikasjonen gjør ingen nye behandlingsforsøk
og kvitterer ikke ut den feilede batchen. Etter restart leser Kafka fra sist lagrede offset.
Nais kan restarte podden automatisk, så vedvarende feil må rettes for å unngå en restart-loop.
