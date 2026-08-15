import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { createReadMarker, MARK_READ_THROTTLE_MS } from './readMarker';

describe('createReadMarker', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(0);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('첫 호출은 즉시 보낸다', () => {
    const send = vi.fn();
    const { mark } = createReadMarker(send, () => true);

    mark('room-1');

    expect(send).toHaveBeenCalledTimes(1);
    expect(send).toHaveBeenCalledWith('room-1');
  });

  it('창 안의 호출은 즉시 보내지 않고, 창이 끝날 때 한 번 보충된다', () => {
    const send = vi.fn();
    const { mark } = createReadMarker(send, () => true);

    mark('room-1');
    vi.advanceTimersByTime(500);
    mark('room-1');

    expect(send).toHaveBeenCalledTimes(1); // 창 안이라 아직 보내지 않음

    vi.advanceTimersByTime(500); // 창 끝
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('창 안에 여러 번 호출해도 보충은 한 번만 일어난다', () => {
    const send = vi.fn();
    const { mark } = createReadMarker(send, () => true);

    mark('room-1');
    vi.advanceTimersByTime(200);
    mark('room-1');
    vi.advanceTimersByTime(200);
    mark('room-1');
    vi.advanceTimersByTime(200);
    mark('room-1');

    expect(send).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(MARK_READ_THROTTLE_MS);
    expect(send).toHaveBeenCalledTimes(2);
  });

  it('보충 시점에 그 방을 더 이상 보고 있지 않으면 보내지 않는다', () => {
    const send = vi.fn();
    let viewing = true;
    const { mark } = createReadMarker(send, () => viewing);

    mark('room-1');
    vi.advanceTimersByTime(300);
    mark('room-1');
    viewing = false;

    vi.advanceTimersByTime(MARK_READ_THROTTLE_MS);
    expect(send).toHaveBeenCalledTimes(1); // 최초 즉시 전송만, 보충은 없음
  });

  it('창 안에서 방이 바뀌면 마지막 방으로 보충된다', () => {
    const send = vi.fn();
    const { mark } = createReadMarker(send, () => true);

    mark('room-1');
    vi.advanceTimersByTime(300);
    mark('room-2');
    vi.advanceTimersByTime(300);
    mark('room-3');

    vi.advanceTimersByTime(MARK_READ_THROTTLE_MS);
    expect(send).toHaveBeenCalledTimes(2);
    expect(send).toHaveBeenNthCalledWith(2, 'room-3');
  });

  it('창이 끝난 뒤의 호출은 다시 즉시 보낸다', () => {
    const send = vi.fn();
    const { mark } = createReadMarker(send, () => true);

    mark('room-1');
    vi.advanceTimersByTime(MARK_READ_THROTTLE_MS);
    mark('room-1');

    expect(send).toHaveBeenCalledTimes(2);
  });

  it('cancel() 후에는 보충이 오지 않는다', () => {
    const send = vi.fn();
    const { mark, cancel } = createReadMarker(send, () => true);

    mark('room-1');
    vi.advanceTimersByTime(300);
    mark('room-1');
    cancel();

    vi.advanceTimersByTime(MARK_READ_THROTTLE_MS);
    expect(send).toHaveBeenCalledTimes(1);
  });
});
