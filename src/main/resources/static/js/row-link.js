// 행 전체 클릭 → 상세. tr에 data-href가 있으면 그리로 간다.
//
// document에 위임해서 한 번만 건다. 화면마다 인라인으로 복붙하던 것을 여기로 모았는데,
// 그때 리스너를 거는 대상이 .table-wrap과 main으로 갈려 있었다 —
// querySelector('.table-wrap')는 첫 표에만 걸려서, 표가 둘인 화면(발주 관리, 반품 리포트)에서는
// 조용히 안 먹는다. document면 그 구분 자체가 없어진다.
//
// 접근성: 행 클릭은 마우스 전용이다. 대부분의 화면은 ID나 상세 셀에 <a>를 남겨 키보드·
// 스크린리더 경로를 지킨다 — 행 클릭은 그 위에 얹은 편의다.
// ponytail: 수불대장·반품 리포트는 그 셀 링크가 없어 키보드로는 상세에 갈 길이 없다.
// 필요해지면 tr에 tabindex와 Enter 핸들러를 붙인다.
document.addEventListener('click', function (e) {
  var tr = e.target.closest('tr[data-href]');
  if (!tr || e.target.closest('a')) return;
  if (window.getSelection().toString()) return;   // 텍스트 드래그 복사 중이면 이동하지 않는다
  location.href = tr.dataset.href;
});
