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

## Etterlevelse: monitorering av kritisk dataflyt

- Overordnet rutine og krav: `docs/monitorering-kritisk-dataflyt.md`
- Dataflytregister og kritikalitet: `docs/dataflyt-register-kritikalitet.md`

