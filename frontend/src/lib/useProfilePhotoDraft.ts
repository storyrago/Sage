import { useCallback, useEffect, useRef, useState } from 'react';
import { uploadImage, updateProfileImage } from './api';

// 백엔드 spring.servlet.multipart.max-file-size(10MB)·nginx client_max_body_size(10m)와 같은 기준.
// 서버까지 갔다가 실패하면 큰 파일을 올리는 시간을 버린다.
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;

// 프로필 사진을 "고른 상태"로 들고 있다가 확정 시점에 업로드·저장한다.
// 온보딩 화면과 설정 모달이 같은 동작을 공유한다.
export function useProfilePhotoDraft(token: string) {
  const [file, setFile] = useState<File | null>(null);
  const [previewUrl, setPreviewUrl] = useState('');
  const [error, setError] = useState('');
  const [photoSaved, setPhotoSaved] = useState(false);
  const uploadedUrlRef = useRef('');   // 업로드 성공 URL 캐시. 재시도 시 다시 올리지 않는다

  // 미리보기 URL은 교체될 때와 화면을 떠날 때 해제한다
  useEffect(() => {
    if (!previewUrl) return;
    return () => URL.revokeObjectURL(previewUrl);
  }, [previewUrl]);

  const pick = useCallback((f: File) => {
    if (!f.type.startsWith('image/')) {
      setError('이미지 파일만 올릴 수 있어요.');
      return;
    }
    if (f.size > MAX_IMAGE_BYTES) {
      setError('사진은 10MB까지 올릴 수 있어요.');
      return;
    }
    setError('');
    uploadedUrlRef.current = '';
    setPhotoSaved(false);
    setFile(f);
    setPreviewUrl(URL.createObjectURL(f));
  }, []);

  // 고른 파일이 없으면 요청을 보내지 않고 null을 반환한다.
  // 실패하면 error를 채운 뒤 예외를 다시 던져 호출한 화면이 저장 순서를 멈플 수 있게 한다.
  const commit = useCallback(async (): Promise<string | null> => {
    if (!file) return null;
    try {
      if (!uploadedUrlRef.current) {
        uploadedUrlRef.current = await uploadImage(token, file);
      }
      await updateProfileImage(token, uploadedUrlRef.current);
      setPhotoSaved(true);
      setError('');
      return uploadedUrlRef.current;
    } catch (e) {
      setError(e instanceof Error ? e.message : '사진 저장에 실패했어요. 다시 시도해 주세요.');
      throw e;
    }
  }, [file, token]);

  const reset = useCallback(() => {
    setFile(null);
    setPreviewUrl('');
    setError('');
    setPhotoSaved(false);
    uploadedUrlRef.current = '';
  }, []);

  return {
    previewUrl,
    hasDraft: file !== null && !photoSaved,   // 고른 사진이 있고 아직 서버에 반영되지 않음
    error,
    pick,
    commit,
    reset,
  };
}
