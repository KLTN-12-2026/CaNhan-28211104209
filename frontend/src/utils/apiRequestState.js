export function selectApiRequestState(result, requestKey, enabled) {
  const isCurrentResult = enabled && result?.requestKey === requestKey;

  return {
    data: isCurrentResult ? result.data : null,
    loading: enabled && !isCurrentResult,
    error: isCurrentResult ? result.error : "",
  };
}
