"""
음성 트리거 워커.

마이크에서 음성을 계속 듣다가 VAD(Voice Activity Detection)로 발화 한 덩어리를 잡고,
faster-whisper로 텍스트 변환한 뒤 Spring Boot 서버에 POST.

필수 설치:
  pip install faster-whisper webrtcvad sounddevice numpy requests

faster-whisper는 처음 실행 시 모델을 다운로드함 (small=~500MB, base=~140MB).

마이크 디바이스 목록 보기:
  python -c "import sounddevice; print(sounddevice.query_devices())"

특정 마이크 지정:
  python scripts/stt_worker.py --device-index 1

사용 예시:
  python scripts/stt_worker.py --model small --language ko
"""
import argparse
import json
import queue
import sys
import threading
import time
from collections import deque
from pathlib import Path

import numpy as np
import requests
import sounddevice as sd
import webrtcvad
from faster_whisper import WhisperModel


# 봇 발화 추정 종료 시각 (epoch). 이 시각까지는 audio_queue에 들어오는 PCM을 드롭해
# 봇 응답 중에 마이크에서 PCM이 쌓이고 봇이 말을 끝낸 뒤 줄줄이 transcribe되는
# "발화 지연 누적" 현상을 막는다.
# 정확한 종료 시점은 서버가 알지만(SSE/WebSocket 필요), 일단 글자 수로 추정해서 단순화.
# - SPOKE 응답 받으면 botText 길이로 추정 TTS 재생 시간 계산 → bot_speaking_until 갱신
# - 추정값이 짧으면 마이크 빨리 풀려서 다음 발화 받음 (안전)
# - 추정값이 길면 봇이 이미 끝났는데 마이크가 잠겨있음 (살짝 답답)
# → 보수적으로 글자당 0.2s + 안전 마진 1s 정도.
_bot_speaking_until: float = 0.0
_bot_speaking_lock = threading.Lock()


def _bot_is_speaking() -> bool:
    with _bot_speaking_lock:
        return time.time() < _bot_speaking_until


def _mark_bot_speaking(estimated_sec: float):
    global _bot_speaking_until
    with _bot_speaking_lock:
        _bot_speaking_until = time.time() + estimated_sec


def _drain_queue(q: "queue.Queue"):
    """남은 항목을 전부 버린다. PCM 누적 차단용."""
    while True:
        try:
            q.get_nowait()
        except queue.Empty:
            break


# 오디오 파라미터 (WebRTC VAD 요구사항: 8/16/32 kHz, 10/20/30 ms 프레임)
SAMPLE_RATE = 16000
FRAME_DURATION_MS = 30
FRAME_SIZE = int(SAMPLE_RATE * FRAME_DURATION_MS / 1000)  # 480 샘플
BYTES_PER_SAMPLE = 2  # int16


class SpeechDetector:
    """
    VAD로 발화 시작/끝을 감지.
    - 연속 N개 프레임이 voiced면 발화 시작
    - 연속 M개 프레임이 silence면 발화 끝
    """
    def __init__(self, vad_aggressiveness: int = 2,
                 start_voiced_frames: int = 8,    # 240ms 연속 voiced -> 시작
                 end_silence_frames: int = 25,    # 750ms 연속 silence -> 끝
                 on_speech_start=None):            # 발화 시작 시 호출할 콜백
        self.vad = webrtcvad.Vad(vad_aggressiveness)
        self.start_voiced_frames = start_voiced_frames
        self.end_silence_frames = end_silence_frames
        self.on_speech_start = on_speech_start

        self.is_speaking = False
        self.voiced_window = deque(maxlen=start_voiced_frames)
        self.silence_window = deque(maxlen=end_silence_frames)
        # 발화 시작 직전의 작은 버퍼 (시작 직전 살짝 캡처)
        self.pre_buffer = deque(maxlen=10)
        self.current_utterance = []

    def process_frame(self, frame_bytes: bytes):
        """
        한 프레임 처리. 발화 끝나면 완성된 PCM bytes 반환, 아니면 None.
        """
        is_voiced = self.vad.is_speech(frame_bytes, SAMPLE_RATE)

        if not self.is_speaking:
            self.pre_buffer.append(frame_bytes)
            self.voiced_window.append(is_voiced)
            # 시작 윈도우가 다 voiced면 발화 시작
            if (len(self.voiced_window) == self.start_voiced_frames
                    and all(self.voiced_window)):
                self.is_speaking = True
                self.current_utterance = list(self.pre_buffer)
                self.silence_window.clear()
                print("🎤 발화 감지", flush=True)
                if self.on_speech_start:
                    self.on_speech_start()
            return None
        else:
            self.current_utterance.append(frame_bytes)
            self.silence_window.append(is_voiced)
            # 끝 윈도우가 다 silence면 발화 끝
            if (len(self.silence_window) == self.end_silence_frames
                    and not any(self.silence_window)):
                self.is_speaking = False
                utterance = b"".join(self.current_utterance)
                self.current_utterance = []
                self.voiced_window.clear()
                self.pre_buffer.clear()
                return utterance
            return None


