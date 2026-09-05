package com.jhg.wms.service;

import com.jhg.wms.domain.Inventory;
import com.jhg.wms.domain.Reservation;
import com.jhg.wms.domain.ReservationStatus;
import com.jhg.wms.repository.InventoryRepository;
import com.jhg.wms.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 예약 체류 분석 조회. 읽기만 한다 — 예약·재고를 만들지도 고치지도 않는다.
 *
 * <p>LLM을 부르지 않는다. 숫자를 읽고 무엇을 쓸지 정하는 일은 MCP 클라이언트의 모델이 한다
 * ({@code CycleCountAnalyticsService}와 같은 규칙이다).
 *
 * <p><b>체류는 {@code createdAt}부터 종료 시각까지다.</b> SHIPPED의 종료는 {@code issuedAt}이다 —
 * 출고 시각 전용 컬럼은 없고, {@code Reservation.releasedAt} 주석이 그 부재를 의도로 적어뒀다
 * ("같은 사실을 두 컬럼에 적으면 언젠가 둘이 어긋난다"). {@code deliveredAt}은 쓰지 않는다:
 * 재고는 출고 시점에 이미 나갔고 그 뒤 며칠이 걸리든 창고에 붙들려 있지 않다.
 *
 * <p><b>출고 경로와 해제 경로를 합치지 않는다.</b> 출고 체류는 정상 처리에 걸린 시간이고
 * 해제 체류는 헛되이 묶여 있던 시간이다. 조치할 곳이 서로 다른데 숫자 하나로 뭉개면
 * 그 숫자로는 아무것도 못 고친다.
 *
 * <p><b>구간 판정은 종료 시각이다</b>({@code basis="endedAt"}). 실사와 기준이 다른 이유는
 * {@code ReservationRepository.findEndedBetween} 주석에 있다.
 */
@Service
@Transactional(readOnly = true)
public class ReservationAnalyticsService {

    /**
     * 예약 시각은 Instant인데(서비스 경계를 넘는 값이라 의도적이다) 조회 인자는 LocalDate라
     * 존이 필요하다. 단일 창고라 설정값으로 빼지 않는다 — 필요해지면 그때 뺀다.
     */
    private static final ZoneId ZONE = ZoneId.systemDefault();

    private final ReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;

    public ReservationAnalyticsService(ReservationRepository reservationRepository,
                                       InventoryRepository inventoryRepository) {
        this.reservationRepository = reservationRepository;
        this.inventoryRepository = inventoryRepository;
    }

    /**
     * @param medianMinutes count가 0이면 <b>null</b>이다. 0으로 내면 "0분 만에 끝났다"로 읽힌다 —
     *                      잴 것이 없는 것과 다르다({@code accuracy: null}과 같은 규칙).
     */
    public record DwellStats(int count, Long medianMinutes, Long p90Minutes, Long maxMinutes) {}

    /**
     * @param stillOpen to 시점에 아직 끝나지 않은 예약 수. 생존 편향의 크기다.
     * @param excludedMissingCreatedAt createdAt이 null이라 잴 수 없었던 예약 수. 조용히 빼면
     *                                 분모를 속이는 것과 같다.
     */
    public record DwellReport(LocalDate from, LocalDate to, String basis,
                              DwellStats shipped, DwellStats released,
                              int stillOpen, int excludedMissingCreatedAt) {}

    /**
     * @param occurrences 그 상품이 든 예약 중 체류를 잰 건수. <b>합은 예약 건수가 아니다</b> —
     *                    예약 하나가 상품 여럿을 담으면 담은 수만큼 계상된다.
     */
    public record ProductDwell(Long productId, String productName,
                               int occurrences, long medianMinutes, long maxMinutes,
                               int shippedCount, int releasedCount) {}

    /** 기간 내 끝난 예약의 체류 분포. 출고와 해제를 각각 낸다. */
    public DwellReport dwell(LocalDate from, LocalDate to) {
        List<Long> shipped = new ArrayList<>();
        List<Long> released = new ArrayList<>();
        int excluded = 0;

        for (Reservation r : endedIn(from, to)) {
            if (r.getCreatedAt() == null) { excluded++; continue; }
            long minutes = Duration.between(r.getCreatedAt(), endedAt(r)).toMinutes();
            switch (r.getStatus()) {
                case SHIPPED -> shipped.add(minutes);
                case RELEASED -> released.add(minutes);
                case RESERVED -> { }   // 조회가 걸러내므로 여기 오지 않는다
            }
        }

        long openAtEnd = reservationRepository.countOpenAt(startOf(to.plusDays(1)));
        return new DwellReport(from, to, "endedAt",
                stats(shipped), stats(released), (int) openAtEnd, excluded);
    }

