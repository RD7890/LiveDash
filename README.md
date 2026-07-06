# LiveDash

[![Build](https://github.com/RD7890/LiveDash/actions/workflows/build.yml/badge.svg)](https://github.com/RD7890/LiveDash/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/RD7890/LiveDash?include_prereleases)](https://github.com/RD7890/LiveDash/releases/latest)

Offline-first Android communication app over local Wi-Fi hotspot. No internet. No cloud.

## How it works

1. **Viewer** enables hotspot, opens LiveDash, picks **Viewer**, starts session — QR code appears
2. **Sender** connects to hotspot, opens LiveDash, picks **Sender**, scans QR — enters display name
3. Real-time chat with reply support
4. Tap the floating camera button on overlay to beam a screenshot instantly
5. Dashboard sees all senders, live streams, and chat

## Features

- Fully offline — local hotspot only, zero cloud
- QR-based pairing — no manual IP entry needed
- Live H.264 video stream per sender
- Two-way chat with reply support (long-press any message)
- Screenshot capture and history
- Draggable overlay (screenshot, chat, disconnect buttons)
- Auto-reconnect on network recovery
- Foreground service keeps connection alive
- Local persistence (session, chat, name)
- Dual APK: arm64-v8a + armeabi-v7a

## Stack

- Kotlin + Jetpack Compose + Material 3
- Java-WebSocket (embedded WS server + client)
- MediaProjection API (screen capture + H.264 encoding)
- WindowManager TYPE_APPLICATION_OVERLAY
- ZXing (QR generation + scanning)
- DataStore (local persistence)
- Foreground Services

## Install

Download from [Releases](https://github.com/RD7890/LiveDash/releases/latest):

- `LiveDash-vX.X-arm64.apk` — most modern phones (64-bit)
- `LiveDash-vX.X-arm32.apk` — older 32-bit phones

Developer: Rohan Dora | Package: `com.rohan.livedash`
