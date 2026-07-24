# Tesla Dashboard — Android App

<!-- 스크린샷을 여기에 추가하세요 -->
![Dashboard Screenshot](screenshot.png)

---

Tesla Fleet API 기반 자체 호스팅 브릿지 서버에 연결하는 안드로이드 대시보드 앱입니다.
WebView로 브릿지 서버의 웹 UI를 불러오며, 차량 제어·상태 확인을 스마트폰에서 수행합니다.

---

## 주요 기능

- 배터리 잔량 및 예상 주행거리 실시간 확인
- 도어 잠금 / 잠금 해제
- 프렁크 · 트렁크 열기 (길게 눌러 확인 후 실행)
- 충전 포트 열기 / 닫기
- 공조(에어컨/히터) 제어 및 온도 조절
- 좌석 히터 5개 · 스티어링 휠 히터 개별 제어
- 차대번호(VIN) 표시 및 복사
- 차량 슬립 상태 시 자동 Wake 후 명령 전송

---

## 기술 스택

| 항목 | 내용 |
|------|------|
| 앱 타입 | Android WebView (Native Activity) |
| 최소 SDK | Android 7.0 (API 24) |
| 빌드 방식 | javac + d8 + aapt (Gradle 미사용) |
| 통신 | HTTPS (자체 도메인 + Let's Encrypt) |
| 브릿지 서버 | FastAPI + tesla-http-proxy (Docker) |
| 인증 | BRIDGE_TOKEN (단일 토큰 인증) |
| 리버스 프록시 | Caddy (자동 TLS) |

---

## 명령 전송 방식

```
Android App (WebView)
    └─ HTTPS → Caddy (Let's Encrypt TLS)
        └─ FastAPI Bridge
            └─ tesla-http-proxy (mTLS 서명)
                └─ Tesla Fleet API
```

1. 앱이 브릿지 서버 대시보드를 WebView로 로드
2. 버튼 클릭 → 브릿지 `/api/command/{action}` 호출
3. 브릿지가 차량 슬립 여부 확인 → 필요 시 Wake 후 명령 전송
4. tesla-http-proxy가 Fleet API 규격에 맞게 서명하여 전달

---

## 빌드 방법

```bash
# secrets.xml 생성 (실제 값 입력)
cp secrets.xml.example res/values/secrets.xml
# 편집기로 bridge_base, bridge_key 값 입력

# 키스토어 생성 (최초 1회)
keytool -genkey -v -keystore tesla.keystore -alias tesla -keyalg RSA -keysize 2048 -validity 10000

# 빌드
bash build.sh
```

---

## 보안

- `secrets.xml` 및 `*.keystore`는 `.gitignore`로 제외됨
- 브릿지 토큰 없이는 서버 접근 불가
- HTTPS 전용 통신
