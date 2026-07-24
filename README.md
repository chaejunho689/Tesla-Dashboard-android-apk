# Tesla Dashboard — Android App

Tesla Fleet API 기반 자체 호스팅 브릿지 서버에 연결하는 안드로이드 대시보드 앱입니다.

---

<!-- 첫 번째 스크린샷: 대시보드 상단 ~ 지도 영역 -->
![Dashboard Overview](screenshots/screenshot_overview.png)

### 대시보드 · 지도

- 배터리 잔량 및 예상 주행거리 실시간 표시
- 실시간 차량 위치 지도 표시
- 총 주행거리 · 소프트웨어 버전 · 차대번호(VIN) 확인
- VIN 길게 눌러 클립보드 복사

---

<!-- 두 번째 스크린샷: 제어 버튼 영역 -->
![Control Panel](screenshots/screenshot_control.png)

### 제어

- 도어 잠금 / 잠금 해제
- 프렁크 · 트렁크 열기 (길게 눌러 확인 후 실행)
- 충전 포트 열기 / 닫기
- 공조 ON/OFF · 온도 설정
- 좌석 히터 5개 · 스티어링 휠 히터 개별 제어
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

---

## 빌드 방법

```bash
# secrets.xml 생성 (실제 값 입력)
cp secrets.xml.example res/values/secrets.xml

# 키스토어 생성 (최초 1회)
keytool -genkey -v -keystore tesla.keystore -alias tesla -keyalg RSA -keysize 2048 -validity 10000

# 빌드
bash build.sh
```
