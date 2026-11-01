---
title: Offline models
description: Understand local model code, empty production catalogs, delivery requirements, and the limits of generated answers.
lang: en
order: 5
audience: user
translationKey: offline-models
essential: false
---

Local model code exists in the Vezuna source, but production AI availability is not configured. The current production model catalog is intentionally empty. No public model download is offered by this website.

## Local processing model

When configured, local inference runs in process through Llamatik/llama.cpp. Prompts and answers stay on the device. Model files are checked against catalog size and SHA-256 metadata, and the catalog must come from a trusted store channel.

Play Asset Delivery and Apple-hosted assets are the planned delivery mechanisms. Store-mediated licensing may activate AI features. Records, backup, OCR review, comparison, and exports remain independent of model installation and AI licensing.

## Release gates

Production use still requires:

- Real model catalog entries and store asset identifiers.
- Hosted model packs with verified size and SHA-256 metadata.
- Model and native-library license and attribution review.
- Store licensing and hosted-download tests.
- Physical-device memory, speed, and inference checks.

The repository’s synthetic Qwen fixture is for Android instrumentation only. It is staged into test-APK assets after exact size and hash verification. It is not a production model catalog or a downloadable app feature.

## Generated text is not medical advice

Local AI answers are unverified generated text and can be wrong. They do not diagnose conditions, recommend treatment, or change medication doses. Do not rely on generated output as a substitute for a qualified healthcare professional or the original record.
