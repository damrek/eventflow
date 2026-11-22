# 07 — Instrucciones de trabajo para OpenCode

## Reglas del agente

1. Antes de editar, inspecciona el repositorio, el entorno y los comandos disponibles.
2. Lee `EVENTFLOW_HANDOFF.md` y solo el documento temático relevante para la tarea actual.
3. Trabaja una fase cada vez. No generes todos los servicios/infraestructura de golpe.
4. Antes de añadir una librería o usar una API nueva, consulta Context7 y confirma con documentación oficial. Si no está disponible, dilo y consulta la fuente oficial; nunca finjas haberlo hecho.
5. No fijes versiones por intuición. Comprueba compatibilidad de Boot, Spring AMQP, Springdoc, Testcontainers, Resilience4j y frontend antes de incorporarlos. Registra desviaciones como ADR.
6. Propón un plan breve antes de cambios amplios. Implementa cambios pequeños, ejecuta tests y corrige fallos antes de seguir.
7. No introduzcas patrones/servicios no justificados. Prioriza un sistema pequeño, correcto y explicable.
8. No pongas lógica de negocio en controladores, listeners, componentes UI ni scripts.
9. No compartas tablas, entidades JPA ni repositorios entre servicios.
10. No afirmes rendimiento, escalabilidad o resiliencia sin ejecutar y guardar pruebas.
11. Actualiza README, diagramas, variables `.env.example` y ADR cuando cambie una decisión.
12. Al completar la puerta de calidad, detente y espera la siguiente instrucción.

## Prompt inicial

```text
Actúa como tech lead y senior engineer de EventFlow.
Lee EVENTFLOW_HANDOFF.md y docs/07-opencode.md. Inspecciona el
repositorio y las herramientas disponibles. Consulta docs/06-roadmap-calidad.md
y docs/01-arquitectura.md. Empieza por Fase 0 y prepara Fase 1 (Catalog).
Antes de elegir versiones/APIs, verifica Context7 y documentación oficial.
No implementes otras fases todavía. Propón el plan, implementa en pasos
pequeños, ejecuta build y tests, corrige fallos y actualiza documentación.
Al terminar, entrega: archivos modificados, decisiones, comandos ejecutados,
resultados reales de tests, limitaciones y siguiente fase. Detente.
```

## Plantilla de cierre de fase

- **Entrega:** qué se implementó.
- **Decisiones:** decisiones técnicas y ADR creados.
- **Validación:** comandos y resultados exactos.
- **Pendiente/riesgos:** defectos conocidos y trabajo aplazado.
- **Siguiente paso:** fase siguiente, sin ejecutarla automáticamente.

Si hay ambigüedad, elige la opción más simple que preserve las invariantes de negocio, documenta la decisión y continúa. Si una fase excede el presupuesto, recorta funcionalidades opcionales; no elimines tests de concurrencia, idempotencia o recuperación de Outbox.
