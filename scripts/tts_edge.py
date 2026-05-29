"""
Microsoft Edge TTS 무료 래퍼.
Spring Boot에서 ProcessBuilder로 호출.

사용법:
  python tts_edge.py --text "안녕" --voice ko-KR-SunHiNeural --rate "+0%" --pitch "+0Hz" --output out.mp3
  # --text 생략 시 stdin에서 본문을 읽음 (명령줄 인자 인용 이슈 회피용):
  echo 안녕 | python tts_edge.py --voice ko-KR-SunHiNeural --output out.mp3

가용 한국어 음성 (Edge TTS는 현재 3개만 살아있음):
  - ko-KR-SunHiNeural        (여)
  - ko-KR-InJoonNeural       (남)
  - ko-KR-HyunsuMultilingualNeural (남, 다국어)

필수 패키지:
  pip install edge-tts
"""
import argparse
import asyncio
import sys

import edge_tts


async def synthesize(text: str, voice: str, rate: str, pitch: str, output: str) -> None:
    communicate = edge_tts.Communicate(text, voice, rate=rate, pitch=pitch)
    await communicate.save(output)


def main() -> int:
    parser = argparse.ArgumentParser()
    # --text 생략 시 stdin에서 읽음. 호출 측이 따옴표 등 특수문자가 섞인 본문을
    # 명령줄 인자로 넘기면 OS별 인자 인용이 깨질 수 있어 stdin 경로를 둔다.
    parser.add_argument("--text", default=None)
    parser.add_argument("--voice", default="ko-KR-SunHiNeural")
    parser.add_argument("--rate", default="+0%")
    parser.add_argument("--pitch", default="+0Hz")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    text = args.text
    if text is None:
        text = sys.stdin.buffer.read().decode("utf-8")
    text = text.strip()
    if not text:
        print("ERROR: empty text (no --text and empty stdin)", file=sys.stderr)
        return 1

    try:
        asyncio.run(synthesize(text, args.voice, args.rate, args.pitch, args.output))
        print(f"OK: {args.output}")
        return 0
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
