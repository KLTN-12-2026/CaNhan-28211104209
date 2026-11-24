import assert from 'node:assert/strict';
import { test } from 'node:test';
import { candidateSetLines, planSourceLabel, previewExplanation, previewHeading } from './itineraryPreview.js';

test('each preview status has its own heading and only failures carry an explanation', () => {
  const headings = ['VALIDATED', 'NO_FEASIBLE_PLAN', 'NEEDS_DATA', 'UNAVAILABLE'].map(previewHeading);
  assert.equal(new Set(headings).size, 4);
  const set = { eligibleCount: 2, candidateIds: ['s', 'a'], excluded: [], truncatedLocationIds: [] };
  assert.equal(previewExplanation({ status: 'VALIDATED', planSource: 'QWEN', candidateSet: set }), null);
  assert.match(previewExplanation({ status: 'NO_FEASIBLE_PLAN', planSource: 'BACKEND_SEARCH', candidateSet: set }), /Đã xét mọi thứ tự/);
  assert.match(previewExplanation({ status: 'NEEDS_DATA', planSource: null, candidateSet: set }), /không phải kết luận/);
  assert.match(previewExplanation({ status: 'UNAVAILABLE', planSource: null, candidateSet: null }), /chưa thể kết luận/);
  assert.equal(previewHeading('SOMETHING_ELSE'), 'KHÔNG XÁC ĐỊNH ĐƯỢC KẾT QUẢ');
  assert.equal(previewExplanation(null), null);
});

test('G6-R1: NO_FEASIBLE_PLAN claims an exhaustive search only when the backend searched the candidate set', () => {
  const set = { eligibleCount: 2, candidateIds: ['s', 'a'], excluded: [], truncatedLocationIds: [] };
  const earlyClosed = { status: 'NO_FEASIBLE_PLAN', planSource: null, candidateSet: null, notices: ['Điểm xuất phát đóng cửa'] };
  for (const early of [earlyClosed, { ...earlyClosed, planSource: 'BACKEND_SEARCH' }, { ...earlyClosed, candidateSet: set }]) {
    const text = previewExplanation(early);
    assert.doesNotMatch(text, /Đã xét mọi thứ tự/, JSON.stringify(early));
    assert.match(text, /dừng trước khi xét/);
    assert.match(text, /không phải kết luận đã thử mọi thứ tự/);
  }
  assert.match(previewExplanation({ status: 'NO_FEASIBLE_PLAN', planSource: 'BACKEND_SEARCH', candidateSet: set }), /Đã xét mọi thứ tự 2–4 điểm/);
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
