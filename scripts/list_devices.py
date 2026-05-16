"""마이크 디바이스 목록 출력. 사용: python scripts/list_devices.py"""
import sounddevice as sd

print("== 입력 가능한 오디오 장치 ==")
default_in = sd.default.device[0] if sd.default.device else None
for i, d in enumerate(sd.query_devices()):
    if d["max_input_channels"] > 0:
        mark = " <-- 기본" if i == default_in else ""
        print(f"  {i}: {d['name']} (channels={d['max_input_channels']}){mark}")
