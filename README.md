# NEXA-X

**App Android para pilotar drones "WiFi UAV" desde el móvil, con pantalla táctil o con un mando Bluetooth (Xbox o genérico).**

Interfaz estilo terminal en gris y blanco, botones del mando configurables y maniobras automáticas.

---

## ✨ Funciones

- 🕹️ **Doble control**: joysticks táctiles y mando Bluetooth a la vez.
- 🎮 **Mando configurable**: asigna cualquier botón a cualquier acción desde la pantalla CONFIG.
- 🔀 **Modo 1 / Modo 2** de palancas e inversión de cada eje: avance, lateral, giro y altura.
- 🤖 **Piloto IA**: maniobras automáticas (giro 360°, cuadrado, círculo, sube/baja). Se cancelan al tocar un joystick.
- 🛑 **Parada de emergencia**: mantener 1 s el botón PARADA o LB+RB.
- 📡 **Estado del enlace** en tiempo real y consola de eventos.
- 🛬 **Aterrizaje automático** si sales de la app en pleno vuelo.

## 📱 Drones compatibles

Drones que se manejan con la app **WiFi UAV** y crean una red WiFi que empieza por
`FLOW_`, `WIFI_`, `DRONE_`, `WTECH-` o `GD89Pro_`. Por ejemplo K417, V88, D16/GT3/V66, E58 y similares.

No funciona con DJI, Tello ni drones de otras apps.

## 🚀 Uso

1. Instala `NEXA-X.apk`. Hace falta permitir "instalar apps de origen desconocido".
2. *(Opcional)* Empareja tu mando en el Bluetooth del teléfono.
3. Enciende el dron y conecta el móvil a su red WiFi (`FLOW_xxxx`…).
4. Abre NEXA-X y espera a ver `[ WIFI DRON :: OK ]`.

| Acción | Pantalla | Mando (por defecto) |
|---|---|---|
| Despegar | DESPEGAR | A |
| Aterrizar | ATERRIZAR | B |
| Cambiar velocidad | VEL | X |
| Cancelar piloto IA | tocar joystick | Y |
| Maniobras | PILOTO_IA | Cruceta ↑ → ↓ ← |
| Parada de motores | PARADA (1 s) | LB + RB (1 s) |

## 🛠️ Compilar

No necesita Android Studio. En Ubuntu/Debian:

```bash
sudo apt install aapt apksigner zipalign dalvik-exchange android-sdk-platform-23 openjdk-17-jdk
sh build.sh
```

`build.sh` genera una clave de firma propia (`nexa.keystore`) si no existe.

## ⚠️ Seguridad

Vuela en espacios abiertos o en interior despejado, lejos de personas, y cumple la normativa de drones de tu país.
Prueba primero con las hélices quitadas. Usas este software bajo tu propia responsabilidad.

## 🙏 Créditos

El protocolo de comunicación con los drones WiFi UAV está basado en el trabajo de ingeniería inversa de
[turbodrone](https://github.com/marshallrichards/turbodrone) (licencia Apache-2.0).
