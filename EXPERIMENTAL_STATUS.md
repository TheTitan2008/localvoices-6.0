# Experimental ASR variants

This document records the state of the local ASR variants and the reproducible
APK artifacts built from them.

## Current baseline

The working baseline is the local Whisper pipeline. It now clusters reliable
voice windows before assigning a known person, so an impostor cannot be removed
from clustering merely because one window crossed the known-speaker threshold.
Whisper decoding no longer uses `max_len = 1` or `no_context = true`, which was
causing artificial one-token fragments and poorer long-form decoding.

## GigaAM integration

The official GigaAM repository documents `v3_e2e_ctc` and `v3_e2e_rnnt`, ONNX
export, and word-level timestamps. The model card currently lists a PyTorch
checkpoint and custom Python model code; it does not provide an Android-ready
ONNX package with a Kotlin decoder. The v3 checkpoint is hundreds of megabytes
before ONNX/runtime packaging. The official Python API also limits short
`transcribe` calls and uses an external VAD for long-form operation.

The project now includes a real GigaAM-v3 E2E CTC Android flavor. It uses the
official `transcribe.cpp` C++ implementation, the GGUF Q4_K_M model, CPU
inference, Russian SentencePiece decoding, and token timestamps. GigaAM is
isolated in the `:gigaam` Android library so its ggml target cannot conflict
with Whisper's native build. The GigaAM flavor bypasses Qwen punctuation because
the model already emits cased Russian punctuation.

## Required comparison table

| Variant | ASR implementation | WER/CER | False accepts | 1-minute time | RAM | APK |
|---|---|---:|---:|---:|---:|---:|
| Whisper | implemented | Not measured on a labelled corpus | Not measured | Not measured on the user's phone | Not measured | measured in `VALIDATION.md` |
| GigaAM | transcribe.cpp, GGUF Q4_K_M, token timestamps | Not measured | Not measured | Not measured on the user's phone | Not measured | `LocalVoices-4.0-GigaAM.apk` |
| Whisper → GigaAM | not yet Android-integrated | Not measured | Not measured | Not measured | Not measured | not released |
| GigaAM → Whisper | not yet Android-integrated | Not measured | Not measured | Not measured | Not measured | not released |

No subjective result is presented as WER or DER without reference transcripts
and speaker labels.

## Version 4.0: real GigaAM flavor

- `:app:assembleGigaamDebug`: BUILD SUCCESSFUL for ARM64 and x86_64.
- APK: `deliverables/LocalVoices-4.0-GigaAM.apk`, 896,846,790 bytes,
  SHA-256 `AAAB1B83E148AA11390DFDF77233CE7FB37DD77E517857C02A509A0A9F01525C`.
- The APK bundles `gigaam-v3-e2e-ctc-Q4_K_M.gguf` (182,497,888 bytes,
  SHA-256 `35581e11e048eef785657cff07e5fced794bba6d9c75143257f6452c8aeea655`).
- The preserved Whisper flavor also builds successfully as
  `deliverables/LocalVoices-4.0-Whisper.apk` (SHA-256
  `05E1FC7EF1F56C38D887AFE1E9B0A6F97FF936C2E6790DE32A7FBEFE152454AE`).
- Source backup before the experiment: `deliverables/LocalVoices-source-pre-experiment-20261106.zip`.
