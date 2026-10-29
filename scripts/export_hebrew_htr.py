#!/usr/bin/env python3
"""Export Mishkefet-v1 Hebrew handwriting recognition to quantized ONNX for Android."""
import argparse
import json
import sys
from pathlib import Path

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", required=True, help="Checkout of the upstream heb-ocr project")
    ap.add_argument("--checkpoint", required=True, help="Path to mishkefet-v1.pt")
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()

    source = Path(args.source).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(source))
    torch.set_num_threads(2)

    from hebocr.recognize import Recognizer

    recognizer = Recognizer(args.checkpoint, device="cpu", lm=None, beam_width=0, tta=0)
    model = recognizer.model.cpu().eval()
    charset_path = out_dir / "handwriting_charset.json"
    charset_path.write_text(
        json.dumps(recognizer.charset.chars, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )

    fp32_path = out_dir / "handwriting_htr_fp32.onnx"
    quant_path = out_dir / "handwriting_htr_int8.onnx"
    sample = torch.zeros((1, 1, 64, 512), dtype=torch.float32)

    torch.onnx.export(
        model,
        sample,
        str(fp32_path),
        export_params=True,
        opset_version=17,
        do_constant_folding=True,
        input_names=["images"],
        output_names=["logprobs"],
        dynamic_axes={"images": {3: "width"}, "logprobs": {1: "time_steps"}},
        dynamo=False,
    )
    onnx.checker.check_model(onnx.load(str(fp32_path)))

    quantize_dynamic(
        str(fp32_path),
        str(quant_path),
        weight_type=QuantType.QInt8,
        per_channel=False,
        op_types_to_quantize=["MatMul", "Gemm"],
    )
    fp32_path.unlink(missing_ok=True)

    # Exercise more than one width so a fixed-shape graph cannot be shipped silently.
    session = ort.InferenceSession(str(quant_path), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    expected_classes = len(recognizer.charset.chars)
    for width in (320, 640):
        values = np.zeros((1, 1, 64, width), dtype=np.float32)
        output = session.run(None, {input_name: values})[0]
        if output.ndim != 3 or output.shape[0] != 1 or output.shape[2] != expected_classes:
            raise RuntimeError(f"Unexpected output shape {output.shape}; classes={expected_classes}")
        if output.shape[1] != width // 4:
            raise RuntimeError(f"Dynamic width failed at {width}: got {output.shape[1]} time steps")
        if not np.isfinite(output).all():
            raise RuntimeError("ONNX model returned non-finite values")

    print(f"Created {quant_path} ({quant_path.stat().st_size / 1024 / 1024:.1f} MiB)")
    print(f"Created {charset_path} ({expected_classes} classes)")
    print("Dynamic-width ONNX smoke test passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