def pcm_bytes_to_float32(pcm_bytes: bytes) -> np.ndarray:
    """int16 PCM bytes -> float32 ndarray [-1, 1]."""
    return np.frombuffer(pcm_bytes, dtype=np.int16).astype(np.float32) / 32768.0


def build_initial_prompt(prompt_file: str, extra_prompt: str) -> str:
    """
    Whisper initial_prompt 조립. 자주 등장하는 단어를 미리 알려주면 인식률이 올라간다.
    - prompt_file: JSON. 객체면 키, 배열이면 원소를 단어 목록으로 사용. (밑줄로 시작하는 키는 메타로 보고 제외)
    - extra_prompt: 사용자가 직접 넣는 추가 힌트 문자열.
    """
    parts: list[str] = []
    if prompt_file:
        path = Path(prompt_file)
        if path.is_file():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                if isinstance(data, dict):
                    words = [k for k in data.keys() if not k.startswith("_")]
                elif isinstance(data, list):
                    words = [str(x) for x in data]
                else:
                    words = []
                if words:
                    parts.append(", ".join(words))
                    print(f"📚 prompt-file 로드: {path.name} ({len(words)} 항목)", flush=True)
            except Exception as e:
                print(f"⚠️ prompt-file 로드 실패 ({path}): {e}", flush=True)
        else:
            print(f"⚠️ prompt-file 없음 (건너뜀): {path}", flush=True)
    if extra_prompt:
        parts.append(extra_prompt)
    prompt = ". ".join(p for p in parts if p).strip()
    # Whisper initial_prompt는 ~224 토큰 권장. 한국어 기준 대략 600자 이내로 자르면 안전.
    if len(prompt) > 600:
        prompt = prompt[:600]
        print("⚠️ initial_prompt 너무 길어 600자로 잘라냄", flush=True)
    return prompt


def _pre_capture(server_url: str):
    """발화 시작 시점에 화면을 미리 캡처해둔다. fire-and-forget."""
    try:
        requests.post(f"{server_url}/api/screen/pre-capture", timeout=5)
    except Exception as e:
        print(f"⚠️ 프리캡처 실패 (무시): {e}", flush=True)


def _pre_capture_async(server_url: str):
    threading.Thread(target=_pre_capture, args=(server_url,), daemon=True).start()


