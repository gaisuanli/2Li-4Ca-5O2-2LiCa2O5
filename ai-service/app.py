"""Minimal AI inference adapter for the building-safety platform.

支持三种推理模式：
1. QWEN_VL：调用阿里云通义千问 VL 视觉大模型 API（推荐，无需本地模型）
2. MODEL：使用本地 YOLOv8 模型推理（需要 ultralytics 依赖和模型文件）
3. DEMO_SAMPLE：返回固定演示结果（用于测试）

通过环境变量 AI_MODE 选择模式：qwen_vl / model / demo / disabled
"""

from __future__ import annotations

import base64
import json
import os
import tempfile
import urllib.request
import urllib.error
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODEL_PATH = ROOT / "models" / "last.pt"

# ===== 模式配置 =====
# AI_MODE 优先级高于旧的 ENABLE_MODEL / DEMO_MODE，向后兼容
AI_MODE = os.getenv("AI_MODE", "").lower().strip()
ENABLE_MODEL = os.getenv("AI_ENABLE_MODEL", "false").lower() == "true"
DEMO_MODE = os.getenv("AI_DEMO_MODE", "false").lower() == "true"

if not AI_MODE:
    if ENABLE_MODEL:
        AI_MODE = "model"
    elif DEMO_MODE:
        AI_MODE = "demo"
    else:
        AI_MODE = "disabled"

# ===== 通义千问 VL 配置 =====
QWEN_VL_API_KEY = os.getenv("QWEN_VL_API_KEY", "")
QWEN_VL_MODEL = os.getenv("QWEN_VL_MODEL", "qwen-vl-max")
QWEN_VL_BASE_URL = os.getenv(
    "QWEN_VL_BASE_URL",
    "https://dashscope.aliyuncs.com/compatible-mode/v1",
).rstrip("/")
QWEN_VL_TIMEOUT = int(os.getenv("QWEN_VL_TIMEOUT", "30"))

# ===== 通义千问 VL 系统提示词 =====
QWEN_VL_SYSTEM_PROMPT = """你是建筑工地安全检测助手。分析图片并识别危险行为。

危险类型枚举（riskType 必须使用以下英文值）：
NO_HELMET=未戴安全帽, NO_VEST=未穿反光衣, NO_SAFETY_BELT=未系安全带, CLIMBING=违规攀爬, FALL=跌倒, SMOKING=抽烟, OPEN_FLAME=明火, CROWDING=人员聚集, ILLEGAL_ENTRY=违规进入, PHONE_CALL=打电话, WORKING_AT_HEIGHT=无防护高处作业

【输出要求】只输出一个 JSON 对象，不要任何解释、分析、markdown 标记或额外文字。
有危险：{"detections": [{"riskType": "NO_HELMET", "confidence": 0.95}]}
无危险：{"detections": []}
confidence 取 0.0-1.0。"""

_model = None


def health_payload() -> dict:
    mode = AI_MODE.upper()
    payload = {
        "status": "UP",
        "mode": mode,
        "modelFilePresent": MODEL_PATH.exists(),
        "modelFile": "models/last.pt",
    }
    if AI_MODE == "qwen_vl":
        payload["modelFile"] = QWEN_VL_MODEL
        payload["modelFilePresent"] = bool(QWEN_VL_API_KEY)
        payload["notice"] = "通义千问 VL 视觉推理模式，输出仍需经过人工复核。"
    elif AI_MODE == "model":
        payload["notice"] = "本地 YOLO 模型推理模式，输出仍需经过人工复核。"
    elif AI_MODE == "demo":
        payload["notice"] = "演示模式返回固定结果，不代表真实推理。"
    else:
        payload["notice"] = "适配器已停用，设置 AI_MODE=qwen_vl 启用通义千问 VL。"
    return payload


