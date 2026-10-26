export function eventsForSelectedIncident(selectedIncidentId, eventHistory) {
  if (!selectedIncidentId || eventHistory?.incidentId !== selectedIncidentId) {
    return [];
  }
  return Array.isArray(eventHistory.events) ? eventHistory.events : [];
}
