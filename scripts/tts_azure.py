"""
Azure Speech REST API TTS 래퍼.
Spring Boot에서 ProcessBuilder로 호출.

사용법:
  AZURE_SPEECH_KEY=... AZURE_SPEECH_REGION=koreacentral \
  python tts_azure.py --text "안녕" --voice ko-KR-JiMinNeural --rate "+0%" --pitch "+0Hz" --output out.mp3

필수 환경변수:
  AZURE_SPEECH_KEY    Azure Speech 리소스의 키 (필수)
  AZURE_SPEECH_REGION 리소스 region (예: koreacentral, eastus 등)

필수 패키지:
  pip install requests
"""
import argparse
import os
import sys
import xml.sax.saxutils as su

import requests


def synthesize(text: str, voice: str, rate: str, pitch: str, output: str,
               key: str, region: str) -> int:
    endpoint = f"https://{region}.tts.speech.microsoft.com/cognitiveservices/v1"

    text_escaped = su.escape(text)
    ssml = (
        f"<speak version='1.0' xml:lang='ko-KR'>"
        f"<voice name='{voice}'>"
        f"<prosody rate='{rate}' pitch='{pitch}'>{text_escaped}</prosody>"
        f"</voice>"
        f"</speak>"
    )

    headers = {
        "Ocp-Apim-Subscription-Key": key,
        "Content-Type": "application/ssml+xml",
        "X-Microsoft-OutputFormat": "audio-24khz-48kbitrate-mono-mp3",
        "User-Agent": "reaction-bot",
    }

    resp = requests.post(endpoint, headers=headers,
                         data=ssml.encode("utf-8"), timeout=30)
    if not resp.ok:
        # 401 = 키 오류, 400 = SSML/voice 오류 등
        print(f"ERROR: Azure TTS HTTP {resp.status_code} - {resp.text[:200]}",
              file=sys.stderr)
        return 1
    if not resp.content:
        print("ERROR: Azure TTS가 빈 응답을 보냄 (voice 이름 오류 가능성)",
              file=sys.stderr)
        return 1

    with open(output, "wb") as f:
        f.write(resp.content)
    print(f"OK: {output} ({len(resp.content)} bytes)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--text", required=True)
    parser.add_argument("--voice", default="ko-KR-JiMinNeural")
    parser.add_argument("--rate", default="+0%")
    parser.add_argument("--pitch", default="+0Hz")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    key = os.environ.get("AZURE_SPEECH_KEY")
    region = os.environ.get("AZURE_SPEECH_REGION", "koreacentral")

    if not key:
        print("ERROR: 환경변수 AZURE_SPEECH_KEY 가 설정되지 않음", file=sys.stderr)
        return 1

    try:
        return synthesize(args.text, args.voice, args.rate, args.pitch,
                          args.output, key, region)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