def _qwen_vl_infer(image_base64: str) -> tuple[int, dict]:
    """调用通义千问 VL OpenAI 兼容接口进行视觉推理"""
    if not QWEN_VL_API_KEY:
        return HTTPStatus.SERVICE_UNAVAILABLE, {
            "code": "QWEN_VL_NOT_CONFIGURED",
            "message": "未配置 QWEN_VL_API_KEY 环境变量",
        }

    # 通义千问 VL OpenAI 兼容接口支持 data URL 传入 base64 图片
    request_body = {
        "model": QWEN_VL_MODEL,
        "messages": [
            {"role": "system", "content": QWEN_VL_SYSTEM_PROMPT},
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": "识别这张图片中的建筑工地危险行为，只输出 JSON。"},
                    {
                        "type": "image_url",
                        "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"},
                    },
                ],
            },
        ],
        "temperature": 0.1,
    }

    try:
        body_bytes = json.dumps(request_body).encode("utf-8")
        req = urllib.request.Request(
            f"{QWEN_VL_BASE_URL}/chat/completions",
            data=body_bytes,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {QWEN_VL_API_KEY}",
            },
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=QWEN_VL_TIMEOUT) as response:
            response_text = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        error_body = error.read().decode("utf-8", errors="replace")[:500]
        return HTTPStatus.BAD_GATEWAY, {
            "code": "QWEN_VL_HTTP_ERROR",
            "message": f"通义千问 API 返回 HTTP {error.code}: {error_body}",
        }
    except urllib.error.URLError as error:
        return HTTPStatus.SERVICE_UNAVAILABLE, {
            "code": "QWEN_VL_UNREACHABLE",
            "message": f"无法连接通义千问 API: {error.reason}",
        }
    except Exception as error:
        return HTTPStatus.INTERNAL_SERVER_ERROR, {
            "code": "QWEN_VL_REQUEST_FAILED",
            "message": str(error)[:300],
        }

    # 解析通义千问 VL 响应
    try:
        response_json = json.loads(response_text)
        content = response_json["choices"][0]["message"]["content"]
    except (json.JSONDecodeError, KeyError, IndexError) as error:
        return HTTPStatus.BAD_GATEWAY, {
            "code": "QWEN_VL_INVALID_RESPONSE",
            "message": f"无法解析通义千问响应: {error}",
        }

    # 通义千问可能返回 markdown 代码块包裹的 JSON，需要提取
    detections = _extract_detections(content)
    if detections is None:
        return HTTPStatus.BAD_GATEWAY, {
            "code": "QWEN_VL_PARSE_FAILED",
            "message": f"通义千问未返回有效 JSON，原始内容: {content[:300]}",
        }

    return HTTPStatus.OK, {
        "mode": "QWEN_VL",
        "modelVersion": QWEN_VL_MODEL,
        "detections": detections,
        "notice": "通义千问 VL 视觉推理结果，需经过人工复核。",
    }


def _extract_detections(content: str) -> list | None:
    """从通义千问 VL 的文本响应中提取 detections 列表

    支持多种格式：
    - 纯 JSON: {"detections": [...]}
    - markdown 代码块: ```json {"detections": [...]} ```
    - 混合文本中嵌入的 JSON
    """
    if not content:
        return []

    content = content.strip()

    # 尝试直接解析
    try:
        data = json.loads(content)
        if isinstance(data, dict) and "detections" in data:
            return _validate_detections(data["detections"])
    except json.JSONDecodeError:
        pass

    # 尝试从 markdown 代码块中提取
    if "```" in content:
        parts = content.split("```")
        for part in parts:
            part = part.strip()
            # 去掉语言标识（json、```等）
            if part.startswith("json"):
                part = part[4:].strip()
            try:
                data = json.loads(part)
                if isinstance(data, dict) and "detections" in data:
                    return _validate_detections(data["detections"])
            except json.JSONDecodeError:
                continue

    # 尝试从文本中查找第一个 JSON 对象
    start = content.find("{")
    end = content.rfind("}")
    if start != -1 and end != -1 and end > start:
        try:
            data = json.loads(content[start : end + 1])
            if isinstance(data, dict) and "detections" in data:
                return _validate_detections(data["detections"])
        except json.JSONDecodeError:
            pass

    return None


