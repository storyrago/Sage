# 이미지 접근 제어 전환 런북

설계: `docs/superpowers/specs/2026-08-15-room-image-access-design.md`

수동 작업이 둘 있고 **순서를 지켜야 한다.** 3을 먼저 하면 이관 전 프로필 사진이 전부 깨진다.

## 0. IAM 사전 확인

서명은 앱 컨테이너 환경변수(`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`)의 고정 IAM 사용자로 이뤄진다.
지금은 버킷 정책이 `Principal: "*"`라 모든 GET이 통과하지만, 3단계에서 정책을 `profiles/*`로
좁히는 순간부터 `rooms/`·레거시 루트 GET은 이 IAM 사용자 자신의 정책으로만 평가된다.
`PutObject`만 있고 `GetObject`가 없으면 채팅 이미지가 한 번에 전부 깨진다.

이 IAM 사용자 정책에 다음이 있는지 콘솔에서 확인한다:

```json
{
  "Effect": "Allow",
  "Action": "s3:GetObject",
  "Resource": "arn:aws:s3:::realtimechat-images-storyrago/*"
}
```

버킷 정책을 건드리기 **전에**, 실행 중인 앱에서 `rooms/` 객체의 서명된 URL을 하나 받아 직접 호출해
200이 나오는지 확인한다 — 콘솔 정책 읽기만으로는 실수를 잡지 못한다.

```bash
curl -s -o /dev/null -w '%{http_code}\n' "<앱이 돌려준 서명된 rooms/ URL>"
```

## 1. 코드 배포

develop 머지 → CD. 이 시점에는 버킷 정책이 아직 전체 공개라 아무것도 깨지지 않는다.
새 업로드부터 `profiles/`·`rooms/` 접두사가 붙는다.

## 2. 기존 프로필 사진 이관

기존 객체는 버킷 루트에 평면으로 있다. 프로필 사진만 `profiles/`로 옮긴다.
채팅 이미지는 옮기지 않는다 — 루트에 두면 새 정책에서 자동으로 비공개가 되고, 서명은 키만 알면 된다.

옮길 대상 확인(RDS):

```sql
SELECT id, profile_image_url FROM members WHERE profile_image_url IS NOT NULL;
```

각 URL의 마지막 `/` 뒤가 키다. 키마다 복사한다:

```bash
aws s3 cp "s3://realtimechat-images-storyrago/<KEY>" "s3://realtimechat-images-storyrago/profiles/<KEY>"
```

DB를 갱신한다(복사가 전부 끝난 뒤에 한 번에):

```sql
UPDATE members
SET profile_image_url = REPLACE(
      profile_image_url,
      'amazonaws.com/',
      'amazonaws.com/profiles/')
WHERE profile_image_url LIKE '%amazonaws.com/%'
  AND profile_image_url NOT LIKE '%amazonaws.com/profiles/%';
```

`NOT LIKE`가 있어야 두 번 실행해도 `profiles/profiles/`가 되지 않는다.

갱신 결과가 실제 객체와 맞는지 확인한 뒤 원본을 지운다:

```bash
aws s3 rm "s3://realtimechat-images-storyrago/<KEY>"
```

## 2.5 본문에 박힌 이미지 URL 측정

메시지 `content`에 텍스트로 박힌 이미지 URL(예: 채팅으로 URL을 직접 붙여넣은 경우)은
`imageUrl` 컬럼이 아니라서 서명 대상이 아니다. 프론트가 `content`에서 추출해 그대로 렌더링하므로
정책 전환 시점에 영구히 깨진다.

```sql
SELECT COUNT(*) FROM messages WHERE content LIKE '%amazonaws.com/%';
```

0이면 신경 쓸 것이 없다. 0이 아니면 그 메시지들은 전환 후 이미지가 깨진 채로 보인다 —
전환을 진행할지는 운영자가 그 개수를 보고 판단한다.

## 3. 버킷 정책 교체

AWS 콘솔 → S3 → `realtimechat-images-storyrago` → 권한 → 버킷 정책.
기존 "버킷 전체 `s3:GetObject` 공개"를 다음으로 바꾼다:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "PublicReadProfiles",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": "arn:aws:s3:::realtimechat-images-storyrago/profiles/*"
    }
  ]
}
```

## 4. 확인

```bash
# 프로필 사진: 서명 없이 200
curl -s -o /dev/null -w '%{http_code}\n' "https://realtimechat-images-storyrago.s3.ap-northeast-2.amazonaws.com/profiles/<KEY>"

# 채팅 이미지: 서명 없이 403
curl -s -o /dev/null -w '%{http_code}\n' "https://realtimechat-images-storyrago.s3.ap-northeast-2.amazonaws.com/<CHAT_KEY>"

# 채팅 이미지: 앱이 서명한 URL로는 200 (IAM 사용자의 GetObject 권한 확인)
curl -s -o /dev/null -w '%{http_code}\n' "<앱이 돌려준 서명된 rooms/ URL>"
```

앱에서: 아바타가 보이고, 채팅 이미지도 보이고, 채팅 이미지 URL을 로그아웃한 브라우저에 붙여넣으면 403.

## 롤백

버킷 정책을 원래대로(버킷 전체 공개) 되돌리면 즉시 원복된다. 코드는 그대로 두어도 무해하다.