def _post_worker(server_url: str, text: str):
    """
    별도 스레드에서 서버 호출. 응답 도착 = 봇 LLM+TTS+재생 모두 끝난 시점이라
    (AudioPlayer.play()가 재생 끝까지 블로킹 후 HTTP 응답이 와서 그렇다) 잠금을 즉시 해제한다.
    """
    try:
        r = requests.post(
            f"{server_url}/api/react/speech",
            json={"text": text},
            timeout=60,
        )
        if r.ok:
            data = r.json()
            result = data.get("result")
            bot_text = data.get("botText") or ""
            print(f"📡 서버 응답: {result} | {bot_text}", flush=True)
        else:
            print(f"⚠️ 서버 응답 {r.status_code}: {r.text}", flush=True)
    except Exception as e:
        print(f"⚠️ 서버 호출 실패: {e}", flush=True)
    finally:
        # 응답 받았으면(혹은 실패했으면) 봇 발화는 끝났거나 안 한 거.
        # 봇 자기 목소리 마지막 잔향 컷용으로 0.5초만 더 잠그고 풀어준다.
        _mark_bot_speaking(0.5)


def post_to_server(server_url: str, text: str):
    """
    fire-and-forget. 메인 루프가 응답 안 기다림 → 다음 발화 즉시 transcribe 가능.

    POST 보낸 직후부터 보수적으로 마이크 잠금 (봇이 LLM+TTS+재생 처리 중 → 그 시간 동안
    들어오는 사용자/봇 자기 목소리 PCM 드롭). 응답이 오면 _post_worker가 잠금 즉시 해제.
    """
    # 봇 응답 latency 보수적 상한. CLI 콜드 스타트 ~10s + TTS 합성/재생 ~3s + 안전 마진 2s.
    # 응답이 더 빨리 오면 _post_worker가 자동으로 풀어주므로 너무 짧게 잡지 말 것.
    _mark_bot_speaking(15.0)
    threading.Thread(
        target=_post_worker,
        args=(server_url, text),
        daemon=True,
    ).start()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--server-url", default="http://localhost:8080")
    parser.add_argument("--model", default="small",
                        help="faster-whisper 모델: tiny, base, small, medium, large-v3")
    parser.add_argument("--language", default="ko")
    parser.add_argument("--device-index", type=int, default=None,
                        help="마이크 디바이스 인덱스. 안 주면 기본 입력")
    parser.add_argument("--vad-aggressiveness", type=int, default=2,
                        help="0(관대)~3(엄격). 게임 BGM 섞이면 3 추천")
    parser.add_argument("--compute-type", default="int8",
                        help="int8(CPU 빠름), float16(GPU), float32(정확)")
    parser.add_argument("--device", default="cpu",
                        help="cpu, cuda, auto. cuda는 NVIDIA cuBLAS 설치 필요")
    parser.add_argument("--beam-size", type=int, default=5,
                        help="빔 서치 크기. 1=속도 우선, 5=권장, 10=정확도 우선(느림)")
    parser.add_argument("--initial-prompt", default="",
                        help="Whisper에 미리 알려줄 단어/문장 (인식률↑). 예: 게임 용어, 사람 이름")
    parser.add_argument("--prompt-file", default="",
                        help="JSON 파일 경로. 객체면 키 목록, 배열이면 원소를 initial_prompt에 추가")
    parser.add_argument("--min-avg-logprob", type=float, default=-1.0,
                        help="Whisper 세그먼트 avg_logprob 최솟값. 이보다 낮으면 hallucination으로 보고 드롭. "
                             "비음성 노이즈에서 -1.5 이하가 흔함. 더 공격적 필터링 원하면 -0.8 권장.")
    args = parser.parse_args()

    initial_prompt = build_initial_prompt(args.prompt_file, args.initial_prompt)
    if initial_prompt:
        print(f"📚 initial_prompt 활성 ({len(initial_prompt)}자)", flush=True)

    print(f"🤖 Whisper 모델 로딩: {args.model} ({args.compute_type}, device={args.device})", flush=True)
    model = WhisperModel(args.model, device=args.device, compute_type=args.compute_type)
    print("✅ 모델 로딩 완료", flush=True)

    detector = SpeechDetector(
        vad_aggressiveness=args.vad_aggressiveness,
        on_speech_start=lambda: _pre_capture_async(args.server_url),
    )
    audio_queue: "queue.Queue[bytes]" = queue.Queue()

    def audio_callback(indata, frames, time_info, status):
        if status:
            print(f"⚠️ 오디오 상태: {status}", file=sys.stderr, flush=True)
        # int16 PCM bytes로 변환해서 큐에 넣음
        pcm = (indata[:, 0] * 32767).astype(np.int16).tobytes()
        audio_queue.put(pcm)

    print(f"🎙️ 마이크 시작 (device={args.device_index})", flush=True)
    stream = sd.InputStream(
        samplerate=SAMPLE_RATE,
        channels=1,
        dtype="float32",
        blocksize=FRAME_SIZE,
        device=args.device_index,
        callback=audio_callback,
    )
    stream.start()

    try:
        buffer = b""
        was_bot_speaking = False
        while True:
            chunk = audio_queue.get()

            # 봇 발화 추정 시간 동안엔 마이크 PCM 자체를 버린다.
            # 이렇게 안 하면 봇이 말하는 5~10초 동안 audio_queue에 PCM이 쌓이고,
            # 봇 발화 끝난 뒤 줄줄이 VAD/transcribe 돌면서 "한참 전에 한 말"이 늦게 처리됨.
            # buffer까지 초기화해야 부분 frame이 다음 발화로 잘못 합쳐지지 않음.
            if _bot_is_speaking():
                if not was_bot_speaking:
                    print("🔇 봇 발화 중 — 마이크 입력 드롭 시작", flush=True)
                    was_bot_speaking = True
                _drain_queue(audio_queue)
                buffer = b""
                # detector 상태도 리셋 (봇 끝난 뒤 첫 발화 깔끔하게 잡기 위해)
                detector.is_speaking = False
                detector.voiced_window.clear()
                detector.silence_window.clear()
                detector.pre_buffer.clear()
                detector.current_utterance = []
                continue
            if was_bot_speaking:
                print("🎙️ 봇 발화 종료 — 마이크 입력 재개", flush=True)
                was_bot_speaking = False

            buffer += chunk
            # FRAME_SIZE * BYTES_PER_SAMPLE 만큼씩 끊어서 VAD에 전달
            frame_bytes_size = FRAME_SIZE * BYTES_PER_SAMPLE
            while len(buffer) >= frame_bytes_size:
                frame = buffer[:frame_bytes_size]
                buffer = buffer[frame_bytes_size:]
                utterance = detector.process_frame(frame)
                if utterance is None:
                    continue

                # 발화 완성. Whisper에 보냄.
                duration_sec = len(utterance) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                print(f"📝 발화 완료 ({duration_sec:.1f}s). STT 시작...", flush=True)
                start = time.time()
                audio_float = pcm_bytes_to_float32(utterance)
                segments, _ = model.transcribe(
                    audio_float,
                    language=args.language,
                    beam_size=args.beam_size,
                    vad_filter=False,           # 이미 VAD 거침
                    no_speech_threshold=0.6,
                    initial_prompt=initial_prompt or None,
                    condition_on_previous_text=False,  # 직전 출력 끌어와 hallucinate 하는 거 방지
                    temperature=0.0,                    # 결정론적 디코딩
                )
                # 세그먼트 신뢰도 집계 — hallucination은 보통 avg_logprob가 매우 낮음
                seg_list = list(segments)
                text = "".join(s.text for s in seg_list).strip()
                min_logprob = min(
                    (s.avg_logprob for s in seg_list if s.avg_logprob is not None),
                    default=0.0,
                )
                elapsed = time.time() - start
                print(f"📝 STT 결과 ({elapsed:.1f}s, logprob={min_logprob:.2f}): '{text}'", flush=True)

                if not text:
                    continue
                if min_logprob < args.min_avg_logprob:
                    print(f"🚫 낮은 신뢰도 ({min_logprob:.2f} < {args.min_avg_logprob}) — hallucination으로 보고 드롭",
                          flush=True)
                    continue
                post_to_server(args.server_url, text)

    except KeyboardInterrupt:
        print("\n👋 종료", flush=True)
    finally:
        stream.stop()
        stream.close()


if __name__ == "__main__":
    main()