    /**
     * 상품별 체류 묶음. 반복해서 오래 붙들린 상품이 먼저 온다.
     *
     * <p>정렬은 <b>반복 횟수가 먼저</b>고 그다음이 중앙값이다. 한 번 아주 오래 걸린 것보다
     * 여러 번 반복해서 오래 걸리는 쪽이 로케이션·재고 부족 같은 구조적 원인을 가리키기 때문이다
     * ({@code CycleCountAnalyticsService.variances}와 같은 규칙이다).
     */
    public List<ProductDwell> dwellByProduct(LocalDate from, LocalDate to) {
        Map<Long, List<Long>> minutesByProduct = new LinkedHashMap<>();
        Map<Long, int[]> countsByProduct = new HashMap<>();   // [shipped, released]

        for (Reservation r : endedIn(from, to)) {
            if (r.getCreatedAt() == null) continue;
            long minutes = Duration.between(r.getCreatedAt(), endedAt(r)).toMinutes();
            for (Long productId : r.getQtyByProductId().keySet()) {
                minutesByProduct.computeIfAbsent(productId, k -> new ArrayList<>()).add(minutes);
                int[] counts = countsByProduct.computeIfAbsent(productId, k -> new int[2]);
                if (r.getStatus() == ReservationStatus.SHIPPED) counts[0]++; else counts[1]++;
            }
        }
        if (minutesByProduct.isEmpty()) return List.of();

        // Collectors.toMap은 값이 null이면 NPE다. 이름은 나중에 도입된 컬럼이라 null일 수 있고,
        // 이름 없는 상품 하나가 보고서 전체를 막으면 안 된다(variances와 같은 이유).
        Map<Long, String> nameByProduct = new HashMap<>();
        for (Inventory inv : inventoryRepository.findByProductIdIn(minutesByProduct.keySet()))
            nameByProduct.put(inv.getProductId(), inv.getProductName());

        List<ProductDwell> result = new ArrayList<>();
        minutesByProduct.forEach((productId, minutes) -> {
            List<Long> sorted = minutes.stream().sorted().toList();
            int[] counts = countsByProduct.get(productId);
            result.add(new ProductDwell(productId, nameByProduct.get(productId),
                    sorted.size(), percentile(sorted, 0.5), sorted.get(sorted.size() - 1),
                    counts[0], counts[1]));
        });

        result.sort(Comparator.comparingInt(ProductDwell::occurrences).reversed()
                .thenComparing(Comparator.comparingLong(ProductDwell::medianMinutes).reversed())
                .thenComparing(ProductDwell::productId));
        return result;
    }

    private static DwellStats stats(List<Long> minutes) {
        if (minutes.isEmpty()) return new DwellStats(0, null, null, null);
        List<Long> sorted = minutes.stream().sorted().toList();
        return new DwellStats(sorted.size(), percentile(sorted, 0.5), percentile(sorted, 0.9),
                sorted.get(sorted.size() - 1));
    }

    /**
     * 정렬된 목록의 p분위(nearest-rank). <b>보간하지 않는다</b> — 표본이 작을 때 실제로 없던
     * 체류시간을 만들어내고, 보고서는 그 값을 실측치로 인용하게 된다.
     */
    private static long percentile(List<Long> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    /** SHIPPED는 issuedAt, RELEASED는 releasedAt. 조회가 그 둘만 돌려준다. */
    private static Instant endedAt(Reservation r) {
        return switch (r.getStatus()) {
            case SHIPPED -> r.getIssuedAt();
            case RELEASED -> r.getReleasedAt();
            case RESERVED -> null;
        };
    }

    /** to는 그날 하루를 포함한다 — 화면·보고서가 "9/1~9/3"을 3일로 읽는 것과 맞춘다. */
    List<Reservation> endedIn(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new IllegalArgumentException("시작일이 종료일보다 뒤입니다.");
        return reservationRepository.findEndedBetween(startOf(from), startOf(to.plusDays(1)));
    }

    private static Instant startOf(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }
}
