"""Minimal AI inference adapter for the building-safety platform.

The supplied weight file is retained at models/last.pt. Real inference is opt-in
because the ZIP does not include a validated runtime, label map, or test metrics.
"""

from __future__ import annotations

import base64
import json
import os
import tempfile
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODEL_PATH = ROOT / "models" / "last.pt"
ENABLE_MODEL = os.getenv("AI_ENABLE_MODEL", "false").lower() == "true"
DEMO_MODE = os.getenv("AI_DEMO_MODE", "false").lower() == "true"
_model = None


def health_payload() -> dict:
    mode = "MODEL" if ENABLE_MODEL else "DEMO_SAMPLE" if DEMO_MODE else "DISABLED"
    return {
        "status": "UP",
        "mode": mode,
        "modelFilePresent": MODEL_PATH.exists(),
        "modelFile": "models/last.pt",
        "notice": "真实推理需显式启用并完成独立测试集验证。",
    }


def infer(payload: dict) -> tuple[int, dict]:
    if DEMO_MODE and not ENABLE_MODEL:
        return HTTPStatus.OK, {
            "mode": "DEMO_SAMPLE",
            "modelVersion": "supplied-last.pt-unvalidated",
            "inferenceTimeMs": None,
            "detections": [
                {"riskType": "未佩戴安全帽", "confidence": 0.91, "box": [120, 80, 260, 360]}
            ],
            "notice": "这是显式启用的固定演示结果，不代表模型真实推理。",
        }
    if not ENABLE_MODEL:
        return HTTPStatus.SERVICE_UNAVAILABLE, {
            "code": "MODEL_DISABLED",
            "message": "AI 模型未启用。设置 AI_ENABLE_MODEL=true 并安装依赖后重启。",
        }
    image_base64 = payload.get("imageBase64")
    if not image_base64:
        return HTTPStatus.BAD_REQUEST, {"code": "IMAGE_REQUIRED", "message": "imageBase64 不能为空"}
    try:
        raw = base64.b64decode(image_base64, validate=True)
    except ValueError:
        return HTTPStatus.BAD_REQUEST, {"code": "INVALID_IMAGE", "message": "imageBase64 格式无效"}
    if len(raw) > 8 * 1024 * 1024:
        return HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"code": "IMAGE_TOO_LARGE", "message": "图片不能超过 8 MB"}

    global _model
    try:
        if _model is None:
            from ultralytics import YOLO  # Imported only when explicitly enabled.

            _model = YOLO(str(MODEL_PATH))
        with tempfile.NamedTemporaryFile(suffix=".jpg", delete=False) as temporary:
            temporary.write(raw)
            image_path = temporary.name
        try:
            results = _model.predict(image_path, verbose=False)
            detections = []
            for result in results:
                names = result.names
                for box in result.boxes:
                    class_id = int(box.cls.item())
                    detections.append({
                        "riskType": names.get(class_id, str(class_id)),
                        "confidence": round(float(box.conf.item()), 4),
                        "box": [round(float(value), 2) for value in box.xyxy[0].tolist()],
                    })
            return HTTPStatus.OK, {
                "mode": "MODEL",
                "modelVersion": MODEL_PATH.name,
                "detections": detections,
                "notice": "输出仍需经过人工复核。",
            }
        finally:
            Path(image_path).unlink(missing_ok=True)
    except Exception as error:  # Runtime/model errors must not expose a stack trace over HTTP.
        return HTTPStatus.INTERNAL_SERVER_ERROR, {"code": "INFERENCE_FAILED", "message": str(error)[:300]}


class Handler(BaseHTTPRequestHandler):
    server_version = "SiteSafeAI/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._write(HTTPStatus.OK, health_payload())
        else:
            self._write(HTTPStatus.NOT_FOUND, {"code": "NOT_FOUND", "message": "接口不存在"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/infer":
            self._write(HTTPStatus.NOT_FOUND, {"code": "NOT_FOUND", "message": "接口不存在"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length <= 0 or length > 12 * 1024 * 1024:
                raise ValueError("请求体大小无效")
            payload = json.loads(self.rfile.read(length))
            status, body = infer(payload)
            self._write(status, body)
        except (ValueError, json.JSONDecodeError) as error:
            self._write(HTTPStatus.BAD_REQUEST, {"code": "INVALID_JSON", "message": str(error)})

    def _write(self, status: int, payload: dict) -> None:
        encoded = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def log_message(self, message: str, *args: object) -> None:
        print(f"AI {self.address_string()} {message % args}")


if __name__ == "__main__":
    port = int(os.getenv("AI_PORT", "5001"))
    print(f"AI adapter: http://127.0.0.1:{port} ({health_payload()['mode']})")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
