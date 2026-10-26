export function createRequestGeneration() {
  let current = 0;

  return {
    begin() {
      current += 1;
      return current;
    },
    isCurrent(generation) {
      return generation === current;
    },
    invalidate(generation) {
      if (generation === undefined || generation === current) {
        current += 1;
      }
    },
  };
}

export function shouldClearUnauthorizedSession(requestToken, currentToken) {
  return Boolean(requestToken && requestToken === currentToken);
}
