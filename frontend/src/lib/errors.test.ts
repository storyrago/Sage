import { describe, it, expect } from 'vitest';
import { isSessionExpiredError } from './errors';
import { ApiError } from './api';

describe('isSessionExpiredError', () => {
  it('401 UNAUTHORIZED는 세션 만료다', () => {
    expect(isSessionExpiredError(new ApiError('만료', 401, 'UNAUTHORIZED'))).toBe(true);
  });

  it('코드 없는 401도 세션 만료로 본다', () => {
    expect(isSessionExpiredError(new ApiError('실패', 401))).toBe(true);
  });

  it('같은 401이라도 다른 코드는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new ApiError('비밀번호 틀림', 401, 'INVALID_PASSWORD'))).toBe(false);
    expect(isSessionExpiredError(new ApiError('비밀번호 불일치', 401, 'INVALID_PASSWORD'))).toBe(false);
  });

  it('403·500·502는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new ApiError('권한 없음', 403, 'NOT_JOINED_ROOM'))).toBe(false);
    expect(isSessionExpiredError(new ApiError('서버 오류', 500))).toBe(false);
    expect(isSessionExpiredError(new ApiError('게이트웨이', 502))).toBe(false);
  });

  it('네트워크 실패(TypeError)는 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new TypeError('Failed to fetch'))).toBe(false);
  });

  it('ApiError가 아닌 값은 세션 만료가 아니다', () => {
    expect(isSessionExpiredError(new Error('그냥 오류'))).toBe(false);
    expect(isSessionExpiredError(undefined)).toBe(false);
    expect(isSessionExpiredError('문자열')).toBe(false);
  });
});
