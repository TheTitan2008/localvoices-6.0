"""Host smoke test for downloaded Silero/WeSpeaker models, not a diarization benchmark.
Requires numpy and sherpa-onnx. Actual Android inference still needs device validation.
Usage: python scripts/verify-models.py models third_party/whisper.cpp/samples/jfk.wav
"""
import json
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx as sherpa


def verify(models, wav):
    with wave.open(str(wav), 'rb') as source:
        assert source.getframerate() == 16000 and source.getnchannels() == 1
        assert source.getsampwidth() == 2
        samples = np.frombuffer(source.readframes(source.getnframes()), dtype='<i2').astype(np.float32) / 32768
    config = sherpa.VadModelConfig()
    config.silero_vad.model = str(models / 'silero_vad.onnx')
    config.sample_rate = 16000
    vad = sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=120)
    for i in range(0, len(samples), 512):
        frame = np.zeros(512, dtype=np.float32)
        frame[:len(samples[i:i+512])] = samples[i:i+512]
        vad.accept_waveform(frame)
    vad.flush()
    spans = []
    while not vad.empty():
        segment = vad.front
        spans.append((segment.start / 16000, len(segment.samples) / 16000))
        vad.pop()
    assert sum(duration for _, duration in spans) > 3, 'Speech was not detected'
    silence_vad = sherpa.VoiceActivityDetector(config, buffer_size_in_seconds=10)
    for _ in range(100):
        silence_vad.accept_waveform(np.zeros(512, dtype=np.float32))
    silence_vad.flush()
    assert silence_vad.empty(), 'Silence was detected as speech'
    encoder_config = sherpa.SpeakerEmbeddingExtractorConfig(
        model=str(models / 'speaker.onnx'), num_threads=2, provider='cpu')
    encoder = sherpa.SpeakerEmbeddingExtractor(encoder_config)
    vectors = []
    for audio in [samples[:80000], samples[-80000:]]:
        stream = encoder.create_stream()
        stream.accept_waveform(sample_rate=16000, waveform=audio)
        stream.input_finished()
        assert encoder.is_ready(stream)
        vector = np.asarray(encoder.compute(stream), dtype=np.float32)
        assert len(vector) >= 128 and np.isfinite(vector).all()
        norm = np.linalg.norm(vector)
        assert norm > 0
        vectors.append(vector / norm)
    return dict(sherpa_version=sherpa.__version__, speech_spans=spans,
                silence_rejected=True, embedding_dimension=len(vectors[0]),
                same_recording_cosine=float(vectors[0] @ vectors[1]))


if __name__ == '__main__':
    print(json.dumps(verify(Path(sys.argv[1]), Path(sys.argv[2])), indent=2))
