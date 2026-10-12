# Íconos de la app por organización (EY-CENTRAL, 2026-10-08)

Dejá acá un PNG por ícono, con este nombre y formato:

- `icon-<nombre>.png` — minúsculas, sin espacios ni acentos (ej. `icon-prisma.png`). `default` está reservado (Employ).
- **1024 × 1024 px, cuadrado, PNG sin transparencia**, esquinas rectas, fondo hasta los bordes.
- Logo centrado dentro del 66 % central (círculo de 675 px): Android recorta en círculo o cuadrado redondeado.
- Sin texto chico, sombras, bordes ni efectos.

Después:

```
cd ey-central/zScripts
node generate-app-icons.mjs
```

Genera todos los tamaños de Android (adaptive + legacy), el `appiconset` de iOS, los `activity-alias` del manifest y
`catalog.json` (la lista de nombres para el Manager). Luego hay que publicar una release de la app: el catálogo viaja adentro.
La web lo activa con `app.setIcon({ name })` (contrato en zDocumentation/04_BRIDGE.txt).

**Etiqueta en el Manager**: `catalog.json` trae `{ name, label }`. La etiqueta sale del nombre con mayúscula inicial
("semino" → "Semino"). Para otra, crear `labels.json` acá: `{ "semino": "Semino Agro" }` y volver a correr el generador.
