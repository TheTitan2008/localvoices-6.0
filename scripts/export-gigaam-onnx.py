"""Export GigaAM-v3 ASR to ONNX for the experimental Android backend.

This is intentionally a build-time tool. It downloads no audio and the APK
must contain the resulting ONNX files before a GigaAM product flavor can be
released. The official GigaAM package currently performs the model export;
Android inference still needs a decoder/tokenizer implementation and must not
silently fall back to Whisper.
"""
import argparse
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="v3_e2e_ctc", choices=("v3_e2e_ctc", "v3_e2e_rnnt"))
    parser.add_argument("--out", type=Path, default=Path("models/gigaam-onnx"))
    args = parser.parse_args()
    try:
        import gigaam
    except ImportError as exc:
        raise SystemExit("Install the official GigaAM package and its PyTorch/ONNX dependencies first") from exc
    args.out.mkdir(parents=True, exist_ok=True)
    model = gigaam.load_model(args.model)
    model.to_onnx(dir_path=str(args.out), dtype="float32")
    print(f"Exported {args.model} to {args.out}")


if __name__ == "__main__":
    main()
