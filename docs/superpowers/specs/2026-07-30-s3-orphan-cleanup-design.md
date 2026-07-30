# S3 고아 객체 정리 정책 설계

- 작성일: 2026-07-30
- 범위: `S3Service`, `MemberService`, `MessageService`, 새 이벤트·리스너. **프론트 변경 없음.**
- 선행 문서: `docs/superpowers/specs/2026-07-30-onboarding-photo-design.md` §7

## 1. 배경과 목표

업로드된 이미지를 **삭제하는 코드가 어디에도 없다.** 참조가 끊긴 객체는 버킷에 영구히 남는다.

확인된 누수원 세 가지 중 두 가지를 이번에 다룬다.

| # | 누수원 | 성격 | 이번 범위 |
|---|---|---|---|
| 1 | 프로필 사진 교체 | 사진을 바꿀 때마다 옛 객체가 남는다. 확실히 발생 | **포함** |
| 2 | 이미지 메시지 삭제 | `Message.softDelete()`가 `imageUrl = null`로 참조만 끊는다(`Message.java:72`). 참조 없음이 가장 확실한 케이스 | **포함** |
| 3 | 확정되지 않은 업로드 | 업로드 후 저장 실패·이탈 시 발생 | 제외 — 업로드 경로 전체를 `pending/` prefix로 바꾸는 일이라 별도 과제 |

**이미 쌓여 있는 누적분은 건드리지 않는다.** 버킷 목록과 DB 참조를 대조하는 일회성 정리는, 방금 업로드됐지만 아직 저장되지 않은 객체를 "참조 없음"으로 오판해 살아있는 이미지를 지울 수 있다. 필요해지면 안전장치를 갖춘 별도 설계로 다룬다.

현재 구성:
- 버킷 `realtimechat-images-storyrago`, 리전 `ap-northeast-2` (`application.yaml`)
- 키는 `{uuid}_{원본파일명}`으로 버킷 루트에 평면 저장 (`S3Service.java:27`)
- 프로필 사진과 채팅 이미지가 같은 버킷을 쓴다
- `S3Client`는 기본 자격증명 체인을 쓴다 (`S3Config.java:16`)

## 2. 결정 사항

**D1. 삭제하지 않고 태그를 단다.**
참조가 끊긴 객체에 `orphan=true` 태그를 붙이고, S3 수명주기 규칙이 30일 뒤 만료시킨다.

즉시 삭제는 복구 창이 없다. 버그로 잘못된 URL이 저장되거나 태깅 조건이 틀렸을 때, 삭제한 객체는 되돌릴 수 없다. 30일은 사고를 알아차리기에 충분하고 저장 비용은 무시할 수 있다.

**D2. 태깅은 트랜잭션이 커밋된 뒤에 한다.**
`MemberService.updateProfileImage`와 `MessageService.delete`는 모두 `@Transactional`이다. 트랜잭션 안에서 태깅하면 **DB가 롤백돼도 태그는 S3에 남는다.** 그러면 여전히 사용 중인 사진이 30일 뒤 조용히 사라진다.

`ApplicationEventPublisher`로 이벤트를 발행하고 `@TransactionalEventListener(phase = AFTER_COMMIT)`에서 태깅한다. 커밋된 뒤에만 태그가 붙는다.

**D3. 우리 버킷의 객체만 태깅한다.**
URL이 `https://{bucket}.s3.{region}.amazonaws.com/`로 시작하지 않으면 아무것도 하지 않는다. `Member.profileImageUrl`에는 구글·카카오가 준 CDN URL이 들어 있을 수 있다. 남의 URL에 태깅을 시도하면 안 된다.

키는 그 접두사를 제거한 나머지다. 업로드 시 URL을 문자열 연결로 조립하므로(`S3Service.java:44`) 역변환도 접두사 제거로 정확히 일치한다.

**D4. 옛 URL과 새 URL이 같으면 태깅하지 않는다.**
같은 사진으로 다시 저장하는 경로가 실제로 존재한다 — 프로필 사진 확정이 성공한 뒤 닉네임 저장이 실패해 사용자가 재시도하면 같은 URL로 `updateProfileImage`가 다시 호출된다. 이때 태깅하면 **살아있는 사진에 만료 태그가 붙는다.**

**D5. 태깅 실패는 삼킨다.**
`orphan` 태깅이 실패해도 프로필 변경·메시지 삭제는 성공해야 한다. 예외를 잡아 로그만 남긴다. 정리는 부가 작업이지 본 작업이 아니다.

