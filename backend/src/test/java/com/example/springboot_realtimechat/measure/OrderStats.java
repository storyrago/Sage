package com.example.springboot_realtimechat.measure;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

/**
 * 한 구독자가 받은 seq 목록을 기대 범위 [expectedFrom, expectedTo]와 비교한 결과.
 * 범위 밖 seq는 버리고 센다 — 시나리오 2에서 구간별로 잘라 보기 위해서다.
 */
record OrderStats(int received, long inversions, int missing, int duplicates) {

    static OrderStats of(List<Long> receivedSeqs, long expectedFrom, long expectedTo) {
        List<Long> inRange = receivedSeqs.stream()
                .filter(seq -> seq >= expectedFrom && seq <= expectedTo)
                .toList();
        long inversions = 0;
        for (int i = 1; i < inRange.size(); i++) {
            if (inRange.get(i) < inRange.get(i - 1)) {
                inversions++;
            }
        }
        Set<Long> distinct = new HashSet<>(inRange);
        int duplicates = inRange.size() - distinct.size();
        int missing = (int) LongStream.rangeClosed(expectedFrom, expectedTo)
                .filter(seq -> !distinct.contains(seq))
                .count();
        return new OrderStats(inRange.size(), inversions, missing, duplicates);
    }

    boolean perfect() {
        return inversions == 0 && missing == 0 && duplicates == 0;
    }

    String summary() {
        return "received=" + received + " inversions=" + inversions
                + " missing=" + missing + " duplicates=" + duplicates;
    }
}
