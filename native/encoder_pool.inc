// Included at the end of pinned whisper.cpp. CPU backend only; no hidden-state API
// is claimed upstream. Read the real encoder output via the ggml backend API.
extern "C" int localvoices_encoder_pool(whisper_context * ctx, int valid_frames, float * output, int capacity) {
    if (!ctx || !ctx->state || !ctx->state->embd_enc) return -1;
    ggml_tensor * t = ctx->state->embd_enc;
    if (t->type != GGML_TYPE_F32 || capacity < t->ne[0]) return -2;
    const int channels = (int)t->ne[0];
    const int frames = std::max(1, std::min(valid_frames, (int)t->ne[1]));
    std::vector<float> row(channels);
    std::fill(output, output + channels, 0.0f);
    for (int frame = 0; frame < frames; ++frame) {
        ggml_backend_tensor_get(t, row.data(), frame * t->nb[1], channels * sizeof(float));
        for (int c = 0; c < channels; ++c) output[c] += row[c] / frames;
    }
    double norm = 0;
    for (int c = 0; c < channels; ++c) norm += output[c] * output[c];
    norm = sqrt(norm);
    if (norm < 1e-10) return -3;
    for (int c = 0; c < channels; ++c) output[c] /= (float)norm;
    return channels;
}