def _validate_detections(detections) -> list:
    """校验并清洗检测结果"""
    if not isinstance(detections, list):
        return []
    valid = []
    valid_types = {
        "NO_HELMET", "NO_VEST", "NO_SAFETY_BELT", "CLIMBING", "FALL",
        "SMOKING", "OPEN_FLAME", "CROWDING", "ILLEGAL_ENTRY",
        "PHONE_CALL", "WORKING_AT_HEIGHT",
    }
    for item in detections:
        if not isinstance(item, dict):
            continue
        risk_type = item.get("riskType", "")
        confidence = item.get("confidence", 0)
        if not isinstance(risk_type, str) or not risk_type:
            continue
        # 允许标准枚举值或中文描述（统一转大写）
        risk_type = risk_type.upper().strip()
        if risk_type not in valid_types:
            # 未知的 riskType 也保留，但标记为 OTHER
            risk_type = f"OTHER_{risk_type}" if not risk_type.startswith("OTHER") else risk_type
        try:
            confidence = float(confidence)
        except (TypeError, ValueError):
            confidence = 0.5
        if confidence < 0:
            confidence = 0.0
        elif confidence > 1:
            confidence = 1.0
        valid.append({"riskType": risk_type, "confidence": round(confidence, 4)})
    return valid


def _yolo_infer(image_base64: str) -> tuple[int, dict]:
    """使用本地 YOLOv8 模型推理"""
    global _model
    try:
        if _model is None:
            from ultralytics import YOLO  # Imported only when explicitly enabled.

            _model = YOLO(str(MODEL_PATH))
        raw = base64.b64decode(image_base64, validate=True)
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
    except Exception as error:
        return HTTPStatus.INTERNAL_SERVER_ERROR, {
            "code": "INFERENCE_FAILED",
            "message": str(error)[:300],
        }


def infer(payload: dict) -> tuple[int, dict]:
    if AI_MODE == "disabled":
        return HTTPStatus.SERVICE_UNAVAILABLE, {
            "code": "MODEL_DISABLED",
            "message": "AI 适配器未启用。设置 AI_MODE=qwen_vl 启用通义千问 VL。",
        }

    if AI_MODE == "demo":
        return HTTPStatus.OK, {
            "mode": "DEMO_SAMPLE",
            "modelVersion": "supplied-last.pt-unvalidated",
            "inferenceTimeMs": None,
            "detections": [
                {"riskType": "NO_HELMET", "confidence": 0.91, "box": [120, 80, 260, 360]}
            ],
            "notice": "这是显式启用的固定演示结果，不代表模型真实推理。",
        }

    image_base64 = payload.get("imageBase64")
    if not image_base64:
        return HTTPStatus.BAD_REQUEST, {"code": "IMAGE_REQUIRED", "message": "imageBase64 不能为空"}
    try:
        raw = base64.b64decode(image_base64, validate=True)
    except ValueError:
        return HTTPStatus.BAD_REQUEST, {"code": "INVALID_IMAGE", "message": "imageBase64 格式无效"}
    if len(raw) > 8 * 1024 * 1024:
        return HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {
            "code": "IMAGE_TOO_LARGE",
            "message": "图片不能超过 8 MB",
        }

    if AI_MODE == "qwen_vl":
        return _qwen_vl_infer(image_base64)

    if AI_MODE == "model":
        return _yolo_infer(image_base64)

    return HTTPStatus.SERVICE_UNAVAILABLE, {
        "code": "UNKNOWN_MODE",
        "message": f"未知的 AI_MODE: {AI_MODE}",
    }


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
    mode = AI_MODE.upper()
    print(f"AI adapter: http://127.0.0.1:{port} (mode={mode})")
    if AI_MODE == "qwen_vl":
        if QWEN_VL_API_KEY:
            print(f"  通义千问 VL: model={QWEN_VL_MODEL}, base={QWEN_VL_BASE_URL}")
        else:
            print("  警告: 未配置 QWEN_VL_API_KEY 环境变量")
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
