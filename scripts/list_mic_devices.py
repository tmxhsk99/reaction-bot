"""
입력 가능한 오디오 장치 목록을 JSON으로 출력.
ConfigController가 호출해서 /config UI 마이크 선택 드롭다운에 채워준다.

MME 호스트 API만 노출한다. PortAudio는 같은 물리 마이크를 호스트 API별로
(MME/DirectSound/WASAPI/WDM-KS) 중복 나열하는데:
  - MME는 물리 장치당 1개 + Windows가 임의 샘플레이트를 리샘플링해줘서
    stt_worker의 16kHz 스트림이 항상 열린다.
  - WDM-KS는 독점 접근 + 네이티브 포맷 전용이라 골라도
    PaErrorCode -9996 (Invalid device)으로 죽는 함정.
(트레이드오프: MME는 장치명이 31자에서 잘림)

출력: [{"index": 0, "name": "...", "channels": 2, "default": true}, ...]
"""
import json
import sys

import sounddevice as sd


def main():
    devices = sd.query_devices()
    hostapis = sd.query_hostapis()
    try:
        default_input = sd.default.device[0]
    except Exception:
        default_input = None

    out = []
    for i, d in enumerate(devices):
        if d.get("max_input_channels", 0) <= 0:
            continue
        api_name = hostapis[d.get("hostapi", -1)].get("name", "") if 0 <= d.get("hostapi", -1) < len(hostapis) else ""
        if api_name != "MME":
            continue
        out.append({
            "index": i,
            "name": d.get("name", ""),
            "channels": d.get("max_input_channels", 0),
            "default": (i == default_input),
        })

    # 환경에 MME가 아예 없는 특수 케이스(비-Windows 등)면 필터 없이 전체 노출 (빈 드롭다운 방지).
    if not out:
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
