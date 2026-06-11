# Monitorering av kritisk dataflyt

Kort styringsdokument for `eessi-pensjon-dodsmelding`.

## Kritisk dataflyt i appen

- Kafka inn: `pdl.leesah-v1` (doedsmelding)
- Intern vurdering/behandling av H070
- EUX/RINA ut: oppretting og sending av H070
- Stotteflyt: EDIFACT-jobb mot GCP-bucket

Detaljer: `docs/dataflyt-register-kritikalitet.md`.

