"""
입력 가능한 오디오 장치 목록을 JSON으로 출력.
ConfigController가 호출해서 /config UI 마이크 선택 드롭다운에 채워준다.

출력: [{"index": 0, "name": "...", "channels": 2, "default": true}, ...]
"""
import json
import sys

import sounddevice as sd


def main():
    devices = sd.query_devices()
    try:
        default_input = sd.default.device[0]
    except Exception:
        default_input = None

    out = []
    for i, d in enumerate(devices):
        if d.get("max_input_channels", 0) <= 0:
            continue
        out.append({
            "index": i,
            "name": d.get("name", ""),
            "channels": d.get("max_input_channels", 0),
            "default": (i == default_input),
        })

    sys.stdout.write(json.dumps(out, ensure_ascii=False))


if __name__ == "__main__":
    main()
