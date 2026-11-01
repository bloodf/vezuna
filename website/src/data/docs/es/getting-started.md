---
title: Primeros pasos
description: Crea un perfil ficticio, revisa resultados importados y entiende permisos, copias de seguridad y soporte.
lang: es
order: 1
audience: user
translationKey: getting-started
essential: true
---

Vezuna organiza registros de salud familiares en Android y iPhone sin exigir una cuenta en la app. El mínimo actual en el código fuente es **Android 8.0 (API 26)**. En iPhone, requiere **iOS 18.5 o posterior**. Las pruebas de lanzamiento en dispositivos físicos siguen pendientes.

## Ejemplo sintético de primer uso

1. Crea un perfil ficticio llamado **Alex Ejemplo**.
2. Importa una imagen o un PDF de laboratorio ficticio con **Hemoglobina — 13,2 g/dL**. Copia la fecha y el intervalo de referencia del propio documento sintético.
3. Revisa el borrador extraído, corrige nombre, valor, unidad, referencia o fecha y confirma el resultado.
4. Importa un segundo informe ficticio con **Hemoglobina — 13,8 g/dL**, revísalo y confírmalo.
5. Abre la comparación para ver los resultados confirmados a lo largo del tiempo.
6. Para probar recordatorios, añade **Medicamento de demostración**, elige un horario y usa las acciones **tomado**, **omitido** o **pospuesto**. Este ejemplo no constituye una recomendación de dosis.

El OCR genera borradores que deben revisarse. Solo los resultados confirmados aparecen en comparaciones y exportaciones. Vezuna no diagnostica enfermedades, no recomienda tratamientos ni modifica dosis.

## Permisos opcionales

Con tu permiso, Vezuna lee frecuencia cardíaca, pasos y peso desde Health Connect en Android o HealthKit en iPhone. El acceso es de solo lectura. Rechazar el permiso no impide usar registros manuales.

La cámara y las imágenes seleccionadas apoyan la importación de informes. Las notificaciones apoyan los recordatorios locales de medicamentos.

## Privacidad, exportaciones y eliminación

No subas informes médicos, recetas, capturas de registros, archivos del almacenamiento ni copias de seguridad reales a issues, pull requests, informes de seguridad o servicios externos de IA y análisis. Utiliza únicamente datos fictícios.

Los PDF y CSV compartidos son texto sin cifrar y quedan fuera de la protección del almacenamiento. Al desinstalar la app se eliminan el almacenamiento local y los modelos descargados; los archivos ya exportados no se eliminan.

Los servicios de las tiendas pueden gestionar licencias y la entrega prevista de modelos. El comportamiento de privacidad de SDK y plataformas debe comprobarse por separado en la compilación de lanzamiento.

## Copias de seguridad e identidad

Las copias de seguridad de la app están cifradas para restaurarse **únicamente en el mismo dispositivo y con la misma clave**. No existe recuperación entre dispositivos ni entre apps con identificadores diferentes. No las considers protección contra la pérdida del dispositivo.

El identificador actual es `app.vezuna`. Cambiarlo crea una instalación separada y no transfiere la sandbox anterior ni las claves de Android Keystore.

## Modelos sin conexión

Existe código para inferencia local, pero el catálogo de producción está vacío. Este sitio no ofrece modelos públicos para descargar. El texto generado por IA puede ser incorrecto y no es orientación médica. Los registros, las copias y las exportaciones no dependen de modelos ni de licencias de IA.

## Soporte

Para preguntas de privacidad o problemas generales, utiliza las [issues de GitHub](https://github.com/bloodf/vezuna/issues). Para vulnerabilidades, selecciona **Security → Report a vulnerability** en el repositorio. No publiques detalles de explotación ni datos de salud.

Indica la plataforma, la versión de la app o el commit, los pasos para reproducir el problema y su impacto, siempre con datos sintéticos. El proyecto se mantiene con el mejor esfuerzo, sin compromiso de tiempo de respuesta ni programa de recompensas.
