import assert from 'node:assert/strict';
import { test } from 'node:test';
import { candidateSetLines, planSourceLabel, previewExplanation, previewHeading } from './itineraryPreview.js';

test('each preview status has its own heading and only failures carry an explanation', () => {
  const headings = ['VALIDATED', 'NO_FEASIBLE_PLAN', 'NEEDS_DATA', 'UNAVAILABLE'].map(previewHeading);
  assert.equal(new Set(headings).size, 4);
  assert.equal(previewExplanation('VALIDATED'), null);
  assert.match(previewExplanation('NO_FEASIBLE_PLAN'), /Đã xét mọi thứ tự/);
  assert.match(previewExplanation('NEEDS_DATA'), /không phải kết luận/);
  assert.match(previewExplanation('UNAVAILABLE'), /chưa thể kết luận/);
  assert.equal(previewHeading('SOMETHING_ELSE'), 'KHÔNG XÁC ĐỊNH ĐƯỢC KẾT QUẢ');
});

test('plan source distinguishes Qwen order from backend search and hides unknown values', () => {
  assert.match(planSourceLabel('QWEN'), /Qwen/);
  assert.match(planSourceLabel('BACKEND_SEARCH'), /hệ thống đã tự xét/);
  assert.equal(planSourceLabel(null), null);
  assert.equal(planSourceLabel('GUESS'), null);
});

test('candidate set lines name the cap, truncated and excluded locations', () => {
  const names = { a: 'Bảo tàng', p6: 'Chợ', nometa: 'Điểm mới', shut: 'Đền' };
  const lines = candidateSetLines({
    eligibleCount: 10,
    candidateIds: ['s', 'a', 'p0', 'p1', 'p2', 'p3', 'p4', 'p5'],
    excluded: [{ locationId: 'nometa', reason: 'MISSING_METADATA' }, { locationId: 'shut', reason: 'NOT_OPEN_ON_DATE' }, { locationId: 'x', reason: 'NEW_REASON' }],
    truncatedLocationIds: ['p6', 'p7'],
  }, names);
  assert.equal(lines[0], 'Đã xét 8 địa điểm ứng viên đủ dữ liệu.');
  assert.match(lines[1], /chưa xét 2 điểm khác \(Chợ, p7\)/);
  assert.equal(lines[2], 'Không dùng Điểm mới: chưa có thông tin tham quan được kiểm chứng.');
  assert.equal(lines[3], 'Không dùng Đền: không mở cửa hoặc chưa rõ giờ mở cửa ngày này.');
  assert.equal(lines[4], 'Không dùng x: thiếu dữ liệu kiểm chứng.');
  assert.deepEqual(candidateSetLines(null, names), []);
  assert.deepEqual(candidateSetLines({ candidateIds: ['s', 'a'], excluded: [], truncatedLocationIds: [] }), ['Đã xét 2 địa điểm ứng viên đủ dữ liệu.']);
});
