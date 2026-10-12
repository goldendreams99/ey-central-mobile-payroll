================================================================================
RECURSOS PARA ICONOS Y SPLASH SCREENS
================================================================================

Colocar los siguientes archivos en esta carpeta:

1. icon.png
   - Tamanio: 1024x1024 px minimo
   - Formato: PNG con fondo transparente o solido
   - Este icono se usara para generar todos los tamanios de Android e iOS

2. icon-foreground.png (opcional, para Android Adaptive Icons)
   - Tamanio: 1024x1024 px
   - El icono debe estar centrado con padding (zona segura: 66% del centro)
   - Fondo transparente

3. icon-background.png (opcional, para Android Adaptive Icons)
   - Tamanio: 1024x1024 px
   - Color/patron de fondo para adaptive icons

4. splash.png
   - Tamanio: 2732x2732 px (para cubrir iPad Pro)
   - El logo debe estar centrado
   - Se recortara para diferentes dispositivos

5. splash-dark.png (opcional)
   - Igual que splash.png pero para modo oscuro


================================================================================
GENERAR ICONOS
================================================================================

Despues de colocar los archivos, ejecutar:

    npx capacitor-assets generate

Esto generara automaticamente:
- Android: todos los mipmap (mdpi a xxxhdpi) + adaptive icons
- iOS: todos los tamanios de AppIcon (20x20 a 1024x1024)
- Splash screens para ambas plataformas


================================================================================
ZONA SEGURA (ADAPTIVE ICONS)
================================================================================

Para Android 8+ los iconos son "adaptive" (pueden ser circulares, cuadrados,
con bordes redondeados, etc segun el launcher).

El icono debe estar centrado en una "zona segura" del 66%:

    +------------------+
    |                  |
    |   +---------+    |
    |   |         |    |
    |   |  LOGO   |    |  <-- Zona segura (66%)
    |   |         |    |
    |   +---------+    |
    |                  |
    +------------------+

Si el logo llega hasta los bordes, se cortara en algunos launchers.

