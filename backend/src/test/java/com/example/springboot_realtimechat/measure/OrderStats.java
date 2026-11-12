package com.example.springboot_realtimechat.measure;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

/**
 * 한 구독자가 받은 seq 목록을 기대 집합과 비교한 결과.
 * 기대 집합 밖 seq는 버리고 센다 — 시나리오 2에서 구간별로 잘라 보기 위해서다.
 */
record OrderStats(int received, long inversions, int missing, int duplicates) {

    /** 기대 집합이 연속 범위가 아닐 때 쓴다(예: 메시지 본문 접두어로 나눈 구간). */
    static OrderStats of(List<Long> receivedSeqs, Collection<Long> expected) {
        Set<Long> expectedSet = new HashSet<>(expected);
        List<Long> inExpected = receivedSeqs.stream()
                .filter(expectedSet::contains)
                .toList();
        long inversions = 0;
        for (int i = 1; i < inExpected.size(); i++) {
            if (inExpected.get(i) < inExpected.get(i - 1)) {
                inversions++;
            }
        }
        Set<Long> distinct = new HashSet<>(inExpected);
        int duplicates = inExpected.size() - distinct.size();
        int missing = (int) expectedSet.stream()
                .filter(seq -> !distinct.contains(seq))
                .count();
        return new OrderStats(inExpected.size(), inversions, missing, duplicates);
    }

    /** 기대 집합이 [expectedFrom, expectedTo] 연속 범위일 때 쓰는 편의 오버로드. */
    static OrderStats of(List<Long> receivedSeqs, long expectedFrom, long expectedTo) {
        List<Long> expected = LongStream.rangeClosed(expectedFrom, expectedTo).boxed().toList();
        return of(receivedSeqs, expected);
    }

    boolean perfect() {
        return inversions == 0 && missing == 0 && duplicates == 0;
    }

    String summary() {
        return "received=" + received + " inversions=" + inversions
                + " missing=" + missing + " duplicates=" + duplicates;
    }
}
