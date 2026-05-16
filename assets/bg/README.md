# Background Assets

이 폴더에는 아바타 페이지의 배경 미디어 파일을 두세요.

## 기본 경로

- `bg.mp4` — 권장 (가볍고 부드러움)
- 또는 `bg.gif` (대안)

페이지에서 참조하는 경로는 [src/main/resources/static/avatar/index.html](../../src/main/resources/static/avatar/index.html) 에서 변경 가능합니다.

## 형식 권장

- **MP4** — 약 30초~1분 루프, 1080p 이하 권장. ffmpeg로 GIF에서 변환 시:
  ```powershell
  ffmpeg -i 원본.gif -movflags faststart -pix_fmt yuv420p \
         -vf "scale=trunc(iw/2)*2:trunc(ih/2)*2" bg.mp4
  ```
- **GIF** — 호환성 좋지만 같은 화질에서 mp4보다 10배 큼

## 배경 없이 쓰고 싶을 때

`index.html` 의 `<video class="bg" ...>` 줄을 주석 처리하면 페이지가 투명 배경이 되어 OBS에서 다른 소스와 합성 가능합니다.

자세한 가이드는 프로젝트 루트 [README.md §8.3](../../README.md) 참고.
