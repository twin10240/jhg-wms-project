package com.jhg.wms.web;

import com.jhg.wms.service.ReservationAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 예약 체류 분석 조회 REST. MCP 서버가 이것을 부른다.
 *
 * <p>계산하지 않는다 — 서비스에 위임하고 레코드를 그대로 직렬화할 뿐이다.
 * 여기에 집계를 넣으면 화면과 보고서가 다른 숫자를 낼 수 있게 된다
 * ({@code CycleCountAnalyticsController}와 같은 규칙이다).
 *
 * <p>읽기 전용이다. 경로가 {@code /api/**} 안인 것도 의도다 — apiChain의 basic 인증·
 * CSRF 비활성·401 직접 응답을 그대로 쓰고 {@code SecurityConfig}를 고치지 않는다.
 *
 * <p>{@code from}·{@code to}에 기본값을 두지 않는다. 보고서는 분모가 무엇인지 분명해야 한다.
 *
 * <p><b>소비자</b>: {@code mcp-server/wms_mcp/client.py}가 이 경로와 파라미터 이름을 그대로
 * 하드코딩해 부른다. 여기서 바꾸면 그쪽도 같이 고쳐야 한다(Java 테스트는 그 불일치를 잡지 못한다).
 *
 * <p>400 평문 오류 계약은 {@link AnalyticsErrorAdvice}가 담당한다.
 */
@RestController
@RequestMapping("/api/analytics")
public class ReservationAnalyticsController {

    private final ReservationAnalyticsService reservationAnalyticsService;

    public ReservationAnalyticsController(ReservationAnalyticsService reservationAnalyticsService) {
        this.reservationAnalyticsService = reservationAnalyticsService;
    }

    @GetMapping("/reservation-dwell")
    public ReservationAnalyticsService.DwellReport dwell(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reservationAnalyticsService.dwell(from, to);
    }

    @GetMapping("/reservation-dwell-by-product")
    public List<ReservationAnalyticsService.ProductDwell> dwellByProduct(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reservationAnalyticsService.dwellByProduct(from, to);
    }
}