*대가*: 권한이 없거나 설정이 잘못돼도 조용히 지나간다. 배포 후 로그를 한 번 확인해야 한다(§5).

**D6. 이벤트는 하나로 통일한다.**
프로필 교체와 메시지 삭제가 발행하는 이벤트를 `ImageDereferencedEvent(String url)` 하나로 둔다. 리스너도 하나다. 두 경로의 후속 처리가 완전히 같으므로 나눌 이유가 없다.

## 3. 구성 요소

| 파일 | 책임 | 작업 |
|---|---|---|
| `service/S3Service.java` | `tagAsOrphan(String url)` — URL 검증·키 추출·태깅 | 수정 |
| `event/ImageDereferencedEvent.java` | 참조가 끊긴 이미지 URL을 나르는 이벤트 | 생성 |
| `event/ImageCleanupListener.java` | `AFTER_COMMIT`에서 `tagAsOrphan` 호출, 실패 로깅 | 생성 |
| `service/MemberService.java` | 프로필 교체 시 옛 URL로 이벤트 발행(D4 조건) | 수정 |
| `service/MessageService.java` | 메시지 삭제 시 붙어 있던 URL로 이벤트 발행 | 수정 |

## 4. 동작

```
updateProfileImage(memberId, newUrl)
  ├ 옛 URL 읽기
  ├ 엔티티 갱신
  └ 옛 URL이 비어있지 않고 newUrl과 다르면 → ImageDereferencedEvent 발행
                                                    ↓ (커밋 후)
delete(messageId, memberId)                    ImageCleanupListener
  ├ 붙어 있던 imageUrl 읽기                          ├ 우리 버킷 URL인가? (아니면 종료)
  ├ softDelete()                                    ├ 키 추출 → PutObjectTagging(orphan=true)
  └ imageUrl이 있었으면 → 이벤트 발행                 └ 실패 시 로그만 남기고 종료
```

## 5. 사람이 해야 하는 설정 (AWS 콘솔)

코드만으로는 아무것도 정리되지 않는다. 아래 둘이 있어야 동작한다.

1. **버킷 수명주기 규칙** — 대상: 태그 `orphan=true`, 동작: 객체 생성 30일 후 만료(Expire current version)
2. **IAM 권한** — 배포에 쓰는 자격증명에 `s3:PutObjectTagging` 추가

D5에 따라 권한이 없어도 애플리케이션은 정상 동작하고 로그만 남는다. **배포 후 프로필 사진을 한 번 바꿔보고 컨테이너 로그에 태깅 실패가 찍히지 않는지 확인해야 한다.**

수명주기 규칙의 만료 기준은 **객체 생성 시각**이지 태깅 시각이 아니다. 따라서 오래전에 업로드된 사진을 지금 교체하면 태그가 붙는 즉시 만료 대상이 될 수 있다. 참조가 이미 끊긴 객체이므로 의도에 어긋나지 않지만, "태그 후 30일"이 아니라는 점은 알고 있어야 한다.

## 6. 검증

- `./gradlew test` — `S3Client`를 모킹해 아래를 확인한다:
  - 우리 버킷 URL이면 `PutObjectTagging`이 호출된다
  - 외부 URL(구글 CDN 등)이면 호출되지 않는다
  - `null`·빈 문자열이면 호출되지 않는다
  - 옛 URL과 새 URL이 같으면 호출되지 않는다(D4)
  - S3가 예외를 던져도 프로필 변경·메시지 삭제는 성공한다(D5)
  - 트랜잭션이 롤백되면 태깅이 일어나지 않는다(D2)
- 배포 후: 프로필 사진을 바꾸고 S3 콘솔에서 옛 객체에 `orphan=true` 태그가 붙었는지, 컨테이너 로그에 태깅 실패가 없는지 확인

## 7. 남는 것

- **확정되지 않은 업로드는 여전히 남는다.** 업로드 경로를 `pending/` prefix로 바꾸고 확정 시 정식 위치로 옮기는 방식이 표준이지만, 채팅 이미지 업로드까지 함께 바뀌므로 별도 과제로 둔다.
- **이미 쌓인 누적분은 그대로다.** 이번 변경은 앞으로 발생하는 것만 처리한다.
- **태그가 붙은 뒤 되살릴 경로는 없다.** 사용자가 옛 사진으로 되돌리는 기능이 없으므로 현재는 문제가 아니지만, 그런 기능이 생기면 태그 제거가 함께 필요하다.
