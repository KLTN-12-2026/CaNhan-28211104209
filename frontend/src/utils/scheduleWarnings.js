const kinds = new Set(['RETURN_DUE_NEXT_DAY_BOOKING', 'OVERDUE', 'PREDECESSOR_NOT_RETURNED']);

export function scheduleWarnings(booking) {
  const items = booking?.scheduleWarnings?.items;
  return Array.isArray(items) ? items.filter(item => item && item.bookingId === booking.id && kinds.has(item.kind)) : [];
}

export function matchesScheduleWarning(booking, filter) {
  if (!filter) return true;
  return scheduleWarnings(booking).some(item => filter === 'early'
    ? item.kind === 'RETURN_DUE_NEXT_DAY_BOOKING'
    : filter === 'overdue' && ['OVERDUE', 'PREDECESSOR_NOT_RETURNED'].includes(item.kind));
}
