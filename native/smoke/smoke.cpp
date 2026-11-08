#include <whisper.h>
#include <fstream>
#include <vector>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <cstring>
extern "C" int localvoices_encoder_pool(whisper_context *, int, float *, int);
int main(int argc, char ** argv) {
    if (argc != 3) { std::cerr << "Usage: encoder-smoke model.bin 16k-mono.wav\n"; return 2; }
    try {
        std::ifstream file(argv[2], std::ios::binary);
        char riff[12]; file.read(riff, 12);
        if (!file || std::memcmp(riff, "RIFF", 4) || std::memcmp(riff + 8, "WAVE", 4)) throw std::runtime_error("Not WAV");
        std::vector<float> audio;
        while (file) {
            char tag[4]; uint32_t size = 0; file.read(tag, 4); file.read(reinterpret_cast<char *>(&size), 4);
            if (!file) break;
            if (!std::memcmp(tag, "data", 4)) {
                std::vector<int16_t> pcm(size / 2); file.read(reinterpret_cast<char *>(pcm.data()), size);
                for (auto v : pcm) audio.push_back(v / 32768.0f);
                break;
            } else file.seekg(size + (size & 1), std::ios::cur);
        }
        if (audio.size() < 32000) throw std::runtime_error("No audio");
        auto params = whisper_context_default_params(); params.use_gpu = false;
        auto ctx = whisper_init_from_file_with_params(argv[1], params);
        if (!ctx) throw std::runtime_error("No model");
        auto p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        p.language = "en"; p.n_threads = 4; p.print_progress = false; p.print_realtime = false; p.token_timestamps = true; p.split_on_word = true; p.max_len = 1;
        if (whisper_full(ctx, p, audio.data(), audio.size())) throw std::runtime_error("ASR failed");
        std::string transcript;
        for (int i = 0; i < whisper_full_n_segments(ctx); ++i) transcript += whisper_full_get_segment_text(ctx, i);
        if (transcript.find("country") == std::string::npos) throw std::runtime_error("Unexpected JFK ASR output");
        std::cout << "ASR: " << transcript << "\n";
        const int n = std::min((int)audio.size(), 80000);
        std::vector<float> first(2048), second(2048);
        int dim = 0;
        for (int repeat = 0; repeat < 2; ++repeat) {
            if (whisper_pcm_to_mel(ctx, audio.data(), n, 4) || whisper_encode(ctx, 0, 4)) throw std::runtime_error("Encode failed");
            auto & v = repeat == 0 ? first : second;
            dim = localvoices_encoder_pool(ctx, (n + 319) / 320, v.data(), v.size());
            if (dim != 512) throw std::runtime_error("Expected base 512D output");
            double norm = 0; for (int i = 0; i < dim; ++i) { if (!std::isfinite(v[i])) throw std::runtime_error("Non-finite output"); norm += v[i] * v[i]; }
            if (std::abs(norm - 1.0) > 1e-4) throw std::runtime_error("Embedding not normalized");
        }
        double similarity = 0; for (int i = 0; i < dim; ++i) similarity += first[i] * second[i];
        if (similarity < .9999) throw std::runtime_error("Non-deterministic output");
        whisper_free(ctx);
        std::cout << "PASS: real encoder pooling, dim=" << dim << ", repeat cosine=" << similarity << "\n";
    } catch (const std::exception & e) { std::cerr << e.what() << "\n"; return 1; }
}
